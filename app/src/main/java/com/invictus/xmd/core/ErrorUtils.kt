package com.invictus.xmd.core

object ErrorUtils {
    val ANSI_REGEX = Regex("""\u001B\[[0-?]*[ -/]*[@-~]""")

    fun cleanErrorText(error: String?): String {
        if (error.isNullOrBlank()) return "No error or warning details available."
        return error.replace(ANSI_REGEX, "").trim()
    }

    fun isWarningText(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val cleaned = text.replace(ANSI_REGEX, "").trimStart()
        return cleaned.startsWith("warning", ignoreCase = true)
    }
}
