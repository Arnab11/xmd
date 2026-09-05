package com.invictus.xmd.utils

/**
 * Shared formatting utilities for byte counts, transfer speeds, and remaining times.
 * Used across UI (DownloadsScreen) and background services (DownloadService) to ensure
 * consistent unit display throughout the application.
 */

/** Bytes → human-readable string using binary prefixes (B, KB, MB, GB). */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.2f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.0f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

/** Bytes-per-second transfer rate → human-readable speed string (B/s, KB/s, MB/s). */
fun formatSpeed(bps: Double): String = when {
    bps >= 1_048_576.0 -> "%.1f MB/s".format(bps / 1_048_576.0)
    bps >= 1_024.0 -> "%.0f KB/s".format(bps / 1_024.0)
    bps > 0.0 -> "%.0f B/s".format(bps)
    else -> ""
}
