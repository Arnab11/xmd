package com.invictus.xmd.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkParserBracketUrlTest {

    private val url = "https://quick.example.site/abc::def/" +
        "Police.Story.3.Supercop.1992.Dual.Audio.Hindi.[MkvMoviesPoint].1080p.BluRay.mkv.zip?bytes=2066167823"

    @Test
    fun bracketedUrlIsGenericDownload() {
        assertTrue(LinkParser.isGenericDownloadUrl(url))
        assertTrue(LinkParser.isSupportedDirectInput(url))
        assertFalse(LinkParser.needsYtDlp(url))
        assertTrue(LinkParser.hasKnownDownloadExtension(url.substringBefore('?')))
    }

    @Test
    fun lenientUriKeepsHostAndDecodedPath() {
        val uri = UrlUtils.lenientUri(url)
        assertNotNull(uri)
        assertEquals("quick.example.site", uri!!.host)
        assertTrue(uri.path.contains("[MkvMoviesPoint]"))
    }

    @Test
    fun spacesAndBadPercentAreEscaped() {
        assertNotNull(UrlUtils.lenientUri("https://x.com/a b/c%zz.zip"))
    }

    @Test
    fun garbageStillInvalid() {
        assertFalse(LinkParser.isGenericDownloadUrl("uu"))
    }
}
