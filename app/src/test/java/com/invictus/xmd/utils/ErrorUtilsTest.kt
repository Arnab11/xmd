package com.invictus.xmd.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorUtilsTest {

    @Test
    fun testCleanErrorTextWithAnsiSequences() {
        val raw = "\u001B[0;33mWARNING:\u001B[0m [youtube] JavaScript player config missing or invalid"
        val cleaned = ErrorUtils.cleanErrorText(raw)
        assertEquals("WARNING: [youtube] JavaScript player config missing or invalid", cleaned)
    }

    @Test
    fun testCleanErrorTextWithNullOrBlank() {
        assertEquals("No error or warning details available.", ErrorUtils.cleanErrorText(null))
        assertEquals("No error or warning details available.", ErrorUtils.cleanErrorText(""))
        assertEquals("No error or warning details available.", ErrorUtils.cleanErrorText("   "))
    }

    @Test
    fun testIsWarningText() {
        assertTrue(ErrorUtils.isWarningText("WARNING: [youtube] video is age-restricted"))
        assertTrue(ErrorUtils.isWarningText("warning: format may be missing"))
        assertTrue(ErrorUtils.isWarningText("\u001B[0;33mWARNING:\u001B[0m something"))
        assertFalse(ErrorUtils.isWarningText("ERROR: [youtube] Video unavailable"))
        assertFalse(ErrorUtils.isWarningText("Download timed out"))
        assertFalse(ErrorUtils.isWarningText(null))
        assertFalse(ErrorUtils.isWarningText(""))
    }
}
