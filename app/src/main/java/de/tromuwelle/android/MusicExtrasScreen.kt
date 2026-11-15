package de.tromuwelle.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import kotlinx.coroutines.isActive
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Personal music playlists are owned and permission-filtered by the Tromuwelle server.
 * Changes use the server's replace-items operation, preserving ordering and duplicates.
 */
@Composable
fun MusicPlaylistsScreen(session: TromuwelleSession, onBack: () -> Unit,
                         onPlay: (List<LibraryEntry>) -> Unit) {
    val api = remember(session) { TromuwelleApi(session) }
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selected by remember { mutableIntStateOf(0) }
    var selectedName by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<LibraryEntry>>(emptyList()) }
    var createName by remember { mutableStateOf("") }
    var renameName by remember { mutableStateOf("") }
    var addQuery by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<LibraryEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    fun song(row: JSONObject) = LibraryEntry(
        row.optString("id"), row.optString("title"), row.optString("artist") + " · " + row.optString("album"),
        EntryKind.TRACK, row.optString("path"), row.optLong("duration"),
        artwork = "/api/music/cover/" + session.query(row.optString("id"))
    )
    fun loadList() {
        scope.launch {
            try { playlists = withContext(Dispatchers.IO) { api.musicPlaylists().objects() } }
            catch (e: Exception) { message = e.message ?: "Playlists konnten nicht geladen werden." }
        }
    }
    fun loadDetail(id: Int) {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api.musicPlaylist(id) }
                tracks = (result.optJSONArray("items") ?: org.json.JSONArray()).objects().map(::song)
                selectedName = result.optString("name")
                renameName = selectedName
                message = ""
            } catch (e: Exception) { message = e.message ?: "Playlist konnte nicht geöffnet werden." }
        }
    }
    fun act(success: String, block: suspend () -> Unit, refreshDetail: Boolean = false) {
        scope.launch {
            busy = true
            try {
                block()
                message = success
                loadList()
                if (refreshDetail && selected != 0) loadDetail(selected)
            } catch (e: Exception) { message = e.message ?: "Aktion fehlgeschlagen." }
            finally { busy = false }
        }
    }
    fun saveOrder(newOrder: List<LibraryEntry>) {
        val id = selected
        val ids = newOrder.map { it.id }
        act("Playlist gespeichert.", { withContext(Dispatchers.IO) { api.saveMusicPlaylistItems(id, ids) } }, true)
    }
    LaunchedEffect(Unit) { loadList() }
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (selected == 0) "Meine Playlists" else selectedName,
                style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = {
                if (selected != 0) { selected = 0; tracks = emptyList(); results = emptyList() }
                else onBack()
            }) { Text(if (selected == 0) "Schließen" else "Zurück") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        if (selected == 0) {
            OutlinedTextField(createName, { createName = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Name einer neuen Playlist") })
            Button(enabled = !busy && createName.trim().isNotBlank(), onClick = {
                val name = createName.trim()
                act("Playlist erstellt.", {
                    withContext(Dispatchers.IO) { api.createMusicPlaylist(name) }
                })
                createName = ""
            }) { Text("Playlist erstellen") }
            LazyColumn {
                items(playlists, key = { it.optInt("id") }) { entry ->
                    ListItem(headlineContent = { Text(entry.optString("name")) },
                        supportingContent = { Text(entry.optInt("tracks").toString() + " Titel") },
                        trailingContent = {
                            TextButton(onClick = {
                                selected = entry.optInt("id")
                                loadDetail(selected)
                            }) { Text("Öffnen") }
                        })
                    HorizontalDivider()
                }
            }
        } else {
            OutlinedTextField(renameName, { renameName = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Playlistname") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && renameName.trim().isNotBlank(), onClick = {
                    val id = selected; val name = renameName.trim()
                    act("Playlist umbenannt.", {
                        withContext(Dispatchers.IO) { api.renameMusicPlaylist(id, name) }
                    }, true)
                }) { Text("Umbenennen") }
                Button(enabled = !busy && tracks.isNotEmpty(), onClick = { onPlay(tracks) }) {
                    Text("Alle abspielen")
                }
                TextButton(onClick = { confirmDelete = true }) { Text("Löschen") }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(addQuery, { addQuery = it }, modifier = Modifier.weight(1f),
                    label = { Text("Titel zum Hinzufügen suchen") }, singleLine = true)
                TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        try {
                            results = withContext(Dispatchers.IO) {
                                api.json("/api/music/tracks?q=" + session.query(addQuery) + "&limit=30")
                                    .optJSONArray("items")?.objects()?.map(::song).orEmpty()
                            }
                            message = ""
                        } catch (e: Exception) { message = e.message ?: "Suche fehlgeschlagen." }
                    }
                }) { Text("Suchen") }
            }
            if (results.isNotEmpty()) {
                Text("Suchergebnisse", style = MaterialTheme.typography.titleSmall)
                Column(Modifier.heightIn(max = 210.dp).verticalScroll(rememberScrollState())) {
                    results.forEach { item ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(item.title, Modifier.weight(1f), maxLines = 1)
                            TextButton(enabled = !busy && tracks.size < 1000, onClick = {
                                saveOrder(tracks + item)
                            }) { Text("+") }
                        }
                    }
                }
            }
            Text("Reihenfolge · " + tracks.size + " Titel", style = MaterialTheme.typography.titleSmall)
            LazyColumn {
                items(tracks.size) { index ->
                    val item = tracks[index]
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall)
                            Text(item.description, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        TextButton(enabled = !busy && index > 0, onClick = {
                            val next = tracks.toMutableList()
                            java.util.Collections.swap(next, index, index - 1)
                            saveOrder(next)
                        }) { Text("↑") }
                        TextButton(enabled = !busy && index < tracks.lastIndex, onClick = {
                            val next = tracks.toMutableList()
                            java.util.Collections.swap(next, index, index + 1)
                            saveOrder(next)
                        }) { Text("↓") }
                        TextButton(enabled = !busy, onClick = {
                            saveOrder(tracks.toMutableList().also { it.removeAt(index) })
                        }) { Text("×") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(onDismissRequest = { confirmDelete = false },
            title = { Text("Playlist löschen?") },
            text = { Text("Die Playlist wird gelöscht; die Musikdateien bleiben erhalten.") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    val id = selected
                    act("Playlist gelöscht.", {
                        withContext(Dispatchers.IO) { api.deleteMusicPlaylist(id) }
                    })
                    selected = 0
                    tracks = emptyList()
                }) { Text("Löschen") }
            }, dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") }
            })
    }
}

data class LyricLine(val timeMs: Long, val text: String)

/** LRC supports multiple timestamps per line and hundredths or thousandths of a second. */
fun parseSyncedLyrics(input: String): List<LyricLine> {
    val marker = Regex("""\\[(\\d{1,3}):(\\d{2})(?:[.,](\\d{1,3}))?]""")
    return input.lineSequence().flatMap { row ->
        val matches = marker.findAll(row).toList()
        val text = marker.replace(row, "").trim()
        if (text.isEmpty()) emptyList<LyricLine>().asSequence() else matches.asSequence().map { match ->
            val fraction = match.groupValues[3]
            val ms = if (fraction.isBlank()) 0L else
                (fraction.toLong() * when(fraction.length) {
                    1 -> 100L
                    2 -> 10L
                    else -> 1L
                })
            val t = (match.groupValues[1].toLong() * 60L +
                match.groupValues[2].toLong()) * 1000L + ms
            LyricLine(t, text)
        }
    }.sortedBy { it.timeMs }.toList()
}

@Composable
fun MusicLyricsDialog(session: TromuwelleSession, track: LibraryEntry,
                      player: androidx.media3.common.Player? = null, onDismiss: () -> Unit) {
    val api = remember(session) { TromuwelleApi(session) }
    var data by remember(track.id) { mutableStateOf<JSONObject?>(null) }
    var error by remember(track.id) { mutableStateOf("") }
    var sync by remember { mutableStateOf(true) }
    var currentMs by remember { mutableLongStateOf(0) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val lines = remember(data) { parseSyncedLyrics(data?.optString("synced").orEmpty()) }
    val active = lines.indexOfLast { it.timeMs <= currentMs }.coerceAtLeast(0)
    LaunchedEffect(track.id) {
        try { data = withContext(Dispatchers.IO) { api.musicLyrics(track.id) } }
        catch (e: Exception) { error = e.message ?: "Lyrics nicht verfügbar." }
    }
    LaunchedEffect(player, sync, track.id) {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            if (sync) currentMs = player?.currentPosition ?: 0L
            kotlinx.coroutines.delay(120L)
        }
    }
    LaunchedEffect(active, sync, data) {
        if (sync && lines.isNotEmpty()) listState.animateScrollToItem(active.coerceAtLeast(0))
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(track.title + " · Lyrics") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val result = data
                if (result == null && error.isBlank()) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (result != null) {
                    Text("Quelle: " + result.optString("provider", "unbekannt"),
                        style = MaterialTheme.typography.labelSmall)
                    if (lines.isNotEmpty()) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("Zeile synchron markieren")
                            Spacer(Modifier.weight(1f))
                            Switch(checked = sync, onCheckedChange = { sync = it })
                        }
                        LazyColumn(Modifier.heightIn(max = 390.dp), state = listState,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(lines.size) { i ->
                                Text(lines[i].text,
                                    color = if (sync && i == active)
                                        MaterialTheme.colorScheme.primary else
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = if (sync && i == active)
                                        MaterialTheme.typography.titleLarge
                                    else MaterialTheme.typography.bodyLarge)
                            }
                        }
                    } else {
                        val plain = result.optString("plain").ifBlank { result.optString("synced") }
                        if (plain.isNotBlank()) {
                            Column(Modifier.heightIn(max = 390.dp).verticalScroll(rememberScrollState())) {
                                Text(plain, style = MaterialTheme.typography.bodyLarge)
                            }
                        } else Text(when(result.optString("status")) {
                            "rate_limited" -> "Lyricsanbieter vorübergehend begrenzt. Später erneut versuchen."
                            "busy" -> "Der Server verarbeitet gerade eine andere Lyrics-Anfrage."
                            else -> "Für diesen Titel sind keine Lyrics verfügbar."
                        })
                    }
                }
                if(error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } })
}
