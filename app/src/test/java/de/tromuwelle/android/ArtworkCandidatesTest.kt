package de.tromuwelle.android

import org.junit.Assert.*
import org.junit.Test

class ArtworkCandidatesTest {
    private val encode: (String) -> String = { it.replace("/", "%2F").replace(" ", "+") }

    @Test fun moviesTryRealArtworkThenVideoFrame() {
        val video = LibraryEntry("films/movie one.mkv", "Movie", kind = EntryKind.FILE,
            path = "films/movie one.mkv", artwork = "/api/media/thumbnail?path=films")
        assertEquals(listOf(
            "/api/media/artwork?kind=poster&path=films%2Fmovie+one.mkv",
            "/api/media/thumbnail?path=films%2Fmovie+one.mkv"),
            artworkCandidates(video, encode))
    }
    @Test fun foldersUseAuthorizedFolderPreview() {
        val folder = LibraryEntry("films", "Films", kind = EntryKind.FOLDER, path = "films")
        assertEquals(listOf("/api/media/folder-thumbnail?path=films"),
            artworkCandidates(folder, encode))
    }
    @Test fun musicAndPodcastsPreserveProvidedCovers() {
        val item = LibraryEntry("one", "Song", kind = EntryKind.TRACK,
            artwork = "https://cdn.example.org/cover.jpg")
        assertEquals(listOf(item.artwork), artworkCandidates(item, encode))
        assertEquals(emptyList<String>(), artworkCandidates(
            LibraryEntry("live", "Live", kind = EntryKind.TV), encode))
    }
}
