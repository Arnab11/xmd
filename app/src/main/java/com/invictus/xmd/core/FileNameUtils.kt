package com.invictus.xmd.core

import java.io.File

/**
 * Strategy chosen when adding a download whose destination file conflicts
 * with an existing download item or on-disk file.
 *
 * Mirrors `ab-download-manager`'s `OnDuplicateStrategy`.
 */
enum class OnDuplicateStrategy {
    /** Appends an index (e.g. `file_1.ext`) so the download creates a new separate file. */
    AddNumbered,

    /** Overwrites the existing on-disk file and replaces the conflicting queue item. */
    OverrideDownload;

    companion object {
        fun default() = AddNumbered
    }
}

object FileNameUtils {

    /**
     * Computes the target save directory for a file given an optional custom directory
     * override and category, respecting Settings default save location and categorization flag.
     */
    fun resolveDestinationFolder(customSaveDir: String?, category: DownloadCategory): File {
        return if (!customSaveDir.isNullOrBlank()) {
            File(customSaveDir)
        } else {
            val saveRoot = File(Settings.defaultSaveLocation())
            if (Settings.categorizationDisabled()) {
                saveRoot
            } else {
                File(saveRoot, category.folderName)
            }
        }
    }

    /**
     * Computes the absolute target [File] on disk for a download item.
     */
    fun resolveDestinationFile(fileName: String, customSaveDir: String?, category: DownloadCategory): File {
        return File(resolveDestinationFolder(customSaveDir, category), fileName)
    }

    /**
     * Determines the destination file for an existing [QueueItem].
     */
    fun destinationFileOf(item: QueueItem): File? {
        if (!item.filePath.isNullOrBlank()) {
            return File(item.filePath!!)
        }
        val name = item.fileName?.takeUnless { it.isBlank() } ?: return null
        return resolveDestinationFile(name, item.customSaveDirPath, item.category)
    }

    /**
     * Finds an existing download in the queue whose destination file matches [targetFile].
     */
    fun findConflictingDownload(targetFile: File, queue: List<QueueItem>): QueueItem? {
        val targetPath = targetFile.absoluteFile
        return queue.firstOrNull { item ->
            val existingFile = destinationFileOf(item)?.absoluteFile
            existingFile != null && existingFile == targetPath
        }
    }

    /**
     * Returns whether [targetFile] conflicts with an already-finished/on-disk file
     * or an item currently tracked in [queue].
     */
    fun isDuplicate(targetFile: File, queue: List<QueueItem>): Boolean {
        if (targetFile.exists()) return true
        return findConflictingDownload(targetFile, queue) != null
    }

    /**
     * Generates a numbered file name if [targetFile] exists on disk or matches
     * any file in [activeQueueFiles], matching ab-download-manager's numbered naming.
     * For example, `video.mp4` -> `video_1.mp4`, `video_2.mp4`, etc.
     */
    fun numberedNameIfExists(targetFile: File, activeQueueFiles: Set<File> = emptySet()): String {
        val targetAbs = targetFile.absoluteFile
        val activeAbs = activeQueueFiles.map { it.absoluteFile }.toSet()
        if (!targetAbs.exists() && targetAbs !in activeAbs) {
            return targetFile.name
        }
        val ext = targetFile.extension.takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
        val baseName = targetFile.nameWithoutExtension
        val parent = targetFile.parentFile

        var counter = 1
        while (counter < 100_000) {
            val candidate = File(parent, "${baseName}_${counter}${ext}").absoluteFile
            if (!candidate.exists() && candidate !in activeAbs) {
                return candidate.name
            }
            counter++
        }
        return "${baseName}_${System.currentTimeMillis()}${ext}"
    }
}
