package de.tromuwelle.android

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, ComponentName(this, TromuwellePlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
            future.addListener({
                runCatching { controller = future.get() }
            }, ContextCompat.getMainExecutor(this))
        }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                TromuwelleScreen((application as TromuwelleApplication).session, controller,
                    onPip = {
                        if (android.os.Build.VERSION.SDK_INT >= 26) {
                            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().build()) }
                        }
                    })
            }
        }
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

@Composable
private fun TromuwelleScreen(session: TromuwelleSession, player: Player?, onPip: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("favorites", 0) }
    val favorites = remember {
        mutableStateListOf<String>().apply { addAll(prefs.getStringSet("items", emptySet()).orEmpty()) }
    }
    val catalog = remember(session) { TromuwelleCatalog(session) }
    var server by remember { mutableStateOf(session.server) }
    var username by remember { mutableStateOf(session.username) }
    var password by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    var accountPage by remember { mutableStateOf(false) }
    var managementPage by remember { mutableStateOf(false) }
    var setupRequired by remember { mutableStateOf(false) }
    var setupToken by remember { mutableStateOf("") }
    var previousAdminPassword by remember { mutableStateOf("") }
    var adultTag by remember { mutableStateOf("") }
    var mediaSort by remember { mutableStateOf("name") }
    var mediaMinHeight by remember { mutableIntStateOf(0) }
    var connecting by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf(LibrarySection.TV) }
    var browse by remember { mutableStateOf(MusicBrowse.TRACKS) }
    var artist by remember { mutableStateOf("") }
    var artistName by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var albumName by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<LibraryEntry>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var playbackError by remember { mutableStateOf("") }
    var current by remember { mutableStateOf<LibraryEntry?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var compatible by remember { mutableStateOf(false) }
    var quality by remember { mutableIntStateOf(1080) }
    var onlyFavorites by remember { mutableStateOf(false) }
    var loadToken by remember { mutableIntStateOf(0) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
            override fun onPlayerError(errorValue: androidx.media3.common.PlaybackException) {
                playbackError = errorValue.localizedMessage ?: "Wiedergabe fehlgeschlagen"
            }
            override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) {
                if (item != null) playbackError = ""
            }
        }
        player?.addListener(listener)
        isPlaying = player?.isPlaying ?: false
        onDispose { player?.removeListener(listener) }
    }

    fun refresh(append: Boolean = false) {
        val token = ++loadToken
        val targetSection = section
        val targetBrowse = browse
        val targetArtist = artist
        val targetAlbum = album
        val targetFolder = folder
        val targetSearch = search.trim()
        val nextOffset = if (append) entries.size else 0
        scope.launch {
            loading = true
            try {
                val page = withContext(Dispatchers.IO) {
                    catalog.load(targetSection, targetSearch, targetFolder, targetBrowse, targetArtist, targetAlbum,
                        nextOffset, adultTag, if (targetSection == LibrarySection.PRIVATE && mediaSort == "name")
                            "newest" else mediaSort, mediaMinHeight)
                }
                if (token == loadToken) {
                    entries = if (append) entries + page.items else page.items
                    total = page.total
                    error = ""
                }
            } catch (exception: Exception) {
                if (token == loadToken) {
                    if ((exception as? ServerException)?.status == 401) {
                        connected = false
                        session.logout()
                    }
                    error = exception.message ?: "Die Daten konnten nicht geladen werden."
                }
            } finally {
                if (token == loadToken) loading = false
            }
        }
    }

    fun switchSection(next: LibrarySection) {
        section = next
        search = ""
        artist = ""; artistName = ""; album = ""; albumName = ""
        browse = MusicBrowse.TRACKS
        folder = ""
        adultTag = ""; mediaSort = "name"; mediaMinHeight = 0
        entries = emptyList()
        refresh()
    }

    fun playItem(entry: LibraryEntry) {
        val controller = player ?: run {
            playbackError = "Player wird verbunden. Bitte erneut versuchen."
            return
        }
        try {
            val queue = if (entry.kind == EntryKind.TRACK) entries.filter { it.kind == EntryKind.TRACK }
                .map { it.mediaItem(session) } else listOf(entry.mediaItem(session, compatible, quality))
            val position = if (entry.kind == EntryKind.TRACK)
                entries.filter { it.kind == EntryKind.TRACK }.indexOf(entry).coerceAtLeast(0) else 0
            controller.setMediaItems(queue, position, 0L)
            controller.prepare()
            controller.play()
            current = entry
            playbackError = ""
        } catch (exception: Exception) {
            playbackError = exception.message ?: "Wiedergabe konnte nicht gestartet werden."
        }
    }

    fun toggleFavorite(entry: LibraryEntry) {
        val key = entry.kind.name + ":" + entry.id
        if (key in favorites) favorites.remove(key) else favorites.add(key)
        prefs.edit().putStringSet("items", favorites.toSet()).apply()
    }

    if (connected && accountPage) {
        AccountScreen(session, onBack = { accountPage = false }, onSessionRevoked = {
            player?.stop(); player?.clearMediaItems(); current = null
            connected = false; accountPage = false
        })
        return
    }
    if (connected && managementPage) {
        ManagementScreen(session, onBack = { managementPage = false }, onSourcesChanged = { refresh() })
        return
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                Text("Tromuwelle", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Deine Medien. Dein Server.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!connected) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Mit dem Server verbinden", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(server, { server = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("HTTPS-Serveradresse") }, placeholder = { Text("https://server:8097") },
                    singleLine = true)
                OutlinedTextField(username, { username = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Benutzername") }, singleLine = true)
                OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Passwort") }, visualTransformation = PasswordVisualTransformation(),
                    singleLine = true)
                OutlinedButton(enabled = !connecting && server.startsWith("https://"), onClick = {
                    scope.launch {
                        try {
                            val status = withContext(Dispatchers.IO) { session.status(server) }
                            setupRequired = status.optBoolean("setup_required")
                            error = if (setupRequired) "Ersteinrichtung erforderlich: Administrator anlegen."
                                else "Server erreichbar. Bitte anmelden."
                        } catch (e: Exception) { error = e.message ?: "Prüfung fehlgeschlagen." }
                    }
                }) { Text("Server / Ersteinrichtung prüfen") }
                if (setupRequired) {
                    OutlinedTextField(setupToken, { setupToken = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Einrichtungstoken") },
                        visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(previousAdminPassword, { previousAdminPassword = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("Bisheriges Adminpasswort (optional)") },
                        visualTransformation = PasswordVisualTransformation())
                    Text("Neues Administratorkonto: Passwort mindestens 12 Zeichen.")
                }
                Button(modifier = Modifier.fillMaxWidth(), enabled = !connecting, onClick = {
                    scope.launch {
                        connecting = true; error = ""
                        try {
                            withContext(Dispatchers.IO) {
                                if (setupRequired) session.setup(server, username, password, setupToken, previousAdminPassword)
                                else session.login(server, username, password)
                            }
                            password = ""; setupToken = ""; previousAdminPassword = ""
                            server = session.server
                            connected = true
                            refresh()
                        } catch (e: Exception) { error = e.message ?: "Anmeldung fehlgeschlagen" }
                        finally { connecting = false }
                    }
                }) { Text(if (connecting) "Verbinde …" else "Anmelden") }
                if (connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Für lokale HTTPS-Server muss das CA-Zertifikat auf diesem Gerät als vertrauenswürdig installiert sein.",
                    style = MaterialTheme.typography.bodySmall)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibrarySection.entries.filter { it != LibrarySection.PRIVATE }.forEach { item ->
                    FilterChip(selected = item == section, onClick = { switchSection(item) },
                        label = { Text(item.label) })
                }
                if (session.account.isAdmin) TextButton(onClick = { managementPage = true }) {
                    Text("Verwaltung")
                }
                TextButton(onClick = { accountPage = true }) { Text("Konto") }
                TextButton(onClick = {
                    player?.stop(); player?.clearMediaItems(); current = null
                    scope.launch {
                        withContext(Dispatchers.IO) { session.logout() }
                        connected = false
                    }
                }) { Text("Abmelden") }
            }

            if (current != null || player?.currentMediaItem != null) {
                Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val now = player?.mediaMetadata?.title?.toString().orEmpty()
                            .ifBlank { current?.title.orEmpty() }
                        Text(now, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                        if (current?.isVideo() == true && !fullscreen) {
                            AndroidView(factory = { ctx -> PlayerView(ctx).apply {
                                useController = true; keepScreenOn = true
                            } }, update = { it.player = player }, modifier = Modifier.fillMaxWidth().height(210.dp))
                        }
                        if (player != null) NativePlayerControls(player,
                            isLive = current?.kind == EntryKind.TV,
                            isAudio = current?.isVideo() != true,
                            onFullscreen = { fullscreen = true }, onPip = onPip)
                        if (playbackError.isNotBlank()) {
                            Text(playbackError, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                            if (current?.kind == EntryKind.FILE && !compatible) {
                                Button(onClick = {
                                    compatible = true
                                    current?.let { playItem(it) }
                                }) { Text("Kompatible Wiedergabe versuchen") }
                            }
                        }
                        TextButton(onClick = {
                            player?.stop(); player?.clearMediaItems()
                            current = null; playbackError = ""
                        }) { Text("Wiedergabe schließen") }
                    }
                }
            }
            if (section == LibrarySection.MUSIC) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MusicBrowse.entries.forEach { item ->
                        FilterChip(selected = item == browse, onClick = {
                            browse = item; artist = ""; album = ""; artistName = ""; albumName = ""; search = ""; refresh()
                        }, label = { Text(item.label) })
                    }
                }
                if (artist.isNotBlank() || album.isNotBlank()) {
                    TextButton(onClick = {
                        if (album.isNotBlank()) { album = ""; albumName = ""; browse = MusicBrowse.ALBUMS }
                        else { artist = ""; artistName = ""; browse = MusicBrowse.ARTISTS }
                        refresh()
                    }) { Text("‹ Zurück" + listOf(artistName, albumName).filter { it.isNotBlank() }.joinToString(" / ", " · ")) }
                }
            }
            if (section == LibrarySection.MEDIA) {
                TextButton(onClick = { switchSection(LibrarySection.PRIVATE) }) {
                    Text("Private 18+-Filmbibliothek öffnen")
                }
            }
            if (section == LibrarySection.PRIVATE) {
                TextButton(onClick = { switchSection(LibrarySection.MEDIA) }) {
                    Text("‹ Reguläre Mediathek")
                }
                Text("Private 18+-Filme · nur freigegebene Quellen",
                    style = MaterialTheme.typography.titleMedium)
            }
            if (section == LibrarySection.MEDIA && folder.isNotBlank() && search.isBlank()) {
                TextButton(onClick = { folder = folder.substringBeforeLast('/', ""); refresh() }) {
                    Text("‹ Ordner zurück · " + folder)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(search, { search = it }, modifier = Modifier.weight(1f),
                    singleLine = true, label = { Text("Suchen") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { refresh() }))
                TextButton(onClick = { refresh() }) { Text("Los") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = onlyFavorites, onClick = { onlyFavorites = !onlyFavorites },
                    label = { Text("★ Favoriten") })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { refresh() }) { Text("Aktualisieren") }
            }
            if (section == LibrarySection.MEDIA) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !compatible, onClick = { compatible = false },
                        label = { Text("Original") })
                    FilterChip(selected = compatible, onClick = { compatible = true },
                        label = { Text("Kompatibel") })
                    if (compatible) listOf(480,720,1080,2160).forEach { h ->
                        FilterChip(selected = quality == h, onClick = { quality = h },
                            label = { Text("${h}p") })
                    }
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            val shown = if (onlyFavorites) entries.filter { (it.kind.name + ":" + it.id) in favorites } else entries
            if (shown.isEmpty() && !loading && error.isBlank()) {
                Text("Keine Einträge gefunden.", Modifier.padding(20.dp))
            }
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                items(shown, key = { it.kind.name + ":" + it.id }) { entry ->
                    Row(Modifier.fillMaxWidth().clickable {
                        when(entry.kind) {
                            EntryKind.FOLDER -> { folder = entry.path; search = ""; refresh() }
                            EntryKind.ARTIST -> {
                                artist = entry.id; artistName = entry.title; album = ""
                                browse = MusicBrowse.ALBUMS; search = ""; refresh()
                            }
                            EntryKind.ALBUM -> {
                                album = entry.id; albumName = entry.title; browse = MusicBrowse.TRACKS
                                search = ""; refresh()
                            }
                            else -> playItem(entry)
                        }
                    }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        AuthenticatedArtwork(session, entry, Modifier.size(48.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, style = MaterialTheme.typography.titleSmall)
                            if (entry.description.isNotBlank())
                                Text(entry.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        if(entry.playable()) {
                            TextButton(onClick = { toggleFavorite(entry) }) {
                                Text(if ((entry.kind.name + ":" + entry.id) in favorites) "★" else "☆")
                            }
                        }
                    }
                    HorizontalDivider()
                }
                if (!onlyFavorites && !loading && entries.size < total &&
                    (section == LibrarySection.MUSIC || section == LibrarySection.PRIVATE ||
                        (section == LibrarySection.MEDIA && (search.isNotBlank() || mediaMinHeight > 0 || mediaSort != "name")))) {
                    item {
                        TextButton(onClick = { refresh(append = true) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Weitere laden (${entries.size} / $total)")
                        }
                    }
                }
            }
        }
    }
    if (fullscreen && current?.isVideo() == true) {
        Dialog(onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Black) {
                Column {
                    TextButton(onClick = { fullscreen = false }) { Text("Schließen") }
                    AndroidView(factory = { ctx -> PlayerView(ctx).apply { keepScreenOn = true } },
                        update = { it.player = player }, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
