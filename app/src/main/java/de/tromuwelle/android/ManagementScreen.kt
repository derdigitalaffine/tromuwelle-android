package de.tromuwelle.android

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private enum class AdminArea(val title: String) {
    PLAYLISTS("TV-Playlists"), RADIO("Radio"), STREAM("Streaming"), ROOTS("Medienquellen"),
    USERS("Benutzer"), SCANS("Indexierung"), SECURITY("Sicherheit")
}

private data class RootDraft(val name: String, val path: String,
                             val protected: Boolean = false, val kind: String = "other") {
    fun json(): JSONObject = JSONObject().put("name", name.trim()).put("path", path.trim())
        .put("protected", protected).put("kind", kind)
}

private fun readLimitedFile(context: android.content.Context, uri: Uri, maxBytes: Int): ByteArray {
    val input = context.contentResolver.openInputStream(uri) ?: error("Datei nicht erreichbar.")
    return input.use { source ->
        val buffer = ByteArray(16 * 1024)
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            require(out.size() + read <= maxBytes) { "Datei zu groß." }
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    }
}

/** All server admin controls use real FastAPI endpoints and enforce role display. */
@Composable
fun ManagementScreen(session: TromuwelleSession, onBack: () -> Unit, onSourcesChanged: () -> Unit) {
    if (!session.account.isAdmin) {
        Column(Modifier.padding(20.dp)) {
            Text("Diese Ansicht benötigt Administratorrechte.")
            Button(onClick = onBack) { Text("Zurück") }
        }
        return
    }

    val api = remember(session) { TromuwelleApi(session) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var area by remember { mutableStateOf(AdminArea.PLAYLISTS) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var playlists by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var radios by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var users by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var settings by remember { mutableStateOf(JSONObject()) }
    var roots by remember { mutableStateOf<List<RootDraft>>(emptyList()) }
    var legacyStatus by remember { mutableStateOf(JSONObject()) }
    var legacyScope by remember { mutableStateOf("all") }
    var legacyPassword by remember { mutableStateOf("") }
    var index by remember { mutableStateOf(JSONObject()) }
    var musicIndex by remember { mutableStateOf(JSONObject()) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    var playlistName by remember { mutableStateOf("") }
    var playlistUrl by remember { mutableStateOf("") }
    var editingPlaylistId by remember { mutableStateOf("") }
    var radioId by remember { mutableStateOf("") }
    var radioName by remember { mutableStateOf("") }
    var radioUrl by remember { mutableStateOf("") }
    var radioGenre by remember { mutableStateOf("") }
    var radioLogo by remember { mutableStateOf("") }
    var buffer by remember { mutableFloatStateOf(36f) }
    var maxHeight by remember { mutableIntStateOf(0) }
    var mediaPath by remember { mutableStateOf("") }
    var userName by remember { mutableStateOf("") }
    var userPassword by remember { mutableStateOf("") }
    var userRole by remember { mutableStateOf("viewer") }
    var userRoots by remember { mutableStateOf<Set<String>>(emptySet()) }
    var editingUserId by remember { mutableIntStateOf(-1) }
    var resetPassword by remember { mutableStateOf("") }

    fun load(selected: AdminArea = area) {
        scope.launch {
            try {
                when (selected) {
                    AdminArea.PLAYLISTS -> playlists = withContext(Dispatchers.IO) { api.playlists().objects() }
                    AdminArea.RADIO -> radios = withContext(Dispatchers.IO) { api.radioStations().objects() }
                    AdminArea.STREAM, AdminArea.ROOTS -> {
                        val data = withContext(Dispatchers.IO) { api.settings() }
                        settings = data
                        buffer = data.optInt("buffer_seconds", 36).toFloat()
                        maxHeight = data.optInt("max_height")
                        mediaPath = data.optString("media_path")
                        roots = (data.optJSONArray("media_roots") ?: JSONArray()).objects().map {
                            RootDraft(it.optString("name"), it.optString("path"),
                                it.optBoolean("protected"), it.optString("kind", "other"))
                        }
                    }
                    AdminArea.USERS -> {
                        if (session.account.managed) users = withContext(Dispatchers.IO) { api.users().objects() }
                        settings = withContext(Dispatchers.IO) { api.settings() }
                    }
                    AdminArea.SECURITY -> {
                        legacyStatus = withContext(Dispatchers.IO) { session.status(session.server) }
                        legacyScope = legacyStatus.optString("scope", "all")
                    }
                    AdminArea.SCANS -> {
                        index = try { withContext(Dispatchers.IO) { api.mediaIndex() } }
                        catch (e: Exception) { JSONObject().put("error", e.message) }
                        musicIndex = try { withContext(Dispatchers.IO) { api.musicStatus() } }
                        catch (e: Exception) { JSONObject().put("error", e.message) }
                    }
                }
            } catch (e: Exception) { error = e.message ?: "Einstellungen konnten nicht geladen werden." }
        }
    }

    fun act(success: String, action: suspend () -> Unit, sources: Boolean = false) {
        scope.launch {
            busy = true; error = ""; notice = ""
            try {
                withContext(Dispatchers.IO) { action() }
                notice = success
                load()
                if (sources) onSourcesChanged()
            } catch (e: Exception) { error = e.message ?: "Aktion fehlgeschlagen." }
            finally { busy = false }
        }
    }

    val playlistPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if (playlistName.trim().isBlank()) error = "Bitte zuerst den Playlistnamen eingeben."
            else act("M3U-Playlist importiert.", {
                val data = readLimitedFile(context, uri, 12_000_000)
                api.uploadPlaylist(playlistName.trim(), data)
            }, sources = true)
        }
    }
    val radioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) act("Radio-Senderliste importiert.", {
            val data = readLimitedFile(context, uri, 1_000_000)
            val text = data.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            api.importRadio(text)
        }, sources = true)
    }

    LaunchedEffect(area) { load(area) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Verwaltung", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Schließen") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AdminArea.entries.forEach { tab ->
                FilterChip(selected = tab == area, onClick = { area = tab; error = ""; notice = "" },
                    label = { Text(tab.title) })
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)) {
            when (area) {
                AdminArea.PLAYLISTS -> {
                    item {
                        Text("TV-Playlists", style = MaterialTheme.typography.titleLarge)
                        Text("M3U-URL oder lokale M3U-Datei hinzufügen.",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(playlistName, { playlistName = it },
                            label = { Text("Playlistname") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(playlistUrl, { playlistUrl = it },
                            label = { Text("M3U-Abonnement (HTTP/HTTPS)") }, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !busy && playlistName.isNotBlank() && playlistUrl.isNotBlank(),
                                onClick = {
                                    val name = playlistName
                                    val url = playlistUrl
                                    act("Playlist gespeichert.", {
                                        api.addPlaylist(name, url)
                                    }, sources = true)
                                }) { Text("URL hinzufügen") }
                            OutlinedButton(enabled = !busy && playlistName.isNotBlank(),
                                onClick = { playlistPicker.launch(arrayOf("*/*")) }) {
                                Text("M3U hochladen")
                            }
                        }
                        HorizontalDivider()
                    }
                    items(playlists, key = { it.optString("id") }) { row ->
                        val id = row.optString("id")
                        val builtin = row.optBoolean("builtin", false)
                        Text(row.optString("name") + " · " + row.optInt("channel_count") + " Sender",
                            style = MaterialTheme.typography.titleMedium)
                        if (row.optString("last_error").isNotBlank() && row.optString("last_error") != "null")
                            Text(row.optString("last_error"), color = MaterialTheme.colorScheme.error)
                        if (!builtin) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(enabled = !busy && row.optString("source") != "upload",
                                    onClick = {
                                        act("Playlist aktualisiert.", { api.refreshPlaylist(id) }, sources = true)
                                    }) { Text("Aktualisieren") }
                                TextButton(onClick = {
                                    editingPlaylistId = id; playlistName = row.optString("name")
                                }) { Text("Umbenennen") }
                                TextButton(onClick = {
                                    confirm = ("Playlist „" + row.optString("name") + "“ löschen?") to {
                                        act("Playlist entfernt.", { api.deletePlaylist(id) }, sources = true)
                                    }
                                }) { Text("Löschen") }
                            }
                            if (editingPlaylistId == id) {
                                OutlinedTextField(playlistName, { playlistName = it },
                                    label = { Text("Neuer Name") }, modifier = Modifier.fillMaxWidth())
                                Button(onClick = {
                                    val name = playlistName
                                    act("Playlist umbenannt.", { api.renamePlaylist(id, name) })
                                    editingPlaylistId = ""
                                }) { Text("Namen speichern") }
                            }
                        }
                        HorizontalDivider()
                    }
                }
                AdminArea.RADIO -> {
                    item {
                        Text("Radiosender", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(radioName, { radioName = it },
                            label = { Text("Sendername") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(radioUrl, { radioUrl = it },
                            label = { Text("Stream-URL (HTTP/HTTPS)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(radioGenre, { radioGenre = it },
                            label = { Text("Genre") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(radioLogo, { radioLogo = it },
                            label = { Text("Logo-URL (optional)") }, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !busy && radioName.isNotBlank() && radioUrl.isNotBlank(),
                                onClick = {
                                    val selected = radioId.ifBlank { null }
                                    val name = radioName
                                    val url = radioUrl
                                    val genre = radioGenre
                                    val logo = radioLogo
                                    act("Radiosender gespeichert.", {
                                        api.saveRadio(selected, name, url, genre, logo)
                                    }, sources = true)
                                    radioId = ""; radioName = ""; radioUrl = ""; radioGenre = ""; radioLogo = ""
                                }) { Text(if (radioId.isBlank()) "Hinzufügen" else "Speichern") }
                            OutlinedButton(onClick = { radioPicker.launch(arrayOf("*/*")) }) {
                                Text("M3U/PLS importieren")
                            }
                        }
                    }
                    items(radios, key = { it.optString("id") }) { item ->
                        val id = item.optString("id")
                        Text(item.optString("name"), style = MaterialTheme.typography.titleMedium)
                        Text(item.optString("genre"), style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = {
                                radioId = id; radioName = item.optString("name")
                                radioUrl = item.optString("url"); radioGenre = item.optString("genre")
                                radioLogo = item.optString("logo")
                            }) { Text("Bearbeiten") }
                            TextButton(onClick = {
                                confirm = ("Radiosender „" + item.optString("name") + "“ löschen?") to {
                                    act("Radiosender gelöscht.", { api.deleteRadio(id) }, sources = true)
                                }
                            }) { Text("Löschen") }
                        }
                        HorizontalDivider()
                    }
                }
                AdminArea.STREAM -> {
                    item {
                        Text("Streaming", style = MaterialTheme.typography.titleLarge)
                        Text("Live-Puffer: " + buffer.toInt() + " Sekunden")
                        Slider(value = buffer, onValueChange = { buffer = it }, valueRange = 16f..120f,
                            steps = 103)
                        Text("Maximale HLS-Auflösung")
                        Row(Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0, 240, 360, 480, 720, 1080, 1440).forEach { h ->
                                FilterChip(selected = maxHeight == h, onClick = { maxHeight = h },
                                    label = { Text(if(h == 0) "Auto" else h.toString() + "p") })
                            }
                        }
                        Button(enabled = !busy, onClick = {
                            val seconds = buffer.toInt()
                            val height = maxHeight
                            act("Stream-Einstellungen gespeichert.", {
                                api.saveStreamSettings(seconds, height)
                            })
                        }) { Text("Stream-Einstellungen speichern") }
                        HorizontalDivider()
                        Text("Medienportal", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(mediaPath, { mediaPath = it },
                            label = { Text("Portal-Unterpfad (leer = deaktiviert)") },
                            modifier = Modifier.fillMaxWidth())
                        Button(enabled = !busy, onClick = {
                            val path = mediaPath
                            act("Medienportal-Einstellung gespeichert.", { api.saveMediaPath(path) },
                                sources = true)
                        }) { Text("Medienportal speichern") }
                    }
                }
                AdminArea.ROOTS -> {
                    item {
                        Text("Freigegebene Medienordner", style = MaterialTheme.typography.titleLarge)
                        Text("Host-Verzeichnisse müssen bereits im Servercontainer unter /media/roots eingebunden sein.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    items(roots.size) { index ->
                        val root = roots[index]
                        fun edit(changed: RootDraft) {
                            roots = roots.toMutableList().also { it[index] = changed }
                        }
                        Text("Quelle " + (index + 1), style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(root.name, { edit(root.copy(name = it)) },
                            label = { Text("Anzeigename") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(root.path, { edit(root.copy(path = it)) },
                            label = { Text("Pfad relativ zu /media/roots") }, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("other", "movies", "series", "music", "adult_movies").forEach { kind ->
                                FilterChip(selected = root.kind == kind, onClick = { edit(root.copy(kind = kind)) },
                                    label = { Text(kind) })
                            }
                        }
                        Row {
                            Checkbox(checked = root.protected, onCheckedChange = { edit(root.copy(protected = it)) })
                            Text("Geschützte Quelle", modifier = Modifier.padding(top = 12.dp))
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { roots = roots.toMutableList().also { it.removeAt(index) } }) {
                                Text("Entfernen")
                            }
                        }
                        HorizontalDivider()
                    }
                    item {
                        OutlinedButton(onClick = { roots = roots + RootDraft("", "") }) {
                            Text("Medienordner hinzufügen")
                        }
                        Button(enabled = !busy && roots.all { it.name.isNotBlank() && it.path.isNotBlank() },
                            onClick = {
                                val data = JSONArray().also { arr -> roots.forEach { arr.put(it.json()) } }
                                act("Medienordner gespeichert.", { api.saveRoots(data) }, sources = true)
                            }) { Text("Alle Medienordner speichern") }
                    }
                }
                AdminArea.USERS -> {
                    item {
                        Text("Benutzerverwaltung", style = MaterialTheme.typography.titleLarge)
                        if (!session.account.managed) {
                            Text("Persönliche Benutzerkonten sind auf diesem Server noch nicht aktiviert.")
                        } else {
                            OutlinedTextField(userName, { userName = it },
                                label = { Text("Neuer Benutzername") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(userPassword, { userPassword = it },
                                label = { Text("Passwort (mind. 12 Zeichen)") },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("viewer", "admin").forEach { role ->
                                    FilterChip(selected = userRole == role, onClick = { userRole = role },
                                        label = { Text(if(role == "admin") "Administrator" else "Leser") })
                                }
                            }
                            Text("Freigegebene Ordner", style = MaterialTheme.typography.titleSmall)
                            val allRoots = settings.optJSONArray("media_roots")?.objects().orEmpty()
                            allRoots.forEach { root ->
                                val path = root.optString("path")
                                Row {
                                    Checkbox(checked = path in userRoots, onCheckedChange = { yes ->
                                        userRoots = if(yes) userRoots + path else userRoots - path
                                    })
                                    Text(root.optString("name"), modifier = Modifier.padding(top = 11.dp))
                                }
                            }
                            Button(enabled = !busy && userName.length >= 3 && userPassword.length >= 12,
                                onClick = {
                                    val rootsArray = userRoots.toJsonArray()
                                    val name = userName
                                    val password = userPassword
                                    val role = userRole
                                    act("Benutzer angelegt.", {
                                        api.createUser(name, password, role, rootsArray)
                                    })
                                    userName = ""; userPassword = ""; userRoots = emptySet()
                                }) { Text("Benutzer anlegen") }
                        }
                        HorizontalDivider()
                    }
                    if (session.account.managed) items(users, key = { it.optInt("id") }) { user ->
                        val id = user.optInt("id")
                        Text(user.optString("username") + " · " + user.optString("role"),
                            style = MaterialTheme.typography.titleMedium)
                        if (editingUserId == id) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("viewer", "admin").forEach { role ->
                                    FilterChip(selected = userRole == role, onClick = { userRole = role },
                                        label = { Text(role) })
                                }
                            }
                            val allRoots = settings.optJSONArray("media_roots")?.objects().orEmpty()
                            allRoots.forEach { root ->
                                val path = root.optString("path")
                                Row {
                                    Checkbox(checked = path in userRoots, onCheckedChange = { yes ->
                                        userRoots = if(yes) userRoots + path else userRoots - path
                                    })
                                    Text(root.optString("name"), modifier = Modifier.padding(top = 11.dp))
                                }
                            }
                            Button(onClick = {
                                val chosen = userRoots.toJsonArray()
                                val role = userRole
                                act("Benutzerrechte gespeichert. Aktive Sitzungen wurden widerrufen.", {
                                    api.updateUser(id, role, chosen)
                                })
                                editingUserId = -1
                            }) { Text("Rechte speichern") }
                            OutlinedTextField(resetPassword, { resetPassword = it },
                                label = { Text("Neues Passwort (mind. 12 Zeichen)") },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth())
                            Button(enabled = resetPassword.length >= 12, onClick = {
                                val pw = resetPassword
                                act("Passwort zurückgesetzt.", { api.changePassword(id, pw) })
                                resetPassword = ""
                            }) { Text("Passwort zurücksetzen") }
                        }
                        Row {
                            TextButton(onClick = {
                                if (editingUserId == id) editingUserId = -1 else {
                                    editingUserId = id
                                    userRole = user.optString("role")
                                    userRoots = user.optJSONArray("roots")?.let { arr ->
                                        (0 until arr.length()).map { arr.optString(it) }.toSet()
                                    } ?: emptySet()
                                }
                            }) { Text(if (editingUserId == id) "Schließen" else "Bearbeiten") }
                            TextButton(onClick = {
                                confirm = ("Benutzer „" + user.optString("username") + "“ löschen?") to {
                                    act("Benutzer entfernt.", { api.deleteUser(id) })
                                }
                            }) { Text("Löschen") }
                        }
                        HorizontalDivider()
                    }
                }
                AdminArea.SECURITY -> {
                    item {
                        Text("Sicherheit", style = MaterialTheme.typography.titleLarge)
                        if(session.account.managed) {
                            Text("Persönliche Konten sind aktiviert. Rollen und Medienrechte unter Benutzer verwalten.")
                        } else {
                            Text("Klassischer Sammel-Passwortschutz", style = MaterialTheme.typography.titleMedium)
                            Text("Schutz aktiviert: " +
                                if(legacyStatus.optBoolean("enabled")) "Ja" else "Nein")
                            Text("Schutzbereich")
                            Row(Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("all", "media", "settings", "media_settings", "folders").forEach { scope ->
                                    FilterChip(selected = legacyScope == scope, onClick = {
                                        legacyScope = scope
                                    }, label = { Text(scope) })
                                }
                            }
                            OutlinedTextField(legacyPassword, { legacyPassword = it },
                                label = { Text(if (legacyStatus.optBoolean("enabled"))
                                    "Bestehendes Passwort" else "Neues Passwort (mindestens 8 Zeichen)") },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = !busy && legacyPassword.isNotBlank(), onClick = {
                                    val pass = legacyPassword; val scope = legacyScope
                                    act("Schutzbereich gespeichert.", {
                                        api.legacyProtection(true, pass, scope)
                                        session.persistCurrentCookies()
                                    })
                                    legacyPassword = ""
                                }) { Text("Aktivieren / Ändern") }
                                OutlinedButton(enabled = !busy && legacyStatus.optBoolean("enabled") &&
                                    legacyPassword.isNotBlank(), onClick = {
                                    val pass = legacyPassword; val scope = legacyScope
                                    act("Passwortschutz deaktiviert.", {
                                        api.legacyProtection(false, pass, scope)
                                    })
                                    legacyPassword = ""
                                }) { Text("Deaktivieren") }
                            }
                        }
                    }
                }
                AdminArea.SCANS -> {
                    item {
                        Text("Indexierung", style = MaterialTheme.typography.titleLarge)
                        Text("Medienindex", style = MaterialTheme.typography.titleMedium)
                        Text(index.optString("message", index.optString("error", "Status verfügbar")))
                        Text("Status: " + if(index.optBoolean("running")) "Läuft" else "Bereit")
                        Row {
                            Button(enabled = !busy && !index.optBoolean("running"), onClick = {
                                act("Medienindex gestartet.", { api.startMediaIndex() })
                            }) { Text("Starten") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(enabled = !busy && index.optBoolean("running"), onClick = {
                                act("Index-Stopp angefordert.", { api.stopMediaIndex() })
                            }) { Text("Stoppen") }
                        }
                        HorizontalDivider()
                        Text("Musikscanner", style = MaterialTheme.typography.titleMedium)
                        Text(musicIndex.optString("message", musicIndex.optString("error", "Status verfügbar")))
                        Text("Status: " + if(musicIndex.optBoolean("running")) "Läuft" else "Bereit")
                        Row {
                            Button(enabled = !busy && !musicIndex.optBoolean("running"), onClick = {
                                act("Musikscan gestartet.", { api.startMusicScan() })
                            }) { Text("Starten") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(enabled = !busy && musicIndex.optBoolean("running"), onClick = {
                                act("Musikscan-Stopp angefordert.", { api.stopMusicScan() })
                            }) { Text("Stoppen") }
                        }
                        TextButton(onClick = { load() }) { Text("Status aktualisieren") }
                    }
                }
            }
        }
    }
    confirm?.let { dialog ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text("Löschen bestätigen") },
            text = { Text(dialog.first) },
            confirmButton = { Button(onClick = { confirm = null; dialog.second() }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Abbrechen") } })
    }
}
