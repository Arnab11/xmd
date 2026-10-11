package com.invictus.xmd.domain.download

import java.io.File

/**
 * Incomplete-file naming, ported from ABDM's `IncompleteFileUtil`.
 *
 * While a download is in flight its bytes live in
 * `<name>.dl-<id>.xmd.part` next to the real target instead of the real
 * name itself, so a half-finished file never masquerades as complete
 * (in the queue, in a file manager, or to a media scanner). On success the
 * part file is atomically renamed to the real name.
 *
 * The name is trimmed when it would exceed common filename limits.
 */
object IncompleteFileUtil {
    private const val MAX_NAME_LEN = 200

    fun addIncompleteIndicator(file: File, id: Long): File {
        val parent = file.parentFile ?: return File(file.name + ".dl-$id.xmd.part")
        var base = file.name
        val suffix = ".dl-$id.xmd.part"
        if (base.length + suffix.length > MAX_NAME_LEN) {
            val ext = file.extension.takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
            val keep = (MAX_NAME_LEN - suffix.length - ext.length).coerceAtLeast(8)
            base = file.nameWithoutExtension.take(keep) + ext
        }
        return File(parent, base + suffix)
    }

    fun isIncompleteFile(file: File): Boolean =
        file.name.endsWith(".xmd.part")

    /** Finds a leftover part file for [target] regardless of the id suffix. */
    fun findStalePart(target: File): File? {
        val parent = target.parentFile ?: return null
        val prefix = target.nameWithoutExtension.take(40)
        return parent.listFiles { f ->
            f.name != target.name && f.name.startsWith(prefix) && f.name.endsWith(".xmd.part")
        }?.firstOrNull()
    }
}
