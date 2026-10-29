package `fun`.bm.comfreyweight.core.tasks.patching

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.core.tasks.patching.ApplyFeaturePatches
import io.papermc.paperweight.core.tasks.patching.parseFeaturePatchMetadata
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

class ApplyFeaturePatchesTest : TaskTest() {
    private lateinit var applyTask: ApplyFeaturePatches
    private lateinit var continueTask: ApplyFeaturePatches

    @BeforeTest
    fun setup() {
        val project = setupProject()
        applyTask = project.tasks.register("applyFeaturePatches", ApplyFeaturePatches::class).get()
        continueTask = project.tasks.register("continueFeaturePatches", ApplyFeaturePatches::class).get()
    }

    @Test
    fun `should parse feature patch metadata`() {
        val metadata = parseFeaturePatchMetadata(testResource.resolve("input/metadata.patch"))
        assertEquals("MiniDigger | Martin", metadata.authorName)
        assertEquals("admin@benndorf.dev", metadata.authorEmail)
        assertEquals("Sun, 20 Oct 2024 09:24:36 +0200", metadata.date)
        assertEquals("Feature\n\nBody line.", metadata.message)
    }

    @Test
    fun `should apply feature patches`(@TempDir tempDir: Path) {
        val repo = createFeatureRepo(tempDir)
        val patches = tempDir.resolve("patches").createDirectories()
        testInput.resolve("patches/0001-Change-a.patch").copyTo(patches.resolve("0001-Change-a.patch"))

        configureApply(repo, patches)
        applyTask.run()

        assertEquals(testResource.resolve("output/applied/Test.java").readText(), repo.resolve("Test.java").readText())
        assertEquals("Change a", logSubject(repo))
        assertEquals("Patch Author", Git(repo)("log", "-1", "--format=%an").getText().trim())
        assertEquals("patch@example.com", Git(repo)("log", "-1", "--format=%ae").getText().trim())
        assertEquals("Patch Author", Git(repo)("log", "-1", "--format=%cn").getText().trim())
        assertEquals("patch@example.com", Git(repo)("log", "-1", "--format=%ce").getText().trim())
    }

    @Test
    fun `should roll back the failed patch and continue with original metadata`(@TempDir tempDir: Path) {
        val repo = createFeatureRepo(tempDir)
        val patches = tempDir.resolve("patches")
        testInput.resolve("patches").copyRecursivelyTo(patches)

        configureApply(repo, patches)
        applyTask.continueTaskName.set("continueFeaturePatches")

        val ex = assertFailsWith<PaperweightException> { applyTask.run() }
        assertContains(ex.message!!, "0002-Broken.patch")

        val testJava = repo.resolve("Test.java").readText()
        assertEquals(testResource.resolve("output/applied/Test.java").readText(), testJava)
        assertFalse(testJava.contains("doesNotExist"))
        assertFalse(testJava.contains("return \"b3\";"))
        assertEquals("Change a", logSubject(repo))
        assertTrue(Git(repo)("status", "--porcelain").getText().isBlank())
        assertTrue(repo.resolve(".git/paperweight-feature-patch-continue.json").isRegularFile())

        testInput.resolve("continue/Test.java").copyTo(repo.resolve("Test.java"), overwrite = true)

        continueTask.repo.set(repo.toFile())
        continueTask.patches.set(patches.toFile())
        continueTask.continueApply.set(true)
        continueTask.printOutput.set(true)
        continueTask.verbose.set(true)
        continueTask.run()

        assertEquals(testResource.resolve("output/continued/Test.java").readText(), repo.resolve("Test.java").readText())
        assertFalse(repo.resolve(".git/paperweight-feature-patch-continue.json").exists())
        assertEquals("3", Git(repo)("rev-list", "--count", "file..HEAD").getText().trim())

        val author = Git(repo)("log", "-1", "--skip=1", "--format=%an%n%ae%n%cn%n%ce%n%s%n%b").getText().trim().lines()
        assertEquals("Continue Author", author[0])
        assertEquals("continue@example.com", author[1])
        assertEquals("Continue Author", author[2])
        assertEquals("continue@example.com", author[3])
        assertEquals("Broken patch", author[4])
        assertTrue(author.drop(5).any { it == "Keep the original patch metadata." })
        assertEquals("Change b", logSubject(repo))
        assertEquals("Patch Author", Git(repo)("log", "-1", "--format=%an").getText().trim())
        assertEquals("patch@example.com", Git(repo)("log", "-1", "--format=%ae").getText().trim())
    }

    private fun configureApply(repo: Path, patches: Path) {
        applyTask.repo.set(repo.toFile())
        applyTask.patches.set(patches.toFile())
        applyTask.printOutput.set(true)
        applyTask.verbose.set(true)
    }

    private fun createFeatureRepo(tempDir: Path): Path {
        val repo = setupDir(tempDir, testInput, "base")
        setupGitRepo(repo.toFile(), "main", "file")
        Git(repo)("config", "core.autocrlf", "false").executeSilently()
        return repo
    }

    private fun logSubject(repo: Path): String {
        return Git(repo)("log", "-1", "--format=%s").getText().trim()
    }

    companion object {
        private val testResource: Path = Path.of("src/test/resources/apply_patches/feature_patches")
        private val testInput: Path = testResource.resolve("input")
    }
}
