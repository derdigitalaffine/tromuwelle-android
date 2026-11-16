package de.tromuwelle.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AirwavesTest {
    @Test fun airwavesStreamsGetDistinctRadioNamespace() {
        val item = JSONObject().put("id", 42).put("name", "Airport approach")
            .put("kind", "atc").put("city", "Boston").put("state", "MA")
        val entry = airwavesRadioEntry(item)
        assertEquals("airwaves:42", entry.id)
        assertEquals(EntryKind.RADIO, entry.kind)
        assertTrue(entry.description.contains("US-Flugfunk"))
        assertTrue(entry.description.contains("Boston"))
    }

    @Test fun fireStreamsRemainSeparateFromGeneralRadio() {
        val item = JSONObject().put("id", 9).put("name", "County dispatch").put("kind", "fire")
        val entry = airwavesRadioEntry(item)
        assertTrue(entry.description.contains("US-Feuerwehrfunk"))
        assertEquals("airwaves:9", entry.id)
    }
}
