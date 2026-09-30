package com.invictus.xmd.utils.media

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.roundToInt

/**
 * On-demand probe of a finished media file for the download Details dialog.
 * Nothing is persisted -- it just reads the file, so old downloads work too.
 * Every field is null when it can't be determined (non-media file, corrupt
 * file, missing track, ...).
 */
data class MediaProbe(
    val durationMs: Long?,
    /** "H.264 / AAC" for video, "AAC" for audio-only. */
    val codec: String?,
    /** "1080p • 5.2 Mbps • 30 fps" for video, "128 kbps" for audio-only. */
    val quality: String?,
)

object MediaInfoProbe {

    fun probe(path: String): MediaProbe? {
        val file = File(path)
        if (!file.isFile) return null

        var durationMs: Long? = null
        var overallBitrate: Long? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            overallBitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }

        var videoMime: String? = null
        var audioMime: String? = null
        var width = 0
        var height = 0
        var fps = 0
        var videoBitrate = 0L
        var audioBitrate = 0L
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") && videoMime == null -> {
                        videoMime = mime
                        width = f.intOrZero(MediaFormat.KEY_WIDTH)
                        height = f.intOrZero(MediaFormat.KEY_HEIGHT)
                        fps = f.numberOrZero(MediaFormat.KEY_FRAME_RATE)
                        videoBitrate = f.intOrZero(MediaFormat.KEY_BIT_RATE).toLong()
                    }
                    mime.startsWith("audio/") && audioMime == null -> {
                        audioMime = mime
                        audioBitrate = f.intOrZero(MediaFormat.KEY_BIT_RATE).toLong()
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { extractor.release() }
        }

        if (videoMime == null && audioMime == null && durationMs == null) return null

        val codec = listOfNotNull(videoMime?.let(::codecName), audioMime?.let(::codecName))
            .joinToString(" / ")
            .ifBlank { null }

        val quality = if (videoMime != null) {
            // File-level bitrate covers the whole container; subtract nothing
            // fancy -- prefer the video track's own rate when the format has it.
            val vBitrate = videoBitrate.takeIf { it > 0 } ?: overallBitrate
            listOfNotNull(
                // Shorter side, so portrait clips read as 1080p too.
                minOf(width, height).takeIf { it > 0 }?.let { "${it}p" },
                vBitrate?.takeIf { it > 0 }?.let(::formatBitrate),
                fps.takeIf { it > 0 }?.let { "$it fps" },
            ).joinToString(" \u2022 ").ifBlank { null }
        } else {
            (audioBitrate.takeIf { it > 0 } ?: overallBitrate)
                ?.takeIf { it > 0 }
                ?.let(::formatBitrate)
        }

        return MediaProbe(durationMs?.takeIf { it > 0 }, codec, quality)
    }

    private fun MediaFormat.intOrZero(key: String): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(0) else 0

    // KEY_FRAME_RATE is an int in most files but a float in some.
    private fun MediaFormat.numberOrZero(key: String): Int {
        if (!containsKey(key)) return 0
        return runCatching { getInteger(key) }.getOrNull()
            ?: runCatching { getFloat(key).roundToInt() }.getOrDefault(0)
    }

    private fun formatBitrate(bps: Long): String =
        if (bps >= 1_000_000) String.format(java.util.Locale.US, "%.1f Mbps", bps / 1_000_000.0)
        else "${(bps / 1000.0).roundToInt()} kbps"

    private fun codecName(mime: String): String = when (mime.lowercase()) {
        "video/avc" -> "H.264"
        "video/hevc" -> "H.265"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/av01" -> "AV1"
        "video/mp4v-es" -> "MPEG-4"
        "video/3gpp" -> "H.263"
        "video/dolby-vision" -> "Dolby Vision"
        "audio/mp4a-latm" -> "AAC"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/mpeg" -> "MP3"
        "audio/flac" -> "FLAC"
        "audio/ac3" -> "AC-3"
        "audio/eac3" -> "E-AC-3"
        "audio/raw" -> "PCM"
        "audio/3gpp" -> "AMR-NB"
        "audio/amr-wb" -> "AMR-WB"
        else -> mime.substringAfter('/').uppercase()
    }
}
