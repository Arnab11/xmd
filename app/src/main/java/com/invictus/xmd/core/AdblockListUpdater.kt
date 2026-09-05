package com.invictus.xmd.core

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Fetches a maintained, hosts-format ad/tracker blocklist over the
 * network and merges it into the bundled asset list -- see
 * [AdblockFilter]'s class doc for why the bundled list alone (a few
 * hundred hand-picked domains) isn't enough for real-world coverage.
 * Called from [AdblockFilter] at most once per its refresh interval, off
 * the main thread, and fails silently on any error (network issues,
 * malformed response, oversized response, every source down) -- the
 * caller just keeps whatever's already loaded.
 *
 * Deliberately reuses the plain hosts-file format ("0.0.0.0 domain" /
 * "127.0.0.1 domain" per line) rather than parsing full ABP/EasyList
 * filter syntax ($third-party, $script, cosmetic rules, exception rules,
 * etc.) -- that format needs a much more careful rule engine to avoid
 * false-positive over-blocking, and hosts-format lists already cover the
 * large majority of ad/tracker domains this app cares about with a much
 * simpler, safer parser. [AdblockFilter.blockedUrlPatterns] and
 * [AdblockFilter.cosmeticSelectors] separately cover what a pure hosts
 * list structurally can't (path-based ads, cosmetic leftovers).
 */
object AdblockListUpdater {

    // Tried in order; the first source that fetches successfully wins.
    // Both are long-running, widely used, hosts-format lists maintained
    // independently of this app -- StevenBlack's is a broad unified list
    // (ads + malware + a few other categories combined, which is a
    // bonus here, not a downside); someonewhocares.org is a much older,
    // smaller, ads/tracking-focused list kept as a fallback in case the
    // first source is ever unreachable or restructured.
    private val SOURCES = listOf(
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        "https://someonewhocares.org/hosts/zero/hosts",
    )

    // Guards against a misbehaving/compromised source handing back
    // something absurd. A real hosts list this app needs is well under
    // this even at its largest.
    private const val MAX_RESPONSE_BYTES = 30L * 1024 * 1024

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Fetches, parses, and merges with the bundled asset list; on
     *  success, persists the merged set to internal storage (so future
     *  cold starts load it before any network round trip is needed) and
     *  records the refresh timestamp. Returns the merged set, or null if
     *  every source failed. Must be called off the main thread -- does
     *  blocking network I/O. */
    fun refresh(context: Context): Set<String>? {
        for (url in SOURCES) {
            val remote = runCatching { fetchAndParse(url) }.getOrNull()
            if (remote.isNullOrEmpty()) continue

            val bundled = runCatching {
                context.assets.open("adblock_hosts.txt").bufferedReader().useLines { lines ->
                    lines.map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .toHashSet()
                }
            }.getOrDefault(emptySet())

            val merged = HashSet<String>(remote.size + bundled.size)
            merged.addAll(remote)
            merged.addAll(bundled)
            persist(context, merged)
            Settings.setAdblockListUpdatedAt(System.currentTimeMillis())
            return merged
        }
        return null
    }

    private fun fetchAndParse(url: String): Set<String>? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "XMD-Adblock/1.0")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val declaredLength = response.header("Content-Length")?.toLongOrNull()
            if (declaredLength != null && declaredLength > MAX_RESPONSE_BYTES) return null

            val domains = HashSet<String>()
            var bytesRead = 0L
            for (rawLine in body.byteStream().bufferedReader().lineSequence()) {
                bytesRead += rawLine.length + 1
                // Response ran past the size guard (either a source with no
                // Content-Length header, or one that lied about it) -- stop
                // reading rather than let an unbounded stream keep growing
                // this set in memory.
                if (bytesRead > MAX_RESPONSE_BYTES) break

                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2) continue
                val ip = parts[0]
                if (ip != "0.0.0.0" && ip != "127.0.0.1") continue
                val domain = parts[1].lowercase()
                if (domain.isEmpty() || domain == "localhost" || domain == "0.0.0.0" ||
                    domain.endsWith(".local")
                ) continue
                domains.add(domain)
            }
            return domains
        }
    }

    private fun persist(context: Context, domains: Set<String>) {
        runCatching {
            val target = AdblockFilter.cacheFile(context)
            val tmp = File(context.filesDir, AdblockFilter.CACHE_FILE_NAME + ".tmp")
            tmp.bufferedWriter().use { writer ->
                for (domain in domains) {
                    writer.write(domain)
                    writer.newLine()
                }
            }
            // Atomic-ish swap so a process death mid-write never leaves a
            // half-written cache file for the next cold start to load.
            if (!tmp.renameTo(target)) {
                tmp.delete()
            }
        }
    }
}
