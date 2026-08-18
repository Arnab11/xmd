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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Environment
import okhttp3.OkHttpClient
import java.io.File
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
        .build()

    /** Active engines keyed by queue item id, so per-item controls can target the right download. */
    private val engines = ConcurrentHashMap<String, DownloadEngine>()
    private var runJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                if (runJob?.isActive != true) {
                    runJob = lifecycleScope.launch { runQueue() }
                }
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

    private suspend fun runQueue() {
        val workerCount = Settings.maxConcurrentDownloads().coerceIn(1, 5)

        withContext(Dispatchers.IO) {
            (1..workerCount).map { async { worker() } }.awaitAll()
        }

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
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

            val destinationDir = File(Environment.getExternalStorageDirectory(), "umd/${category.folderName}")
            val destination = File(destinationDir, fileName)
            destinationFile = destination

            // Pause (engine.pause()) blocks in-place inside downloadAuto and never throws here --
            // the engine stays registered in `engines` so Resume can call engine.resume() on the
            // very same in-flight connection. Only a genuine Cancel throws, ending this coroutine.
            engine.downloadAuto(directUrl, destination)
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
