package com.invictus.xmd.domain.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowDetectorTest {

    @Test fun seasonEpisode() {
        assertTrue(ShowDetector.isEpisode("The.Boys.S03E05.1080p.WEB-DL.x264.mkv"))
        assertEquals("The Boys", ShowDetector.showFolderName("The.Boys.S03E05.1080p.WEB-DL.x264.mkv"))
    }

    @Test fun crossAndWordEpisode() {
        assertEquals("Breaking Bad", ShowDetector.showFolderName("Breaking Bad 1x02.mp4"))
        assertEquals("Show Name", ShowDetector.showFolderName("Show Name Episode 12.mp4"))
        assertEquals("Naruto", ShowDetector.showFolderName("Naruto Ep.045 English Sub.mp4"))
    }

    @Test fun animeStyle() {
        assertTrue(ShowDetector.isEpisode("[SubsPlease] Frieren - 05 (1080p) [ABC].mkv"))
        assertEquals("Frieren", ShowDetector.showFolderName("[SubsPlease] Frieren - 05 (1080p) [ABC].mkv"))
    }

    @Test fun notEpisodes() {
        assertFalse(ShowDetector.isEpisode("Movie.2019.1080p.BluRay.x264.mkv"))
        assertFalse(ShowDetector.isEpisode("holiday 1920x1080.mp4"))
        assertFalse(ShowDetector.isEpisode("Movie - 1080.mkv"))
        assertFalse(ShowDetector.isEpisode("report ep 5.pdf"))
    }

    @Test fun fallbackAndUnsorted() {
        assertEquals("Unsorted", ShowDetector.showFolderName("Ep 5.mp4"))
        assertEquals("Some Show", ShowDetector.showFolderName("Ep 5.mp4", titleHint = "Some Show Episode 5"))
        assertEquals("Some Show", ShowDetector.showFolderName("Ep 5.mp4", sourceUrl = "https://cdn.example.com/Some%20Show/Ep%205.mp4"))
    }

    @Test fun seasonPackTorrent() {
        assertTrue(ShowDetector.isEpisodeOrSeasonPack("Show.Name.S01.1080p.BluRay"))
        assertEquals("Show Name", ShowDetector.showFolderName("Show.Name.S01.1080p.BluRay"))
        assertFalse(ShowDetector.isEpisodeOrSeasonPack("Some.Movie.2020.1080p"))
    }
}
