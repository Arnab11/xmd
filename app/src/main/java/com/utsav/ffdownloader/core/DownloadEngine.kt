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
private const val MULTI_CONNECTION_MIN_BYTES = 8L * 1024 * 1024
private const val PROGRESS_THROTTLE_NANOS = 200_000_000L // ~5 UI updates/sec

class DownloadEngine(
    private val client: OkHttpClient,
    private val progress: ProgressFn = { _, _, _ -> },
    private val log: LogFn = {},
    private val connections: Int = 4,
    private val speedLimitBytesPerSec: Long = 0L
) {
    private val paused    = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val lastProgressEmitNanos = AtomicLong(0L)
    private val limiter   = RateLimiter(speedLimitBytesPerSec)

    // ── Rate limiter (unchanged) ──────────────────────────────────────────
    private class RateLimiter(private val bytesPerSecond: Long) {
        private val lock  = Any()
        private val startNanos = System.nanoTime()
        private var bytesConsumed = 0L

        fun acquire(bytes: Int) {
            if (bytesPerSecond <= 0) return
            var sleepNanos = 0L
            synchronized(lock) {
                bytesConsumed += bytes
                val elapsedNanos  = System.nanoTime() - startNanos
                val expectedNanos = (bytesConsumed.toDouble() / bytesPerSecond * 1_000_000_000L).toLong()
                if (expectedNanos > elapsedNanos) sleepNanos = expectedNanos - elapsedNanos
            }
            if (sleepNanos > 0) Thread.sleep(sleepNanos / 1_000_000, (sleepNanos % 1_000_000).toInt())
        }
    }

    // ── Sliding-window speed meter ────────────────────────────────────────
    /**
     * Thread-safe 3-second sliding window speed meter.
     *
     * Previous code used (totalBytesDownloaded / totalElapsedSeconds), which
     * produced an ever-decaying average dragged down by TCP slow-start at the
     * beginning of the download. This meter only considers bytes received in
     * the last [windowMs] ms, so it tracks the *current* speed the way the
     * system status-bar does — no drift, no slow-start penalty.
     *
     * In multi-connection mode a single shared instance is passed to all
     * segment workers so their bytes are summed into one accurate aggregate.
     */
    private class SpeedMeter(private val windowMs: Long = 3_000L) {
        private val lock    = Any()
        // ArrayDeque of (timestampNanos, bytes) pairs
        private val samples = ArrayDeque<Pair<Long, Long>>()

        fun record(bytes: Long) {
            if (bytes <= 0) return
            val now = System.nanoTime()
            synchronized(lock) {
                samples.addLast(now to bytes)
                val cutoff = now - windowMs * 1_000_000L
                while (samples.isNotEmpty() && samples.first().first < cutoff) {
                    samples.removeFirst()
                }
            }
        }

        /** Returns bytes/sec over the sliding window; 0.0 if fewer than 2 samples. */
        fun bps(): Double {
            synchronized(lock) {
                if (samples.size < 2) return 0.0
                val windowNanos = samples.last().first - samples.first().first
                if (windowNanos <= 0L) return 0.0
                // Sum bytes of every sample EXCEPT the first (it's the window anchor)
                val totalBytes = samples.drop(1).sumOf { it.second }
                return totalBytes * 1_000_000_000.0 / windowNanos
            }
        }
    }

    // ── Public control ────────────────────────────────────────────────────
    fun pause()  { paused.set(true) }
    fun resume() { paused.set(false) }
    fun cancel() { cancelled.set(true); paused.set(false) }

    private fun checkpoint() {
        if (cancelled.get()) throw DownloadCancelledException()
        while (paused.get()) {
            Thread.sleep(100)
            if (cancelled.get()) throw DownloadCancelledException()
        }
    }

    private fun emitProgress(done: Long, total: Long, speedBps: Double, force: Boolean = false) {
        val now  = System.nanoTime()
        val last = lastProgressEmitNanos.get()
        if (!force && now - last < PROGRESS_THROTTLE_NANOS) return
        if (lastProgressEmitNanos.compareAndSet(last, now) || force) {
            progress(done, total, speedBps)
        }
    }

    // ── Companions (filename utils, unchanged) ────────────────────────────
    companion object {
        private val INVALID_CHARS       = charArrayOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
        private val CONTENT_RANGE_TOTAL = Pattern.compile("/(\\d+)$")

        private fun sanitize(name: String): String =
            name.map { if (it in INVALID_CHARS) '_' else it }.joinToString("").take(220)

        fun filenameFromUrl(url: String): String {
            val path = runCatching { URI(url).path }.getOrNull().orEmpty()
            val raw  = path.substringAfterLast('/').let {
                runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
            }
            return sanitize(raw.ifBlank { "download.bin" })
        }

        fun filenameFromLink(link: String): String {
            val fragment = runCatching { URI(link).fragment }.getOrNull()?.trim().orEmpty()
            if (fragment.isEmpty()) return ""
            return sanitize(fragment)
        }
    }

    // ── Entry point ───────────────────────────────────────────────────────
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
                    destination.delete()
                    cancelled.set(false)
                }
            }
        }
        download(url, destination)
    }

    // ── Range probe (unchanged) ───────────────────────────────────────────
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

    // ── Multi-connection download ──────────────────────────────────────────
    private fun downloadMulti(url: String, destination: File, totalSize: Long) {
        destination.parentFile?.mkdirs()
        RandomAccessFile(destination, "rw").use { it.setLength(totalSize) }

        val segmentSize = totalSize / connections
        val ranges = (0 until connections).map { i ->
            val start = i * segmentSize
            val end   = if (i == connections - 1) totalSize - 1 else (start + segmentSize - 1)
            start to end
        }

        val doneCounter = AtomicLong(0L)
        // One shared SpeedMeter so all segment threads contribute to the
        // same sliding window — gives the true aggregate download speed.
        val speedMeter  = SpeedMeter()
        val failure     = AtomicReference<Exception?>(null)
        val executor    = Executors.newFixedThreadPool(connections)

        try {
            val futures = ranges.map { (start, end) ->
                executor.submit {
                    try {
                        downloadRange(url, destination, start, end, doneCounter, totalSize, speedMeter)
                    } catch (e: Exception) {
                        failure.compareAndSet(null, e)
                        cancel()
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

    // Signature changed: accepts shared SpeedMeter instead of `started: Long`
    private fun downloadRange(
        url: String,
        destination: File,
        start: Long,
        end: Long,
        doneCounter: AtomicLong,
        totalSize: Long,
        speedMeter: SpeedMeter          // ← shared across all segment workers
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
                        speedMeter.record(read.toLong())          // ← sliding window
                        emitProgress(done, totalSize, speedMeter.bps())
                    }
                }
            }
        }
    }

    // ── Single-connection download (with resume) ───────────────────────────
    fun download(url: String, destination: File) {
        destination.parentFile?.mkdirs()
        cancelled.set(false)
        paused.set(false)

        val existingSize    = if (destination.isFile) destination.length() else 0L
        val requestBuilder  = Request.Builder().url(url)
        if (existingSize > 0) requestBuilder.header("Range", "bytes=$existingSize-")

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
                        log("File already complete: ${destination.name}"); return
                    }
                    log("Resuming ${destination.name} from $existingSize bytes")
                    streamToFile(response, destination, existingSize, totalSize, append = true)
                }
                200 -> {
                    val totalSize = response.header("content-length")?.toLongOrNull() ?: 0L
                    if (existingSize > 0 && totalSize > 0 && existingSize >= totalSize) {
                        log("File already complete: ${destination.name}"); return
                    }
                    if (existingSize > 0) log("Server ignored resume; restarting ${destination.name}")
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
        val body       = response.body ?: throw RuntimeException("Empty response body")
        var done       = initial
        val speedMeter = SpeedMeter()           // ← per-download sliding window

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
                    speedMeter.record(read.toLong())              // ← sliding window
                    emitProgress(done, totalSize, speedMeter.bps())
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
