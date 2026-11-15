package de.tromuwelle.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Explicit 18+ searches stay inside the protected library, never in this UI. */
internal enum class SearchScope(val label: String) {
    ALL("Alles"), MUSIC("Musik"), MEDIA("Filme"), PODCAST("Podcasts"),
    RADIO("Radio"), TV("Live-TV")
}

/** Keep the protected private library out of global search, even with ALL selected. */
internal fun sectionsForSearchScope(scope: SearchScope): List<LibrarySection> = when (scope) {
    SearchScope.ALL -> listOf(LibrarySection.MUSIC, LibrarySection.MEDIA,
        LibrarySection.RADIO, LibrarySection.TV)
    SearchScope.MUSIC -> listOf(LibrarySection.MUSIC)
    SearchScope.MEDIA -> listOf(LibrarySection.MEDIA)
    SearchScope.RADIO -> listOf(LibrarySection.RADIO)
    SearchScope.TV -> listOf(LibrarySection.TV)
    SearchScope.PODCAST -> emptyList()
}

@Composable
fun StreamGlobalSearch(
    session: TromuwelleSession,
    onBack: () -> Unit,
    onOpen: (LibrarySection, LibraryEntry) -> Unit,
    onSection: (LibrarySection) -> Unit,
    onPodcast: (JSONObject) -> Unit,
    onFavorite: (LibraryEntry) -> Unit,
    isFavorite: (LibraryEntry) -> Boolean
) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("tromuwelle_search_history", 0)
    }
    val historyKey = session.server + ":" + session.username
    val history = remember(historyKey) {
        mutableStateListOf<String>().apply {
            addAll(preferences.getString(historyKey, "").orEmpty()
                .split("\n").filter { it.isNotBlank() }.take(8))
        }
    }
    var query by remember(historyKey) { mutableStateOf("") }
    var submitted by remember(historyKey) { mutableStateOf("") }
    var scope by remember(historyKey) { mutableStateOf(SearchScope.ALL) }
    var loading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<Map<LibrarySection, List<LibraryEntry>>>(emptyMap()) }
    var podcasts by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var failed by remember { mutableStateOf<List<String>>(emptyList()) }

    fun submit(value: String) {
        val next = value.trim().take(120)
        query = next
        submitted = if (next.length >= 2) next else ""
        if (next.length >= 2) {
            val nextHistory = searchHistoryAfter(history, next)
            history.clear()
            history.addAll(nextHistory)
            preferences.edit().putString(historyKey, nextHistory.joinToString("\n")).apply()
        }
    }

    // Like a modern streaming library: results follow typing automatically;
    // manual IME search commits a query to this account's local recent history.
    LaunchedEffect(query) {
        delay(380)
        val clean = query.trim()
        submitted = if (clean.length >= 2) clean else ""
    }

    LaunchedEffect(session.server, session.username, submitted, scope) {
        val term = submitted
        if (term.length < 2) {
            results = emptyMap()
            podcasts = emptyList()
            failed = emptyList()
            loading = false
            return@LaunchedEffect
        }
        // Do not display results from the preceding query or scope while new
        // requests are running. This also avoids short-lived wrong hit counts.
        results = emptyMap()
        podcasts = emptyList()
        failed = emptyList()
        loading = true
        try {
            val fetched = withContext(Dispatchers.IO) {
                coroutineScope {
                    val catalog = TromuwelleCatalog(session)
                    // A selected media filter must not trigger four unrelated
                    // API calls. This matters on slow home servers and mobile data.
                    val sections = sectionsForSearchScope(scope)
                    val requests = sections.map { section ->
                        async {
                            try {
                                Triple(section,
                                    rankSearchResults(
                                        catalog.load(section, search = term).items.take(30), term
                                    ).take(18), null as String?)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                val notAvailable = (e as? ServerException)?.status in listOf(403, 404)
                                Triple(section, emptyList<LibraryEntry>(),
                                    if (notAvailable) null else section.label)
                            }
                        }
                    }
                    val podcastRequest = if (scope == SearchScope.ALL ||
                        scope == SearchScope.PODCAST) async {
                        try {
                            val items = TromuPodcasts(session)
                                .episodes(search = term, limit = 24).first
                            val sorted = items.sortedWith(compareByDescending<JSONObject> {
                                searchRelevance(it.optString("title"),
                                    it.optString("feed_title"), term)
                            }.thenBy { it.optString("title") })
                            sorted to null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val unavailable = (e as? ServerException)?.status in listOf(403, 404)
                            emptyList<JSONObject>() to if (unavailable) null else "Podcasts"
                        }
                    } else null
                    val ordinary = requests.awaitAll()
                    val podcastResult = podcastRequest?.await() ?: (emptyList<JSONObject>() to null)
                    Triple(ordinary.associate { it.first to it.second },
                        podcastResult.first,
                        ordinary.mapNotNull { it.third } + listOfNotNull(podcastResult.second))
                }
            }
            results = fetched.first
            podcasts = fetched.second
            failed = fetched.third
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = listOf(e.message ?: "Suche konnte nicht geladen werden.")
            results = emptyMap()
            podcasts = emptyList()
        } finally {
            loading = false
        }
    }

    val shownSections = sectionsForSearchScope(scope)
    val showPodcasts = scope == SearchScope.ALL || scope == SearchScope.PODCAST
    val count = shownSections.sumOf { results[it]?.size ?: 0 } +
        (if (showPodcasts) podcasts.size else 0)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Zurück")
            }
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                Text("Entdecken & Suchen",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold)
                Text("Deine Inhalte. Ein Suchfeld.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(120) },
            placeholder = { Text("Künstler, Filme, Podcasts, Sender …") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (query.isNotBlank()) IconButton(onClick = {
                    query = ""; submitted = ""
                }) { Icon(Icons.Rounded.Close, contentDescription = "Suche löschen") }
                else Icon(Icons.Rounded.Tune, contentDescription = null)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit(query) }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(22.dp)
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            items(SearchScope.entries) { choice ->
                FilterChip(selected = scope == choice, onClick = { scope = choice },
                    label = { Text(choice.label) },
                    leadingIcon = if (scope == choice) {
                        { Icon(Icons.Rounded.Check, null, Modifier.size(17.dp)) }
                    } else null)
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (submitted.isBlank()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("Was möchtest du entdecken?",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                    Text("Musik, Mediathek, Radio, Fernsehen und Podcastfolgen werden " +
                        "direkt auf deinem Tromuwelle-Server durchsucht.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (history.isNotEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Zuletzt gesucht", modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold)
                            TextButton(onClick = {
                                history.clear()
                                preferences.edit().remove(historyKey).apply()
                            }) { Text("Verlauf löschen") }
                        }
                    }
                    items(history.toList()) { term ->
                        ListItem(
                            headlineContent = { Text(term) },
                            leadingContent = { Icon(Icons.Rounded.History, null) },
                            trailingContent = { Icon(Icons.Rounded.NorthWest, null) },
                            modifier = Modifier.fillMaxWidth().clickable { submit(term) })
                    }
                }
                item {
                    Text("Schnellzugriff",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(LibrarySection.MUSIC, LibrarySection.MEDIA,
                            LibrarySection.RADIO).forEach { section ->
                            AssistChip(onClick = { onSection(section) },
                                label = { Text(section.label) })
                        }
                    }
                }
                item {
                    Text("Die private 18+-Bibliothek wird hier nicht durchsucht.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (loading) "Treffer werden gesucht …"
                            else "$count Treffer für „$submitted“",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (loading) CircularProgressIndicator(Modifier.size(17.dp),
                            strokeWidth = 2.dp)
                    }
                }
                if (failed.isNotEmpty() && !loading) item {
                    Text("Nicht erreichbar: " + failed.distinct().joinToString(", "),
                        Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (count == 0 && !loading) item {
                    Column(Modifier.fillMaxWidth().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.SearchOff, null, Modifier.size(42.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Keine passenden Inhalte",
                            fontWeight = FontWeight.Bold)
                        Text("Versuche einen kürzeren Suchbegriff oder wähle „Alles“.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                shownSections.forEach { section ->
                    val entries = results[section].orEmpty()
                    if (entries.isNotEmpty()) {
                        item(key = "header:" + section.name) {
                            Row(Modifier.fillMaxWidth()
                                .padding(start = 18.dp, end = 12.dp, top = 15.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(section.label, modifier = Modifier.weight(1f),
                                    fontWeight = FontWeight.ExtraBold,
                                    style = MaterialTheme.typography.titleLarge)
                                TextButton(onClick = { onSection(section) }) {
                                    Text("Alle öffnen")
                                    Icon(Icons.Rounded.ChevronRight, null)
                                }
                            }
                        }
                        if (section == LibrarySection.MEDIA || section == LibrarySection.TV ||
                            section == LibrarySection.RADIO) {
                            item(key = "rail:" + section.name) {
                                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(entries, key = { it.id }) { entry ->
                                        StreamPosterCard(session, entry,
                                            onClick = { onOpen(section, entry) },
                                            modifier = Modifier.width(196.dp),
                                            wide = entry.isVideo())
                                    }
                                }
                            }
                        } else {
                            items(entries, key = { section.name + ":" + it.id }) { entry ->
                                StreamTrackRow(session, entry, favorite = isFavorite(entry),
                                    onOpen = { onOpen(section, entry) },
                                    onFavorite = { onFavorite(entry) }, onInfo = null)
                            }
                        }
                    }
                }
                if (showPodcasts && podcasts.isNotEmpty()) {
                    item(key = "podcast-header") {
                        Row(Modifier.fillMaxWidth()
                            .padding(start = 18.dp, end = 12.dp, top = 15.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Podcastfolgen", modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.ExtraBold,
                                style = MaterialTheme.typography.titleLarge)
                            Icon(Icons.Rounded.Headphones, null,
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    items(podcasts, key = { "podcast:" + it.optString("id") }) { episode ->
                        val entry = LibraryEntry(
                            id = "podcast:" + episode.optString("id"),
                            title = episode.optString("title"),
                            description = episode.optString("feed_title"),
                            kind = EntryKind.TRACK,
                            duration = episode.optLong("duration"),
                            artwork = episode.optString("image")
                                .ifBlank { episode.optString("feed_image") }
                                .takeIf { it.startsWith("https://") } ?: ""
                        )
                        Row(Modifier.fillMaxWidth().clickable { onPodcast(episode) }
                            .padding(start = 16.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            AuthenticatedArtwork(session, entry,
                                Modifier.size(56.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.title, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold)
                                Text(entry.description, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Rounded.PlayCircleOutline, null,
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
