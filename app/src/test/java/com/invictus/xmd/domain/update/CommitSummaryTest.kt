package com.invictus.xmd.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommitSummaryTest {

    @Test
    fun groupsConventionalCommitsAndDropsHousekeeping() {
        val summary = summarizeCommitMessages(
            listOf(
                "feat(ui): show downloaded size for YouTube rows",
                "fix: back press froze the update sheet (#12)",
                "chore: bump gradle",
                "ci: tweak workflow",
                "refactor: simplify queue repository.",
                "Merge branch 'main' into dev",
            ),
        )
        assertEquals(
            "New\n• Show downloaded size for YouTube rows\n\n" +
                "Fixes\n• Back press froze the update sheet\n\n" +
                "Improvements\n• Simplify queue repository",
            summary,
        )
    }

    @Test
    fun guessesSectionFromFirstWordWhenNoPrefix() {
        val summary = summarizeCommitMessages(listOf("Add SponsorBlock chips in one row", "Fixed ETA format"))
        assertEquals("New\n• Add SponsorBlock chips in one row\n\nFixes\n• Fixed ETA format", summary)
    }

    @Test
    fun dedupesAndCapsEachSection() {
        val messages = (1..8).map { "feat: thing $it" } + "feat: Thing 1"
        val summary = summarizeCommitMessages(messages, perSectionLimit = 3)!!
        assertEquals("New\n• Thing 1\n• Thing 2\n• Thing 3\n• +5 more", summary)
    }

    @Test
    fun returnsNullWhenNothingUserFacing() {
        assertNull(summarizeCommitMessages(listOf("chore: bump", "docs: update readme", "Merge pull request #3")))
        assertNull(summarizeCommitMessages(emptyList()))
    }
}
