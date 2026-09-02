package com.invictus.xmd.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.invictus.xmd.BuildConfig
import com.invictus.xmd.ui.theme.XmdTheme

/**
 * Root of the redesigned Settings screen: a list of category rows that push
 * the matching sub-fragment via [SettingsActivity.openCategory]. Rendering
 * moved to Compose ([SettingsRootScreen]); this Fragment now only hosts a
 * [ComposeView] instead of inflating fragment_settings_root.xml -- the
 * Fragment-based push/pop navigation in [SettingsActivity] is untouched.
 */
class SettingsRootFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            XmdTheme {
                SettingsRootScreen(
                    showYoutubeRow = BuildConfig.HAS_YOUTUBE_SUPPORT,
                    onOpenAppearance = { open(SettingsAppearanceFragment(), "settings_appearance") },
                    onOpenConnections = { open(SettingsConnectionsFragment(), "settings_connections") },
                    onOpenBrowser = { open(SettingsBrowserFragment(), "settings_browser") },
                    onOpenDownloads = { open(SettingsDownloadsFragment(), "settings_downloads") },
                    onOpenYoutube = { open(SettingsYoutubeFragment(), "settings_youtube") },
                    onOpenAbout = { open(AboutFragment(), "settings_about") },
                )
            }
        }
    }

    private fun open(fragment: Fragment, tag: String) {
        (activity as? SettingsActivity)?.openCategory(fragment, tag)
    }
}
