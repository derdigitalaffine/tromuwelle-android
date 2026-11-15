package de.tromuwelle.android

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.Player
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Independently connected external Subsonic music libraries. All network requests and
 * credential changes run off the main thread. No credentials or stream URLs shown in UI.
 */
@Composable
fun ExternalSubsonicScreen(player: Player? = null, onClose: () -> Unit,
                           onPlay: (SubsonicSource, List<JSONObject>, Int) -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { SubsonicSourceStore(context) }
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf(store.all()) }
    var selected by remember { mutableStateOf<SubsonicSource?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var section by remember { mutableStateOf("artists") }
    var playlistId by remember { mutableStateOf("") }
    var playlistName by remember { mutableStateOf("") }
    var createPlaylistName by remember { mutableStateOf("") }
    var playlistTargets by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var actionSong by remember { mutableStateOf<JSONObject?>(null) }
    var lyricsSong by remember { mutableStateOf<JSONObject?>(null) }
    var playlistPickerSong by remember { mutableStateOf<JSONObject?>(null) }
    var downloadSong by remember { mutableStateOf<JSONObject?>(null) }
    var confirmPlaylistDelete by remember { mutableStateOf(false) }
    var artistId by remember { mutableStateOf("") }
    var albumId by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var offset by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var pageMayHaveMore by remember { mutableStateOf(false) }
    var requestEpoch by remember { mutableIntStateOf(0) }
    var activeLoad by remember { mutableStateOf<Job?>(null) }
    var lastRequestedSearch by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<SubsonicSource?>(null) }
    val downloadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = selected
        val song = downloadSong
        if (uri != null && source != null && song != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        SubsonicClient(source).downloadTo(context, song.optString("id"), uri)
                    }
                    error = "Download erfolgreich."
                } catch (e: Exception) { error = e.message ?: "Download fehlgeschlagen." }
                downloadSong = null
            }
        } else downloadSong = null
    }

    fun load(more: Boolean = false) {
        val server = selected ?: return
        // Never append a second page while a request is in progress or after
        // the server returned fewer items than its nominal page size.
        if (more && (busy || !pageMayHaveMore)) return
        val requestId = ++requestEpoch
        activeLoad?.cancel()
        val currentSection = section
        val currentSearch = search.trim()
        val currentArtist = artistId
        val currentAlbum = albumId
        val currentPlaylist = playlistId
        val start = if (more) offset else 0
        lastRequestedSearch = search
        if (!more) {
            rows = emptyList()
            offset = 0
            pageMayHaveMore = false
        }
        activeLoad = scope.launch {
            busy = true
            try {
                // The network reply is immutable request-local data: no Compose
                // state may be written on the IO worker.
                val response = withContext(Dispatchers.IO) {
                    val client = SubsonicClient(server)
                    if (currentSection == "playlistTracks") {
                        val playlist = client.playlist(currentPlaylist)
                        val tracks = playlist.optJSONArray("entry") ?: org.json.JSONArray()
                        (0 until tracks.length()).mapNotNull { tracks.optJSONObject(it) } to
                            playlist.optString("name")
                    } else {
                        val page = when (currentSection) {
                            "artists" -> client.artists()
                            "albums" -> client.albums(currentArtist, start)
                            "playlists" -> client.playlists()
                            else -> client.songs(currentSearch, currentAlbum, start)
                        }
                        page to ""
                    }
                }
                if (requestId != requestEpoch || selected?.id != server.id) return@launch
                val unfiltered = response.first
                val filtered = when (currentSection) {
                    "artists", "albums", "playlists" -> unfiltered.filter {
                        it.optString("name").contains(currentSearch, ignoreCase = true)
                    }
                    else -> unfiltered
                }
                rows = if (more) {
                    // Different pages may overlap on servers with a changing
                    // catalogue; preserve the first copy and stable order.
                    (rows + filtered).distinctBy { it.optString("id") }
                } else filtered
                offset = start + unfiltered.size
                pageMayHaveMore = canPageSubsonic(currentSection, currentArtist,
                    currentAlbum, currentSearch) && unfiltered.size >= SUBSONIC_PAGE_SIZE
                if (currentSection == "playlistTracks") playlistName = response.second
                error = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestId == requestEpoch) {
                    error = e.message ?: "Subsonic-Bibliothek nicht erreichbar."
                }
            } finally {
                if (requestId == requestEpoch) busy = false
            }
        }
    }
    // Search is automatic but debounced. Navigation calls load() explicitly;
    // its lastRequestedSearch marker prevents the duplicate request.
    LaunchedEffect(search, section, selected?.id) {
        delay(420)
        if (selected != null && search != lastRequestedSearch) load()
    }
    fun open(source: SubsonicSource) {
        selected = source
        section = "artists"; artistId = ""; albumId = ""; playlistId = ""; search = ""
        offset = 0; rows = emptyList()
        pageMayHaveMore = false
        load()
    }
    fun edit(source: SubsonicSource? = null) {
        editingId = source?.id.orEmpty()
        name = source?.name.orEmpty()
        address = source?.address.orEmpty()
        username = source?.user.orEmpty()
        password = ""
        showEditor = true
        error = ""
    }
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(selected?.name ?: "Musikserver", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = {
                if (selected != null) {
                    ++requestEpoch; activeLoad?.cancel()
                    selected = null; rows = emptyList(); busy = false
                    pageMayHaveMore = false; error = ""
                }
                else onClose()
            }) { Text(if (selected != null) "Serverauswahl" else "Schließen") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (selected == null) {
            Text("Externe Navidrome-, Airsonic-, Gonic- und OpenSubsonic-Server",
                style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { edit() }) { Text("HTTPS-Musikserver hinzufügen") }
            LazyColumn {
                items(sources, key = { it.id }) { source ->
                    ListItem(
                        headlineContent = { Text(source.name) },
                        supportingContent = { Text(source.address + " · " + source.user) },
                        modifier = Modifier.clickable { open(source) },
                        trailingContent = {
                            Row {
                                TextButton(onClick = { edit(source) }) { Text("Ändern") }
                                TextButton(onClick = { confirmation = source }) { Text("Löschen") }
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("artists" to "Interpreten", "albums" to "Alben",
                    "songs" to "Titel", "playlists" to "Playlists").forEach { (id, label) ->
                    FilterChip(selected = section == id, onClick = {
                        section = id; artistId = ""; albumId = ""; search = ""
                        offset = 0; load()
                    }, label = { Text(label) })
                }
            }
            if (section == "playlists") {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(createPlaylistName, { createPlaylistName = it },
                        label = { Text("Neue Playlist") }, modifier = Modifier.weight(1f))
                    Button(enabled = !busy && createPlaylistName.trim().isNotBlank(), onClick = {
                        val server = selected ?: return@Button
                        val name = createPlaylistName.trim()
                        scope.launch {
                            busy = true
                            try {
                                withContext(Dispatchers.IO) { SubsonicClient(server).createPlaylist(name) }
                                createPlaylistName = ""; load()
                            } catch (e: Exception) { error = e.message ?: "Anlegen fehlgeschlagen." }
                            finally { busy = false }
                        }
                    }) { Text("+") }
                }
            }
            if (section == "playlistTracks") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { section = "playlists"; playlistId = ""; load() }) {
                        Text("‹ Playlists")
                    }
                    Button(enabled = rows.isNotEmpty(), onClick = {
                        val server = selected ?: return@Button
                        onPlay(server, rows, 0)
                    }) { Text("Alle abspielen") }
                    TextButton(onClick = { confirmPlaylistDelete = true }) { Text("Löschen") }
                }
                Text(playlistName + " · " + rows.size + " Titel",
                    style = MaterialTheme.typography.titleMedium)
            }
            if (artistId.isNotBlank() || albumId.isNotBlank()) {
                TextButton(onClick = {
                    if (albumId.isNotBlank()) { albumId = ""; section = "albums" }
                    else { artistId = ""; section = "artists" }
                    load()
                }) { Text("‹ Zurück") }
            }
            Row {
                OutlinedTextField(search, { search = it.take(120) }, singleLine = true,
                    modifier = Modifier.weight(1f),
                    label = { Text("Musik durchsuchen") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (search.isNotBlank()) IconButton(onClick = {
                            search = ""; load()
                        }) { Icon(Icons.Rounded.Close, contentDescription = "Suche löschen") }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { load() }))
                IconButton(onClick = { load() }, enabled = !busy) {
                    Icon(Icons.Rounded.Refresh, contentDescription =
                        if (section == "songs" && search.isBlank()) "Neue Titelauswahl" else "Aktualisieren")
                }
            }
            if (rows.isNotEmpty()) {
                Text("${rows.size} Einträge" + if (pageMayHaveMore) " · weitere verfügbar" else "",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (section == "songs" && search.isBlank() && albumId.isBlank()) {
                Text("Zufällige Titel aus deiner Sammlung · mit Aktualisieren neu mischen",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (rows.isEmpty() && !busy && error.isBlank())
                Text("Keine Einträge gefunden.", Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(rows.size, key = { index -> "$section:${rows[index].optString("id")}:$index" }) { index ->
                    val entry = rows[index]
                    val label = entry.optString("title")
                        .ifBlank { entry.optString("name", "Unbenannt") }
                    val description = listOf(entry.optString("artist"),
                        entry.optString("album"), entry.optString("year"))
                        .filter { it.isNotBlank() && it != "null" }.joinToString(" · ")
                    Row(Modifier.fillMaxWidth().clickable {
                        when (section) {
                            "artists" -> { artistId = entry.optString("id"); section = "albums"; search = ""; load() }
                            "albums" -> { albumId = entry.optString("id"); section = "songs"; search = ""; load() }
                            "playlists" -> {
                                playlistId = entry.optString("id")
                                playlistName = entry.optString("name")
                                section = "playlistTracks"
                                search = ""
                                load()
                            }
                            else -> {
                                val server = selected ?: return@clickable
                                onPlay(server, rows, index)
                            }
                        }
                    }.padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        ExternalSubsonicCover(selected, entry, Modifier.size(52.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (description.isNotBlank())
                                Text(description, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (section == "songs" || section == "playlistTracks") {
                            IconButton(onClick = { actionSong = entry }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "Titelaktionen")
                            }
                        } else Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                    }
                    HorizontalDivider()
                }
                if (pageMayHaveMore) {
                    item {
                        TextButton(enabled = !busy && pageMayHaveMore,
                            onClick = { load(true) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Weitere laden")
                        }
                    }
                }
            }
        }
    }
    actionSong?.let { song ->
        val server = selected
        AlertDialog(onDismissRequest = { actionSong = null },
            title = { Text(song.optString("title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    TextButton(onClick = { lyricsSong = song; actionSong = null }) {
                        Text("Liedtext anzeigen")
                    }
                    TextButton(onClick = {
                        downloadSong = song
                        actionSong = null
                        downloadPicker.launch(song.optString("title").ifBlank { "song" } +
                            "." + song.optString("suffix").ifBlank { "mp3" })
                    }) { Text("Original herunterladen") }
                    TextButton(onClick = {
                        actionSong = null
                        if (server != null) {
                            playlistPickerSong = song
                            scope.launch {
                                try {
                                    playlistTargets = withContext(Dispatchers.IO) {
                                        SubsonicClient(server).playlists()
                                    }
                                } catch (e: Exception) { error = e.message ?: "Playlists nicht erreichbar." }
                            }
                        }
                    }) { Text("Zu Playlist hinzufügen") }
                    TextButton(onClick = {
                        actionSong = null
                        if (server != null) {
                            val starred = song.optString("starred").isNotBlank()
                            scope.launch {
                                busy = true
                                try {
                                    withContext(Dispatchers.IO) {
                                        SubsonicClient(server).starSong(song.optString("id"), !starred)
                                    }
                                    load()
                                } catch (e: Exception) { error = e.message ?: "Favorit konnte nicht geändert werden." }
                                finally { busy = false }
                            }
                        }
                    }) { Text(if (song.optString("starred").isBlank()) "★ Als Favorit markieren" else "☆ Favorit entfernen") }
                    if (section == "playlistTracks") {
                        TextButton(onClick = {
                            actionSong = null
                            val index = rows.indexOfFirst { it === song }
                            val id = playlistId
                            if (server != null && index >= 0) {
                                scope.launch {
                                    busy = true
                                    try {
                                        withContext(Dispatchers.IO) {
                                            SubsonicClient(server).removePlaylistIndex(id, index)
                                        }
                                        load()
                                    } catch (e: Exception) { error = e.message ?: "Entfernen fehlgeschlagen." }
                                    finally { busy = false }
                                }
                            }
                        }) { Text("Aus dieser Playlist entfernen") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { actionSong = null }) { Text("Schließen") } })
    }
    playlistPickerSong?.let { song ->
        val server = selected
        AlertDialog(onDismissRequest = { playlistPickerSong = null },
            title = { Text("Playlist auswählen") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(
                    androidx.compose.foundation.rememberScrollState())) {
                    playlistTargets.forEach { target ->
                        TextButton(onClick = {
                            playlistPickerSong = null
                            if (server != null) {
                                scope.launch {
                                    busy = true
                                    try {
                                        withContext(Dispatchers.IO) {
                                            SubsonicClient(server).addPlaylistSong(
                                                target.optString("id"), song.optString("id"))
                                        }
                                        error = "Titel zur Playlist hinzugefügt."
                                        load()
                                    } catch (e: Exception) { error = e.message ?: "Hinzufügen fehlgeschlagen." }
                                    finally { busy = false }
                                }
                            }
                        }) { Text(target.optString("name")) }
                    }
                    if (playlistTargets.isEmpty()) Text("Keine Playlists vorhanden.")
                }
            },
            confirmButton = { TextButton(onClick = { playlistPickerSong = null }) { Text("Schließen") } })
    }
    if (confirmPlaylistDelete) {
        AlertDialog(onDismissRequest = { confirmPlaylistDelete = false },
            title = { Text("Playlist endgültig löschen?") },
            text = { Text("Die Playlist wird auf diesem Subsonic-Server entfernt. Songs bleiben erhalten.") },
            confirmButton = {
                Button(onClick = {
                    confirmPlaylistDelete = false
                    val server = selected
                    val id = playlistId
                    if (server != null) {
                        section = "playlists"; playlistId = ""; playlistName = ""
                        scope.launch {
                            busy = true
                            try {
                                withContext(Dispatchers.IO) { SubsonicClient(server).deletePlaylist(id) }
                                load()
                            } catch (e: Exception) { error = e.message ?: "Löschen fehlgeschlagen." }
                            finally { busy = false }
                        }
                    }
                }) { Text("Löschen") }
            }, dismissButton = {
                TextButton(onClick = { confirmPlaylistDelete = false }) { Text("Abbrechen") }
            })
    }
    lyricsSong?.let { song ->
        val server = selected
        if (server != null) ExternalSubsonicLyricsDialog(server, song, player,
            onClose = { lyricsSong = null })
    }
    if (showEditor) AlertDialog(onDismissRequest = { showEditor = false },
        title = { Text(if(editingId.isBlank()) "Musikserver hinzufügen" else "Musikserver bearbeiten") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(
                androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(address, { address = it },
                    label = { Text("HTTPS-URL (auch mit /subsonic möglich)") },
                    modifier = Modifier.fillMaxWidth())
                CertificateTrustControl(address = address)
                OutlinedTextField(username, { username = it }, label = { Text("Benutzername") },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = {
                    Text(if (editingId.isBlank()) "Passwort" else "Neues Passwort (leer = unverändert)")
                }, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())
                Text("Zugangsdaten sind nur gerätegebunden verschlüsselt gespeichert. HTTPS ist Pflicht.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(enabled = !busy && name.isNotBlank() && address.isNotBlank() &&
                username.isNotBlank(), onClick = {
                val existing = sources.firstOrNull { it.id == editingId }
                val p = password.ifBlank { existing?.password.orEmpty() }
                val source = SubsonicSource(existing?.id ?: java.util.UUID.randomUUID().toString(),
                    name.trim(), address.trim(), username.trim(), p)
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            SubsonicClient(source).ping()
                            store.save(source)
                        }
                        sources = store.all()
                        selected = if (selected?.id == source.id) source else selected
                        password = ""; error = ""; showEditor = false
                    } catch (e: Exception) {
                        error = tlsConnectionDiagnostic(e)
                    } finally { busy = false }
                }
            }) { Text("Verbindung testen & speichern") }
        },
        dismissButton = { TextButton(onClick = { showEditor = false; password = "" }) {
            Text("Abbrechen")
        } })
    if (confirmation != null) AlertDialog(onDismissRequest = { confirmation = null },
        title = { Text("Musikserver entfernen?") },
        text = { Text("Gespeicherte Zugangsdaten für diesen Server werden gelöscht.") },
        confirmButton = { Button(onClick = {
            val target = confirmation ?: return@Button
            store.remove(target.id)
            sources = store.all()
            if (selected?.id == target.id) selected = null
            confirmation = null
        }) { Text("Löschen") } },
        dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Abbrechen") } })
}
