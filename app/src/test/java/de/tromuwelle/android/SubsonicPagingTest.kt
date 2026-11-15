package de.tromuwelle.android

import org.junit.Assert.*
import org.junit.Test

class SubsonicPagingTest {
    @Test fun onlyAlbumListAndSearchResultsCanBePaged() {
        assertTrue(canPageSubsonic("albums", "", "", ""))
        assertTrue(canPageSubsonic("albums", "", "", "bach"))
        assertFalse(canPageSubsonic("albums", "artist-12", "", ""))
        assertTrue(canPageSubsonic("songs", "", "", "jazz"))
        assertFalse(canPageSubsonic("songs", "", "", ""))
        assertFalse(canPageSubsonic("songs", "", "album-4", "jazz"))
    }

    @Test fun nonPageableResourcesNeverAppendDuplicates() {
        for (type in listOf("artists", "playlists", "playlistTracks", "directory", "")) {
            assertFalse(canPageSubsonic(type, "", "", "query"))
        }
        assertEquals(100, SUBSONIC_PAGE_SIZE)
    }
}
