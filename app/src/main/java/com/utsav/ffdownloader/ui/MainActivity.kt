package com.utsav.ffdownloader.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings as AndroidSettings
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.utsav.ffdownloader.R
import com.utsav.ffdownloader.core.ItemStatus
import com.utsav.ffdownloader.core.LinkParser
import com.utsav.ffdownloader.core.QueueItem
import com.utsav.ffdownloader.core.QueueRepository
import com.utsav.ffdownloader.core.ResolutionError
import com.utsav.ffdownloader.core.Settings
import com.utsav.ffdownloader.service.DownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private lateinit var adapter: QueueAdapter
    private var pendingChallengeContinuation: ((directUrl: String?, error: String?) -> Unit)? = null

    private val challengeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val directUrl = result.data?.getStringExtra(ChallengeActivity.EXTRA_DIRECT_URL)
        val error = result.data?.getStringExtra(ChallengeActivity.EXTRA_ERROR)
        pendingChallengeContinuation?.invoke(directUrl, error)
        pendingChallengeContinuation = null
    }

    private lateinit var linksInput: EditText

    // Storage permission request for Android 8-9 (API 26-28)
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Storage permission denied — downloads will fail. Grant it in App Settings.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val clipboardManager by lazy { getSystemService(ClipboardManager::class.java) }
    private var lastHandledClipboardText: String? = null
    private var pendingClipboardLink: String? = null
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { checkClipboard() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.app_name)
        toolbar.setTitleTextColor(getColor(R.color.ff_text))
        setSupportActionBar(toolbar)

        adapter = QueueAdapter(
            onPauseResume = { item -> onItemPauseResume(item) },
            onCancel = { item -> DownloadService.cancelItem(this, item.id) }
        )

        linksInput = findViewById(R.id.linksInput)

        val recycler = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.queueRecycler)
        val emptyLabel = findViewById<android.widget.TextView>(R.id.emptyLabel)
        val summary = findViewById<android.widget.TextView>(R.id.queueSummary)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<android.view.View>(R.id.prepareButton).setOnClickListener { onPrepareClicked() }
        findViewById<android.view.View>(R.id.downloadButton).setOnClickListener { onDownloadClicked() }
        findViewById<android.view.View>(R.id.cancelButton).setOnClickListener { DownloadService.cancelAll(this) }

        findViewById<android.view.View>(R.id.clipboardAddButton).setOnClickListener { onClipboardAddClicked() }
        findViewById<android.view.View>(R.id.clipboardDismissButton).setOnClickListener { dismissClipboardBanner() }

        linksInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updatePrepareButtonVisibility() }
        })
        updatePrepareButtonVisibility()
        checkStoragePermission()

        QueueRepository.items.observe(this) { list ->
            adapter.submitList(list)
            emptyLabel.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

            if (list.isEmpty()) {
                summary.visibility = android.view.View.GONE
            } else {
                val downloading = list.count { it.status == ItemStatus.DOWNLOADING }
                val ready = list.count { it.status == ItemStatus.READY }
                val queued = list.count { it.status == ItemStatus.PENDING || it.status == ItemStatus.RESOLVING || it.status == ItemStatus.NEEDS_CHALLENGE }
                val paused = list.count { it.status == ItemStatus.PAUSED }
                val done = list.count { it.status == ItemStatus.DONE }
                val failed = list.count { it.status == ItemStatus.FAILED }

                val parts = mutableListOf<String>()
                if (downloading > 0) parts += "$downloading downloading"
                if (ready > 0) parts += "$ready ready"
                if (queued > 0) parts += "$queued resolving"
                if (paused > 0) parts += "$paused paused"
                if (done > 0) parts += "$done done"
                if (failed > 0) parts += "$failed failed"

                summary.text = parts.joinToString("  •  ")
                summary.visibility = android.view.View.VISIBLE
            }
        }
    }

    /**
     * Ensures the app can write to /sdcard/umd/.
     * - Android 8-9 (API 26-28): requests WRITE_EXTERNAL_STORAGE at runtime.
     * - Android 10+ (API 29+): opens system "All files access" screen for
     *   MANAGE_EXTERNAL_STORAGE, which is the only way to write outside the
     *   app sandbox to an arbitrary folder like /sdcard/umd/.
     */
    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ — need MANAGE_EXTERNAL_STORAGE for /sdcard/umd/
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("Storage Permission Required")
                    .setMessage(
                        "This app needs 'All files access' to save downloads to the " +
                        "\"umd\" folder in your internal storage.\n\n" +
                        "Tap Allow on the next screen."
                    )
                    .setPositiveButton("Allow") { _, _ ->
                        val intent = Intent(
                            AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.fromParts("package", packageName, null)
                        )
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        Toast.makeText(
                            this,
                            "Downloads will fail without storage permission.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    .show()
            }
        } else {
            // Android 8-9 — WRITE_EXTERNAL_STORAGE is enough
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        checkClipboard()
    }

    override fun onPause() {
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        super.onPause()
    }

    /**
     * Clipboard access from a background app is blocked by Android since
     * API 29 -- this only sees what's copied while the app is in the
     * foreground (onResume, or while it's the focused window), never
     * system-wide in the background. That's a platform privacy restriction,
     * not something we can work around without an Accessibility Service
     * (which we deliberately avoid -- too invasive and likely to trip
     * Play Protect).
     */
    private fun checkClipboard() {
        val clip = clipboardManager.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isEmpty() || text == lastHandledClipboardText) return
        if (!LinkParser.isShareLink(text) && !LinkParser.isFitgirlPage(text)) return
        if (linksInput.text?.toString()?.contains(text) == true) return
        if (QueueRepository.current().any { it.sourceUrl == text }) return

        pendingClipboardLink = text
        findViewById<TextView>(R.id.clipboardBannerText).text = getString(R.string.clipboard_link_detected, text)
        findViewById<View>(R.id.clipboardBanner).visibility = View.VISIBLE
    }

    private fun onClipboardAddClicked() {
        val link = pendingClipboardLink ?: return
        val current = linksInput.text?.toString().orEmpty()
        linksInput.setText(if (current.isBlank()) link else "$current\n$link")
        linksInput.setSelection(linksInput.text?.length ?: 0)
        lastHandledClipboardText = link
        dismissClipboardBanner()
    }

    private fun dismissClipboardBanner() {
        lastHandledClipboardText = pendingClipboardLink ?: lastHandledClipboardText
        pendingClipboardLink = null
        findViewById<View>(R.id.clipboardBanner).visibility = View.GONE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            showSettingsDialog()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun onItemPauseResume(item: QueueItem) {
        if (item.status == ItemStatus.PAUSED) {
            DownloadService.resumeItem(this, item.id)
        } else {
            DownloadService.pauseItem(this, item.id)
        }
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val group = view.findViewById<RadioGroup>(R.id.connectionsGroup)
        val speedInput = view.findViewById<EditText>(R.id.speedLimitInput)
        val concurrentInput = view.findViewById<EditText>(R.id.maxConcurrentInput)

        val idForConnections = mapOf(
            2 to R.id.conn2, 4 to R.id.conn4, 8 to R.id.conn8, 16 to R.id.conn16
        )
        val currentConnections = Settings.connectionsPerDownload()
        (view.findViewById<RadioButton>(idForConnections[currentConnections] ?: R.id.conn4)).isChecked = true
        speedInput.setText(Settings.speedLimitKBps().toString())
        concurrentInput.setText(Settings.maxConcurrentDownloads().toString())

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(view)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                val checkedId = group.checkedRadioButtonId
                val connections = idForConnections.entries.firstOrNull { it.value == checkedId }?.key ?: 4
                val speedLimit = speedInput.text?.toString()?.toIntOrNull() ?: 0
                val maxConcurrent = concurrentInput.text?.toString()?.toIntOrNull() ?: 2

                Settings.setConnectionsPerDownload(connections)
                Settings.setSpeedLimitKBps(speedLimit)
                Settings.setMaxConcurrentDownloads(maxConcurrent)
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onPrepareClicked() {
        val rawLines = currentInputLines()

        if (rawLines.isEmpty()) {
            Toast.makeText(this, "Paste at least one link", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val expanded = try {
                withContext(Dispatchers.IO) { LinkParser.expandSources(rawLines, client) }
            } catch (e: ResolutionError) {
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
                return@launch
            }

            QueueRepository.setLinks(expanded)
            resolveAll()
        }
    }

    private suspend fun resolveAll() {
        val items = QueueRepository.current().filter { it.status == ItemStatus.PENDING }
        for ((index, item) in items.withIndex()) {
            QueueRepository.update(item.id) { it.copy(status = ItemStatus.RESOLVING) }
            resolveOne(item)
            if (index + 1 < items.size) delay(500)
        }
    }

    private suspend fun resolveOne(item: QueueItem) {
        if (LinkParser.isGenericDownloadUrl(item.sourceUrl)) {
            QueueRepository.update(item.id) { it.copy(directUrl = item.sourceUrl, status = ItemStatus.READY) }
            return
        }

        if (!LinkParser.isShareLink(item.sourceUrl)) {
            QueueRepository.update(item.id) {
                it.copy(status = ItemStatus.FAILED, error = "Not a valid URL: ${item.sourceUrl}")
            }
            return
        }

        val fileId = try {
            LinkParser.fileId(item.sourceUrl)
        } catch (e: ResolutionError) {
            QueueRepository.update(item.id) { it.copy(status = ItemStatus.FAILED, error = e.message) }
            return
        }

        QueueRepository.update(item.id) { it.copy(status = ItemStatus.NEEDS_CHALLENGE) }

        val (directUrl, error) = suspendCancellableCoroutine<Pair<String?, String?>> { cont ->
            pendingChallengeContinuation = { url, err -> cont.resume(url to err) }
            val intent = Intent(this@MainActivity, ChallengeActivity::class.java)
                .putExtra(ChallengeActivity.EXTRA_SHARE_URL, item.sourceUrl)
                .putExtra(ChallengeActivity.EXTRA_FILE_ID, fileId)
            challengeLauncher.launch(intent)
        }

        if (directUrl != null) {
            QueueRepository.update(item.id) { it.copy(directUrl = directUrl, status = ItemStatus.READY) }
        } else {
            QueueRepository.update(item.id) { it.copy(status = ItemStatus.FAILED, error = error ?: "Could not resolve link") }
        }
    }

    private fun currentInputLines(): List<String> =
        linksInput.text?.toString().orEmpty()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * FuckingFast share links and fitgirl-repacks pages need the Prepare
     * step (challenge-solving / page-scanning) before they're downloadable.
     * Plain direct URLs don't -- for those we hide Prepare entirely and let
     * Download queue + start in one tap.
     */
    private fun updatePrepareButtonVisibility() {
        val lines = currentInputLines()
        val needsPrepare = lines.isEmpty() || lines.any {
            LinkParser.isShareLink(it) || LinkParser.isFitgirlPage(it)
        }
        findViewById<View>(R.id.prepareButton).visibility = if (needsPrepare) View.VISIBLE else View.GONE
        findViewById<com.google.android.material.button.MaterialButton>(R.id.downloadButton).setText(
            if (needsPrepare) R.string.action_download else R.string.action_download_direct
        )
    }

    private fun onDownloadClicked() {
        val prepareNeeded = findViewById<View>(R.id.prepareButton).visibility == View.VISIBLE

        if (prepareNeeded) {
            val readyCount = QueueRepository.current().count { it.status == ItemStatus.READY }
            if (readyCount == 0) {
                Toast.makeText(this, "No ready files yet — tap Prepare first", Toast.LENGTH_SHORT).show()
                return
            }
            DownloadService.start(this)
            linksInput.setText("")
            return
        }

        // Direct-URL fast path: every pasted line is already a plain downloadable
        // URL (no FF/fitgirl resolution needed), so skip Prepare and go straight
        // to queued + downloading.
        val rawLines = currentInputLines()
        if (rawLines.isEmpty()) {
            Toast.makeText(this, "Paste at least one link", Toast.LENGTH_SHORT).show()
            return
        }

        QueueRepository.setLinks(rawLines)
        rawLines.forEach { link ->
            val item = QueueRepository.current().firstOrNull { it.sourceUrl == link }
            if (item != null) {
                QueueRepository.update(item.id) { it.copy(directUrl = link, status = ItemStatus.READY) }
            }
        }
        DownloadService.start(this)
        linksInput.setText("")
    }
}
