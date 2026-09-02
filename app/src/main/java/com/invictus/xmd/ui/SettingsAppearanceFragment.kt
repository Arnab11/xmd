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
import com.invictus.xmd.core.Settings
import com.invictus.xmd.ui.theme.XmdTheme

/**
 * Theme color + dark mode. Picker/switch logic moved verbatim from
 * MainActivity.setupThemePicker()/toggleDarkMode() (old Settings dialog) --
 * same recreate()-on-change approach. recreate() here targets
 * SettingsActivity (this fragment's host), which now applies the theme
 * itself in onCreate() (like MainActivity/ChallengeActivity do) so the
 * recreate actually repaints this screen. MainActivity picks up the change
 * on its own next onResume (it compares the currently-applied theme style
 * against Settings and recreates itself if they've diverged), so backing
 * out of Settings repaints it immediately too, no app restart needed.
 *
 * Rendering moved to Compose ([SettingsAppearanceScreen]); this Fragment
 * hosts a [ComposeView] instead of inflating fragment_settings_appearance.xml.
 */
class SettingsAppearanceFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            // Local state exists only so the screen doesn't visibly lag
            // between tap and requireActivity().recreate() actually
            // repainting -- recreate() is still the source of truth for
            // every persisted value.
            var currentTheme by mutableStateOf(Settings.appTheme())
            var isDark by mutableStateOf(Settings.isDarkMode())
            var isAmoled by mutableStateOf(Settings.isAmoledMode())

            XmdTheme {
                SettingsAppearanceScreen(
                    currentTheme = currentTheme,
                    isDark = isDark,
                    isAmoled = isAmoled,
                    onThemeSelected = { theme ->
                        if (theme != Settings.appTheme()) {
                            currentTheme = theme
                            Settings.setAppTheme(theme)
                            requireActivity().recreate()
                        }
                    },
                    onDarkModeChanged = { checked ->
                        if (checked != Settings.isDarkMode()) {
                            isDark = checked
                            Settings.setDarkMode(checked)
                            requireActivity().recreate()
                        }
                    },
                    onAmoledModeChanged = { checked ->
                        if (checked != Settings.isAmoledMode()) {
                            isAmoled = checked
                            Settings.setAmoledMode(checked)
                            requireActivity().recreate()
                        }
                    },
                )
            }
        }
    }
}
