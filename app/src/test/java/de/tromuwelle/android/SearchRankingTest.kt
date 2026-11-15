package de.tromuwelle.android

import org.junit.Assert.*
import org.junit.Test

class SearchRankingTest {
    @Test fun exactTitleAndWordStartBeatSubtitle() {
        assertTrue(searchRelevance("Radio Paloma", "", "radio paloma") >
            searchRelevance("Radio Paloma Hits", "", "radio paloma"))
        assertTrue(searchRelevance("WDR 2", "", "WDR") >
            searchRelevance("Sommermusik", "WDR Sammlung", "WDR"))
    }
    @Test fun sortsWithoutInventingResults() {
        val items = listOf(
            LibraryEntry("2", "Meine Playlist", "Jazz", EntryKind.ALBUM),
            LibraryEntry("1", "Jazz", "", EntryKind.ALBUM),
            LibraryEntry("1", "Jazz", "", EntryKind.ALBUM),
            LibraryEntry("3", "Jazz Classics", "", EntryKind.ALBUM))
        assertEquals(listOf("1", "3", "2"),
            rankSearchResults(items, "jazz").map { it.id })
    }
    @Test fun historyDeduplicatesAndKeepsLastEight() {
        assertEquals(listOf("Pop", "Jazz", "Rock"),
            searchHistoryAfter(listOf("Jazz", "Rock", "pop"), "Pop"))
        assertEquals(listOf("Jazz"), searchHistoryAfter(listOf("Jazz"), " "))
        assertEquals(8, searchHistoryAfter((1..12).map { "query$it" }, "Fresh").size)
    }
}
