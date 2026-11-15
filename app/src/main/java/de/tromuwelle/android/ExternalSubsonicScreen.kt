package de.tromuwelle.android

import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.Player
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
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
        val client = SubsonicClient(server)
        val currentSection = section
        val currentSearch = search
        val currentArtist = artistId
        val currentAlbum = albumId
        val start = if (more) offset else 0
        scope.launch {
            busy = true
            try {
                val data = withContext(Dispatchers.IO) {
                    when (currentSection) {
                        "artists" -> client.artists().filter { it.optString("name")
                            .contains(currentSearch, ignoreCase = true) }
                        "albums" -> client.albums(currentArtist, start).filter {
                            it.optString("name").contains(currentSearch, ignoreCase = true)
                        }
                        "playlistTracks" -> {
                            val playlist = client.playlist(playlistId)
                            playlistName = playlist.optString("name")
                            val list = playlist.optJSONArray("entry") ?: org.json.JSONArray()
                            (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                        }
                        "playlists" -> client.playlists().filter { it.optString("name")
                            .contains(currentSearch, ignoreCase = true) }
                        else -> client.songs(currentSearch, currentAlbum, start)
                    }
                }
                rows = if (more) rows + data else data
                offset = if (more) start + data.size else data.size
                error = ""
            } catch (e: Exception) { error = e.message ?: "Subsonic-Bibliothek nicht erreichbar." }
            finally { busy = false }
        }
    }
    fun open(source: SubsonicSource) {
        selected = source
        section = "artists"; artistId = ""; albumId = ""; playlistId = ""; search = ""
        offset = 0; rows = emptyList()
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
                if (selected != null) { selected = null; rows = emptyList() }
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
                OutlinedTextField(search, { search = it }, singleLine = true,
                    modifier = Modifier.weight(1f), label = { Text("Suchen") })
                TextButton(onClick = { load() }) { Text("Suchen") }
            }
            if (rows.isEmpty() && !busy && error.isBlank())
                Text("Keine Einträge gefunden.", Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(rows.size) { index ->
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
                    }.padding(vertical = 12.dp, horizontal = 8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.titleMedium)
                            if (description.isNotBlank())
                                Text(description, style = MaterialTheme.typography.bodySmall)
                        }
                        if (section == "songs" || section == "playlistTracks") {
                            TextButton(onClick = { actionSong = entry }) { Text("⋮") }
                        } else Text("›")
                    }
                    HorizontalDivider()
                }
                if ((section == "albums" && artistId.isBlank()) ||
                    (section == "songs" && albumId.isBlank())) {
                    item {
                        TextButton(enabled = !busy && rows.isNotEmpty(),
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(address, { address = it },
                    label = { Text("HTTPS-URL (auch mit /subsonic möglich)") },
                    modifier = Modifier.fillMaxWidth())
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
                        error = e.message ?: "Verbindung fehlgeschlagen."
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
