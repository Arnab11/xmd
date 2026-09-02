package com.invictus.xmd.ui

import android.content.Context
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
import com.invictus.xmd.core.Settings
import com.invictus.xmd.ui.theme.XmdTheme

/**
 * Browser settings: the global adblock toggle (see AdblockFilter/
 * Settings.adblockEnabled) and the website source-pack import/export
 * trigger (moved here from Downloads -- it's a browser bookmark/shortcut
 * list, not a download setting). Private DNS mode lives in its own
 * in-browser dialog (BrowserFragment's overflow menu) rather than here.
 *
 * Rendering moved to Compose ([SettingsBrowserScreen]); this Fragment
 * hosts a [ComposeView] instead of inflating fragment_settings_browser.xml.
 */
class SettingsBrowserFragment : Fragment() {

    /** Implemented by [SettingsActivity]; import logic is host-owned since
     *  it needs an Activity-scoped lifecycleScope + dialog host. */
    interface Callbacks {
        fun startWebImportFlow()
        fun startWebExportFlow()
    }

    private var callbacks: Callbacks? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callbacks = context as? Callbacks
    }

    override fun onDetach() {
        super.onDetach()
        callbacks = null
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            var adblockEnabled by mutableStateOf(Settings.adblockEnabled())

            XmdTheme {
                SettingsBrowserScreen(
                    adblockEnabled = adblockEnabled,
                    onAdblockChanged = { checked ->
                        adblockEnabled = checked
                        Settings.setAdblockEnabled(checked)
                    },
                    onImportWebsites = { callbacks?.startWebImportFlow() },
                    onExportWebsites = { callbacks?.startWebExportFlow() },
                )
            }
        }
    }
}
