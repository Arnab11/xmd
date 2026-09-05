package com.invictus.xmd.utils.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.invictus.xmd.database.entities.QueueItem
import com.invictus.xmd.domain.download.DownloadCategory
import com.invictus.xmd.domain.download.ItemStatus

class FileNameUtilsTest {

    @Test
    fun testResolveDestinationFileWithCustomDir() {
        val file = FileNameUtils.resolveDestinationFile(
            fileName = "sample.mp4",
            customSaveDir = "/storage/emulated/0/MyFolder",
            category = DownloadCategory.VIDEOS
        )
        assertEquals(File("/storage/emulated/0/MyFolder/sample.mp4"), file)
    }

    @Test
    fun testNumberedNameIfExistsWhenUnique() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "xmd_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val target = File(tempDir, "video.mp4")
            val numbered = FileNameUtils.numberedNameIfExists(target, emptySet())
            assertEquals("video.mp4", numbered)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testNumberedNameIfExistsWhenFileExistsOnDisk() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "xmd_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val file1 = File(tempDir, "video.mp4")
            file1.writeText("test content")

            val numbered1 = FileNameUtils.numberedNameIfExists(file1, emptySet())
            assertEquals("video_1.mp4", numbered1)

            val file2 = File(tempDir, "video_1.mp4")
            file2.writeText("test content 2")

            val numbered2 = FileNameUtils.numberedNameIfExists(file1, emptySet())
            assertEquals("video_2.mp4", numbered2)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testNumberedNameIfExistsWhenInActiveQueue() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "xmd_test_${System.currentTimeMillis()}")
        val file = File(tempDir, "song.mp3")
        val activeQueue = setOf(file, File(tempDir, "song_1.mp3"))

        val numbered = FileNameUtils.numberedNameIfExists(file, activeQueue)
        assertEquals("song_2.mp3", numbered)
    }

    @Test
    fun testDuplicateDetectionByDestinationNotUrl() {
        val tempFolder = "/storage/emulated/0/Downloads"
        val existingItem = QueueItem(
            id = "1",
            sourceUrl = "https://example.com/same_url.mp4",
            fileName = "original_name.mp4",
            customSaveDirPath = tempFolder,
            category = DownloadCategory.VIDEOS,
            status = ItemStatus.DOWNLOADING
        )

        val queue = listOf(existingItem)

        // 1. Same URL but DIFFERENT file name -> NOT a duplicate!
        val differentNameFile = File(tempFolder, "renamed_video.mp4")
        assertNull(FileNameUtils.findConflictingDownload(differentNameFile, queue))

        // 2. Same destination file (even if from another URL) -> IS a duplicate!
        val sameDestinationFile = File(tempFolder, "original_name.mp4")
        val conflicting = FileNameUtils.findConflictingDownload(sameDestinationFile, queue)
        assertNotNull(conflicting)
        assertEquals("1", conflicting?.id)
    }
}
