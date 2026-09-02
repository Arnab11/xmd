package com.invictus.xmd.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.invictus.xmd.BuildConfig
import com.invictus.xmd.R
import com.invictus.xmd.ui.theme.XmdTheme

/**
 * App identity, version, GitHub link, AGPL-3.0 license notice, the app's
 * developers, and a list of the open-source libraries Xmd is built on.
 * Credited libraries and their versions are read from what
 * app/build.gradle.kts actually declares; the yt-dlp wrapper is only
 * credited when [BuildConfig.HAS_YOUTUBE_SUPPORT] is true.
 *
 * Rendering moved to Compose ([AboutScreen]); this Fragment hosts a
 * [ComposeView] instead of inflating fragment_about.xml.
 */
class AboutFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val developers = listOf(
                "Utsav Rajput" to "Developer",
                "Arnab Sadhukhan" to "Developer",
                "Ritesh Pandit" to "Developer",
            )
            val credits = buildList {
                add("libtorrent4j" to getString(R.string.about_credit_libtorrent_desc))
                if (BuildConfig.HAS_YOUTUBE_SUPPORT) {
                    add("yt-dlp (youtubedl-android)" to getString(R.string.about_credit_ytdlp_desc))
                }
                add("OkHttp" to getString(R.string.about_credit_okhttp_desc))
                add("jsoup" to getString(R.string.about_credit_jsoup_desc))
                add("Room" to getString(R.string.about_credit_room_desc))
                add("Kotlin Coroutines" to getString(R.string.about_credit_coroutines_desc))
            }

            XmdTheme {
                AboutScreen(
                    versionText = getString(R.string.about_version_format, BuildConfig.VERSION_NAME),
                    onGithubClick = {
                        val url = getString(R.string.about_github_url)
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    },
                    developers = developers,
                    credits = credits,
                )
            }
        }
    }
}
