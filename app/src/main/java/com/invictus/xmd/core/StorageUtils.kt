package com.invictus.xmd.core

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * Resolves a tree [Uri] returned by ActivityResultContracts.OpenDocumentTree
 * (the SAF folder picker used both for a per-download custom save dir --
 * MainActivity's pickSaveDirLauncher -- and for Settings' default save
 * location) down to a plain absolute path on primary external storage.
 * Returns null for anything outside primary storage (e.g. an SD card or
 * other volume), same as before this was shared.
 */
object StorageUtils {
    fun resolveTreeUriToPath(treeUri: Uri): String? {
        return runCatching {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val parts = docId.split(":", limit = 2)
            val volume = parts.getOrNull(0)
            val relativePath = parts.getOrNull(1).orEmpty()
            if (!volume.equals("primary", ignoreCase = true)) return@runCatching null
            val base = Environment.getExternalStorageDirectory()
            (if (relativePath.isBlank()) base else File(base, relativePath)).absolutePath
        }.getOrNull()
    }
}
