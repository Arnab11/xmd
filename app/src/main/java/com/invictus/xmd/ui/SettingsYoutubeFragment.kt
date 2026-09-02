package com.invictus.xmd.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.invictus.xmd.BuildConfig
import com.invictus.xmd.R
import com.invictus.xmd.core.Settings
import com.invictus.xmd.core.YtDlpManager
import com.invictus.xmd.ui.theme.XmdTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Default download quality, video preset ladder (container/fps/codec),
 * audio format, and the yt-dlp engine install/update/nightly-channel
 * controls. The quality/preset dropdowns persist immediately on selection
 * (no Save button); the yt-dlp install/update/nightly controls remain
 * immediate too, now driven by a single [YtDlpOpState] instead of the old
 * refreshYtDlpRow() imperative state juggling.
 *
 * Rendering moved to Compose ([SettingsYoutubeScreen]); this Fragment hosts
 * a [ComposeView] instead of inflating fragment_settings_youtube.xml.
 */
class SettingsYoutubeFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            // Video preset (container/fps/codec) + audio format dropdowns --
            // each a fixed label<->enum pair list, same "pick by displayed
            // label, persist on item click" pattern as the quality dropdown.
            val containerOptions = listOf(
                getString(R.string.preset_any) to Settings.ContainerPreset.ANY,
                getString(R.string.preset_container_mp4) to Settings.ContainerPreset.MP4,
                getString(R.string.preset_container_webm) to Settings.ContainerPreset.WEBM
            )
            val fpsOptions = listOf(
                getString(R.string.preset_any) to Settings.FpsPreset.ANY,
                getString(R.string.preset_fps_30) to Settings.FpsPreset.FPS30,
                getString(R.string.preset_fps_60) to Settings.FpsPreset.FPS60
            )
            val codecOptions = listOf(
                getString(R.string.preset_any) to Settings.CodecPreset.ANY,
                getString(R.string.preset_codec_avc) to Settings.CodecPreset.AVC,
                getString(R.string.preset_codec_vp9) to Settings.CodecPreset.VP9,
                getString(R.string.preset_codec_av1) to Settings.CodecPreset.AV1
            )
            val audioFormatOptions = listOf(
                getString(R.string.audio_format_mp3) to Settings.AudioFormatPreset.MP3,
                getString(R.string.audio_format_m4a) to Settings.AudioFormatPreset.M4A,
                getString(R.string.audio_format_opus) to Settings.AudioFormatPreset.OPUS,
                getString(R.string.audio_format_original) to Settings.AudioFormatPreset.ORIGINAL
            )

            // "Ask always" (blank stored value) first, then one entry per
            // standardQualityOptions() label, same order as the picker
            // dialog itself so the two stay visually consistent.
            val qualityLabels = listOf(getString(R.string.quality_ask_always)) +
                YtDlpManager.standardQualityOptions().map { it.label }
            val askAlwaysLabel = getString(R.string.quality_ask_always)

            fun resolveInitialQualityLabel(): String {
                val savedLabel = Settings.ytDlpDefaultQualityLabel()
                return when {
                    savedLabel.isBlank() -> askAlwaysLabel
                    qualityLabels.contains(savedLabel) -> savedLabel
                    // Saved before the audio format preset changed (see the
                    // matching resolveYoutube() fallback) -- show the
                    // current audio-only label instead of a stale "(MP3)"
                    // that's no longer in the list.
                    savedLabel.startsWith("Audio only") ->
                        qualityLabels.firstOrNull { it.startsWith("Audio only") } ?: savedLabel
                    else -> savedLabel
                }
            }

            var selectedQualityLabel by mutableStateOf(resolveInitialQualityLabel())
            var selectedContainerLabel by mutableStateOf(
                containerOptions.first { it.second == Settings.presetContainer() }.first
            )
            var selectedFpsLabel by mutableStateOf(
                fpsOptions.first { it.second == Settings.presetFps() }.first
            )
            var selectedCodecLabel by mutableStateOf(
                codecOptions.first { it.second == Settings.presetCodec() }.first
            )
            var selectedAudioFormatLabel by mutableStateOf(
                audioFormatOptions.first { it.second == Settings.presetAudioFormat() }.first
            )

            var ytDlpInstalled by mutableStateOf(YtDlpManager.isInstalled(requireContext()))
            var ytDlpUsingNightly by mutableStateOf(Settings.ytDlpUseNightly())
            var ytDlpOpState by mutableStateOf<YtDlpOpState>(YtDlpOpState.Idle)

            fun refreshYtDlpStatus() {
                ytDlpInstalled = YtDlpManager.isInstalled(requireContext())
                ytDlpUsingNightly = Settings.ytDlpUseNightly()
            }

            XmdTheme {
                SettingsYoutubeScreen(
                    liteMode = !BuildConfig.HAS_YOUTUBE_SUPPORT,
                    hintText = getString(R.string.settings_ytdlp_hint),
                    qualityLabels = qualityLabels,
                    selectedQualityLabel = selectedQualityLabel,
                    onQualityChanged = { index ->
                        val chosenLabel = qualityLabels[index]
                        selectedQualityLabel = chosenLabel
                        Settings.setYtDlpDefaultQualityLabel(
                            if (chosenLabel == askAlwaysLabel) "" else chosenLabel
                        )
                    },
                    containerOptions = containerOptions.map { it.first },
                    selectedContainer = selectedContainerLabel,
                    onContainerChanged = { index ->
                        selectedContainerLabel = containerOptions[index].first
                        Settings.setPresetContainer(containerOptions[index].second)
                    },
                    fpsOptions = fpsOptions.map { it.first },
                    selectedFps = selectedFpsLabel,
                    onFpsChanged = { index ->
                        selectedFpsLabel = fpsOptions[index].first
                        Settings.setPresetFps(fpsOptions[index].second)
                    },
                    codecOptions = codecOptions.map { it.first },
                    selectedCodec = selectedCodecLabel,
                    onCodecChanged = { index ->
                        selectedCodecLabel = codecOptions[index].first
                        Settings.setPresetCodec(codecOptions[index].second)
                    },
                    audioFormatOptions = audioFormatOptions.map { it.first },
                    selectedAudioFormat = selectedAudioFormatLabel,
                    onAudioFormatChanged = { index ->
                        selectedAudioFormatLabel = audioFormatOptions[index].first
                        Settings.setPresetAudioFormat(audioFormatOptions[index].second)
                    },
                    ytDlpInstalled = ytDlpInstalled,
                    ytDlpUsingNightly = ytDlpUsingNightly,
                    ytDlpOpState = ytDlpOpState,
                    onInstallOrDeleteClick = {
                        if (ytDlpInstalled) {
                            YtDlpManager.delete(requireContext())
                            Toast.makeText(requireContext(), "yt-dlp removed", Toast.LENGTH_SHORT).show()
                            refreshYtDlpStatus()
                        } else {
                            ytDlpOpState = YtDlpOpState.Installing
                            lifecycleScope.launch {
                                val error = withContext(Dispatchers.IO) { YtDlpManager.install(requireContext()) }
                                // Show the exact failure reason instead of a
                                // generic message -- install() only unpacks
                                // bundled assets, no network involved, so a
                                // guessed "check your connection" message
                                // would usually be wrong.
                                Toast.makeText(
                                    requireContext(),
                                    error?.let { "Install failed: $it" } ?: "yt-dlp installed",
                                    Toast.LENGTH_LONG
                                ).show()
                                refreshYtDlpStatus()
                                ytDlpOpState = YtDlpOpState.Idle
                            }
                        }
                    },
                    onUpdateClick = {
                        ytDlpOpState = YtDlpOpState.Updating
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) { YtDlpManager.update(requireContext()) }
                            Toast.makeText(
                                requireContext(),
                                result?.let { "yt-dlp: $it" } ?: "Update failed — check your connection",
                                Toast.LENGTH_LONG
                            ).show()
                            refreshYtDlpStatus()
                            ytDlpOpState = YtDlpOpState.Idle
                        }
                    },
                    onNightlyToggleClick = {
                        val switchingToNightly = !ytDlpUsingNightly
                        ytDlpOpState = YtDlpOpState.SwitchingChannel(switchingToNightly)
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                YtDlpManager.switchChannel(requireContext(), switchingToNightly)
                            }
                            Toast.makeText(
                                requireContext(),
                                result?.let { "yt-dlp: $it" } ?: "Switch failed — check your connection",
                                Toast.LENGTH_LONG
                            ).show()
                            refreshYtDlpStatus()
                            ytDlpOpState = YtDlpOpState.Idle
                        }
                    },
                )
            }
        }
    }
}
