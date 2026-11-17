package de.tromuwelle.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KaraokeCoreTest {
    @Test fun preparedGalleryKeepsVersionsAndProfiles() {
        val data = JSONObject("""
            {"total":1,"items":[{"id":"s1","title":"Tromu","artist":"Band",
            "album":"Live","duration":183,
            "ready":["2","6"],"versions":[
              {"id":"a2","profile":"2","pinned":false},
              {"id":"a6","profile":"6","pinned":true}]}]}
        """.trimIndent())
        val item = karaokeSongs(data).single()
        assertEquals("s1", item.id)
        assertEquals(listOf("2", "6"), item.ready)
        assertEquals(2, item.versions.size)
        assertTrue(item.versions.last().pinned)
    }

    @Test fun shortBreakDoesNotHideLineTooEarly() {
        val lines = listOf(KaraokeLine("Hallo", 2.0, 4.0),
            KaraokeLine("Welt", 8.0, 9.5))
        assertEquals("Hallo", karaokeFrame(lines, 7.5).current?.text)
        assertEquals("Welt", karaokeFrame(lines, 8.0).current?.text)
    }

    @Test fun longInstrumentalGapHasCountdownAndLastLineOutro() {
        val lines = listOf(KaraokeLine("A", 1.0, 3.0),
            KaraokeLine("B", 22.0, 26.0))
        val pause = karaokeFrame(lines, 12.0)
        assertNull(pause.current)
        assertEquals(10, pause.countdown)
        assertEquals("A", pause.previous?.text)
        assertEquals(true, karaokeFrame(lines, 39.0).outro)
    }

    @Test fun storedWordsPreserveTimestampOrder() {
        val data = JSONObject("""
            {"payload":{"lines":[{"text":"Hallo Welt","start":1,"end":4,
            "words":[{"text":"Hallo","start":1,"end":2},
            {"text":"Welt","start":2.2,"end":3.6}]}]}}
        """.trimIndent())
        val words = karaokeLyrics(data).single().words
        assertEquals(2, words.size)
        assertEquals("Welt", words.last().text)
        assertEquals(2.2, words.last().start, 0.01)
    }

    @Test fun lyricsFallbackUsesLrcMarkersWithoutBlankText() {
        val data = JSONObject("""{"source":{"synced":"[00:01.00]Erster Satz\\n[00:07.30]Zweiter Satz"}}""")
        val lines = karaokeLyrics(data)
        assertEquals(2, lines.size)
        assertEquals(7.3, lines.last().start, 0.01)
        assertTrue(lines.first().end < lines.last().start)
    }

    @Test fun missingLyricsAndEmptyCatalogAreSafe() {
        assertTrue(karaokeLyrics(JSONObject("""{"payload":null,"source":{}}""")).isEmpty())
        assertTrue(karaokeSongs(JSONObject("""{"items":[]}""")).isEmpty())
    }
}
