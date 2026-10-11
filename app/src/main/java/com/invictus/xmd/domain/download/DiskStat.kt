package com.invictus.xmd.domain.download

import java.io.File

/**
 * Free-space queries, mirroring ABDM's `IDistStat.getRemainingSpace`.
 * Uses [File.usableSpace] of the nearest existing ancestor so it works
 * even when the target file/folder doesn't exist yet.
 */
object DiskStat {
    fun getRemainingSpace(path: File): Long {
        var dir: File? = path.absoluteFile
        while (dir != null && !dir.exists()) {
            dir = dir.parentFile
        }
        return try {
            (dir ?: path).usableSpace
        } catch (_: Exception) {
            -1L
        }
    }
}
