package com.utsav.ffdownloader.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

typealias ProgressFn = (done: Long, total: Long, speedBps: Double) -> Unit
typealias LogFn = (String) -> Unit

private const val STREAM_BLOCK_SIZE = 256 * 1024
private const val MULTI_CONNECTION_MIN_BYTES = 8L * 1024 * 1024 // below this, one connection is plenty
private const val PROGRESS_THROTTLE_NANOS = 200_000_000L // ~5 UI updates/sec, avoids flooding the main thread

/**
 * Resumable downloader with pause/cancel support and optional multi-connection
 * (segmented, parallel) downloads for large files on servers that support
 * HTTP range requests. Kotlin port of ff_downloader/core/downloader.py's
 * DownloadEngine, extended with segmented downloads since mobile networks
 * benefit more from parallel connections than the desktop's single-stream
 * approach.
 *
 * Not thread-shared: create one instance per in-flight download (its
 * pause/cancel flags are shared across that download's segment workers,
 * but a fresh instance should be used per queue item).
 */
class DownloadEngine(
    private val client: OkHttpClient,
    private val progress: ProgressFn = { _, _, _ -> },
    private val log: LogFn = {},
    private val connections: Int = 4,
    private val speedLimitBytesPerSec: Long = 0L // 0 = unlimited
) {
    private val paused = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val lastProgressEmitNanos = AtomicLong(0L)
    private val limiter = RateLimiter(speedLimitBytesPerSec)

    /** Continuous cumulative-rate limiter, shared across a download's segment threads. */
    private class RateLimiter(private val bytesPerSecond: Long) {
        private val lock = Any()
        private val startNanos = System.nanoTime()
        private var bytesConsumed = 0L

        fun acquire(bytes: Int) {
            if (bytesPerSecond <= 0) return
            var sleepNanos = 0L
            synchronized(lock) {
                bytesConsumed += bytes
                val elapsedNanos = System.nanoTime() - startNanos
                // How much wall-clock time SHOULD have passed to have consumed this many
                // bytes at the target rate -- if we're ahead of that, sleep the difference.
                // Using total-consumed-since-start (not a periodically-reset window) avoids
                // drift and stays accurate however bursty the underlying reads are.
                val expectedNanos = (bytesConsumed.toDouble() / bytesPerSecond * 1_000_000_000L).toLong()
                if (expectedNanos > elapsedNanos) {
                    sleepNanos = expectedNanos - elapsedNanos
                }
            }
            if (sleepNanos > 0) {
                Thread.sleep(sleepNanos / 1_000_000, (sleepNanos % 1_000_000).toInt())
            }
        }
    }

    fun pause() { paused.set(true) }
    fun resume() { paused.set(false) }
    fun cancel() { cancelled.set(true); paused.set(false) }

    private fun checkpoint() {
        if (cancelled.get()) throw DownloadCancelledException()
        while (paused.get()) {
            Thread.sleep(100)
            if (cancelled.get()) throw DownloadCancelledException()
        }
    }

    /** Emits progress at most every ~200ms per engine instance, except always on [force]. */
    private fun emitProgress(done: Long, total: Long, speedBps: Double, force: Boolean = false) {
        val now = System.nanoTime()
        val last = lastProgressEmitNanos.get()
        if (!force && now - last < PROGRESS_THROTTLE_NANOS) return
        if (lastProgressEmitNanos.compareAndSet(last, now) || force) {
            progress(done, total, speedBps)
        }
    }

    companion object {
        private val INVALID_CHARS = charArrayOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
        private val CONTENT_RANGE_TOTAL = Pattern.compile("/(\\d+)$")

        private fun sanitize(name: String): String =
            name.map { if (it in INVALID_CHARS) '_' else it }.joinToString("").take(220)

        fun filenameFromUrl(url: String): String {
            val path = runCatching { URI(url).path }.getOrNull().orEmpty()
            val raw = path.substringAfterLast('/').let {
                runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
            }
            return sanitize(raw.ifBlank { "download.bin" })
        }

        /** Mirrors desktop's filename_from_link: uses the URL fragment as a display name. */
        fun filenameFromLink(link: String): String {
            val fragment = runCatching { URI(link).fragment }.getOrNull()?.trim().orEmpty()
            if (fragment.isEmpty()) return ""
            return sanitize(fragment)
        }
    }

    /**
     * Picks the fastest safe strategy: multi-connection for large files on a
     * range-supporting server with no existing partial file, single-connection
     * (with resume) otherwise. Falls back to a clean single-connection restart
     * if the multi-connection attempt fails partway.
     */
    fun downloadAuto(url: String, destination: File) {
        cancelled.set(false)
        paused.set(false)

        val alreadyPartial = destination.isFile && destination.length() > 0
        if (connections > 1 && !alreadyPartial) {
            val probe = probeRangeSupport(url)
            if (probe.supportsRanges && probe.totalSize >= MULTI_CONNECTION_MIN_BYTES) {
                try {
                    log("Downloading with $connections parallel connections")
                    downloadMulti(url, destination, probe.totalSize)
                    return
                } catch (e: DownloadCancelledException) {
                    throw e
                } catch (e: Exception) {
                    log("Parallel download failed (${e.message}), retrying single-connection")
                    destination.delete() // scattered partial ranges aren't safely resumable
                    cancelled.set(false) // clear the internal cancel() used to stop sibling segments
                }
            }
        }
        download(url, destination)
    }

    private data class RangeProbe(val totalSize: Long, val supportsRanges: Boolean)

    private fun probeRangeSupport(url: String): RangeProbe {
        return try {
            val request = Request.Builder().url(url).header("Range", "bytes=0-0").build()
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    206 -> {
                        val contentRange = response.header("Content-Range").orEmpty()
                        val matcher = CONTENT_RANGE_TOTAL.matcher(contentRange)
                        val total = if (matcher.find()) matcher.group(1)!!.toLong() else -1L
                        RangeProbe(total, total > 0)
                    }
                    200 -> {
                        val total = response.header("content-length")?.toLongOrNull() ?: -1L
                        RangeProbe(total, false)
                    }
                    else -> RangeProbe(-1L, false)
                }
            }
        } catch (e: Exception) {
            RangeProbe(-1L, false)
        }
    }

    private fun downloadMulti(url: String, destination: File, totalSize: Long) {
        destination.parentFile?.mkdirs()
        RandomAccessFile(destination, "rw").use { it.setLength(totalSize) }

        val segmentSize = totalSize / connections
        val ranges = (0 until connections).map { i ->
            val start = i * segmentSize
            val end = if (i == connections - 1) totalSize - 1 else (start + segmentSize - 1)
            start to end
        }

        val doneCounter = AtomicLong(0L)
        val started = System.nanoTime()
        val failure = AtomicReference<Exception?>(null)
        val executor = Executors.newFixedThreadPool(connections)

        try {
            val futures = ranges.map { (start, end) ->
                executor.submit {
                    try {
                        downloadRange(url, destination, start, end, doneCounter, totalSize, started)
                    } catch (e: Exception) {
                        failure.compareAndSet(null, e)
                        cancel() // stop sibling segments if one fails (unless it's just our own cancel)
                    }
                }
            }
            futures.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        failure.get()?.let { throw it }

        val finalSize = destination.length()
        if (finalSize < totalSize) {
            throw RuntimeException("Parallel download incomplete: $finalSize/$totalSize bytes")
        }
        emitProgress(totalSize, totalSize, 0.0, force = true)
        log("Downloaded ${destination.name}")
    }

    private fun downloadRange(
        url: String,
        destination: File,
        start: Long,
        end: Long,
        doneCounter: AtomicLong,
        totalSize: Long,
        started: Long
    ) {
        val request = Request.Builder().url(url).header("Range", "bytes=$start-$end").build()
        client.newCall(request).execute().use { response ->
            if (response.code != 206 && response.code != 200) {
                throw RuntimeException("Segment $start-$end failed (HTTP ${response.code})")
            }
            val body = response.body ?: throw RuntimeException("Empty segment body")
            RandomAccessFile(destination, "rw").use { raf ->
                raf.seek(start)
                body.byteStream().use { input ->
                    val buffer = ByteArray(STREAM_BLOCK_SIZE)
                    while (true) {
                        checkpoint()
                        val read = input.read(buffer)
                        if (read == -1) break
                        if (read == 0) continue
                        raf.write(buffer, 0, read)
                        val done = doneCounter.addAndGet(read.toLong())
                        limiter.acquire(read)
                        val elapsedSec = ((System.nanoTime() - started) / 1_000_000_000.0).coerceAtLeast(0.001)
                        emitProgress(done, totalSize, done / elapsedSec)
                    }
                }
            }
        }
    }

    /** Single-connection download to [destination], resuming from existing partial content if present. */
    fun download(url: String, destination: File) {
        destination.parentFile?.mkdirs()
        cancelled.set(false)
        paused.set(false)

        val existingSize = if (destination.isFile) destination.length() else 0L
        val requestBuilder = Request.Builder().url(url)
        if (existingSize > 0) {
            requestBuilder.header("Range", "bytes=$existingSize-")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            when (response.code) {
                416 -> {
                    log("File already complete: ${destination.name}")
                    return
                }
                206 -> {
                    val contentRange = response.header("Content-Range").orEmpty()
                    val matcher = CONTENT_RANGE_TOTAL.matcher(contentRange)
                    val totalSize = if (matcher.find()) {
                        matcher.group(1)!!.toLong()
                    } else {
                        existingSize + (response.header("content-length")?.toLongOrNull() ?: 0L)
                    }
                    if (totalSize > 0 && existingSize >= totalSize) {
                        log("File already complete: ${destination.name}")
                        return
                    }
                    log("Resuming ${destination.name} from $existingSize bytes")
                    streamToFile(response, destination, existingSize, totalSize, append = true)
                }
                200 -> {
                    val totalSize = response.header("content-length")?.toLongOrNull() ?: 0L
                    if (existingSize > 0 && totalSize > 0 && existingSize >= totalSize) {
                        log("File already complete: ${destination.name}")
                        return
                    }
                    if (existingSize > 0) {
                        log("Server ignored resume request; restarting ${destination.name}")
                    }
                    streamToFile(response, destination, 0L, totalSize, append = false)
                }
                else -> {
                    val host = runCatching { URI(url).host }.getOrNull()
                    if (host == "dl.fuckingfast.co" && response.code in setOf(401, 403, 404, 410)) {
                        throw RuntimeException(
                            "This direct link has expired or is unavailable. Paste the original " +
                                "share link to prepare a fresh download URL."
                        )
                    }
                    throw RuntimeException("Failed to download file (HTTP ${response.code})")
                }
            }
        }

        log("Downloaded ${destination.name}")
    }

    private fun streamToFile(
        response: Response,
        destination: File,
        initial: Long,
        totalSize: Long,
        append: Boolean
    ) {
        val body = response.body ?: throw RuntimeException("Empty response body")
        var done = initial
        val started = System.nanoTime()

        RandomAccessFile(destination, "rw").use { raf ->
            if (append) raf.seek(destination.length()) else { raf.setLength(0); raf.seek(0) }
            body.byteStream().use { input ->
                val buffer = ByteArray(STREAM_BLOCK_SIZE)
                while (true) {
                    checkpoint()
                    val read = input.read(buffer)
                    if (read == -1) break
                    if (read == 0) continue
                    raf.write(buffer, 0, read)
                    done += read
                    limiter.acquire(read)
                    val elapsedSec = ((System.nanoTime() - started) / 1_000_000_000.0).coerceAtLeast(0.001)
                    emitProgress(done, totalSize, (done - initial) / elapsedSec)
                }
            }
        }

        emitProgress(done, totalSize, 0.0, force = true)

        val finalSize = destination.length()
        if (totalSize > 0 && finalSize < totalSize) {
            throw RuntimeException("Download incomplete: $finalSize/$totalSize bytes")
        }
    }
}
