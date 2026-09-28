package com.invictus.xmd.domain.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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

@Stable
class UpdateController internal constructor(
    private val manager: XmdUpdateManager,
    private val appContext: Context,
    private val scope: CoroutineScope,
) {
    var state: UpdateState by mutableStateOf(UpdateState.Idle)
        private set
    var downloadProgress: Float by mutableFloatStateOf(0f)
        private set
    var isDownloading: Boolean by mutableStateOf(false)
        private set
    var downloadError: String? by mutableStateOf(null)
        private set
    var autoCheckEnabled: Boolean by mutableStateOf(Settings.autoCheckForUpdatesEnabled())
        private set
    var updateChannel: Settings.UpdateChannel by mutableStateOf(Settings.updateChannel())
        private set

    private var checkJob: Job? = null

    fun setAutoCheck(enabled: Boolean) {
        Settings.setAutoCheckForUpdatesEnabled(enabled)
        autoCheckEnabled = enabled
        if (enabled) checkForUpdate(manual = false)
    }

    fun setChannel(next: Settings.UpdateChannel) {
        if (next == updateChannel) return
        Settings.setUpdateChannel(next)
        updateChannel = next
        manager.clearCache()
        downloadProgress = 0f
        downloadError = null
        state = UpdateState.Idle
        checkForUpdate(manual = true)
    }

    fun checkForUpdate(manual: Boolean = false) {
        if (isDownloading) return
        checkJob?.cancel()
        checkJob = scope.launch {
            state = UpdateState.Loading
            try {
                val release = manager.checkForUpdate(channel = updateChannel, forceShow = manual)
                state = if (release != null) {
                    if (manager.getApkFile(release) != null) {
                        downloadProgress = 100f
                        UpdateState.ReadyToInstall(release)
                    } else {
                        val existingProgress = manager.getExistingProgress(release)
                        if (existingProgress > 0f) downloadProgress = existingProgress
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
                state = if (manual) UpdateState.Error(t.message?.takeIf { it.isNotBlank() }) else UpdateState.Idle
            }
        }
    }

    fun downloadUpdate(release: XmdRelease) {
        if (isDownloading) return
        downloadError = null
        scope.launch {
            isDownloading = true
            try {
                manager.downloadUpdate(release).collect { progress -> downloadProgress = progress }
                isDownloading = false
                downloadError = null
                state = UpdateState.ReadyToInstall(release)
            } catch (cancelled: CancellationException) {
                isDownloading = false
                throw cancelled
            } catch (t: Exception) {
                Log.w("XmdUpdate", "Update download failed.", t)
                isDownloading = false
                downloadError = t.message?.takeIf { it.isNotBlank() } ?: "Download failed"
                state = UpdateState.Available(release)
            }
        }
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

    fun dismiss() {
        manager.clearCache()
        downloadProgress = 0f
        downloadError = null
        state = UpdateState.Idle
    }

    /** Clears a transient NoUpdate/Error status once the UI has shown it. */
    fun dismissStatus() {
        if (state == UpdateState.NoUpdate || state is UpdateState.Error) {
            state = UpdateState.Idle
        }
    }

    fun ignoreVersion(version: String) {
        manager.ignoreVersion(version, updateChannel)
        manager.clearCache()
        downloadProgress = 0f
        downloadError = null
        state = UpdateState.Idle
    }

    /** For the sheet's "Version x -> y" row and Ready/Available labelling. */
    fun apkSize(release: XmdRelease): Long = manager.selectApkAsset(release)?.size ?: 0L
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
