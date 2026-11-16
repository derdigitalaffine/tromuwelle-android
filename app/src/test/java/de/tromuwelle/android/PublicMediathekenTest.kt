package de.tromuwelle.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import androidx.media3.common.MimeTypes

class PublicMediathekenTest {
    @Test fun onlyHttpsAndNoEmbeddedPasswordsArePlayable() {
        assertTrue(secureMediathekVideoUrl("https://media.example.org/video/playlist.m3u8"))
        assertFalse(secureMediathekVideoUrl("http://media.example.org/clear.mp4"))
        assertFalse(secureMediathekVideoUrl("javascript:alert(1)"))
        assertFalse(secureMediathekVideoUrl("https://user:password@media.example.org/stream.mp4"))
        assertFalse(secureMediathekVideoUrl("https://media.example.org/stream.mp4#fragment"))
    }

    @Test fun hdThenStandardThenLowFallbackAndHlsMime() {
        val video = JSONObject().put("id", "123").put("title", "Dokumentation")
            .put("channel", "ARD")
            .put("url_video_hd", "http://insecure.example/hd.m3u8")
            .put("url_video", "https://media.example/standard.m3u8")
            .put("url_video_low", "https://media.example/low.mp4")
        assertEquals("https://media.example/standard.m3u8", publicMediathekenUrl(video))
        val item = publicMediathekMediaItem(video)!!
        assertEquals("MEDIATHEK:123", item.mediaId)
        assertEquals(MimeTypes.APPLICATION_M3U8, item.localConfiguration?.mimeType)
        assertEquals("Dokumentation", item.mediaMetadata.title.toString())
        assertNull(publicMediathekMediaItem(JSONObject().put("url_video", "http://only-insecure.example/movie.mp4")))
    }

    @Test fun externalWebsiteRequiresStandardUrl() {
        assertTrue(safePublicWebsite("https://www.ardmediathek.de/"))
        assertFalse(safePublicWebsite("file:///etc/hosts"))
        assertFalse(safePublicWebsite("javascript:alert(1)"))
        assertFalse(safePublicWebsite("https://username:password@example.org"))
    }
}
