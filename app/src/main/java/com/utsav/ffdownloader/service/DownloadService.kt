package com.utsav.ffdownloader.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.utsav.ffdownloader.FfApp
import com.utsav.ffdownloader.R
import com.utsav.ffdownloader.core.CategoryDetector
import com.utsav.ffdownloader.core.DownloadCancelledException
import com.utsav.ffdownloader.core.DownloadCategory
import com.utsav.ffdownloader.core.DownloadEngine
import com.utsav.ffdownloader.core.ItemStatus
import com.utsav.ffdownloader.core.QueueRepository
import com.utsav.ffdownloader.core.Settings
import com.utsav.ffdownloader.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Environment
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Runs the download queue with up to [Settings.maxConcurrentDownloads] items
 * downloading in parallel, each with its own independently pause/resume/
 * cancel-able DownloadEngine, showing an aggregate progress notification.
 */
class DownloadService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.utsav.ffdownloader.action.START"
        const val ACTION_PAUSE_ITEM = "com.utsav.ffdownloader.action.PAUSE_ITEM"
        const val ACTION_RESUME_ITEM = "com.utsav.ffdownloader.action.RESUME_ITEM"
        const val ACTION_CANCEL_ITEM = "com.utsav.ffdownloader.action.CANCEL_ITEM"
        const val ACTION_CANCEL_ALL = "com.utsav.ffdownloader.action.CANCEL_ALL"
        const val EXTRA_ITEM_ID = "extra_item_id"
        private const val NOTIFICATION_ID = 42
        private const val BETWEEN_CLAIM_DELAY_MS = 500L

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun pauseItem(context: Context, itemId: String) {
            context.startService(
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_PAUSE_ITEM)
                    .putExtra(EXTRA_ITEM_ID, itemId)
            )
        }

        fun resumeItem(context: Context, itemId: String) {
            context.startService(
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_RESUME_ITEM)
                    .putExtra(EXTRA_ITEM_ID, itemId)
            )
        }

        fun cancelItem(context: Context, itemId: String) {
            context.startService(
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_CANCEL_ITEM)
                    .putExtra(EXTRA_ITEM_ID, itemId)
            )
        }

        fun cancelAll(context: Context) {
            context.startService(Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL_ALL))
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Force HTTP/1.1. If the server (often Cloudflare/CDN-backed, like
        // dl.fuckingfast.co) speaks HTTP/2, OkHttp will silently multiplex ALL
        // of our "parallel" segment requests over ONE physical TCP connection
        // -- so raising `connections` to 8/16 did nothing for real throughput,
        // it was still one TCP flow with one congestion window. Disabling H2
        // forces each segment onto its own genuine TCP connection, which is
        // what actually unlocks parallel bandwidth on cellular networks (this
        // is the same trick IDM / Chrome's own parallel downloader rely on).
        .protocols(listOf(Protocol.HTTP_1_1))
        // OkHttp's default Dispatcher caps concurrent requests to the SAME
        // host at 5. With up to 16 segments hitting one host, the extras
        // would queue behind the default limit instead of running in
        // parallel -- this raises the ceiling so all segments actually run
        // concurrently now that they're on separate HTTP/1.1 connections.
        .dispatcher(Dispatcher().apply {
            maxRequestsPerHost = 32
            maxRequests = 64
        })
        // Bigger pool of kept-alive connections so segment requests reuse
        // warm sockets instead of paying a fresh TCP+TLS handshake each time.
        .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
        .build()

    /** Active engines keyed by queue item id, so per-item controls can target the right download. */
    private val engines = ConcurrentHashMap<String, DownloadEngine>()

    // Number of worker loops currently alive. Workers exit their loop the
    // moment claimNextReady() returns null (nothing READY *right now*) --
    // previously that meant a single ACTION_START only ever spun up workers
    // once, so an item that became READY *after* the workers had already
    // exhausted the queue (e.g. it was still resolving) would sit at READY
    // forever: no live worker left to claim it, and onStartCommand refused
    // to launch more because a stale `runJob` still looked "active" while
    // the other worker(s) were mid-download.
    //
    // Fix: track live worker count directly, and let every ACTION_START
    // top the count back up to Settings.maxConcurrentDownloads() -- so
    // pressing "Download ready files" again (or any other ACTION_START,
    // e.g. right after a link finishes resolving) always has a chance to
    // spawn a fresh worker for anything newly READY, even while other
    // downloads are still in flight.
    private val activeWorkers = java.util.concurrent.atomic.AtomicInteger(0)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                topUpWorkers()
            }
            ACTION_PAUSE_ITEM -> intent.getStringExtra(EXTRA_ITEM_ID)?.let { id ->
                engines[id]?.pause()
                QueueRepository.update(id) { it.copy(status = ItemStatus.PAUSED) }
            }
            ACTION_RESUME_ITEM -> intent.getStringExtra(EXTRA_ITEM_ID)?.let { id ->
                engines[id]?.resume()
                QueueRepository.update(id) { it.copy(status = ItemStatus.DOWNLOADING) }
            }
            ACTION_CANCEL_ITEM -> intent.getStringExtra(EXTRA_ITEM_ID)?.let { engines[it]?.cancel() }
            ACTION_CANCEL_ALL -> engines.values.forEach { it.cancel() }
        }
        return START_NOT_STICKY
    }

    /** Launches enough fresh worker loops to bring the live count up to the configured max. */
    private fun topUpWorkers() {
        val maxWorkers = Settings.maxConcurrentDownloads().coerceIn(1, 5)
        val toLaunch = maxWorkers - activeWorkers.get()
        if (toLaunch <= 0) return
        repeat(toLaunch) {
            activeWorkers.incrementAndGet()
            lifecycleScope.launch(Dispatchers.IO) {
                worker()
                if (activeWorkers.decrementAndGet() == 0) {
                    withContext(Dispatchers.Main) {
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
    }

    private suspend fun worker() {
        while (true) {
            val item = QueueRepository.claimNextReady() ?: break
            downloadOne(item.id, item.sourceUrl, item.directUrl, item.category)
            kotlinx.coroutines.delay(BETWEEN_CLAIM_DELAY_MS)
        }
    }

    private suspend fun downloadOne(
        itemId: String,
        sourceUrl: String,
        directUrlAtClaim: String?,
        categoryAtClaim: DownloadCategory
    ) {
        var destinationFile: File? = null

        val engine = DownloadEngine(
            client = client,
            progress = { done, total, speed ->
                QueueRepository.update(itemId) { it.copy(bytesDone = done, bytesTotal = total, speedBps = speed) }
                updateNotification()
            },
            log = { },
            connections = Settings.connectionsPerDownload(),
            speedLimitBytesPerSec = Settings.speedLimitKBps().toLong() * 1024L
        )
        engines[itemId] = engine

        try {
            val directUrl = directUrlAtClaim ?: throw RuntimeException("No resolved URL")
            val fileName = DownloadEngine.filenameFromLink(sourceUrl)
                .ifBlank { DownloadEngine.filenameFromUrl(directUrl) }

            // The source URL alone (e.g. a FuckingFast share link) often has no visible
            // extension -- re-detect the category now that the real filename is resolved,
            // so it doesn't wrongly land in Others just because the share link was opaque.
            val category = CategoryDetector.detect(directUrl, hint = fileName)
                .takeIf { it != DownloadCategory.default() } ?: categoryAtClaim
            QueueRepository.update(itemId) { it.copy(fileName = fileName, category = category) }

            // Download into the app's private cache first. Public/shared storage
            // (/sdcard/...) is served through Android's FUSE emulation layer, where
            // every read/write syscall carries extra overhead -- that overhead is
            // what was capping speed well below Chrome's. The private cache sits on
            // the real filesystem with none of that overhead, so the download itself
            // runs at full network speed. The finished file is then moved to
            // /sdcard/umd/ in one continuous copy, which is far faster than paying
            // the FUSE tax on every chunk of the download.
            val tempDir = File(cacheDir, "umd_temp/${category.folderName}")
            val tempFile = File(tempDir, fileName)
            destinationFile = tempFile

            val finalDir = File(Environment.getExternalStorageDirectory(), "umd/${category.folderName}")
            val finalFile = File(finalDir, fileName)

            // Pause (engine.pause()) blocks in-place inside downloadAuto and never throws here --
            // the engine stays registered in `engines` so Resume can call engine.resume() on the
            // very same in-flight connection. Only a genuine Cancel throws, ending this coroutine.
            engine.downloadAuto(directUrl, tempFile)

            QueueRepository.update(itemId) { it.copy(status = ItemStatus.SAVING) }
            withContext(Dispatchers.IO) { moveToPublicStorage(tempFile, finalFile) }
            destinationFile = finalFile

            QueueRepository.update(itemId) { it.copy(status = ItemStatus.DONE) }
        } catch (e: DownloadCancelledException) {
            destinationFile?.delete()
            QueueRepository.update(itemId) { it.copy(status = ItemStatus.FAILED, error = "Cancelled") }
        } catch (e: Exception) {
            QueueRepository.update(itemId) { it.copy(status = ItemStatus.FAILED, error = e.message) }
        } finally {
            engines.remove(itemId)
            updateNotification()
        }
    }

    /**
     * Moves the finished temp file into public storage. `renameTo` is instant
     * when both paths are on the same filesystem, but the private cache and
     * /sdcard/... often sit on different mount views (FUSE), so it commonly
     * fails there -- in which case we fall back to a large-buffer streamed
     * copy, which is still one continuous sequential write instead of the
     * many small interleaved writes a live multi-segment download would do.
     */
    private fun moveToPublicStorage(temp: File, final: File) {
        final.parentFile?.mkdirs()
        if (final.exists()) final.delete()

        if (temp.renameTo(final)) return

        FileInputStream(temp).use { input ->
            FileOutputStream(final).use { output ->
                val buffer = ByteArray(4 * 1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
        temp.delete()
    }

    private fun updateNotification() {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val active = QueueRepository.current().filter { it.status == ItemStatus.DOWNLOADING }
        val totalDone = active.sumOf { it.bytesDone }
        val totalSize = active.sumOf { it.bytesTotal }
        val percent = if (totalSize > 0) ((totalDone * 100) / totalSize).toInt() else 0

        val text = when {
            active.isEmpty() -> "Preparing…"
            active.size == 1 -> active.first().fileName ?: active.first().sourceUrl
            else -> "${active.size} files downloading — $percent%"
        }

        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, FfApp.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, active.isEmpty())
            .setContentIntent(openIntent)
            .build()
    }
}
