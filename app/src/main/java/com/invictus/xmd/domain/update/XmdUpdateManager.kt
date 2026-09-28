package com.invictus.xmd.domain.update

import android.content.Context
import android.os.Build
import android.util.Log
import com.invictus.xmd.BuildConfig
import com.invictus.xmd.preferences.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray

// In-app updater in the same shape as Bunko's BunkoUpdateManager (itself an
// mpvRx port): GitHub Releases for Stable, an auto-built preview manifest
// (latest.json, compared by commit count) for Preview, ignore-version, and a
// parallel + resumable APK download. Xmd-specific bits kept from the old
// UpdateChecker: per-flavor / per-ABI asset selection (lite/full x arm64/v7a),
// org.json instead of kotlinx.serialization (no extra dependency), and real
// check failures surfaced as CheckFailedException so "couldn't check" is never
// mistaken for "up to date".

data class UpdateAsset(val name: String, val downloadUrl: String, val size: Long)

data class XmdRelease(
    val tagName: String,
    val name: String,
    val htmlUrl: String,
    val body: String,
    val publishedAt: String,
    val prerelease: Boolean,
    val assets: List<UpdateAsset>,
    /** Preview manifest only: `git rev-list --count HEAD` of the built commit. */
    val commitCount: Int? = null,
    val commitSha: String? = null,
)

class CheckFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

class XmdUpdateManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    /** Everything this class downloads lives here, so clearing the cache can
     *  never touch unrelated files (torrent staging etc.) in cacheDir. */
    private val updatesDir: File
        get() = File(appContext.cacheDir, "updates").apply { mkdirs() }

    /**
     * Returns the newest release on [channel] if it is newer than this build
     * (and not ignored, unless [forceShow]), or null when up to date.
     * Throws [CheckFailedException] when the check itself could not complete.
     */
    @Throws(CheckFailedException::class)
    suspend fun checkForUpdate(
        channel: Settings.UpdateChannel,
        forceShow: Boolean = false,
    ): XmdRelease? = withContext(Dispatchers.IO) {
        val release = try {
            when (channel) {
                Settings.UpdateChannel.STABLE -> getLatestRelease()
                Settings.UpdateChannel.PREVIEW -> getLatestPrerelease()
            }
        } catch (e: CheckFailedException) {
            throw e
        } catch (e: Exception) {
            Log.w(Tag, "Could not check GitHub releases.", e)
            throw CheckFailedException(e.message ?: "Update check failed", e)
        } ?: return@withContext null

        val current = BuildConfig.VERSION_NAME
        val newer = when (channel) {
            Settings.UpdateChannel.STABLE -> isVersionNewer(release.tagName, current)
            Settings.UpdateChannel.PREVIEW ->
                isPreviewReleaseNewer(release, BuildConfig.GIT_COUNT, current)
        }
        if (!newer) return@withContext null

        val ignored = prefs.getString(ignoredVersionKey(channel), null)
        if (!forceShow && (ignored == release.tagName || ignored == release.tagName.removePrefix("v"))) {
            return@withContext null
        }
        // A newer release without an APK for this flavor/ABI can't be
        // installed in-app -- report it instead of pretending we're current.
        if (selectApkAsset(release) == null) {
            throw CheckFailedException("No compatible APK in ${release.tagName}")
        }
        release
    }

    fun ignoreVersion(version: String, channel: Settings.UpdateChannel) {
        prefs.edit().putString(ignoredVersionKey(channel), version).apply()
    }

    private fun ignoredVersionKey(channel: Settings.UpdateChannel): String =
        "ignored_version_${channel.name.lowercase()}"

    fun selectApkAsset(release: XmdRelease): UpdateAsset? =
        selectXmdApkAsset(release.assets, BuildConfig.FLAVOR, Build.SUPPORTED_ABIS.toList())

    fun downloadUpdate(release: XmdRelease): Flow<Float> {
        val asset = selectApkAsset(release) ?: throw IOException("No compatible APK asset found")
        return downloadApkParallel(asset.downloadUrl, File(updatesDir, asset.name), asset.name, asset.size)
    }

    fun getApkFile(release: XmdRelease): File? {
        val asset = selectApkAsset(release) ?: return null
        val destination = File(updatesDir, asset.name)
        val hasParts = updatesDir.listFiles()?.any { it.name.startsWith("${asset.name}.part") } == true
        return if (destination.exists() && destination.length() > 0L && !hasParts) destination else null
    }

    fun getExistingProgress(release: XmdRelease): Float {
        val asset = selectApkAsset(release) ?: return 0f
        val destination = File(updatesDir, asset.name)
        if (destination.exists() && destination.length() > 0L) return 100f
        if (asset.size <= 0L) return 0f
        val partBytes = updatesDir.listFiles()
            ?.filter { it.name.startsWith("${asset.name}.part") }
            ?.sumOf { it.length() } ?: 0L
        if (partBytes > 0L) {
            return ((partBytes.toFloat() / asset.size.toFloat()) * 100f).coerceIn(0f, 99f)
        }
        return 0f
    }

    fun clearCache() {
        updatesDir.listFiles()?.forEach { it.delete() }
        // APKs the pre-manager updater left directly in cacheDir.
        appContext.cacheDir.listFiles()?.forEach {
            if (it.isFile && it.name.startsWith("Xmd-") && it.name.endsWith(".apk")) it.delete()
        }
    }

    // ---- Network: release feeds ------------------------------------------

    /** GitHub's own "newest non-prerelease" pointer; null when the repo has none (404). */
    private suspend fun getLatestRelease(): XmdRelease? = withContext(Dispatchers.IO) {
        apiClient.newCall(releaseRequest(LatestReleaseUrl)).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw CheckFailedException("HTTP ${response.code}")
            val body = response.body?.string() ?: throw CheckFailedException("Empty response")
            parseRelease(JSONObject(body))
                ?: throw CheckFailedException("Release response missing tag_name")
        }
    }

    private suspend fun getLatestPrerelease(): XmdRelease? = withContext(Dispatchers.IO) {
        // Primary feed: the auto-built preview manifest (preview.yml).
        fetchPreviewManifest()?.let { return@withContext it }
        // Fallback: the newest hand-cut GitHub prerelease (prerelease.yml).
        apiClient.newCall(releaseRequest(ReleasesListUrl)).execute().use { response ->
            if (!response.isSuccessful) throw CheckFailedException("HTTP ${response.code}")
            val body = response.body?.string() ?: throw CheckFailedException("Empty response")
            val array = JSONArray(body)
            for (i in 0 until array.length()) {
                val release = array.optJSONObject(i)?.let(::parseRelease) ?: continue
                // The rolling "preview" release only hosts the manifest feed.
                if (release.prerelease && release.tagName != PreviewReleaseTag) return@withContext release
            }
            null
        }
    }

    private suspend fun fetchPreviewManifest(): XmdRelease? = withContext(Dispatchers.IO) {
        try {
            apiClient.newCall(releaseRequest(PreviewManifestUrl)).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                runCatching { parseRelease(JSONObject(body)) }.getOrNull()
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun parseRelease(json: JSONObject): XmdRelease? {
        val tagName = json.optString("tag_name").ifBlank { return null }
        val assetsJson = json.optJSONArray("assets")
        val assets = buildList {
            if (assetsJson != null) {
                for (i in 0 until assetsJson.length()) {
                    val a = assetsJson.optJSONObject(i) ?: continue
                    val name = a.optString("name").ifBlank { continue }
                    val url = a.optString("browser_download_url").ifBlank { continue }
                    add(UpdateAsset(name = name, downloadUrl = url, size = a.optLong("size", 0L)))
                }
            }
        }
        return XmdRelease(
            tagName = tagName,
            name = json.optString("name", tagName),
            htmlUrl = json.optString("html_url", ReleasesFallbackUrl),
            body = json.optString("body", ""),
            publishedAt = json.optString("published_at", ""),
            prerelease = json.optBoolean("prerelease", false),
            assets = assets,
            commitCount = if (json.has("commit_count")) json.optInt("commit_count") else null,
            commitSha = json.optString("commit_sha").ifBlank { null },
        )
    }

    private fun releaseRequest(url: String): Request =
        Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", userAgent())
            .header("Cache-Control", "no-cache")
            .build()

    private fun userAgent() = "Xmd/${BuildConfig.VERSION_NAME}"

    // ---- Network: parallel + resumable APK download ----------------------

    private fun downloadApkParallel(
        url: String,
        destination: File,
        assetName: String,
        expectedTotalBytes: Long,
    ): Flow<Float> = channelFlow {
        var totalBytes = expectedTotalBytes

        // Unknown size: ask the server via HEAD.
        if (totalBytes <= 0L) {
            val headReq = Request.Builder().url(url).head().header("User-Agent", userAgent()).build()
            try {
                downloadClient.newCall(headReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        totalBytes = resp.header("Content-Length")?.toLongOrNull() ?: 0L
                    }
                }
            } catch (_: Exception) {
            }
        }

        val numChunks = if (totalBytes >= MinParallelBytes) ParallelChunkCount else 1
        val singlePartFile = File(updatesDir, "$assetName.part")

        if (numChunks == 1) {
            downloadSingleStream(url, destination, singlePartFile, totalBytes) { p -> send(p) }
            send(100f)
            return@channelFlow
        }

        val chunkSize = totalBytes / numChunks
        val partFiles = (0 until numChunks).map { i -> File(updatesDir, "$assetName.part$i") }

        val allCompleted = partFiles.mapIndexed { i, file ->
            val startByte = i * chunkSize
            val endByte = if (i == numChunks - 1) totalBytes - 1 else (i + 1) * chunkSize - 1
            file.exists() && file.length() >= endByte - startByte + 1
        }.all { it }

        if (allCompleted) {
            mergeChunkFiles(partFiles, destination)
            send(100f)
            return@channelFlow
        }

        val writtenPerChunk = AtomicLongArray(numChunks)
        for (i in 0 until numChunks) {
            writtenPerChunk.set(i, if (partFiles[i].exists()) partFiles[i].length() else 0L)
        }
        val lastEmittedPercent = AtomicInteger(-1)

        fun checkAndSendProgress() {
            var sum = 0L
            for (i in 0 until numChunks) sum += writtenPerChunk.get(i)
            if (totalBytes > 0L) {
                val percent = ((sum.toDouble() / totalBytes.toDouble()) * 100.0).toFloat().coerceIn(0f, 99.9f)
                val pInt = percent.toInt()
                val prev = lastEmittedPercent.get()
                if (pInt != prev && lastEmittedPercent.compareAndSet(prev, pInt)) trySend(percent)
            }
        }

        checkAndSendProgress() // resuming: show what's already on disk

        coroutineScope {
            val jobs = (0 until numChunks).map { i ->
                val startByte = i * chunkSize
                val endByte = if (i == numChunks - 1) totalBytes - 1 else (i + 1) * chunkSize - 1
                val targetSize = endByte - startByte + 1
                val partFile = partFiles[i]

                async(Dispatchers.IO) {
                    var existingLen = if (partFile.exists()) partFile.length() else 0L
                    if (existingLen >= targetSize) {
                        writtenPerChunk.set(i, targetSize)
                        return@async
                    }

                    var response = downloadClient.newCall(
                        rangeRequest(url, startByte + existingLen, endByte),
                    ).execute()

                    if (response.code == 416) {
                        response.close()
                        partFile.delete()
                        existingLen = 0L
                        writtenPerChunk.set(i, 0L)
                        response = downloadClient.newCall(rangeRequest(url, startByte, endByte)).execute()
                    }

                    if (!response.isSuccessful) {
                        val code = response.code
                        response.close()
                        throw IOException("HTTP error $code for chunk $i")
                    }

                    val body = response.body ?: throw IOException("Empty body for chunk $i")
                    val appendMode = response.code == 206 && existingLen > 0L
                    if (!appendMode) {
                        existingLen = 0L
                        if (partFile.exists()) partFile.delete()
                    }

                    body.byteStream().use { input ->
                        FileOutputStream(partFile, appendMode).use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var read: Int
                            var currentWritten = existingLen
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                currentWritten += read
                                writtenPerChunk.set(i, currentWritten)
                                checkAndSendProgress()
                            }
                            output.flush()
                        }
                    }
                }
            }
            jobs.awaitAll()
        }

        mergeChunkFiles(partFiles, destination)
        send(100f)
    }.flowOn(Dispatchers.IO)

    private fun rangeRequest(url: String, from: Long, to: Long): Request =
        Request.Builder()
            .url(url)
            .header("Range", "bytes=$from-$to")
            .header("User-Agent", userAgent())
            .build()

    private fun mergeChunkFiles(partFiles: List<File>, destination: File) {
        if (destination.exists()) destination.delete()
        val tempDest = File(destination.parentFile, "${destination.name}.merge_tmp")
        if (tempDest.exists()) tempDest.delete()

        FileOutputStream(tempDest).use { out ->
            for (file in partFiles) file.inputStream().use { it.copyTo(out) }
            out.flush()
        }
        if (!tempDest.renameTo(destination)) {
            tempDest.copyTo(destination, overwrite = true)
            tempDest.delete()
        }
        partFiles.forEach { it.delete() }
    }

    private suspend fun downloadSingleStream(
        url: String,
        destination: File,
        tempFile: File,
        expectedTotalBytes: Long,
        onProgress: suspend (Float) -> Unit,
    ) {
        var existingLength = if (tempFile.exists()) tempFile.length() else 0L

        if (expectedTotalBytes > 0L && existingLength >= expectedTotalBytes) {
            promote(tempFile, destination)
            return
        }

        val requestBuilder = Request.Builder().url(url).header("User-Agent", userAgent())
        if (existingLength > 0L) requestBuilder.header("Range", "bytes=$existingLength-")

        var response = downloadClient.newCall(requestBuilder.build()).execute()

        if (response.code == 416) {
            response.close()
            tempFile.delete()
            existingLength = 0L
            response = downloadClient.newCall(
                Request.Builder().url(url).header("User-Agent", userAgent()).build(),
            ).execute()
        }

        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException("Unexpected download code: $code")
        }

        val body = response.body ?: throw IOException("Empty download response")
        val appendMode = response.code == 206 && existingLength > 0L
        if (!appendMode) {
            existingLength = 0L
            if (tempFile.exists()) tempFile.delete()
        }

        val contentLength = body.contentLength()
        val totalBytes = if (contentLength > 0L) {
            if (appendMode) existingLength + contentLength else contentLength
        } else {
            expectedTotalBytes
        }

        body.byteStream().use { input ->
            FileOutputStream(tempFile, appendMode).use { output ->
                val buffer = ByteArray(32 * 1024)
                var bytesRead: Int
                var written = existingLength
                var lastEmittedPercent = -1

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    written += bytesRead
                    if (totalBytes > 0L) {
                        val percent = ((written.toDouble() / totalBytes.toDouble()) * 100.0)
                            .toFloat().coerceIn(0f, 99.9f)
                        val pInt = percent.toInt()
                        if (pInt != lastEmittedPercent) {
                            lastEmittedPercent = pInt
                            onProgress(percent)
                        }
                    } else {
                        onProgress(-1f)
                    }
                }
                output.flush()
            }
        }
        promote(tempFile, destination)
    }

    private fun promote(tempFile: File, destination: File) {
        if (destination.exists()) destination.delete()
        if (!tempFile.renameTo(destination)) {
            tempFile.copyTo(destination, overwrite = true)
            tempFile.delete()
        }
    }

    private companion object {
        const val Tag = "XmdUpdate"
        const val PreferencesName = "xmd_update"
        const val Repo = "Utsavrajputt/xmd"
        const val PreviewReleaseTag = "preview"
        const val LatestReleaseUrl = "https://api.github.com/repos/$Repo/releases/latest"
        const val ReleasesListUrl = "https://api.github.com/repos/$Repo/releases?per_page=20"
        const val ReleasesFallbackUrl = "https://github.com/$Repo/releases"

        /** Published by .github/workflows/preview.yml onto the rolling
         *  "preview" pre-release (not GitHub Pages -- deploy-site.yml already
         *  owns that deployment and a second Pages deploy would overwrite it). */
        const val PreviewManifestUrl =
            "https://github.com/$Repo/releases/download/$PreviewReleaseTag/latest.json"

        const val ParallelChunkCount = 4
        const val MinParallelBytes = 2 * 1024 * 1024L // 2MB

        val apiClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        val downloadClient: OkHttpClient = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply {
                maxRequests = 16
                maxRequestsPerHost = 8
            })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}

// ---- Pure helpers (unit-tested) ------------------------------------------

private val SupportedAbis = listOf("arm64-v8a", "armeabi-v7a")

/**
 * Picks this build's own flavor asset ("Xmd-lite-..." / "Xmd-full-...") for
 * the device's primary supported ABI, falling back to the other ABI Xmd
 * ships (e.g. an x86 emulator). Null if the release has nothing compatible.
 */
internal fun selectXmdApkAsset(
    assets: List<UpdateAsset>,
    flavor: String,
    deviceAbis: List<String>,
): UpdateAsset? {
    val flavorAssets = assets.filter {
        it.downloadUrl.isNotBlank() &&
            it.name.endsWith(".apk", ignoreCase = true) &&
            it.name.startsWith("Xmd-$flavor-", ignoreCase = true)
    }
    val primary = deviceAbis.firstOrNull { it in SupportedAbis } ?: SupportedAbis[0]
    return flavorAssets.firstOrNull { it.name.hasAssetToken(primary) }
        ?: flavorAssets.firstOrNull { asset ->
            SupportedAbis.any { it != primary && asset.name.hasAssetToken(it) }
        }
}

private fun String.hasAssetToken(token: String): Boolean =
    Regex("(?:^|-)${Regex.escape(token)}(?:-|\\.apk$)", RegexOption.IGNORE_CASE).containsMatchIn(this)

fun isVersionNewer(candidate: String, current: String): Boolean {
    val candidateParts = candidate.versionParts() ?: return false
    val currentParts = current.versionParts() ?: return false
    val size = maxOf(candidateParts.size, currentParts.size)
    for (index in 0 until size) {
        val c = candidateParts.getOrElse(index) { 0 }
        val k = currentParts.getOrElse(index) { 0 }
        if (c != k) return c > k
    }
    return false
}

private fun String.versionParts(): List<Int>? {
    val normalized = trim().removePrefix("v").removePrefix("V").substringBefore('-')
    if (normalized.isBlank()) return null
    return normalized.split('.').map { part ->
        val digits = part.takeWhile(Char::isDigit)
        if (digits.isBlank()) return null
        digits.toIntOrNull() ?: return null
    }
}

/**
 * Hand-cut pre-release tags look like `v1.1.0-beta.2` / `-rc.1`. Newer when
 * the base version is ahead, or equal with a higher pre-release number (an
 * installed stable build counts as number 0).
 */
fun isPreviewNewer(candidate: String, current: String): Boolean {
    val candidateBase = candidate.versionParts() ?: return false
    val currentBase = current.versionParts() ?: return false
    val size = maxOf(candidateBase.size, currentBase.size)
    for (index in 0 until size) {
        val c = candidateBase.getOrElse(index) { 0 }
        val k = currentBase.getOrElse(index) { 0 }
        if (c != k) return c > k
    }
    return candidate.previewNumber() > current.previewNumber()
}

/**
 * Manifest model: the preview feed carries a commit count compared against
 * this build's GIT_COUNT; hand-cut prereleases fall back to tag comparison.
 */
internal fun isPreviewReleaseNewer(release: XmdRelease, currentGitCount: Int, currentVersion: String): Boolean {
    val remoteCount = release.previewBuildNumber()
    if (remoteCount != null) return remoteCount > currentGitCount
    return isPreviewNewer(release.tagName, currentVersion)
}

private val PreviewTagRegex = Regex("""(?:preview|beta|rc|alpha)\.(\d+)""", RegexOption.IGNORE_CASE)
private val ManifestTagRegex = Regex("""preview-r(\d+)""", RegexOption.IGNORE_CASE)

/** Build number of an auto-built preview (manifest `commit_count` or `preview-r123` tag); null for other releases. */
internal fun XmdRelease.previewBuildNumber(): Int? =
    commitCount ?: ManifestTagRegex.find(tagName)?.groupValues?.getOrNull(1)?.toIntOrNull()

private fun String.previewNumber(): Int {
    val base = trim().removePrefix("v").removePrefix("V")
    if (!base.contains('-')) return 0
    return PreviewTagRegex.find(base)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
}
