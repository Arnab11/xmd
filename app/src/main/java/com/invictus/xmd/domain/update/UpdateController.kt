package com.invictus.xmd.domain.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.invictus.xmd.preferences.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Update state holder in Bunko's no-ViewModel style: a plain class owned via
// remember, driving the UpdateSheet (MainActivity's root composition and the
// About screen). Auto-check/channel stay backed by Settings so existing users
// keep their stored choices.
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Loading : UpdateState
    data class Available(val release: XmdRelease) : UpdateState
    data object NoUpdate : UpdateState

    /** [reason] is the real failure (HTTP code, timeout...) for a manual check. */
    data class Error(val reason: String?) : UpdateState
    data class ReadyToInstall(val release: XmdRelease) : UpdateState
}

/**
 * Process-wide owner of the running APK download. It lives outside any one
 * screen's composition so closing the update sheet (or leaving MainActivity for
 * About) never cancels the download, and every [UpdateController] -- there is
 * one per screen -- observes the same progress instead of starting a second,
 * conflicting download over the same .part files.
 */
internal object UpdateDownloadHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var isDownloading: Boolean by mutableStateOf(false)
        private set
    var progress: Float by mutableFloatStateOf(0f)
        private set
    var error: String? by mutableStateOf(null)
        private set

    /** The release being (or last) downloaded. */
    var release: XmdRelease? by mutableStateOf(null)
        private set
    var completedTag: String? by mutableStateOf(null)
        private set

    /** Bumped whenever a download ends (done or failed) so a hidden sheet can pop back up. */
    var finishCount: Int by mutableIntStateOf(0)
        private set

    fun start(manager: XmdUpdateManager, target: XmdRelease, startProgress: Float) {
        if (isDownloading) return
        error = null
        completedTag = null
        release = target
        progress = startProgress
        isDownloading = true
        scope.launch {
            try {
                manager.downloadUpdate(target).collect { progress = it }
                completedTag = target.tagName
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Exception) {
                Log.w("XmdUpdate", "Update download failed.", t)
                error = t.message?.takeIf { it.isNotBlank() } ?: "Download failed"
            } finally {
                isDownloading = false
                finishCount++
            }
        }
    }

    /** Forget a finished/failed download (no-op while one is running). */
    fun reset() {
        if (isDownloading) return
        progress = 0f
        error = null
        release = null
        completedTag = null
    }
}

@Stable
class UpdateController internal constructor(
    private val manager: XmdUpdateManager,
    private val appContext: Context,
    private val scope: CoroutineScope,
) {
    private var rawState: UpdateState by mutableStateOf(UpdateState.Idle)

    /** An Available release whose APK the shared download has since finished reads as ReadyToInstall. */
    val state: UpdateState
        get() {
            val current = rawState
            if (current is UpdateState.Available &&
                UpdateDownloadHub.completedTag == current.release.tagName &&
                manager.getApkFile(current.release) != null
            ) {
                return UpdateState.ReadyToInstall(current.release)
            }
            return current
        }

    private var localProgress: Float by mutableFloatStateOf(0f)

    private fun hubApplies(): Boolean {
        val tag = UpdateDownloadHub.release?.tagName ?: return false
        return when (val current = rawState) {
            is UpdateState.Available -> current.release.tagName == tag
            is UpdateState.ReadyToInstall -> current.release.tagName == tag
            else -> false
        }
    }

    val downloadProgress: Float
        get() = if (hubApplies()) UpdateDownloadHub.progress else localProgress
    val isDownloading: Boolean
        get() = UpdateDownloadHub.isDownloading
    val downloadError: String?
        get() = if (hubApplies()) UpdateDownloadHub.error else null

    var autoCheckEnabled: Boolean by mutableStateOf(Settings.autoCheckForUpdatesEnabled())
        private set
    var updateChannel: Settings.UpdateChannel by mutableStateOf(Settings.updateChannel())
        private set

    // Sheet closed (back / swipe) while a download runs: it keeps going in the
    // hub and the sheet comes back when that download ends.
    private var hiddenAtFinish: Int? by mutableStateOf(null)
    val sheetVisible: Boolean
        get() {
            val hidden = hiddenAtFinish ?: return true
            return hidden != UpdateDownloadHub.finishCount
        }

    // Preview APKs are listed with size 0; the real size is fetched via HEAD.
    private var resolvedSizeTag: String? by mutableStateOf(null)
    private var resolvedSize: Long by mutableLongStateOf(0L)

    private var checkJob: Job? = null

    fun setAutoCheck(enabled: Boolean) {
        Settings.setAutoCheckForUpdatesEnabled(enabled)
        autoCheckEnabled = enabled
        if (enabled) checkForUpdate(manual = false)
    }

    fun setChannel(next: Settings.UpdateChannel) {
        if (next == updateChannel || isDownloading) return
        Settings.setUpdateChannel(next)
        updateChannel = next
        manager.clearCache()
        UpdateDownloadHub.reset()
        localProgress = 0f
        rawState = UpdateState.Idle
        checkForUpdate(manual = true)
    }

    fun checkForUpdate(manual: Boolean = false) {
        if (isDownloading) {
            // Another screen's download is running: show it here instead of checking.
            UpdateDownloadHub.release?.let { rawState = UpdateState.Available(it) }
            if (manual) hiddenAtFinish = null
            return
        }
        checkJob?.cancel()
        checkJob = scope.launch {
            rawState = UpdateState.Loading
            try {
                val release = manager.checkForUpdate(channel = updateChannel, forceShow = manual)
                rawState = if (release != null) {
                    hiddenAtFinish = null
                    if (manager.getApkFile(release) != null) {
                        localProgress = 100f
                        UpdateState.ReadyToInstall(release)
                    } else {
                        val existingProgress = manager.getExistingProgress(release)
                        if (existingProgress > 0f) localProgress = existingProgress
                        resolveSize(release)
                        UpdateState.Available(release)
                    }
                } else if (manual) {
                    UpdateState.NoUpdate
                } else {
                    UpdateState.Idle
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Exception) {
                Log.w("XmdUpdate", "Update check failed.", t)
                rawState = if (manual) UpdateState.Error(t.message?.takeIf { it.isNotBlank() }) else UpdateState.Idle
            }
        }
    }

    private fun resolveSize(release: XmdRelease) {
        if (manager.selectApkAsset(release)?.size?.let { it > 0L } == true) return
        scope.launch {
            val size = manager.fetchApkSize(release)
            if (size > 0L) {
                resolvedSize = size
                resolvedSizeTag = release.tagName
            }
        }
    }

    fun downloadUpdate(release: XmdRelease) {
        if (isDownloading) return
        hiddenAtFinish = null
        UpdateDownloadHub.start(manager, release, downloadProgress)
    }

    fun installUpdate(release: XmdRelease) {
        val file = manager.getApkFile(release) ?: return
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }

    /** Sheet closed. Mid-download this only hides the sheet (the download carries on); otherwise it discards the update. */
    fun dismiss() {
        if (isDownloading) {
            hiddenAtFinish = UpdateDownloadHub.finishCount
            return
        }
        manager.clearCache()
        UpdateDownloadHub.reset()
        localProgress = 0f
        rawState = UpdateState.Idle
    }

    /** Clears a transient NoUpdate/Error status once the UI has shown it. */
    fun dismissStatus() {
        if (rawState == UpdateState.NoUpdate || rawState is UpdateState.Error) {
            rawState = UpdateState.Idle
        }
    }

    fun ignoreVersion(version: String) {
        if (isDownloading) return
        manager.ignoreVersion(version, updateChannel)
        manager.clearCache()
        UpdateDownloadHub.reset()
        localProgress = 0f
        rawState = UpdateState.Idle
    }

    /** For the sheet's "Version x -> y" row and Ready/Available labelling. */
    fun apkSize(release: XmdRelease): Long {
        val listed = manager.selectApkAsset(release)?.size ?: 0L
        if (listed > 0L) return listed
        if (resolvedSizeTag == release.tagName && resolvedSize > 0L) return resolvedSize
        return manager.getApkFile(release)?.length() ?: 0L
    }
}

/**
 * @param autoCheckOnStart true for the app's main screen (deferred startup
 * check, mirroring Bunko); false where another screen owns the startup check
 * (About), so opening it doesn't fire a second network call.
 */
@Composable
fun rememberUpdateController(context: Context, autoCheckOnStart: Boolean = true): UpdateController {
    val appContext = context.applicationContext
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    DisposableEffect(scope) {
        onDispose { scope.cancel() }
    }
    val controller = remember(appContext) {
        UpdateController(XmdUpdateManager(appContext), appContext, scope)
    }
    // Deferred so the network call never races first-frame composition.
    DisposableEffect(controller) {
        val job = if (autoCheckOnStart && controller.autoCheckEnabled) {
            scope.launch {
                delay(1500L)
                controller.checkForUpdate(manual = false)
            }
        } else {
            null
        }
        onDispose { job?.cancel() }
    }
    return controller
}
