package com.invictus.xmd.domain.download

import com.invictus.xmd.domain.torrent.TorrentEngine

/** Status of a single queued link as it moves through resolve -> download. */
enum class ItemStatus {
    PENDING,
    RESOLVING,
    NEEDS_CHALLENGE,
    READY,
    DOWNLOADING,
    PAUSED,
    RETRYING,
    SAVING,
    DONE,
    FAILED
}

/**
 * Where a queue item's bytes actually come from. DIRECT covers everything
 * the app already handled (FuckingFast share links, generic direct URLs,
 * fitgirl-expanded links, magnet/.torrent via TorrentEngine) via their own
 * existing engines. YOUTUBE is downloaded/merged by yt-dlp itself (see
 * YtDlpManager) instead -- no directUrl, its own percent-based progress.
 */
enum class MediaPlatform {
    DIRECT,
    YOUTUBE
}

/**
 * User-facing download category. Each maps to its own subfolder under the
 * app's downloads directory (auto-created on first download in that
 * category). Auto-detected per link from its file extension by
 * [CategoryDetector] -- not user-picked anymore (IDM-style auto-categorization).
 */
enum class DownloadCategory(val folderName: String, val label: String) {
    VIDEOS("Videos", "Videos"),
    MUSIC("Music", "Music"),
    DOCUMENTS("Documents", "Documents"),
    APPS("Apps", "Apps"),
    OTHERS("Others", "Others");

    companion object {
        fun default() = OTHERS
    }
}

class ResolutionError(message: String) : Exception(message)
class DownloadCancelledException(message: String = "Download cancelled") : Exception(message)
