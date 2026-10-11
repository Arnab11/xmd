package com.invictus.xmd.domain.download

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Pre-allocates / validates the destination file before any bytes are
 * fetched, ported from ABDM's `EmptyFileCreator`.
 *
 * - Unknown length (-1): truncate to 0, nothing to reserve.
 * - Existing file: throws [NoSpaceInStorageException] when the growth
 *   doesn't fit; truncates when too big; extends when too small.
 * - New file: throws when it doesn't fit, then tries the sparse fast-path
 *   (single byte at the end -- the FS makes a hole, instant even for GBs)
 *   and falls back to zero-filling with progress when sparse isn't usable.
 *
 * Call on Dispatchers.IO -- the zero-fill fallback does real disk I/O.
 */
object EmptyFileCreator {
    private const val BUFFER_SIZE = 8192

    fun prepareFile(file: File, length: Long, onProgressUpdate: ((Int?) -> Unit)? = null) {
        if (length < 0) {
            // Unknown length -- just make sure we start from an empty file.
            if (file.exists()) {
                RandomAccessFile(file, "rw").use { it.setLength(0) }
            }
            onProgressUpdate?.invoke(null)
            return
        }

        if (file.exists()) {
            val currentLength = file.length()
            when {
                currentLength == length -> {
                    onProgressUpdate?.invoke(null)
                    return
                }
                currentLength > length -> {
                    RandomAccessFile(file, "rw").use { it.setLength(length) }
                    onProgressUpdate?.invoke(null)
                    return
                }
                else -> {
                    val required = length - currentLength
                    val remaining = DiskStat.getRemainingSpace(file)
                    if (remaining >= 0 && remaining < required) {
                        throw NoSpaceInStorageException(remaining, required)
                    }
                    if (trySparseExtend(file, length)) {
                        onProgressUpdate?.invoke(null)
                        return
                    }
                    fillOutput(file, required, onProgressUpdate)
                    return
                }
            }
        }

        // New file.
        val remaining = DiskStat.getRemainingSpace(file)
        if (remaining >= 0 && remaining < length) {
            throw NoSpaceInStorageException(remaining, length)
        }
        file.parentFile?.mkdirs()
        if (trySparseExtend(file, length)) {
            onProgressUpdate?.invoke(null)
            return
        }
        file.createNewFile()
        fillOutput(file, length, onProgressUpdate)
    }

    /**
     * Sparse fast-path: create (or reuse) the file and write a single zero
     * byte at the last position. On ext4/f2fs (Android's data partitions)
     * the middle blocks are a hole -- instant, no real I/O. Returns false
     * when anything goes wrong so the caller falls back to zero-fill.
     */
    private fun trySparseExtend(file: File, length: Long): Boolean {
        if (length <= 0) {
            return runCatching {
                if (!file.exists()) file.createNewFile()
                RandomAccessFile(file, "rw").use { it.setLength(0) }
                true
            }.getOrDefault(false)
        }
        return runCatching {
            file.parentFile?.mkdirs()
            if (!file.exists()) file.createNewFile()
            RandomAccessFile(file, "rw").use {
                it.seek(length - 1)
                it.write(0)
            }
            file.length() == length
        }.getOrDefault(false)
    }

    private fun fillOutput(file: File, much: Long, onProgressUpdate: ((Int?) -> Unit)?) {
        var written = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        FileOutputStream(file, true).use { out ->
            while (written < much) {
                val chunk = minOf(BUFFER_SIZE.toLong(), much - written).toInt()
                out.write(buffer, 0, chunk)
                written += chunk
                onProgressUpdate?.invoke(((written * 100) / much).toInt().coerceIn(0, 100))
                // Re-check mid-fill: another app could eat space concurrently.
                if (written % (BUFFER_SIZE * 128) == 0L) {
                    val remaining = DiskStat.getRemainingSpace(file)
                    if (remaining >= 0 && remaining < (much - written)) {
                        throw NoSpaceInStorageException(remaining, much - written)
                    }
                }
            }
            out.fd.sync()
        }
        onProgressUpdate?.invoke(null)
    }
}
