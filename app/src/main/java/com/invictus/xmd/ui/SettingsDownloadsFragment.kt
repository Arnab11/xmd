package com.invictus.xmd.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.invictus.xmd.core.NetworkMonitor
import com.invictus.xmd.core.Settings
import com.invictus.xmd.service.DownloadService
import com.invictus.xmd.ui.theme.XmdTheme

/**
 * Auto-retry, save-to-Downloads, and Wi-Fi-only. Each switch persists
 * immediately on change (no Save button), including the
 * wifi-only-just-enabled pause-in-flight-downloads check, which now runs
 * off the switch's own callback.
 *
 * Rendering moved to Compose ([SettingsDownloadsScreen]); this Fragment
 * hosts a [ComposeView] instead of inflating fragment_settings_downloads.xml.
 */
class SettingsDownloadsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            var autoRetry by mutableStateOf(Settings.autoRetryEnabled())
            var saveToDownloads by mutableStateOf(Settings.saveToDownloadsFolder())
            var wifiOnly by mutableStateOf(Settings.wifiOnlyDownloads())

            XmdTheme {
                SettingsDownloadsScreen(
                    autoRetry = autoRetry,
                    saveToDownloads = saveToDownloads,
                    wifiOnly = wifiOnly,
                    onAutoRetryChanged = { checked ->
                        autoRetry = checked
                        Settings.setAutoRetryEnabled(checked)
                    },
                    onSaveToDownloadsChanged = { checked ->
                        saveToDownloads = checked
                        Settings.setSaveToDownloadsFolder(checked)
                    },
                    onWifiOnlyChanged = { checked ->
                        val wifiOnlyJustEnabled = checked && !Settings.wifiOnlyDownloads()
                        wifiOnly = checked
                        Settings.setWifiOnlyDownloads(checked)
                        if (wifiOnlyJustEnabled && !NetworkMonitor.isOnWifi(requireContext())) {
                            // Turned ON while already on cellular -- the
                            // setting only reacts to a live network
                            // *transition* otherwise, so without this any
                            // download already in flight would keep running
                            // on cellular until the next Wi-Fi drop/regain.
                            DownloadService.pauseForWifiOnly(requireContext())
                        }
                    },
                )
            }
        }
    }
}
