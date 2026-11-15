package de.tromuwelle.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerAddressTest {
    @Test fun validTlsUrl() {
        assertEquals("https://example.org:8097", normalizeServerAddress(" https://example.org:8097/ "))
        assertEquals("https://192.0.2.1", normalizeServerAddress("https://192.0.2.1"))
    }

    @Test fun rejectsInsecureAndDangerousOrigins() {
        listOf("http://example.org", "ftp://example.org", "https://u:p@example.org",
            "https://example.org/api", "https://example.org?q=1", "https://example.org/#x",
            "not a url", "https://").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { normalizeServerAddress(value) }
        }
    }
}
