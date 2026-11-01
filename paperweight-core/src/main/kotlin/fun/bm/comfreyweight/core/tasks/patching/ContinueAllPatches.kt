package `fun`.bm.comfreyweight.core.tasks.patching

import io.papermc.paperweight.core.tasks.patching.FeaturePatchOperations
import io.papermc.paperweight.core.tasks.patching.loadFeaturePatchContinueState
import io.papermc.paperweight.tasks.BaseTask
import io.papermc.paperweight.util.fileExists
import io.papermc.paperweight.util.path
import java.nio.file.Path
import kotlin.io.path.exists
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

abstract class FeaturePatchContinueTask : BaseTask() {

    @get:Internal
    abstract val targets: ListProperty<ContinueTarget>

    fun addTarget(name: String, repo: Path, patches: DirectoryProperty) {
        val target = objects.newInstance(ContinueTarget::class.java)
        target.name.set(name)
        target.repo.set(repo.toFile())
        target.patches.set(patches.fileExists())
        targets.add(target)
    }

    abstract class ContinueTarget {
        @get:Internal
        abstract val name: Property<String>

        @get:Internal
        abstract val repo: DirectoryProperty

        @get:Internal
        abstract val patches: DirectoryProperty
    }
}

@UntrackedTask(because = "Always run when requested")
abstract class ContinueAllPatches : FeaturePatchContinueTask() {

    @TaskAction
    fun run() {
        val continueTargets = targets.get()
        if (continueTargets.isEmpty()) {
            logger.lifecycle("No continue tasks are registered")
            return
        }

        for (target in continueTargets) {
            logger.lifecycle("Trying ${target.name.get()}...")
            FeaturePatchOperations(
                logger = logger,
                continueTaskName = target.name.get(),
                printOutput = true,
                verbose = false,
            ).continueApplyingPatches(
                repo = target.repo.orNull?.path,
                patches = target.patches.orNull?.path,
                skipIfIdle = true,
            )
        }
    }
}

@UntrackedTask(because = "Always run when requested")
abstract class ListContinuablePatches : FeaturePatchContinueTask() {

    @TaskAction
    fun run() {
        val continuable = findContinuable()
        if (continuable.isEmpty()) {
            logger.lifecycle("No interrupted feature patch applies")
            return
        }

        logger.lifecycle("Interrupted feature patch applies:")
        for (entry in continuable) {
            logger.lifecycle("  ${entry.name}")
            logger.lifecycle("    failed: ${entry.failedPatch}")
            logger.lifecycle("    remaining: ${entry.remaining}")
            logger.lifecycle("    ./gradlew ${entry.name}")
        }
    }

    fun findContinuable(): List<ContinuablePatch> {
        return targets.get().mapNotNull { target ->
            val repo = target.repo.orNull?.path ?: return@mapNotNull null
            if (!repo.exists()) {
                return@mapNotNull null
            }
            val state = loadFeaturePatchContinueState(repo) ?: return@mapNotNull null
            ContinuablePatch(
                name = target.name.get(),
                failedPatch = state.failedPatch,
                remaining = state.remainingPatches.size,
            )
        }
    }
}

data class ContinuablePatch(
    val name: String,
    val failedPatch: String,
    val remaining: Int,
)
