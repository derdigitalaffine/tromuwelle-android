package de.tromuwelle.android

import org.junit.Assert.*
import org.junit.Test

class CastStreamLinkTest {
    private val token = "AbcdefghijkLMNOP0123456789_-abcdefghi"
    private val path = "/api/cast/stream/$token"
    private val https = "https://stream.example.org$path"

    @Test fun publicHttpsAndPrivateLanAreAllowed() {
        assertEquals(https, resolveCastStreamUrl(path, https, ""))
        assertEquals("http://192.168.1.44:8098$path",
            resolveCastStreamUrl(path, https, "http://192.168.1.44:8098$path"))
        assertEquals("http://10.0.0.12:8098$path",
            resolveCastStreamUrl(path, https, "http://10.0.0.12:8098$path"))
    }

    @Test fun disallowWrongTokenHostPortOrCleartextPublic() {
        val invalid = listOf(
            "http://8.8.8.8:8098$path",
            "http://192.168.1.44:8099$path",
            "http://192.168.1.44:8098/api/cast/stream/other",
            "http://256.168.1.4:8098$path",
            "http://my-nas.local:8098$path",
            "http://192.168.1.44:8098$path?password=secret",
            "http://192.168.1.44:8098$path#fragment",
            "http://user:password@192.168.1.44:8098$path"
        )
        for (link in invalid) {
            try {
                resolveCastStreamUrl(path, https, link)
                fail("Should reject: $link")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun requireGrantOnlyHttpsAndNoAdditionalQuery() {
        for (candidate in listOf("https://stream.example.org/", "http://stream.example.org$path",
            "https://user:pass@stream.example.org$path",
            "https://stream.example.org$path?x=1",
            "https://stream.example.org/api/music/all")) {
            try {
                resolveCastStreamUrl(path, candidate, "")
                fail("Must reject invalid secure cast URL")
            } catch (_: IllegalArgumentException) {}
        }
        try {
            resolveCastStreamUrl("/api/music/stream/1", https, "")
            fail("Must reject non-cast API")
        } catch (_: IllegalArgumentException) {}
    }
}
