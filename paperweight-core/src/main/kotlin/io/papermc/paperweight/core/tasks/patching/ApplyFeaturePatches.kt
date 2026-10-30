/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2023 Kyle Wood (DenWav)
 *                    Contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation;
 * version 2.1 only, no later versions.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package io.papermc.paperweight.core.tasks.patching

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "The task mutates a Git repository and retains failure state for manual recovery")
abstract class ApplyFeaturePatches : ControllableOutputTask() {

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val base: DirectoryProperty

    @get:OutputDirectory
    abstract val repo: DirectoryProperty

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputDirectory
    @get:Optional
    abstract val patches: DirectoryProperty

    @get:Input
    abstract val verbose: Property<Boolean>

    @get:Internal
    abstract val continueApply: Property<Boolean>

    @get:Internal
    abstract val dropCurrent: Property<Boolean>

    @get:Internal
    abstract val continueTaskName: Property<String>

    override fun init() {
        printOutput.convention(false).finalizeValueOnRead()
        verbose.convention(false)
        continueApply.convention(false)
        dropCurrent.convention(false)
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        if (continueApply.get()) {
            continueApplyingPatches(dropCurrent = dropCurrent.get())
            return
        }

        val base = base.pathOrNull
        if (base != null && base.toAbsolutePath() != repo.path.toAbsolutePath()) {
            val git = Git(repo.path.createDirectories())
            checkoutRepoFromUpstream(
                git,
                base,
                "file",
                branchName = "main",
                ref = true,
            )
        }

        if (!patches.isPresent) {
            return
        }

        val repoPath = repo.path

        val git = Git(repoPath)

        if (git("checkout", "main").runSilently(silenceErr = true) != 0) {
            git("checkout", "-b", "main").runSilently(silenceErr = true)
        }
        git("reset", "--hard", "file").executeSilently(silenceErr = true)
        git("gc").runSilently(silenceErr = true)

        applyGitPatches(git, "server repo", repoPath, loadPatchFiles(patches.path), printOutput.get(), verbose.get())
    }

    /**
     * Continues a feature-patch apply after a failure.
     *
     * The failed patch is rolled back when apply fails. After the user manually applies those
     * changes without committing, this commits the current working tree using the original patch
     * metadata and then applies the remaining patches.
     */
    fun continueApplyingPatches(skipIfIdle: Boolean = false, dropCurrent: Boolean = false) {
        Git.checkForGit()

        val taskLabel = continueTaskName.orNull ?: name
        if (!patches.isPresent || !repo.isPresent) {
            if (skipIfIdle) {
                logger.lifecycle("Skipping $taskLabel: patches directory or repo is not set")
                return
            }
            throw PaperweightException("Cannot continue applying feature patches: patches directory is not set")
        }

        val repoPath = repo.path
        if (!repoPath.exists() || loadFeaturePatchContinueState(repoPath) == null) {
            if (skipIfIdle) {
                logger.lifecycle("Skipping $taskLabel: no interrupted feature patch apply")
                return
            }
            throw PaperweightException(
                "No interrupted feature patch apply to continue. Run the apply feature patches task first."
            )
        }

        val git = Git(repoPath)
        git("am", "--quit").runSilently(silenceErr = true)
        val state = loadFeaturePatchContinueState(repoPath)
            ?: throw PaperweightException(
                "No interrupted feature patch apply to continue. Run the apply feature patches task first."
            )

        val patchDir = patches.path
        val failedPatch = patchDir.resolve(state.failedPatch)
        if (!failedPatch.isRegularFile()) {
            throw PaperweightException("Failed patch ${state.failedPatch} does not exist in $patchDir")
        }

        if (dropCurrent) {
            logger.lifecycle("Dropping failed patch ${state.failedPatch}")
            git("reset", "--hard", "HEAD").runSilently(silenceErr = true)
            git("clean", "-fd").runSilently(silenceErr = true)
        } else {
            val dirty = git("status", "--porcelain").getText()
            if (dirty.isNotBlank()) {
                commitWithPatchMetadata(git, repoPath, failedPatch)
            } else {
                logger.lifecycle("No local changes; skipping commit for ${state.failedPatch}")
            }
        }

        val remaining = state.remainingPatches.map { name ->
            val patch = patchDir.resolve(name)
            if (!patch.isRegularFile()) {
                throw PaperweightException("Remaining patch $name does not exist in $patchDir")
            }
            patch
        }

        clearFeaturePatchContinueState(repoPath)

        if (remaining.isEmpty()) {
            if (printOutput.get()) {
                logger.lifecycle("No remaining patches")
            }
            return
        }

        applyGitPatches(git, "server repo", repoPath, remaining, printOutput.get(), verbose.get())
    }

    private fun applyGitPatches(
        git: Git,
        target: String,
        outputDir: Path,
        patches: List<Path>,
        printOutput: Boolean,
        verbose: Boolean,
    ) {
        if (printOutput) {
            logger.lifecycle("Applying patches to $target...")
        }

        clearFeaturePatchContinueState(outputDir)
        git("am", "--quit").runSilently(silenceErr = true)

        if (patches.isEmpty()) {
            if (printOutput) {
                logger.lifecycle("No patches found")
            }
            return
        }

        val gitOut = printOutput && verbose
        for ((index, patch) in patches.withIndex()) {
            val metadata = parseFeaturePatchMetadata(patch)
            val result = git.withEnv(gitIdentityEnv(metadata))(
                "am",
                "--3way",
                "--ignore-whitespace",
                "--committer-date-is-author-date",
                patch.absolutePathString(),
            ).captureOut(gitOut)
            if (result.exit == 0) {
                continue
            }

            if (!gitOut) {
                logger.lifecycle(result.out)
            }

            rollbackFailedPatch(git, outputDir)
            saveFeaturePatchContinueState(
                outputDir,
                FeaturePatchContinueState(
                    failedPatch = patch.fileName.toString(),
                    remainingPatches = patches.drop(index + 1).map { it.fileName.toString() },
                )
            )

            logger.error("***   Failed to apply patch ${patch.fileName}")
            logger.error("***   The failed patch has been rolled back.")
            logger.error("***   Manually apply the changes from ${patch.fileName}, then run:")
            val continueName = continueTaskName.orNull
            if (continueName != null) {
                logger.error("***     ./gradlew $continueName")
            } else {
                logger.error("***     the continue feature patches task")
            }
            logger.error("***   Do not commit; the continue task commits the current working tree")
            logger.error("***   using the original patch metadata.")

            throw PaperweightException("Failed to apply patch ${patch.fileName}")
        }

        clearFeaturePatchContinueState(outputDir)
        if (printOutput) {
            logger.lifecycle("${patches.size} patches applied cleanly to $target")
        }
    }

    private fun commitWithPatchMetadata(git: Git, repoPath: Path, patch: Path) {
        val metadata = parseFeaturePatchMetadata(patch)
        git("add", "--all").executeSilently(silenceErr = true)

        val messageFile = repoPath.resolve(".git/PAPERWEIGHT_CONTINUE_COMMIT_MSG")
        try {
            messageFile.writeText(metadata.message)
            git.withEnv(gitIdentityEnv(metadata))("commit", "-F", messageFile.absolutePathString()).executeOut()
        } finally {
            messageFile.deleteForcefully()
        }
    }

    private fun gitIdentityEnv(metadata: FeaturePatchMetadata): Map<String, String> {
        val env = mutableMapOf(
            "GIT_AUTHOR_NAME" to metadata.authorName,
            "GIT_AUTHOR_EMAIL" to metadata.authorEmail,
            "GIT_COMMITTER_NAME" to metadata.authorName,
            "GIT_COMMITTER_EMAIL" to metadata.authorEmail,
        )
        if (metadata.date != null) {
            env["GIT_AUTHOR_DATE"] = metadata.date
            env["GIT_COMMITTER_DATE"] = metadata.date
        }
        return env
    }

    private fun rollbackFailedPatch(git: Git, outputDir: Path) {
        if (git("am", "--quit").runSilently(silenceErr = true) != 0) {
            outputDir.resolve(".git/rebase-apply").deleteRecursive()
            outputDir.resolve(".git/am").deleteRecursive()
        }
        git("reset", "--hard", "HEAD").runSilently(silenceErr = true)
        git("clean", "-fd").runSilently(silenceErr = true)
    }

    private fun loadPatchFiles(patchDir: Path): List<Path> {
        return patchDir.useDirectoryEntries("*.patch") { it.toList() }
            .sortedBy { it.fileName.toString() }
    }

    private fun saveFeaturePatchContinueState(outputDir: Path, state: FeaturePatchContinueState) {
        outputDir.resolve(PATCH_APPLY_FAILED).writeText("1")
        featurePatchContinueStateFile(outputDir).writeText(gson.toJson(state))
    }

    private fun loadFeaturePatchContinueState(outputDir: Path): FeaturePatchContinueState? {
        val stateFile = featurePatchContinueStateFile(outputDir)
        if (!stateFile.isRegularFile()) {
            return null
        }
        return gson.fromJson(stateFile)
    }

    private fun clearFeaturePatchContinueState(outputDir: Path) {
        outputDir.resolve(PATCH_APPLY_FAILED).deleteForcefully()
        featurePatchContinueStateFile(outputDir).deleteForcefully()
    }

    private fun featurePatchContinueStateFile(outputDir: Path): Path {
        return outputDir.resolve(".git").resolve(CONTINUE_STATE_FILE)
    }

    companion object {
        const val PATCH_APPLY_FAILED = ".git/patch-apply-failed"
        const val CONTINUE_STATE_FILE = "paperweight-feature-patch-continue.json"
    }
}

internal data class FeaturePatchContinueState(
    val failedPatch: String,
    val remainingPatches: List<String>,
)

internal data class FeaturePatchMetadata(
    val authorName: String,
    val authorEmail: String,
    val date: String?,
    val message: String,
)

internal fun parseFeaturePatchMetadata(patch: Path): FeaturePatchMetadata {
    val lines = patch.readLines()
    var authorName = ""
    var authorEmail = ""
    var date: String? = null
    val subjectParts = mutableListOf<String>()
    var inSubject = false
    var bodyStart = -1

    for ((index, line) in lines.withIndex()) {
        if (index == 0 && line.startsWith("From ")) {
            continue
        }
        when {
            line.startsWith("From: ") -> {
                inSubject = false
                val parsed = parsePatchFromHeader(line.substring(6).trim())
                authorName = parsed.first
                authorEmail = parsed.second
            }
            line.startsWith("Date: ") -> {
                inSubject = false
                date = line.substring(6).trim()
            }
            line.startsWith("Subject: ") -> {
                inSubject = true
                subjectParts.clear()
                subjectParts.add(line.substring(9))
            }
            inSubject && (line.startsWith(" ") || line.startsWith("\t")) -> {
                subjectParts.add(line.trim())
            }
            line.isBlank() -> {
                bodyStart = index + 1
                break
            }
            else -> inSubject = false
        }
    }

    val subject = stripPatchSubjectPrefix(subjectParts.joinToString(" ").trim())
    val bodyLines = mutableListOf<String>()
    if (bodyStart >= 0) {
        for (j in bodyStart until lines.size) {
            val line = lines[j]
            if (line == "---" || line.startsWith("diff --git ")) {
                break
            }
            bodyLines.add(line)
        }
        while (bodyLines.isNotEmpty() && bodyLines.last().isBlank()) {
            bodyLines.removeAt(bodyLines.lastIndex)
        }
    }

    val message = if (bodyLines.isEmpty()) {
        subject
    } else {
        subject + "\n\n" + bodyLines.joinToString("\n")
    }

    if (authorEmail.isEmpty() || subject.isEmpty()) {
        throw PaperweightException("Failed to parse patch metadata from ${patch.fileName}")
    }
    if (authorName.isEmpty()) {
        authorName = authorEmail
    }

    return FeaturePatchMetadata(authorName, authorEmail, date, message)
}

private fun parsePatchFromHeader(from: String): Pair<String, String> {
    val match = Regex("^(.*) <([^>]+)>$").find(from)
    return if (match != null) {
        match.groupValues[1].trim().removeSurrounding("\"") to match.groupValues[2].trim()
    } else {
        from.trim() to from.trim()
    }
}

private fun stripPatchSubjectPrefix(subject: String): String {
    return subject.replace(Regex("^\\[PATCH[^]]*\\]\\s*"), "")
}
