package de.tromuwelle.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackendContractTest {
    @Test fun channelsAndRadioSchemasFromMain() {
        val channels = JSONArray("""[{"id":"p1","name":"Das Erste HD","hd":true,"active":false,"playlistId":"builtin"}]""")
        assertEquals("p1", channels.objects().single().getString("id"))
        val radio = JSONObject("""{"stations":[{"id":"s1","name":"Radio Test","url":"https://example.org/stream","genre":"Pop","logo":""}]}""")
        assertEquals("Pop", radio.getJSONArray("stations").objects().single().getString("genre"))
    }

    @Test fun musicUsesItemsAndTotalRatherThanTracks() {
        val music = JSONObject("""{"total":1,"items":[{"id":"abc","title":"Titel","artist":"Artist","album":"Album","path":"musik/titel.flac","duration":230}]}""")
        assertEquals(1, music.getInt("total"))
        assertEquals("abc", music.getJSONArray("items").objects().single().getString("id"))
        assertNull(music.optJSONArray("tracks"))
    }

    @Test fun mediaCategoryAndSidecarMetadata() {
        val adult = JSONObject("""{"total":1,"items":[{"path":"adult/film.mkv","name":"film.mkv","title":"Film","height":2160,"year":2020}]}""")
        assertEquals(2160, adult.getJSONArray("items").objects().single().getInt("height"))
        val tags = listOf("Doku", "4K", "Doku").distinct().toJsonArray()
        assertEquals(2, tags.length())
        val subtitle = JSONObject("""{"subtitles":[{"path":"adult/film.de.srt","language":"Deutsch","code":"de","format":"srt"}]}""")
        assertEquals("de", subtitle.getJSONArray("subtitles").getJSONObject(0).getString("code"))
    }

    @Test fun roleDeterminesManagementAccess() {
        assertTrue(Account(username="admin", role="admin", managed=true).isAdmin)
        assertFalse(Account(username="viewer", role="viewer", managed=true).isAdmin)
    }
}
