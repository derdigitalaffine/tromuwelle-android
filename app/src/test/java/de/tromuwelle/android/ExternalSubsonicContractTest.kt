package de.tromuwelle.android

import org.junit.Assert.*
import org.junit.Test

class ExternalSubsonicContractTest {
    @Test fun httpsOriginCanUseAContextPath() {
        assertEquals("https://music.example.org/subsonic",
            subsonicOrigin("https://music.example.org/subsonic/rest/"))
        assertEquals("https://music.example.org:4533",
            subsonicOrigin("https://music.example.org:4533"))
    }
    @Test fun insecureOrEmbeddedCredentialAddressesFail() {
        for (value in listOf("http://music.example.org", "https://bob:secret@music.example.org",
            "https://music.example.org?password=secret", "https://music.example.org/#secret",
            "https://music.example.org/\\something")) {
            try {
                subsonicOrigin(value)
                fail("Unzulässige URL angenommen: " + value.take(20))
            } catch (_: IllegalArgumentException) {
                // Credentials and unencrypted servers must not be accepted.
            }
        }
    }
    @Test fun lrcMillisecondsAndMultipleTimeMarkers() {
        val rows = parseSyncedLyrics(
            "[00:03.20]Eins\n[01:00.125][01:02.5]Zwei\n[ar:Artist]\n[01:04]Drei"
        )
        assertEquals(4, rows.size)
        assertEquals(3200L, rows[0].timeMs)
        assertEquals(60125L, rows[1].timeMs)
        assertEquals(62500L, rows[2].timeMs)
        assertEquals(64000L, rows[3].timeMs)
        assertEquals("Zwei", rows[1].text)
    }
    @Test fun lrcPlainTextHasNoTimestampedLines() {
        assertTrue(parseSyncedLyrics("Only plain lyrics\nNo timestamps").isEmpty())
    }
}
