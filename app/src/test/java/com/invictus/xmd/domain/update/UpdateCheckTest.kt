package com.invictus.xmd.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {

    private fun asset(name: String) = UpdateAsset(name = name, downloadUrl = "https://example.com/$name", size = 1L)

    private fun release(tag: String, count: Int? = null) = XmdRelease(
        tagName = tag, name = tag, htmlUrl = "", body = "", publishedAt = "",
        prerelease = true, assets = emptyList(), commitCount = count,
    )

    private val assets = listOf(
        asset("Xmd-lite-arm64-v8a-v1.1.0.apk"),
        asset("Xmd-lite-armeabi-v7a-v1.1.0.apk"),
        asset("Xmd-full-arm64-v8a-v1.1.0.apk"),
        asset("Xmd-full-armeabi-v7a-v1.1.0.apk"),
        asset("checksums.txt"),
    )

    @Test
    fun selectsFlavorAndPrimaryAbi() {
        assertEquals(
            "Xmd-full-arm64-v8a-v1.1.0.apk",
            selectXmdApkAsset(assets, "full", listOf("arm64-v8a", "armeabi-v7a"))?.name,
        )
        assertEquals(
            "Xmd-lite-armeabi-v7a-v1.1.0.apk",
            selectXmdApkAsset(assets, "lite", listOf("armeabi-v7a"))?.name,
        )
    }

    @Test
    fun unknownAbiFallsBackToArm64() {
        assertEquals(
            "Xmd-lite-arm64-v8a-v1.1.0.apk",
            selectXmdApkAsset(assets, "lite", listOf("x86_64", "x86"))?.name,
        )
    }

    @Test
    fun fallsBackToOtherSupportedAbi() {
        val onlyV7 = listOf(asset("Xmd-lite-armeabi-v7a-v1.1.0.apk"))
        assertEquals(
            "Xmd-lite-armeabi-v7a-v1.1.0.apk",
            selectXmdApkAsset(onlyV7, "lite", listOf("arm64-v8a"))?.name,
        )
    }

    @Test
    fun noAssetForOtherFlavorOrNonApk() {
        assertNull(selectXmdApkAsset(listOf(asset("Xmd-lite-arm64-v8a-v1.0.0.apk")), "full", listOf("arm64-v8a")))
        assertNull(selectXmdApkAsset(listOf(asset("checksums.txt")), "lite", listOf("arm64-v8a")))
    }

    @Test
    fun previewManifestAssetNamesAreSelectable() {
        val preview = listOf(
            asset("Xmd-lite-arm64-v8a-preview-r120.apk"),
            asset("Xmd-lite-armeabi-v7a-preview-r120.apk"),
        )
        assertEquals(
            "Xmd-lite-arm64-v8a-preview-r120.apk",
            selectXmdApkAsset(preview, "lite", listOf("arm64-v8a"))?.name,
        )
    }

    @Test
    fun previewRunReleasePointsAtPagesApks() {
        val release = previewReleaseFromRun(
            runNumber = 7,
            headSha = "abcdef1234567",
            updatedAt = "2026-09-28T10:00:00Z",
            runUrl = "https://github.com/x/y/actions/runs/1",
            siteBase = "https://user.github.io/xmd/",
        )!!
        assertEquals("preview-r7", release.tagName)
        assertEquals(7, release.previewBuildNumber())
        assertEquals(4, release.assets.size)
        val picked = selectXmdApkAsset(release.assets, "full", listOf("arm64-v8a"))!!
        assertEquals("Xmd-full-arm64-v8a-preview-r7.apk", picked.name)
        assertEquals("https://user.github.io/xmd/previews/r7/Xmd-full-arm64-v8a-preview-r7.apk", picked.downloadUrl)
        // Newer than an older preview build, not newer than itself; any preview beats a non-preview build (0).
        assertTrue(isPreviewReleaseNewer(release, currentGitCount = 6, currentVersion = "1.0.0-beta.r6"))
        assertFalse(isPreviewReleaseNewer(release, currentGitCount = 7, currentVersion = "1.0.0-beta.r7"))
        assertTrue(isPreviewReleaseNewer(release, currentGitCount = 0, currentVersion = "1.0.0"))
    }

    @Test
    fun previewRunWithoutNumberIsIgnored() {
        assertNull(previewReleaseFromRun(0, "", "", "", "https://example.com"))
    }

    @Test
    fun stableVersionComparisonIgnoresFlavorSuffix() {
        assertTrue(isVersionNewer("v1.1.0", "1.0.0-lite"))
        assertTrue(isVersionNewer("v1.0.1", "1.0.0-full-beta.r55"))
        assertFalse(isVersionNewer("v1.0.0", "1.0.0-lite"))
        assertFalse(isVersionNewer("v1.0.0", "1.0.0-full"))
        assertFalse(isVersionNewer("v0.9.9", "1.0.0"))
        assertFalse(isVersionNewer("nightly", "1.0.0"))
    }

    @Test
    fun preReleaseIsOlderThanItsOwnStable() {
        // The reported bug: installed stable 1.0.0 must not be offered 1.0.0-beta.6.
        assertFalse(isPreviewNewer("v1.0.0-beta.6", "1.0.0-lite"))
        assertFalse(isPreviewNewer("v1.0.0-beta.6", "1.0.0-full"))
        assertFalse(isPreviewNewer("v1.0.0-beta.6", "1.0.0"))
        assertFalse(isPreviewNewer("v1.0.0-rc.1", "1.0.0"))
        // ...while the stable release does replace an installed beta of it.
        assertTrue(isVersionNewer("v1.0.0", "1.0.0-lite-beta.r324"))
        assertTrue(isVersionNewer("v1.0.0", "1.0.0-beta.6"))
    }

    @Test
    fun handCutPrereleaseTagsCompareByStageAndNumber() {
        assertTrue(isPreviewNewer("v1.0.0-beta.5", "1.0.0-beta.4"))
        assertFalse(isPreviewNewer("v1.0.0-beta.4", "1.0.0-beta.4"))
        assertTrue(isPreviewNewer("v1.0.0-rc.1", "1.0.0-beta.6"))
        assertFalse(isPreviewNewer("v1.0.0-beta.6", "1.0.0-rc.1"))
        assertTrue(isPreviewNewer("v1.0.0-beta.6", "1.0.0-lite-beta.r3"))
        assertFalse(isPreviewNewer("v1.0.0-beta.6", "1.0.0-lite-beta.r324"))
        // Higher base version is a real update even as a beta.
        assertTrue(isPreviewNewer("v1.1.0-beta.1", "1.0.0-lite"))
    }

    @Test
    fun manifestReleaseComparesCommitCount() {
        assertTrue(isPreviewReleaseNewer(release("preview-r121", 121), currentGitCount = 120, currentVersion = "1.0.0"))
        assertFalse(isPreviewReleaseNewer(release("preview-r120", 120), currentGitCount = 120, currentVersion = "1.0.0"))
        // No commit_count in JSON: count is parsed from the manifest tag.
        assertEquals(121, release("preview-r121").previewBuildNumber())
        assertTrue(isPreviewReleaseNewer(release("preview-r121"), currentGitCount = 5, currentVersion = "1.0.0"))
    }

    @Test
    fun handCutReleaseFallsBackToTagComparison() {
        assertNull(release("v1.0.0-beta.2").previewBuildNumber())
        assertTrue(isPreviewReleaseNewer(release("v1.0.0-beta.2"), currentGitCount = 999, currentVersion = "1.0.0-lite-beta.1"))
        assertFalse(isPreviewReleaseNewer(release("v1.0.0-beta.6"), currentGitCount = 999, currentVersion = "1.0.0-lite"))
    }
}
