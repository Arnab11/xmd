package com.invictus.xmd.domain.download

/**
 * Base for failures that mean the destination itself can't be prepared
 * (no space, unwritable folder, ...). Mirrors ABDM's
 * `DownloadValidationException` -- these are critical: the job must pause/
 * fail immediately instead of going through network auto-retry, since
 * retrying the same dead link would just burn battery/data.
 */
open class DownloadValidationException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause) {
    open fun isCritical(): Boolean = false
}

/** Not enough free space for the download. Critical -- no retry. */
class NoSpaceInStorageException(available: Long, required: Long) :
    DownloadValidationException(
        "Not enough storage (need ${required / 1024} KB, ${available / 1024} KB free)."
    ) {
    override fun isCritical(): Boolean = true
}

/** Wraps any exception thrown while preparing the destination file/folder. Critical. */
class PrepareDestinationFailedException(cause: Throwable) :
    DownloadValidationException("Couldn't prepare download file: ${cause.message}", cause) {
    override fun isCritical(): Boolean = true
}
