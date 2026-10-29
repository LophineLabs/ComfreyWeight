package `fun`.bm.comfreyweight.core.tasks.patching

import io.papermc.paperweight.core.tasks.patching.ApplyFeaturePatches
import io.papermc.paperweight.tasks.BaseTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "Always run when requested")
abstract class ContinueAllPatches : BaseTask() {

    @get:Internal
    abstract val continueTaskNames: ListProperty<String>

    @TaskAction
    fun run() {
        val names = continueTaskNames.get()
        if (names.isEmpty()) {
            logger.lifecycle("No continue tasks are registered")
            return
        }

        for (name in names) {
            val task = project.tasks.named(name, ApplyFeaturePatches::class.java).get()
            logger.lifecycle("Trying $name...")
            task.continueApplyingPatches(skipIfIdle = true)
        }
    }
}
