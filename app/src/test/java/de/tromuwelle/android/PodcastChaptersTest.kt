package de.tromuwelle.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PodcastChaptersTest {
    @Test fun chaptersSortAndRejectInvalidTimes() {
        val payload = JSONObject().put("items", JSONArray()
            .put(JSONObject().put("start", 60.2).put("title", "Kapitel 2"))
            .put(JSONObject().put("start", 0).put("title", "Start"))
            .put(JSONObject().put("start", -5).put("title", "Ungültig"))
            .put(JSONObject().put("start", 60.2).put("title", "Doppelt"))
            .put(JSONObject().put("start", 120).put("title", "Kapitel 3")))
        val chapters = podcastChapters(payload)
        assertEquals(3, chapters.size)
        assertEquals(0L, chapters[0].startMs)
        assertEquals(60_200L, chapters[1].startMs)
        assertEquals("Kapitel 2", chapters[1].title)
        assertEquals(-1, chapterIndexAt(chapters, -1))
        assertEquals(0, chapterIndexAt(chapters, 59_999))
        assertEquals(1, chapterIndexAt(chapters, 60_200))
        assertEquals(2, chapterIndexAt(chapters, 250_000))
    }

    @Test fun emptyMissingAndOutOfRangeChaptersAreSafe() {
        assertTrue(podcastChapters(JSONObject()).isEmpty())
        assertTrue(podcastChapters(JSONObject().put("items", JSONArray()
            .put(JSONObject().put("start", 1000001.0)))).isEmpty())
    }
}
