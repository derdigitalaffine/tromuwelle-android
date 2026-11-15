package de.tromuwelle.android

import java.util.Locale

/**
 * Deterministic local ordering of search results received from Tromuwelle.
 * No analytics, external recommendations or user-history profiling.
 * Never call this on the private adult library from global search.
 */
fun searchRelevance(title: String, subtitle: String, term: String): Int {
    val query = term.trim().lowercase(Locale.ROOT)
    if (query.isBlank()) return 0
    val normalizedTitle = title.trim().lowercase(Locale.ROOT)
    val details = subtitle.lowercase(Locale.ROOT)
    val tokens = query.split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        normalizedTitle == query -> 100
        normalizedTitle.startsWith(query) -> 84
        normalizedTitle.split(Regex("[^\\p{L}\\p{N}]+")).any { it.startsWith(query) } -> 72
        normalizedTitle.contains(query) -> 60
        tokens.isNotEmpty() && tokens.all { normalizedTitle.contains(it) } -> 48
        details.contains(query) -> 28
        tokens.isNotEmpty() && tokens.all { normalizedTitle.contains(it) || details.contains(it) } -> 16
        else -> 0
    }
}

fun rankSearchResults(entries: List<LibraryEntry>, query: String): List<LibraryEntry> =
    entries.distinctBy { it.kind to it.id }.sortedWith(
        compareByDescending<LibraryEntry> {
            searchRelevance(it.title, it.description, query)
        }.thenBy { it.title.lowercase(Locale.ROOT) }
    )

/** Keep only useful, user-entered recent searches, scoped per server/account. */
fun searchHistoryAfter(history: List<String>, query: String, limit: Int = 8): List<String> {
    val term = query.trim().take(120)
    if (term.length < 2) return history.take(limit)
    return (listOf(term) + history.filterNot { it.equals(term, ignoreCase = true) })
        .take(limit)
}
