package `fun`.bm.comfreyweight.core.tasks.patching

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.core.tasks.patching.ApplyFeaturePatches
import io.papermc.paperweight.tasks.TaskTest
import io.papermc.paperweight.util.Git
import io.papermc.paperweight.util.copyRecursivelyTo
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.kotlin.dsl.register
import org.junit.jupiter.api.io.TempDir

class ContinueAllPatchesTest : TaskTest() {
    private lateinit var applyTask: ApplyFeaturePatches
    private lateinit var continueAll: ContinueAllPatches

    @BeforeTest
    fun setup() {
        val project = setupProject()
        applyTask = project.tasks.register("applyFeaturePatches", ApplyFeaturePatches::class).get()
        continueAll = project.tasks.register("continueAll", ContinueAllPatches::class).get()
    }

    @Test
    fun `should continue the interrupted set and skip idle sets without executing other tasks`(@TempDir tempDir: Path) {
        val interrupted = createFeatureRepo(tempDir.resolve("interrupted"))
        val idle = createFeatureRepo(tempDir.resolve("idle"))
        val patches = tempDir.resolve("patches")
        testInput.resolve("patches").copyRecursivelyTo(patches)

        configureApply(interrupted, patches)
        applyTask.continueTaskName.set("continueFeaturePatches")

        val ex = assertFailsWith<PaperweightException> { applyTask.run() }
        assertContains(ex.message!!, "0002-Broken.patch")
        assertTrue(interrupted.resolve(".git/paperweight-feature-patch-continue.json").isRegularFile())
        assertFalse(idle.resolve(".git/paperweight-feature-patch-continue.json").exists())

        testInput.resolve("continue/Test.java").copyTo(interrupted.resolve("Test.java"), overwrite = true)

        val patchDir = continueAll.objects.directoryProperty().fileValue(patches.toFile())
        continueAll.addTarget("continueFeaturePatches", interrupted, patchDir)
        continueAll.addTarget("continueIdleFeaturePatches", idle, patchDir)
        continueAll.run()

        assertEquals(testResource.resolve("output/continued/Test.java").readText(), interrupted.resolve("Test.java").readText())
        assertFalse(interrupted.resolve(".git/paperweight-feature-patch-continue.json").exists())
        assertEquals("3", Git(interrupted)("rev-list", "--count", "file..HEAD").getText().trim())
        assertEquals("Change b", Git(interrupted)("log", "-1", "--format=%s").getText().trim())

        assertEquals(testResource.resolve("input/base/Test.java").readText(), idle.resolve("Test.java").readText())
        assertEquals("Test", Git(idle)("log", "-1", "--format=%s").getText().trim())
        assertFalse(idle.resolve(".git/paperweight-feature-patch-continue.json").exists())
    }

    private fun configureApply(repo: Path, patches: Path) {
        applyTask.repo.set(repo.toFile())
        applyTask.patches.set(patches.toFile())
        applyTask.printOutput.set(true)
        applyTask.verbose.set(true)
    }

    private fun createFeatureRepo(tempDir: Path): Path {
        tempDir.createDirectories()
        val repo = setupDir(tempDir, testInput, "base")
        setupGitRepo(repo.toFile(), "main", "file")
        Git(repo)("config", "core.autocrlf", "false").executeSilently()
        return repo
    }

    companion object {
        private val testResource: Path = Path.of("src/test/resources/apply_patches/feature_patches")
        private val testInput: Path = testResource.resolve("input")
    }
}
