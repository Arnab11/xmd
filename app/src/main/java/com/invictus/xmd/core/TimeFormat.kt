package com.invictus.xmd.core

/**
 * Formats remaining time the way Chrome's own Android download UI does
 * (chrome/android/.../download/DownloadUtils#formatRemainingTime): a
 * single rounded unit -- seconds, minutes, hours, or days -- so the
 * label never shows something like "1:03:59" and instead reads "1 hour
 * left", rounding the shown unit using the leftover fraction of the next
 * smaller one (e.g. 92 minutes -> "2 hours left", not "1 hour left").
 *
 * Shared by DownloadsScreen's per-row ETA text and DownloadService's
 * notification detail line so both surfaces show the identical format.
 */
fun formatRemainingTimeChrome(totalSeconds: Long): String {
    var remaining = totalSeconds.coerceAtLeast(0)
    var days = 0L
    var hours = 0L
    var minutes = 0L
    if (remaining >= 86_400L) {
        days = remaining / 86_400L
        remaining -= days * 86_400L
    }
    if (remaining >= 3_600L) {
        hours = remaining / 3_600L
        remaining -= hours * 3_600L
    }
    if (remaining >= 60L) {
        minutes = remaining / 60L
        remaining -= minutes * 60L
    }
    val seconds = remaining

    return when {
        days >= 2 -> "${days + (hours + 12) / 24} days left"
        days > 0 -> "1 day left"
        hours >= 2 -> "${hours + (minutes + 30) / 60} hours left"
        hours > 0 -> "1 hour left"
        minutes >= 2 -> "${minutes + (seconds + 30) / 60} mins left"
        minutes > 0 -> "1 min left"
        seconds == 1L -> "1 sec left"
        else -> "$seconds secs left"
    }
}
