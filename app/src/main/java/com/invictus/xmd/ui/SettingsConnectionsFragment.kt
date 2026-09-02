package com.invictus.xmd.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.invictus.xmd.R
import com.invictus.xmd.core.Settings
import com.invictus.xmd.ui.theme.XmdTheme

/**
 * Parallel connections per download, global speed limit, max concurrent
 * downloads. Save logic moved verbatim from the old Settings dialog's
 * positive-button handler (connections + speed + concurrency portion only).
 *
 * Rendering moved to Compose ([SettingsConnectionsScreen]); this Fragment
 * hosts a [ComposeView] instead of inflating fragment_settings_connections.xml.
 */
class SettingsConnectionsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            XmdTheme {
                SettingsConnectionsScreen(
                    initialConnections = Settings.connectionsPerDownload(),
                    initialSpeedLimitKBps = Settings.speedLimitKBps(),
                    initialMaxConcurrent = Settings.maxConcurrentDownloads(),
                    onSave = { connections, speedLimitKBps, maxConcurrent ->
                        Settings.setConnectionsPerDownload(connections)
                        Settings.setSpeedLimitKBps(speedLimitKBps)
                        Settings.setMaxConcurrentDownloads(maxConcurrent)
                        Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }
}
