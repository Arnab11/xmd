package com.invictus.xmd.utils.storage

import android.util.Log
import com.invictus.xmd.database.entities.QueueItem
import com.invictus.xmd.domain.download.ItemStatus
import java.io.File

/**
 * Writes a .m3u8 playlist file listing the finished downloads from one
 * playlist bulk-add (see AddDownloadDialog's "Playlist file" Advanced
 * toggle, off by default, and QueueRepository.maybeGeneratePlaylistFile
 * which calls this once every item in the batch has reached a terminal
 * state) -- so the whole playlist can be opened in one shot in VLC/any
 * other player instead of hunting down N separate files.
 *
 * Only entries that actually finished (ItemStatus.DONE, file still present
 * on disk) are listed -- a failed download just doesn't get a line, rather
 * than leaving a dangling reference in the playlist.
 */
object PlaylistFileUtils {
    private const val TAG = "PlaylistFileUtils"

    /** Filesystem entries for the finished downloads, resolved once from [batchItems]. */
    private fun finishedFiles(batchItems: List<QueueItem>): List<File> =
        batchItems
            .filter { it.status == ItemStatus.DONE }
            .mapNotNull { item -> item.filePath?.takeUnless { it.isBlank() } }
            .map(::File)
            .filter { it.exists() }

    /**
     * Writes `<playlistTitle>.m3u8` (falls back to "Playlist" if blank) into
     * the same folder as the downloaded files themselves, listing every
     * entry from [batchItems] that finished successfully. File paths are
     * written relative (just the file name) since the .m3u8 sits in that
     * same folder -- keeps the playlist working if the whole folder is
     * later moved or copied elsewhere. No-ops if nothing in the batch
     * finished.
     */
    fun writeM3u8(batchItems: List<QueueItem>, playlistTitle: String?) {
        val entries = finishedFiles(batchItems)
        if (entries.isEmpty()) return

        val playlistFolder = entries.first().parentFile ?: return
        val safeName = sanitizeFileName(playlistTitle?.takeUnless { it.isBlank() } ?: "Playlist")
        val m3u8File = File(playlistFolder, "$safeName.m3u8")

        runCatching {
            m3u8File.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write("#EXTM3U")
                writer.newLine()
                entries.forEach { file ->
                    writer.write("#EXTINF:-1,${file.nameWithoutExtension}")
                    writer.newLine()
                    writer.write(file.name)
                    writer.newLine()
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed writing playlist file for \"$playlistTitle\"", e)
        }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120).ifBlank { "Playlist" }
}
