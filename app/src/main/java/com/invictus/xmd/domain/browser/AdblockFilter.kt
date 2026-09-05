package com.invictus.xmd.domain.browser

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import java.io.File
import com.invictus.xmd.FfApp
import com.invictus.xmd.preferences.Settings

/**
 * Ad/tracker blocking for the in-app Browser. Two layers:
 *
 * 1. Host-list blocking (as before): a Set of known ad/tracker domains,
 *    matched against a request's host or any of its parent domains (e.g.
 *    "ads.doubleclick.net" matches a "doubleclick.net" entry). This is
 *    the bulk of what gets blocked.
 * 2. URL-pattern blocking ([blockedUrlPatterns]): a short list of
 *    ad-serving substrings (script names, paths, query markers) checked
 *    against the *full* request URL, for ads served off an otherwise
 *    legitimate/first-party host that a pure domain list can't catch.
 *
 * The host list itself now has two sources, merged together:
 *  - The bundled `assets/adblock_hosts.txt` (a few hundred hand-picked
 *    domains) -- ships with the app, always available offline, but never
 *    updates itself.
 *  - A much larger, actively-maintained hosts-format list fetched over
 *    the network by [AdblockListUpdater] and cached to internal storage,
 *    refreshed at most once a week. This is what actually gets this
 *    blocker from "a few hundred domains" to real-world EasyList-class
 *    coverage -- a bundled list alone can never keep up with how fast ad
 *    infrastructure domains churn.
 *
 * On top of both, [cosmeticHideScript] returns a small CSS injection
 * (via evaluateJavascript, after page load) that hides leftover ad
 * containers even when the underlying request wasn't blockable at the
 * network level at all -- e.g. an ad slot rendered from markup the page
 * itself served, with no separate blockable request.
 *
 * Backed by [Settings.adblockEnabled] as a single global toggle, same as
 * before -- callers should check that themselves before calling into
 * this object, so the whole path is skippable cheaply when adblock is off.
 *
 * [init] loads the merged list once, off the main thread, the first time
 * the Browser is opened (see FfApp.onCreate) rather than at every launch,
 * since most sessions never touch the browser. [isBlocked] is called from
 * shouldInterceptRequest -- WebView's own background thread(s),
 * potentially concurrently across tabs/sub-resources -- so it reads a
 * plain @Volatile Set reference with no locking; while still loading (or
 * mid-refresh) it just uses whatever's currently loaded, never blocks a
 * WebView thread on I/O.
 */
object AdblockFilter {

    private const val ASSET_PATH = "adblock_hosts.txt"
    internal const val CACHE_FILE_NAME = "adblock_hosts_cache.txt"

    // How long a cached remote list is considered fresh before a
    // background refresh is attempted again. Ad/tracker domains don't
    // churn fast enough to need anything tighter, and this keeps a
    // background browsing session from ever triggering more than one
    // network fetch a week.
    private val REFRESH_INTERVAL_MS = java.util.concurrent.TimeUnit.DAYS.toMillis(7)

    @Volatile private var blockedHosts: Set<String>? = null

    // Ad-serving/tracking URL substrings that don't reduce to a single
    // blockable hostname -- first-party-served ad paths, script names,
    // and query markers that show up across many sites regardless of
    // which domain is hosting them. Checked against the full request URL
    // (path + query included), not just the host, so this catches ads a
    // pure domain-blocklist can't (e.g. a tracking pixel path on a social
    // network's own main domain, which can't be host-blocked without
    // blocking the whole site). Kept short and specific on purpose --
    // a broad substring here risks false-positive blocking of real
    // content, so every entry is a pattern seen in practice exclusively
    // on ad/tracking requests, not general page content.
    private val blockedUrlPatterns = listOf(
        "facebook.com/tr", "yandex.ru/ads", "pinimg.com/ct", "tiktok.com/ads",
        "snapchat.com/ads", "/pagead/", "/adserver/", "/adservice/", "/ad-manager/",
        "/gpt.js", "/prebid", "/pubads_impl", "/vast.xml", "/vmap.xml",
        "/ad_status.js", "/adsbygoogle.js", "/openx.js", "/adzones/", "/adx/",
        "/popunder", "/pop_under", "adserver.", "ad-delivery.",
    )

    // Generic cosmetic hiding -- element selectors seen wrapping ad slots
    // across a wide range of sites (not site-specific ABP cosmetic rules,
    // just the handful of id/class conventions ad tech consistently uses
    // for its own containers). Injected as CSS after page load so
    // leftover ad boxes collapse even when the underlying request wasn't
    // blockable at the network level (same-origin ad iframes,
    // server-rendered ad markup). Deliberately conservative: every
    // selector here is ad-specific enough that it won't catch real page
    // content, and hiding (not removing) means nothing about page layout
    // outside the ad slot itself is touched.
    private val cosmeticSelectors = listOf(
        "ins.adsbygoogle",
        "div[id^=\"google_ads_iframe\"]",
        "iframe[id^=\"google_ads_iframe\"]",
        "div[id^=\"div-gpt-ad\"]",
        "div[class*=\"adsbygoogle\"]",
        "div[id^=\"taboola-\"]",
        "div[id^=\"outbrain_\"]",
        "div.OUTBRAIN",
        "amp-ad",
        "amp-embed[type=\"adsense\"]",
        "iframe[src*=\"doubleclick.net\"]",
        "iframe[src*=\"googlesyndication.com\"]",
    )

    private val cosmeticCss: String by lazy {
        cosmeticSelectors.joinToString(",") +
            "{display:none!important;height:0!important;min-height:0!important}"
    }

    /** JS to run via evaluateJavascript after onPageFinished. Idempotent
     *  (checks for its own marker id) and cheap -- inserts a single
     *  style tag; safe to call again on the same page. */
    fun cosmeticHideScript(): String {
        val cssLiteral = "\"" + cosmeticCss.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return """
            (function(){
              if (document.getElementById('__xmd_adblock_css__')) return;
              var s = document.createElement('style');
              s.id = '__xmd_adblock_css__';
              s.textContent = $cssLiteral;
              document.documentElement.appendChild(s);
            })();
        """.trimIndent()
    }

    fun init(context: Context) {
        if (blockedHosts != null) return
        val appContext = context.applicationContext
        Thread {
            val cache = cacheFile(appContext)
            val loaded = runCatching {
                if (cache.exists() && cache.length() > 0L) parseHostLines(cache.bufferedReader())
                else null
            }.getOrNull()

            blockedHosts = loaded?.takeIf { it.isNotEmpty() }
                ?: runCatching { parseHostsAsset(appContext) }.getOrDefault(emptySet())

            maybeRefreshInBackground(appContext)
        }.start()
    }

    internal fun cacheFile(context: Context): File = File(context.filesDir, CACHE_FILE_NAME)

    private fun parseHostsAsset(context: Context): Set<String> =
        context.assets.open(ASSET_PATH).bufferedReader().use { parseHostLines(it) }

    private fun parseHostLines(reader: java.io.BufferedReader): Set<String> =
        reader.useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toHashSet()
        }

    private fun maybeRefreshInBackground(context: Context) {
        val stale = System.currentTimeMillis() - Settings.adblockListUpdatedAt() > REFRESH_INTERVAL_MS
        if (!stale || !isNetworkAvailable(context)) return
        // Already running on a background Thread (called from init above),
        // so this runs inline rather than spawning yet another thread --
        // AdblockListUpdater.refresh does its own network I/O and file
        // write, none of which touches the main thread either way.
        val merged = runCatching { AdblockListUpdater.refresh(context) }.getOrNull()
        if (!merged.isNullOrEmpty()) {
            blockedHosts = merged
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Number of domains currently loaded -- 0 before [init]'s background
     *  parse finishes on a cold start. Exposed for the Settings screen so
     *  the adblock toggle can show real coverage instead of a static
     *  label ("Blocking 187,000+ domains" vs. a number that never moves). */
    fun blockedDomainCount(): Int = blockedHosts?.size ?: 0

    /** True if [uri] (the full request URL) matches a known ad/tracker
     *  host, or an ad-serving URL pattern. Returns false while the host
     *  list is still loading, or if adblock is off (callers should check
     *  [Settings.adblockEnabled] themselves before calling this -- kept
     *  as a separate check rather than folded in here so callers can
     *  skip the whole path cheaply). */
    fun isBlocked(uri: Uri?): Boolean {
        if (uri == null) return false
        if (isHostBlocked(uri.host)) return true
        val full = uri.toString().lowercase()
        return blockedUrlPatterns.any { full.contains(it) }
    }

    private fun isHostBlocked(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val hosts = blockedHosts ?: return false
        val lower = host.lowercase()
        if (hosts.contains(lower)) return true
        var i = lower.indexOf('.')
        while (i >= 0) {
            val parent = lower.substring(i + 1)
            if (hosts.contains(parent)) return true
            i = lower.indexOf('.', i + 1)
        }
        return false
    }
}
