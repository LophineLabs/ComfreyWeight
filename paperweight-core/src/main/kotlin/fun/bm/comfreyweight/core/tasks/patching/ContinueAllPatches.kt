package `fun`.bm.comfreyweight.core.tasks.patching

import io.papermc.paperweight.core.tasks.patching.FeaturePatchOperations
import io.papermc.paperweight.tasks.BaseTask
import io.papermc.paperweight.util.path
import java.nio.file.Path
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "Always run when requested")
abstract class ContinueAllPatches : BaseTask() {

    @get:Internal
    abstract val targets: ListProperty<ContinueTarget>

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

    fun addTarget(name: String, repo: Path, patches: DirectoryProperty) {
        val target = objects.newInstance(ContinueTarget::class.java)
        target.name.set(name)
        target.repo.set(repo.toFile())
        target.patches.set(patches)
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
