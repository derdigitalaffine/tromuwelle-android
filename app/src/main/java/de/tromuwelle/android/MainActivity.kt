package de.tromuwelle.android

import android.app.PictureInPictureParams
import android.app.SearchManager
import android.provider.MediaStore
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject
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
                playVoiceSearch(intent)
            }, ContextCompat.getMainExecutor(this))
        }
        setContent {
            TromuStreamingTheme {
                TromuwelleScreen((application as TromuwelleApplication).session, controller,
                    onPip = {
                        if (BuildConfig.FLAVOR == "mobile" && android.os.Build.VERSION.SDK_INT >= 26) {
                            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().build()) }
                        }
                    })
            }
        }
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        playVoiceSearch(newIntent)
    }

    /** Android Auto / Assistant can play an audio item by spoken search.
     * No video is ever selected by a voice action.
     */
    private fun playVoiceSearch(incoming: Intent?) {
        if (incoming?.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        val query = incoming.getStringExtra(SearchManager.QUERY)
            .orEmpty().trim().take(120)
        if (query.isBlank()) return
        lifecycleScope.launch {
            val app = application as TromuwelleApplication
            val chosen = withContext(Dispatchers.IO) {
                try {
                    if (app.session.restoreVerified()) {
                        val catalog = TromuwelleCatalog(app.session)
                        val songs = catalog.load(LibrarySection.MUSIC, search = query).items
                        val hit = songs.firstOrNull { it.kind == EntryKind.TRACK }
                        if (hit != null) return@withContext hit.mediaItem(app.session)
                        val radio = catalog.load(LibrarySection.RADIO, search = query).items.firstOrNull()
                        if (radio != null) return@withContext radio.mediaItem(app.session)
                    }
                    for (source in SubsonicSourceStore(app).all().take(3)) {
                        val client = SubsonicClient(source)
                        val song = client.songs(query).firstOrNull()
                        if (song != null) return@withContext client.mediaItem(song)
                    }
                    null
                } catch (_: Exception) { null }
            }
            if (chosen != null) {
                controller?.setMediaItem(chosen)
                controller?.prepare()
                controller?.play()
            }
        }
    }

    override fun onStop() {
        // AAOS enforces its parked-only video UI. Additionally stop video as soon
        // as this activity becomes non-visible; do not allow background video.
        if (BuildConfig.FLAVOR == "automotive" && !isChangingConfigurations) {
            val item = controller?.currentMediaItem
            val id = item?.mediaId.orEmpty()
            if (id.startsWith("TV:") ||
                item?.localConfiguration?.mimeType?.startsWith("video/") == true) {
                controller?.pause()
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    var externalPage by remember { mutableStateOf(false) }
    var managementPage by remember { mutableStateOf(false) }
    var setupRequired by remember { mutableStateOf(false) }
    var serverHasAccounts by remember { mutableStateOf<Boolean?>(null) }
    var setupToken by remember { mutableStateOf("") }
    var previousAdminPassword by remember { mutableStateOf("") }
    var adultTag by remember { mutableStateOf("") }
    var mediaSort by remember { mutableStateOf("name") }
    var mediaMinHeight by remember { mutableIntStateOf(0) }
    var infoEntry by remember { mutableStateOf<LibraryEntry?>(null) }
    var downloadEntry by remember { mutableStateOf<LibraryEntry?>(null) }
    var downloadMessage by remember { mutableStateOf("") }
    var adultFacets by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selectedRadioGenre by remember { mutableStateOf("") }
    var selectedRadioCountry by remember { mutableStateOf("") }
    var radioBitrate by remember { mutableIntStateOf(192) }
    var radioCodec by remember { mutableStateOf("mp3") }
    var onlyRecent by remember { mutableStateOf(false) }
    var tvOrder by remember { mutableStateOf("source") }
    val recent = remember { mutableStateListOf<String>() }
    var connecting by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf(LibrarySection.TV) }
    var streamTab by remember { mutableStateOf(StreamTab.HOME) }
    var profileOpen by remember { mutableStateOf(false) }
    var audioSheetOpen by remember { mutableStateOf(false) }
    var globalSearchOpen by remember { mutableStateOf(false) }
    var podcastsOpen by remember { mutableStateOf(false) }
    var activePodcast by remember { mutableStateOf<JSONObject?>(null) }
    var transcriptOpen by remember { mutableStateOf(false) }
    var advancedFiltersOpen by remember { mutableStateOf(false) }
    var videoSelection by remember { mutableStateOf<LibraryEntry?>(null) }
    var browse by remember { mutableStateOf(MusicBrowse.TRACKS) }
    var playlistsPage by remember { mutableStateOf(false) }
    var lyricsEntry by remember { mutableStateOf<LibraryEntry?>(null) }
    var musicSort by remember { mutableStateOf("album") }
    var minBitrate by remember { mutableIntStateOf(0) }
    var onlyLossless by remember { mutableStateOf(false) }
    var musicQuality by remember { mutableStateOf<org.json.JSONObject?>(null) }
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
    var queueEntries by remember { mutableStateOf<List<LibraryEntry>>(emptyList()) }
    var isPlaying by remember { mutableStateOf(false) }
    var radioRetries by remember { mutableIntStateOf(0) }
    var fullscreen by remember { mutableStateOf(false) }
    var videoDetail by remember { mutableStateOf(false) }
    var compatible by remember { mutableStateOf(false) }
    var quality by remember { mutableIntStateOf(1080) }
    var compatStart by remember { mutableStateOf("0") }
    var playbackStartMs by remember { mutableLongStateOf(0L) }
    var onlyFavorites by remember { mutableStateOf(false) }
    var loadToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(connected, session.server, session.username) {
        favorites.clear(); recent.clear()
        if (connected) {
            val prefix = session.server + ":" + session.username
            favorites.addAll(prefs.getStringSet("items:" + prefix, emptySet()).orEmpty())
            recent.addAll(prefs.getString("radioRecent:" + prefix, "").orEmpty()
                .split("|").filter { it.isNotBlank() })
        }
    }
    LaunchedEffect(player) {
        while (isActive) {
            delay(5000)
            val item = player?.currentMediaItem
            val episode = activePodcast
            if (connected && episode != null && item?.mediaId ==
                "PODCAST:" + episode.optString("id") && player != null) {
                val seconds = player.currentPosition.coerceAtLeast(0L) / 1000.0
                val finished = player.playbackState == Player.STATE_ENDED ||
                    (episode.optDouble("duration") > 0 &&
                    seconds >= episode.optDouble("duration") - 12)
                try {
                    withContext(Dispatchers.IO) {
                        TromuPodcasts(session).progress(episode.optString("id"), seconds, finished)
                    }
                } catch (_: Exception) { /* Progress retries at the next interval. */ }
            }
            if (connected && item != null && item.mediaId.isNotBlank() &&
                !item.mediaId.startsWith("TV:") && !item.mediaId.startsWith("RADIO:")) {
                val pos = player.currentPosition + playbackStartMs
                if (pos > 1000) prefs.edit().putLong("resume:" + session.server + ":" +
                    session.username + ":" + item.mediaId, pos).apply()
            }
        }
    }
    LaunchedEffect(connected, section) {
        if (connected && section == LibrarySection.MUSIC) {
            musicQuality = try {
                withContext(Dispatchers.IO) { TromuwelleApi(session).musicQuality() }
            } catch (_: Exception) { null }
        }
        if (connected && section == LibrarySection.PRIVATE) {
            adultFacets = try {
                withContext(Dispatchers.IO) { TromuwelleApi(session).adultFacets().objects() }
            } catch (_: Exception) { emptyList() }
        }
    }

    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val entry = downloadEntry
        if (uri != null && entry != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val path = when(entry.kind) {
                            EntryKind.FILE -> "/api/media/file?path=" + session.query(entry.path) + "&download=true"
                            EntryKind.TRACK -> "/api/music/stream/" + session.query(entry.id) + "?download=true"
                            else -> error("Download ist für diesen Eintrag nicht verfügbar.")
                        }
                        session.saveTo(path, uri)
                    }
                    downloadMessage = "Download erfolgreich abgeschlossen."
                } catch (e: Exception) {
                    downloadMessage = e.message ?: "Download fehlgeschlagen."
                }
                downloadEntry = null
            }
        } else downloadEntry = null
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
            override fun onPlayerError(errorValue: androidx.media3.common.PlaybackException) {
                playbackError = errorValue.localizedMessage ?: "Wiedergabe fehlgeschlagen"
                if (connected && current?.kind == EntryKind.RADIO && radioRetries < 3) {
                    val retryDelay = listOf(1200L, 3500L, 9000L)[radioRetries]
                    radioRetries++
                    scope.launch {
                        delay(retryDelay)
                        if (connected && current?.kind == EntryKind.RADIO) {
                            player?.prepare()
                            player?.play()
                        }
                    }
                }
            }
            override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) {
                if (item != null) {
                    playbackError = ""
                    queueEntries.firstOrNull { it.kind.name + ":" + it.id == item.mediaId ||
                        it.id == item.mediaId }?.let {
                        current = it
                    }
                }
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
                            "newest" else mediaSort, mediaMinHeight,
                        musicSort, minBitrate, onlyLossless)
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
                        withContext(Dispatchers.IO) { session.logout() }
                    }
                    error = exception.message ?: "Die Daten konnten nicht geladen werden."
                }
            } finally {
                if (token == loadToken) loading = false
            }
        }
    }

    fun switchSection(next: LibrarySection) {
        podcastsOpen = false
        globalSearchOpen = false
        section = next
        streamTab = when (next) {
            LibrarySection.MUSIC -> StreamTab.MUSIC
            LibrarySection.RADIO -> StreamTab.RADIO
            LibrarySection.TV -> StreamTab.LIVE
            LibrarySection.MEDIA, LibrarySection.PRIVATE -> StreamTab.VIDEO
        }
        advancedFiltersOpen = false
        onlyFavorites = false
        search = ""
        artist = ""; artistName = ""; album = ""; albumName = ""
        browse = MusicBrowse.TRACKS
        folder = ""
        adultTag = ""; mediaSort = "name"; mediaMinHeight = 0
        selectedRadioGenre = ""; selectedRadioCountry = ""; onlyRecent = false
        entries = emptyList()
        refresh()
    }

    fun playItem(entry: LibraryEntry, fromSeconds: Long = 0L) {
        val controller = player ?: run {
            playbackError = "Player wird verbunden. Bitte erneut versuchen."
            return
        }
        try {
            val saved = if (entry.kind == EntryKind.TRACK || entry.kind == EntryKind.FILE)
                prefs.getLong("resume:" + session.server + ":" + session.username + ":" +
                    entry.kind.name + ":" + entry.id, 0L) else 0L
            val useTranscode = compatible && entry.kind == EntryKind.FILE && !entry.audio
            val startSeconds = if (useTranscode) fromSeconds.takeIf { it > 0 } ?: (saved / 1000) else 0L
            playbackStartMs = if (useTranscode) startSeconds * 1000 else 0L
            val queue = if (entry.kind == EntryKind.TRACK) entries.filter { it.kind == EntryKind.TRACK }
                .map { it.mediaItem(session) } else listOf(entry.mediaItem(session, compatible, quality, startSeconds, radioBitrate, radioCodec))
            queueEntries = if (entry.kind == EntryKind.TRACK)
                entries.filter { it.kind == EntryKind.TRACK } else listOf(entry)
            val position = if (entry.kind == EntryKind.TRACK)
                entries.filter { it.kind == EntryKind.TRACK }.indexOf(entry).coerceAtLeast(0) else 0
            val closeToEnd = entry.duration > 0 && saved >= entry.duration * 1000 - 30_000
            val resume = if (useTranscode || closeToEnd) 0L else saved
            if (entry.kind == EntryKind.FILE && !entry.audio) {
                scope.launch {
                    val item = try {
                        val metadata = withContext(Dispatchers.IO) {
                            TromuwelleApi(session).mediaMetadata(entry.path)
                        }
                        mediaWithSidecarSubtitles(session, queue[0], entry.path, metadata)
                    } catch (_: Exception) { queue[0] }
                    controller.setMediaItems(listOf(item), 0, resume.coerceAtLeast(0L))
                    controller.prepare()
                    controller.play()
                }
            } else {
                controller.setMediaItems(queue, position, resume.coerceAtLeast(0L))
                controller.prepare()
                controller.play()
            }
            current = entry
            videoDetail = entry.isVideo()
            radioRetries = 0
            if (entry.kind == EntryKind.RADIO) {
                recent.remove(entry.id); recent.add(0, entry.id)
                while (recent.size > 15) recent.removeAt(recent.lastIndex)
                prefs.edit().putString("radioRecent:" + session.server + ":" +
                    session.username, recent.joinToString("|")).apply()
            }
            playbackError = ""
        } catch (exception: Exception) {
            playbackError = exception.message ?: "Wiedergabe konnte nicht gestartet werden."
        }
    }

    fun openEntry(entry: LibraryEntry) {
        when (entry.kind) {
            EntryKind.FOLDER -> { folder = entry.path; search = ""; refresh() }
            EntryKind.ARTIST -> {
                artist = entry.id; artistName = entry.title; album = ""
                browse = MusicBrowse.ALBUMS; search = ""; refresh()
            }
            EntryKind.ALBUM -> {
                album = entry.id; albumName = entry.title
                browse = MusicBrowse.TRACKS; search = ""; refresh()
            }
            EntryKind.FILE -> {
                if (entry.isVideo()) videoSelection = entry else playItem(entry)
            }
            else -> playItem(entry)
        }
    }

    fun toggleFavorite(entry: LibraryEntry) {
        val key = entry.kind.name + ":" + entry.id
        if (key in favorites) favorites.remove(key) else favorites.add(key)
        prefs.edit().putStringSet("items:" + session.server + ":" + session.username,
            favorites.toSet()).apply()
    }

    LaunchedEffect(session) {
        if (session.server.isNotBlank()) {
            connecting = true
            try {
                val verified = withContext(Dispatchers.IO) { session.restoreVerified() }
                if (verified) {
                    server = session.server
                    username = session.username
                    connected = true
                    refresh()
                }
            } finally { connecting = false }
        }
    }

    if (externalPage) {
        ExternalSubsonicScreen(player = player, onClose = { externalPage = false },
            onPlay = { source, songs, index ->
                try {
                    val client = SubsonicClient(source)
                    val items = songs.map { client.mediaItem(it) }
                    val chosen = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
                    if (items.isNotEmpty() && player != null) {
                        queueEntries = songs.map { item ->
                            LibraryEntry("ext:" + source.id + ":" + item.optString("id"),
                                item.optString("title"), source.name + " · " + item.optString("artist"),
                                EntryKind.TRACK, item.optString("suffix"), item.optLong("duration"),
                                artwork = if (item.optString("coverArt").isBlank()) "" else
                                    client.coverUrl(item.optString("coverArt")))
                        }
                        player.setMediaItems(items, chosen, 0L)
                        player.prepare()
                        player.play()
                        current = queueEntries[chosen]
                        playbackError = ""
                        playbackStartMs = 0L
                    }
                    externalPage = false
                } catch (e: Exception) {
                    playbackError = e.message ?: "Externer Musikserver konnte nicht abgespielt werden."
                }
            })
        return
    }
    if (connected && playlistsPage) {
        MusicPlaylistsScreen(session, onBack = { playlistsPage = false },
            onPlay = { tracks ->
                val controller = player
                if (controller != null && tracks.isNotEmpty()) {
                    queueEntries = tracks
                    controller.setMediaItems(tracks.map { it.mediaItem(session) })
                    controller.prepare()
                    controller.play()
                    current = tracks.first()
                    playbackStartMs = 0L
                    playbackError = ""
                }
                playlistsPage = false
            })
        return
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

    if (!connected) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Text("Tromuwelle", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Deine Medien. Dein Server.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Mit dem Server verbinden", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(server, { server = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("HTTPS-Serveradresse") }, placeholder = { Text("https://server:8097") },
                    singleLine = true)
                CertificateTrustControl(address = server)
                OutlinedTextField(username, { username = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Benutzername (bei bisherigem Passwortmodus leer lassen)") },
                    singleLine = true)
                Text(when (serverHasAccounts) {
                    true -> "Benutzerverwaltung aktiv: Kontonamen und Passwort eingeben."
                    false -> "Bisheriger Passwortmodus: Es gibt noch keinen Benutzernamen. Feld leer lassen."
                    null -> "Wenn bisher nur ein Passwort vergeben wurde, den Benutzernamen leer lassen."
                }, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Passwort") }, visualTransformation = PasswordVisualTransformation(),
                    singleLine = true)
                OutlinedButton(enabled = !connecting && server.startsWith("https://"), onClick = {
                    scope.launch {
                        try {
                            val status = withContext(Dispatchers.IO) { session.status(server) }
                            setupRequired = status.optBoolean("setup_required")
                            serverHasAccounts = status.optBoolean("managed")
                            error = if (setupRequired) "Ersteinrichtung erforderlich: Administrator anlegen."
                                else "Server erreichbar. Bitte anmelden."
                        } catch (e: Exception) { error = tlsConnectionDiagnostic(e) }
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
                OutlinedButton(modifier = Modifier.fillMaxWidth(),
                    onClick = { externalPage = true }) { Text("Andere Subsonic-Musikserver") }
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
                        } catch (e: Exception) { error = tlsConnectionDiagnostic(e) }
                        finally { connecting = false }
                    }
                }) { Text(if (connecting) "Verbinde …" else "Anmelden") }
                if (connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Selbstsigniertes Zertifikat? Über „Zertifikat prüfen“ den SHA-256-Fingerabdruck " +
                    "mit dem echten Server vergleichen und gezielt freigeben. " +
                    "Alternativ kann das lokale CA-Zertifikat in Android installiert werden.",
                    style = MaterialTheme.typography.bodySmall)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }
    } else {
        StreamScaffold(
            tab = streamTab,
            onTab = { target ->
                globalSearchOpen = false
                podcastsOpen = false
                if (target == StreamTab.HOME) {
                    streamTab = StreamTab.HOME
                } else {
                    val next = when (target) {
                        StreamTab.MUSIC -> LibrarySection.MUSIC
                        StreamTab.VIDEO -> LibrarySection.MEDIA
                        StreamTab.RADIO -> LibrarySection.RADIO
                        StreamTab.LIVE -> LibrarySection.TV
                        StreamTab.HOME -> LibrarySection.TV
                    }
                    switchSection(next)
                }
            },
            onSearch = { globalSearchOpen = true; podcastsOpen = false },
            onProfile = { profileOpen = true },
            current = current,
            player = player,
            isPlaying = isPlaying,
            onPlayer = { audioSheetOpen = true },
            onVideo = { videoDetail = true },
            onClosePlayer = {
                player?.stop(); player?.clearMediaItems()
                current = null; playbackError = ""; audioSheetOpen = false
            }
        ) { innerPadding ->
            if (globalSearchOpen) {
                Box(Modifier.fillMaxSize().padding(innerPadding)) {
                    StreamGlobalSearch(session, onBack = { globalSearchOpen = false },
                        onOpen = { type, entry ->
                            globalSearchOpen = false; switchSection(type); openEntry(entry)
                        },
                        onSection = { switchSection(it); globalSearchOpen = false },
                        onFavorite = { toggleFavorite(it) },
                        isFavorite = { (it.kind.name + ":" + it.id) in favorites })
                }
            } else if (podcastsOpen) {
                Box(Modifier.fillMaxSize().padding(innerPadding)) {
                    StreamPodcasts(session, onBack = { podcastsOpen = false },
                        onPlay = { episode ->
                            try {
                                val ctl = player ?: error("Player wird gestartet, bitte erneut versuchen.")
                                val media = TromuPodcasts(session).mediaItem(episode)
                                ctl.setMediaItem(media)
                                ctl.prepare()
                                val position = (episode.optDouble("position").coerceAtLeast(0.0) * 1000).toLong()
                                if (position > 0) ctl.seekTo(position)
                                ctl.play()
                                activePodcast = episode
                                current = LibraryEntry("podcast:" + episode.optString("id"),
                                    episode.optString("title"), episode.optString("feed_title"),
                                    EntryKind.TRACK, duration = episode.optLong("duration"),
                                    artwork = episode.optString("image").ifBlank {
                                        episode.optString("feed_image") }.takeIf {
                                        it.startsWith("https://") } ?: "")
                                playbackError = ""
                                audioSheetOpen = true
                            } catch (e: Exception) { playbackError = e.message ?: "Abspielen fehlgeschlagen." }
                        })
                }
            } else if (streamTab == StreamTab.HOME) {
                Box(Modifier.fillMaxSize().padding(innerPadding)) {
                    StreamHome(session, recent.toList(), favorites.toSet(), current,
                        onOpen = { openEntry(it) },
                        onSection = { switchSection(it) },
                        onFavorites = { switchSection(LibrarySection.MUSIC); onlyFavorites = true },
                        onPodcasts = {
                            streamTab = StreamTab.MUSIC; podcastsOpen = true
                        })
                }
            } else Column(Modifier.fillMaxSize().padding(innerPadding)) {
                StreamSectionTitle(
                    when (section) {
                        LibrarySection.TV -> "Live-TV"
                        LibrarySection.RADIO -> "Internetradio"
                        LibrarySection.MUSIC -> if (album.isNotBlank()) albumName
                            else if (artist.isNotBlank()) artistName else "Musik"
                        LibrarySection.MEDIA -> "Mediathek"
                        LibrarySection.PRIVATE -> "Private Bibliothek"
                    },
                    when (section) {
                        LibrarySection.TV -> "Deine Fernsehsender"
                        LibrarySection.RADIO -> "Entdecke Sender und Genres"
                        LibrarySection.MUSIC -> "Deine Musik, Alben und Playlists"
                        LibrarySection.MEDIA -> "Filme, Videos und Ordner"
                        LibrarySection.PRIVATE -> "Nur freigegebene private Medien"
                    },
                    streamTab.icon,
                    onRefresh = { refresh() }
                )
                if (section == LibrarySection.MEDIA || section == LibrarySection.PRIVATE) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = {
                            switchSection(if (section == LibrarySection.PRIVATE)
                                LibrarySection.MEDIA else LibrarySection.PRIVATE)
                        }, label = { Text(if (section == LibrarySection.PRIVATE)
                            "Reguläre Mediathek" else "Private 18+-Bibliothek") })
                    }
                }
                if (section == LibrarySection.MUSIC) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        AssistChip(onClick = { podcastsOpen = true },
                            label = { Text("Podcasts") },
                            leadingIcon = {
                                Icon(androidx.compose.material.icons.Icons.Rounded.Headphones,
                                    contentDescription = null, modifier = Modifier.size(17.dp))
                            })
                        AssistChip(onClick = { externalPage = true },
                            label = { Text("Weitere Musikserver") })
                        if (session.account.managed) AssistChip(
                            onClick = { playlistsPage = true },
                            label = { Text("Meine Playlists") })
                    }
                }
                if (playbackError.isNotBlank()) {
                    Text(playbackError, Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.error)
                }
                if (downloadMessage.isNotBlank()) {
                    Text(downloadMessage, Modifier.padding(horizontal = 16.dp))
                    TextButton(onClick = { downloadMessage = "" }) { Text("Schließen") }
                }
                if (current?.kind == EntryKind.FILE && current?.audio == false) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !compatible, onClick = { compatible = false },
                            label = { Text("Original") })
                        FilterChip(selected = compatible, onClick = { compatible = true },
                            label = { Text("Kompatibel") })
                        if (compatible) listOf(480, 720, 1080, 2160).forEach { h ->
                            FilterChip(selected = quality == h, onClick = { quality = h },
                                label = { Text("${h}p") })
                        }
                        TextButton(onClick = { current?.let { playItem(it) } }) { Text("Neu starten") }
                    }
                }

            if (section == LibrarySection.MUSIC) {
                 musicQuality?.let { qualitySummary ->
                    Text("${qualitySummary.optInt("albums")} Alben · " +
                        "${qualitySummary.optInt("artists")} Künstler · " +
                        "${qualitySummary.optInt("lossless")} Lossless",
                        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                }
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
            OutlinedButton(onClick = { advancedFiltersOpen = !advancedFiltersOpen },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp)) {
                Icon(if (advancedFiltersOpen) androidx.compose.material.icons.Icons.Rounded.ExpandLess
                    else androidx.compose.material.icons.Icons.Rounded.Tune,
                    contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (advancedFiltersOpen) "Weniger Filter anzeigen" else "Filter & Sortierung")
            }
            if (advancedFiltersOpen) {
            if (section == LibrarySection.MUSIC && browse == MusicBrowse.TRACKS) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("album", "artist", "title", "newest", "quality", "bitrate", "duration", "track")
                        .forEach { order ->
                            FilterChip(selected = musicSort == order, onClick = {
                                musicSort = order; refresh()
                            }, label = { Text(when (order) {
                                "album" -> "Album/Track"; "artist" -> "Interpret"; "title" -> "Titel"
                                "newest" -> "Neu"; "quality" -> "Qualität"; "bitrate" -> "Bitrate"
                                "duration" -> "Länge"; else -> "Tracknummer"
                            }) })
                        }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 128, 192, 256, 320, 1000).forEach { kbps ->
                        FilterChip(selected = minBitrate == kbps * 1000, onClick = {
                            minBitrate = kbps * 1000; refresh()
                        }, label = { Text(if (kbps == 0) "Alle Bitraten" else "$kbps kbps+") })
                    }
                    FilterChip(selected = onlyLossless, onClick = {
                        onlyLossless = !onlyLossless; refresh()
                    }, label = { Text("Nur Lossless") })
                }
            }
            if (section == LibrarySection.PRIVATE) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = adultTag.isBlank(), onClick = { adultTag = ""; refresh() },
                        label = { Text("Alle Tags") })
                    adultFacets.forEach { facet ->
                        val tag = facet.optString("tag")
                        FilterChip(selected = adultTag == tag, onClick = {
                            adultTag = tag; refresh()
                        }, label = { Text(tag + " (" + facet.optInt("count") + ")") })
                    }
                }
            }
            } // optional advanced filters
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
            if (section == LibrarySection.MEDIA || section == LibrarySection.PRIVATE) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("name", "newest", "quality", "longest").forEach { option ->
                        FilterChip(selected = mediaSort == option, onClick = {
                            mediaSort = option; refresh()
                        }, label = { Text(when(option) {
                            "name" -> "Name"; "newest" -> "Neu"; "quality" -> "Qualität"; else -> "Dauer"
                        }) })
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 720, 1080, 2160).forEach { h ->
                        FilterChip(selected = mediaMinHeight == h, onClick = {
                            mediaMinHeight = h; refresh()
                        }, label = { Text(if (h == 0) "Alle" else h.toString() + "p+") })
                    }
                }
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
            if (section == LibrarySection.RADIO) {
                Text("Server-Transcoding", Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(48, 64, 96, 128, 160, 192, 256, 320).forEach { bitrate ->
                        FilterChip(selected = radioBitrate == bitrate,
                            onClick = { radioBitrate = bitrate }, label = { Text("$bitrate kbit/s") })
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("mp3", "aac", "opus").forEach { codec ->
                        FilterChip(selected = radioCodec == codec, onClick = { radioCodec = codec },
                            label = { Text(codec.uppercase()) })
                    }
                    if (current?.kind == EntryKind.RADIO) {
                        TextButton(onClick = { current?.let { playItem(it) } }) { Text("Anwenden") }
                    }
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            if (section == LibrarySection.RADIO) {
                val genres = entries.map { it.description }.filter { it.isNotBlank() }.distinct().sorted()
                val countries = entries.map { it.country }.filter { it.isNotBlank() }.distinct().sorted()
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = selectedRadioCountry.isBlank(), onClick = {
                        selectedRadioCountry = ""
                    }, label = { Text("🌍 Alle Länder") })
                    countries.forEach { code ->
                        FilterChip(selected = selectedRadioCountry == code, onClick = {
                            selectedRadioCountry = code
                        }, label = { Text(radioCountryLabel(code)) })
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = selectedRadioGenre.isBlank(), onClick = {
                        selectedRadioGenre = ""
                    }, label = { Text("Alle Genres") })
                    genres.forEach { genre ->
                        FilterChip(selected = selectedRadioGenre == genre, onClick = {
                            selectedRadioGenre = genre
                        }, label = { Text(genre) })
                    }
                    FilterChip(selected = onlyRecent, onClick = { onlyRecent = !onlyRecent },
                        label = { Text("Zuletzt gehört") })
                }
            }
            if (section == LibrarySection.TV) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("source", "name", "hd").forEach { order ->
                        FilterChip(selected = tvOrder == order, onClick = { tvOrder = order },
                            label = { Text(when (order) {
                                "name" -> "A–Z"; "hd" -> "HD zuerst"; else -> "Quellreihenfolge"
                            }) })
                    }
                }
            }
            val shown = entries
                .filter { !onlyFavorites || (it.kind.name + ":" + it.id) in favorites }
                .filter { section != LibrarySection.RADIO ||
                    (selectedRadioGenre.isBlank() || it.description == selectedRadioGenre) &&
                    (!onlyRecent || it.id in recent) &&
                    (selectedRadioCountry.isBlank() || it.country == selectedRadioCountry) }
                .let { list -> when {
                    section == LibrarySection.TV && tvOrder == "name" -> list.sortedBy { it.title.lowercase() }
                    section == LibrarySection.TV && tvOrder == "hd" -> list.sortedByDescending { it.description == "HD" }
                    section == LibrarySection.RADIO && onlyRecent -> list.sortedBy { recent.indexOf(it.id) }
                    else -> list
                } }
            StreamLibraryListing(
                session = session,
                entries = shown,
                section = section,
                loading = loading,
                total = total,
                more = !onlyFavorites &&
                    (section == LibrarySection.MUSIC || section == LibrarySection.PRIVATE ||
                        (section == LibrarySection.MEDIA && (search.isNotBlank() ||
                            mediaMinHeight > 0 || mediaSort != "name"))),
                onOpen = { openEntry(it) },
                onFavorite = { toggleFavorite(it) },
                isFavorite = { (it.kind.name + ":" + it.id) in favorites },
                onDownload = {
                    downloadEntry = it
                    savePicker.launch(it.path.substringAfterLast('/').ifBlank { it.title })
                },
                onInfo = { infoEntry = it },
                onLoadMore = { refresh(append = true) }
            )
            } // end streaming content Column
        } // end StreamScaffold
    } // end connected screen
    if (profileOpen && connected) {
        ModalBottomSheet(onDismissRequest = { profileOpen = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Dein Tromuwelle", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold)
                Text(session.account.username.ifBlank { "Gemeinsamer Passwortzugang" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { profileOpen = false; accountPage = true }) {
                    Text("Konto & Sicherheit")
                }
                TextButton(onClick = { profileOpen = false; externalPage = true }) {
                    Text("Weitere Musikserver · OpenSubsonic")
                }
                TextButton(onClick = {
                    profileOpen = false; streamTab = StreamTab.MUSIC; podcastsOpen = true
                }) { Text("Podcasts & Abonnements") }
                if (session.account.managed) TextButton(onClick = {
                    profileOpen = false; playlistsPage = true
                }) { Text("Meine Playlists") }
                if (session.account.isAdmin) TextButton(onClick = {
                    profileOpen = false; managementPage = true
                }) { Text("Serververwaltung") }
                TextButton(onClick = {
                    profileOpen = false; switchSection(LibrarySection.PRIVATE)
                }) { Text("Private 18+-Bibliothek") }
                HorizontalDivider()
                TextButton(onClick = {
                    profileOpen = false
                    player?.stop(); player?.clearMediaItems(); current = null
                    scope.launch {
                        withContext(Dispatchers.IO) { session.logout() }
                        connected = false; streamTab = StreamTab.HOME
                    }
                }) { Text("Abmelden") }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    if (audioSheetOpen && player != null) {
        ModalBottomSheet(onDismissRequest = { audioSheetOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                val playingTitle = player.mediaMetadata.title?.toString()
                    .orEmpty().ifBlank { current?.title.orEmpty() }
                Text("JETZT LÄUFT", color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium)
                Text(playingTitle, style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold)
                Text(current?.description.orEmpty(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                current?.let { item ->
                    AuthenticatedArtwork(session, item, Modifier.fillMaxWidth()
                        .height(250.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(22.dp)))
                }
                NativePlayerControls(player, isLive = current?.kind == EntryKind.RADIO,
                    isAudio = true, onPip = onPip)
                if (activePodcast != null &&
                    player.currentMediaItem?.mediaId == "PODCAST:" + activePodcast?.optString("id")) {
                    if (activePodcast?.optString("transcript_url").orEmpty().isNotBlank()) {
                        Button(onClick = { transcriptOpen = true; audioSheetOpen = false },
                            modifier = Modifier.fillMaxWidth()) {
                            Text("Transkript im Player anzeigen")
                        }
                    }
                    Text("Fortschritt wird automatisch im Tromuwelle-Server gespeichert.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (current?.kind == EntryKind.TRACK &&
                    current?.id?.startsWith("ext:") != true &&
                    current?.id?.startsWith("podcast:") != true) {
                    Button(onClick = { lyricsEntry = current; audioSheetOpen = false },
                        modifier = Modifier.fillMaxWidth()) { Text("Liedtext anzeigen") }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
    videoSelection?.let { entry ->
        StreamVideoDetails(session, entry,
            onDismiss = { videoSelection = null },
            onPlay = { videoSelection = null; playItem(entry) },
            onInfo = { videoSelection = null; infoEntry = entry },
            onDownload = {
                videoSelection = null
                downloadEntry = entry
                savePicker.launch(entry.path.substringAfterLast('/').ifBlank { entry.title })
            },
            onFavorite = { toggleFavorite(entry) },
            favorite = (entry.kind.name + ":" + entry.id) in favorites)
    }
    if (transcriptOpen && activePodcast != null) {
        Dialog(onDismissRequest = { transcriptOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background) {
                StreamPodcastTranscript(session, activePodcast!!, player,
                    onClose = { transcriptOpen = false; audioSheetOpen = true })
            }
        }
    }
    lyricsEntry?.let { entry ->
        MusicLyricsDialog(session, entry, player = player, onDismiss = { lyricsEntry = null })
    }
    infoEntry?.let { entry ->
        MediaInfoDialog(session, entry, section == LibrarySection.PRIVATE,
            onDismiss = { infoEntry = null }, onChanged = { refresh() })
    }
    if ((fullscreen || videoDetail) && current?.isVideo() == true) {
        Dialog(onDismissRequest = { fullscreen = false; videoDetail = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Black) {
                Column(Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(current?.title.orEmpty(), color = androidx.compose.ui.graphics.Color.White,
                            maxLines = 1, modifier = Modifier.weight(1f))
                        TextButton(onClick = { fullscreen = false; videoDetail = false }) {
                            Text("Zur Bibliothek")
                        }
                    }
                    AndroidView(factory = { ctx -> PlayerView(ctx).apply {
                        useController = true; keepScreenOn = true
                    } }, update = { it.player = player }, modifier = Modifier.fillMaxWidth().weight(1f))
                    Surface(color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) {
                            if (player != null) NativePlayerControls(player,
                                isLive = current?.kind == EntryKind.TV, isAudio = false,
                                canSeek = current?.kind != EntryKind.FILE || !compatible,
                                onFullscreen = { fullscreen = true }, onPip = onPip)
                        }
                    }
                }
            }
        }
    }
}

private fun radioCountryLabel(code: String): String = when (code.uppercase()) {
    "DE" -> "🇩🇪 Deutschland"
    "NL" -> "🇳🇱 Niederlande"
    "AT" -> "🇦🇹 Österreich"
    "BE" -> "🇧🇪 Belgien"
    "LU" -> "🇱🇺 Luxemburg"
    else -> code
}
