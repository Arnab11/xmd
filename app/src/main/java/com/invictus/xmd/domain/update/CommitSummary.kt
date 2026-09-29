package com.invictus.xmd.domain.update

/**
 * Turns raw commit messages into the short, user-facing "What's new" text of the
 * Preview update sheet: grouped into New / Fixes / Improvements, one cleaned line
 * per change (no shas, no conventional-commit prefixes), housekeeping commits
 * (chore/ci/docs/merge...) dropped. Returns null when nothing user-facing is left,
 * so the caller can fall back to the release body.
 *
 * Output shape (the sheet renders unbulleted lines as headings):
 *
 *     New
 *     • Save to custom folder
 *
 *     Fixes
 *     • Crash when pausing a YouTube download
 */
internal fun summarizeCommitMessages(messages: List<String>, perSectionLimit: Int = 6): String? {
    val sections = linkedMapOf(
        "New" to mutableListOf<String>(),
        "Fixes" to mutableListOf<String>(),
        "Improvements" to mutableListOf<String>(),
    )
    val seen = mutableSetOf<String>()

    for (message in messages) {
        val subject = message.lineSequence().firstOrNull()?.trim().orEmpty()
        if (subject.isEmpty() || subject.startsWith("Merge ", ignoreCase = true)) continue

        val conventional = ConventionalRegex.matchEntire(subject)
        val type = conventional?.groupValues?.get(1)?.lowercase()
        if (type != null && type in SkippedTypes) continue
        val text = cleanCommitText(conventional?.groupValues?.get(2) ?: subject)
        if (text.isEmpty() || NoiseRegex.containsMatchIn(text)) continue
        if (!seen.add(text.lowercase())) continue

        val section = when {
            type != null -> when (type) {
                "feat", "feature", "add" -> "New"
                "fix", "bugfix", "hotfix" -> "Fixes"
                else -> "Improvements"
            }
            else -> when (text.substringBefore(' ').lowercase()) {
                "add", "adds", "added", "new", "implement", "implemented", "introduce", "support" -> "New"
                "fix", "fixes", "fixed", "resolve", "resolved", "repair", "patch" -> "Fixes"
                else -> "Improvements"
            }
        }
        sections.getValue(section).add(text)
    }

    val blocks = sections.mapNotNull { (title, items) ->
        if (items.isEmpty()) return@mapNotNull null
        buildString {
            append(title)
            items.take(perSectionLimit).forEach { append("\n• ").append(it) }
            if (items.size > perSectionLimit) append("\n• +${items.size - perSectionLimit} more")
        }
    }
    return blocks.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}

private val ConventionalRegex = Regex("""^(\w+)(?:\([^)]*\))?!?:\s*(.+)$""")
private val SkippedTypes = setOf(
    "chore", "ci", "docs", "doc", "test", "tests", "build", "deps", "style", "revert", "wip", "release", "bump",
)
private val NoiseRegex = Regex("""(?i)^(bump|merge|wip)\b|\b(readme|changelog)\b""")
private val PullRequestSuffix = Regex("""\s*\(#\d+\)$""")

private fun cleanCommitText(raw: String): String {
    var text = raw.replace(PullRequestSuffix, "").replace(Regex("\\s+"), " ").trim().trimEnd('.')
    if (text.isEmpty()) return ""
    text = text.replaceFirstChar { it.uppercase() }
    return if (text.length > 90) text.take(89).trimEnd() + "…" else text
}
