package de.tromuwelle.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Original Tromuwelle streaming identity: content-first, dark, accessible, no third-party branding. */
private val Night = Color(0xFF090C13)
private val Raised = Color(0xFF17202C)
private val Accent = Color(0xFF69E5CD)
private val Secondary = Color(0xFFACB6CB)

@Composable
fun TromuStreamingTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Accent, onPrimary = Color(0xFF09241F),
        secondary = Color(0xFF9FB4FF), onSecondary = Night,
        background = Night, onBackground = Color(0xFFF5F7FB),
        surface = Color(0xFF111722), onSurface = Color(0xFFF5F7FB),
        surfaceVariant = Raised, onSurfaceVariant = Secondary,
        primaryContainer = Color(0xFF173E3D),
        onPrimaryContainer = Color(0xFFDBFFF5),
        outline = Color(0xFF354253), error = Color(0xFFFF8791)
    )
    MaterialTheme(colorScheme = colors, shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(28.dp)
    ), content = content)
}

enum class StreamTab(val title: String, val icon: ImageVector) {
    HOME("Start", Icons.Rounded.Home),
    MUSIC("Musik", Icons.Rounded.LibraryMusic),
    VIDEO("Videos", Icons.Rounded.Movie),
    RADIO("Radio", Icons.Rounded.Radio),
    LIVE("Live-TV", Icons.Rounded.LiveTv)
}

@Composable
fun StreamScaffold(
    session: TromuwelleSession,
    tab: StreamTab,
    onTab: (StreamTab) -> Unit,
    onSearch: () -> Unit,
    onOutput: () -> Unit,
    onProfile: () -> Unit,
    current: LibraryEntry?,
    showPrivatePlayback: Boolean = true,
    player: Player?,
    isPlaying: Boolean,
    onPlayer: () -> Unit,
    onVideo: () -> Unit,
    onClosePlayer: () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    // Larger Android tablets use a left rail, leaving the whole vertical
    // viewport for the actual media. Compact phones retain thumb navigation.
    val configuration = LocalConfiguration.current
    val expandedNavigation = configuration.screenWidthDp >= 720 &&
        configuration.screenHeightDp >= 520
    var playbackProgress by remember(player) { mutableFloatStateOf(0f) }
    LaunchedEffect(player) {
        while (isActive) {
            val duration = player?.duration ?: -1L
            val position = player?.currentPosition ?: 0L
            playbackProgress = if (duration in 1..Long.MAX_VALUE)
                (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                else 0f
            delay(600)
        }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(Accent, Color(0xFF6989FF)))),
                    contentAlignment = Alignment.Center) {
                    Text("T", color = Night, fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.headlineSmall)
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("TROMUWELLE", fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.titleMedium)
                    Text(if (tab == StreamTab.HOME) "Deine Medien. Ein Zuhause."
                         else tab.title, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onSearch) {
                    Icon(Icons.Rounded.Search, contentDescription = "Alle Medien durchsuchen")
                }
                IconButton(onClick = onOutput) {
                    Icon(Icons.Rounded.Cast, contentDescription = "Audioausgang und Cast wählen")
                }
                IconButton(onClick = onProfile) {
                    Icon(Icons.Rounded.AccountCircle, contentDescription = "Konto und Verwaltung")
                }
            }
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                if (showPrivatePlayback &&
                    (current != null || player?.currentMediaItem != null)) {
                    val title = player?.mediaMetadata?.title?.toString().orEmpty()
                        .ifBlank { current?.title.orEmpty() }
                    val video = current?.isVideo() == true
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(onClick = if (video) onVideo else onPlayer),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        tonalElevation = 2.dp
                    ) {
                        Column {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                                .background(Accent.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center) {
                                if (current != null)
                                    AuthenticatedArtwork(session, current, Modifier.fillMaxSize())
                                else
                                    Icon(Icons.Rounded.GraphicEq,
                                        contentDescription = null, tint = Accent)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(title.ifBlank { "Wiedergabe" }, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(if (video) "Video öffnen" else current?.description.orEmpty()
                                    .ifBlank { "Jetzt läuft" }, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = onOutput) {
                                Icon(Icons.Rounded.Speaker, contentDescription = "Audioausgabe wählen")
                            }
                            IconButton(onClick = {
                                if (player?.isPlaying == true) player.pause() else player?.play()
                            }) {
                                Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Wiedergeben")
                            }
                            IconButton(onClick = onClosePlayer) {
                                Icon(Icons.Rounded.Close, contentDescription = "Player schließen")
                            }
                        }
                        if (playbackProgress > 0f && current?.kind !in
                            listOf(EntryKind.RADIO, EntryKind.TV)) {
                            LinearProgressIndicator(progress = { playbackProgress },
                                modifier = Modifier.fillMaxWidth().height(3.dp),
                                color = Accent,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant)
                        }
                        }
                    }
                }
                if (!expandedNavigation) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 4.dp) {
                        StreamTab.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = tab == destination,
                                onClick = { onTab(destination) },
                                icon = { Icon(destination.icon, contentDescription = null) },
                                label = { Text(destination.title, maxLines = 1) },
                                alwaysShowLabel = true
                            )
                        }
                    }
                }
            }
        }, content = { innerPadding ->
            if (expandedNavigation) {
                Row(Modifier.fillMaxSize().padding(innerPadding)) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surface,
                        header = {
                            Box(Modifier.padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Waves,
                                    contentDescription = "Tromuwelle",
                                    tint = Accent, modifier = Modifier.size(30.dp))
                            }
                        }) {
                        StreamTab.entries.forEach { destination ->
                            NavigationRailItem(
                                selected = tab == destination,
                                onClick = { onTab(destination) },
                                icon = { Icon(destination.icon, contentDescription = null) },
                                label = { Text(destination.title, maxLines = 1) },
                                alwaysShowLabel = true
                            )
                        }
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    Box(Modifier.weight(1f).fillMaxHeight()) { content(PaddingValues(0.dp)) }
                }
            } else {
                content(innerPadding)
            }
        })
}

@Composable
fun StreamSectionTitle(title: String, subtitle: String, icon: ImageVector,
                       onRefresh: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 17.dp, end = 12.dp, top = 16.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(47.dp).clip(RoundedCornerShape(15.dp))
            .background(Accent.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Accent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.ExtraBold,
                style = MaterialTheme.typography.headlineSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onRefresh != null) IconButton(onClick = onRefresh) {
            Icon(Icons.Rounded.Refresh, contentDescription = "Aktualisieren")
        }
    }
}

@Composable
fun StreamPosterCard(session: TromuwelleSession, entry: LibraryEntry,
                     onClick: () -> Unit, modifier: Modifier = Modifier,
                     wide: Boolean = false) {
    Card(onClick = onClick, modifier = modifier,
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (wide) 1.7f else 1f)
            .clip(RoundedCornerShape(14.dp))) {
            AuthenticatedArtwork(session, entry, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.50f)))))
            if (entry.height >= 720) {
                Text(if (entry.height >= 2160) "4K" else if (entry.height >= 1080) "1080p" else "HD",
                    modifier = Modifier.align(Alignment.TopEnd).padding(7.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
            if (entry.playable()) Icon(Icons.Rounded.PlayCircleFilled,
                contentDescription = null, tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(9.dp).size(25.dp))
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            Text(entry.title, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            Text(entry.description.ifBlank { when (entry.kind) {
                EntryKind.FOLDER -> "Sammlung"
                EntryKind.TV -> "Live"
                EntryKind.RADIO -> "Sender"
                else -> "Tromuwelle"
            } }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun StreamTrackRow(session: TromuwelleSession, entry: LibraryEntry,
                   favorite: Boolean, onOpen: () -> Unit, onFavorite: () -> Unit,
                   onDownload: (() -> Unit)? = null, onInfo: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen)
        .padding(start = 16.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        AuthenticatedArtwork(session, entry,
            Modifier.size(54.dp).clip(RoundedCornerShape(11.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            Text(entry.description.ifBlank { entry.kind.name.lowercase() },
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (entry.playable()) {
            IconButton(onClick = onFavorite, modifier = Modifier.size(42.dp)) {
                Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (favorite) "Favorit entfernen" else "Als Favorit speichern",
                    tint = if (favorite) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onDownload != null) IconButton(onClick = onDownload,
            modifier = Modifier.size(42.dp)) {
            Icon(Icons.Rounded.Download, contentDescription = "Datei herunterladen",
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onInfo != null) IconButton(onClick = onInfo,
            modifier = Modifier.size(42.dp)) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "Dateiinformationen")
        }
    }
}

@Composable
fun StreamLibraryListing(
    session: TromuwelleSession,
    entries: List<LibraryEntry>,
    section: LibrarySection,
    loading: Boolean,
    total: Int,
    more: Boolean,
    onOpen: (LibraryEntry) -> Unit,
    onFavorite: (LibraryEntry) -> Unit,
    isFavorite: (LibraryEntry) -> Boolean,
    onDownload: (LibraryEntry) -> Unit,
    onInfo: (LibraryEntry) -> Unit,
    onLoadMore: () -> Unit
) {
    if (entries.isEmpty() && !loading) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.FilterListOff, null, modifier = Modifier.size(38.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Hier ist noch nichts zu sehen", fontWeight = FontWeight.Bold)
                Text("Prüfe deine Suche, die Filter oder die Medienquellen des Servers.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }
    val grid = section == LibrarySection.MEDIA || section == LibrarySection.PRIVATE ||
        section == LibrarySection.TV || section == LibrarySection.RADIO ||
        entries.firstOrNull()?.kind in listOf(EntryKind.ALBUM, EntryKind.ARTIST)
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val columns = when {
        screenWidth >= 900 -> 4
        screenWidth >= 600 -> 3
        else -> 2
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(if (grid) 12.dp else 0.dp)) {
        if (grid) {
            val chunks = entries.chunked(columns)
            items(chunks.size) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    chunks[row].forEach { entry ->
                        StreamPosterCard(session, entry, { onOpen(entry) },
                            Modifier.weight(1f), wide = entry.kind == EntryKind.FILE ||
                                entry.kind == EntryKind.TV)
                    }
                    repeat(columns - chunks[row].size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else {
            items(entries, key = { it.kind.name + ":" + it.id }) { entry ->
                StreamTrackRow(session, entry, isFavorite(entry), { onOpen(entry) },
                    { onFavorite(entry) },
                    if (entry.kind == EntryKind.TRACK || entry.kind == EntryKind.FILE)
                        ({ onDownload(entry) }) else null,
                    if (entry.kind == EntryKind.FILE) ({ onInfo(entry) }) else null)
            }
        }
        if (more && !loading && entries.size < total) item {
            TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()
                .padding(vertical = 6.dp)) {
                Text("Weitere laden  ·  ${entries.size} von $total")
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Rounded.ExpandMore, null)
            }
        }
    }
}

/** Server API is the single source of truth for home rails; no invented content or rankings. */
private data class HomeSnapshot(
    val albums: List<LibraryEntry> = emptyList(),
    val tracks: List<LibraryEntry> = emptyList(),
    val movies: List<LibraryEntry> = emptyList(),
    val radio: List<LibraryEntry> = emptyList(),
    val tv: List<LibraryEntry> = emptyList(),
    val podcasts: List<LibraryEntry> = emptyList(),
    val podcastEpisodes: List<org.json.JSONObject> = emptyList()
)

@Composable
fun StreamHome(session: TromuwelleSession, recentRadioIds: List<String>,
               favorites: Set<String>, current: LibraryEntry?,
               onOpen: (LibraryEntry) -> Unit,
               onSection: (LibrarySection) -> Unit,
               onPodcasts: () -> Unit,
               onPodcast: (org.json.JSONObject) -> Unit) {
    var snapshot by remember(session.server, session.username) { mutableStateOf(HomeSnapshot()) }
    var loading by remember(session.server, session.username) { mutableStateOf(true) }
    var failed by remember(session.server, session.username) { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    val homeContext = LocalContext.current
    val progressPrefs = remember(homeContext) {
        homeContext.getSharedPreferences("favorites", 0)
    }
    LaunchedEffect(session, session.server, session.username, generation) {
        loading = true; failed = false
        // These six independent API requests run concurrently over the same
        // cookie/TLS client, so one slow NAS thumbnail/index cannot serialize
        // the entire Home feed. Each rail can fail without hiding the others.
        val next = withContext(Dispatchers.IO) {
            coroutineScope {
                val catalog = TromuwelleCatalog(session)
                val errors = AtomicInteger(0)
                fun safe(section: LibrarySection, browse: MusicBrowse = MusicBrowse.TRACKS,
                         sort: String = "name", max: Int = 18): List<LibraryEntry> =
                    try { catalog.load(section, browse = browse, sort = sort).items.take(max) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { errors.incrementAndGet(); emptyList() }

                val albums = async { safe(LibrarySection.MUSIC, MusicBrowse.ALBUMS) }
                val tracks = async { safe(LibrarySection.MUSIC) }
                val movies = async {
                    safe(LibrarySection.MEDIA, sort = "newest")
                        .filter { it.kind == EntryKind.FILE && !it.audio }
                }
                val radio = async { safe(LibrarySection.RADIO, max = 1500) }
                val television = async { safe(LibrarySection.TV, max = 250) }
                val podcastItems = async {
                    try {
                        TromuPodcasts(session).episodes(limit = 80).first
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        if (e !is ServerException || e.status !in listOf(403, 404)) {
                            errors.incrementAndGet()
                        }
                        emptyList()
                    }
                }
                val episodes = podcastItems.await()
                val snapshot = HomeSnapshot(
                    albums = albums.await(),
                    tracks = tracks.await(),
                    movies = movies.await(),
                    radio = radio.await(),
                    tv = television.await(),
                    podcasts = episodes.map { episode ->
                        LibraryEntry(
                            id = "podcast:" + episode.optString("id"),
                            title = episode.optString("title"),
                            description = episode.optString("feed_title"),
                            kind = EntryKind.TRACK,
                            duration = episode.optLong("duration"),
                            artwork = episode.optString("image")
                                .ifBlank { episode.optString("feed_image") }
                                .takeIf { it.startsWith("https://") } ?: ""
                        )
                    },
                    podcastEpisodes = episodes
                )
                snapshot to errors.get()
            }
        }
        snapshot = next.first
        failed = next.second > 0
        loading = false
    }
    val recent = snapshot.radio.filter { it.id in recentRadioIds }
        .sortedBy { recentRadioIds.indexOf(it.id) }
    val saved = (snapshot.albums + snapshot.tracks + snapshot.movies + snapshot.radio + snapshot.tv)
        .filter { it.kind.name + ":" + it.id in favorites }.distinctBy { it.kind.name + ":" + it.id }
    val inProgress = snapshot.movies.filter { entry ->
        val key = "resume:" + session.server + ":" + session.username + ":FILE:" + entry.id
        val pos = progressPrefs.getLong(key, 0L)
        pos > 30_000 && (entry.duration <= 0 || pos < entry.duration * 1000 - 20_000)
    }
    val resumePodcastIds = snapshot.podcastEpisodes
        .filter { it.optDouble("position") >= 30.0 &&
            it.optInt("completed") == 0 && !it.optBoolean("completed") }
        .map { "podcast:" + it.optString("id") }.toSet()
    val continueListening = snapshot.podcasts.filter { it.id in resumePodcastIds }
    val featured = snapshot.movies.firstOrNull() ?: snapshot.albums.firstOrNull()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
                .height(302.dp).clip(RoundedCornerShape(27.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF173E3D),
                    Color(0xFF242A58), Color(0xFF111722))))) {
                if (featured != null && featured.artwork.isNotBlank()) {
                    AuthenticatedArtwork(session, featured, Modifier.fillMaxSize().alpha(0.57f))
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    0.0f to Color.Black.copy(alpha = 0.05f),
                    0.37f to Color(0xFF0B1019).copy(alpha = 0.34f),
                    1.0f to Color(0xFF070A11).copy(alpha = 0.98f))))
                Column(Modifier.align(Alignment.BottomStart).padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(color = Accent.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(8.dp)) {
                        Text(if (featured != null) "DEINE MEDIEN · AUS DER SAMMLUNG"
                            else "TROMUWELLE · DEIN ZUHAUSE",
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Accent, fontWeight = FontWeight.Bold)
                    }
                    Text(featured?.title ?: "Alles, was du\nsehen & hören willst.",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(featured?.description?.ifBlank { "Neu in deiner Medienbibliothek" }
                        ?: "Musik, Filme, Podcasts und Radio auf deinem eigenen Server.",
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = Color(0xFFDCE2EF),
                        style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { if (featured != null) onOpen(featured)
                                           else onSection(LibrarySection.MEDIA) }) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(5.dp))
                            Text(if (featured != null) "Entdecken" else "Mediathek")
                        }
                        OutlinedButton(onClick = { onSection(LibrarySection.MUSIC) }) {
                            Text("Musik")
                        }
                    }
                }
            }
        }
        if (loading) item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 18.dp))
                Text("Deine Inhalte werden zusammengestellt",
                    Modifier.padding(horizontal = 18.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(4) {
                        Box(Modifier.size(154.dp, 172.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant))
                    }
                }
            }
        }
        item {
            Surface(onClick = onPodcasts,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Headphones, null,
                        modifier = Modifier.size(35.dp), tint = Accent)
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Podcasts & Sendungen", fontWeight = FontWeight.ExtraBold)
                        Text("Abonnements, neue Folgen und Transkripte",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Rounded.ChevronRight, null)
                }
            }
        }
        if (current != null) item {
            StreamRail("Gerade aktiv", listOf(current), session, onOpen)
        }
        if (inProgress.isNotEmpty()) item {
            StreamRail("Weiter ansehen", inProgress, session, onOpen) {
                onSection(LibrarySection.MEDIA)
            }
        }
        if (continueListening.isNotEmpty()) item {
            StreamRail("Podcasts weiterhören", continueListening, session,
                onOpen = { card ->
                    snapshot.podcastEpisodes.firstOrNull {
                        "podcast:" + it.optString("id") == card.id
                    }?.let(onPodcast) ?: onPodcasts()
                }, onMore = onPodcasts)
        }
        if (snapshot.podcasts.isNotEmpty()) item {
            StreamRail("Neue Podcastfolgen", snapshot.podcasts, session,
                onOpen = { card ->
                    snapshot.podcastEpisodes.firstOrNull {
                        "podcast:" + it.optString("id") == card.id
                    }?.let(onPodcast) ?: onPodcasts()
                }, onMore = onPodcasts)
        }
        if (recent.isNotEmpty()) item {
            StreamRail("Zuletzt gehört", recent, session, onOpen) { onSection(LibrarySection.RADIO) }
        }
        if (saved.isNotEmpty()) item {
            StreamRail("Deine Favoriten", saved, session, onOpen)
        }
        if (snapshot.albums.isNotEmpty()) item {
            StreamRail("Alben entdecken", snapshot.albums, session, onOpen) {
                onSection(LibrarySection.MUSIC)
            }
        }
        if (snapshot.movies.isNotEmpty()) item {
            StreamRail("Neu in der Mediathek", snapshot.movies, session, onOpen) {
                onSection(LibrarySection.MEDIA)
            }
        }
        if (snapshot.tracks.isNotEmpty()) item {
            StreamRail("Deine Musiksammlung", snapshot.tracks, session, onOpen) {
                onSection(LibrarySection.MUSIC)
            }
        }
        if (snapshot.radio.isNotEmpty()) item {
            StreamRail("Radio hören", snapshot.radio, session, onOpen) {
                onSection(LibrarySection.RADIO)
            }
        }
        if (snapshot.tv.isNotEmpty()) item {
            StreamRail("Live im Fernsehen", snapshot.tv, session, onOpen) {
                onSection(LibrarySection.TV)
            }
        }
        if (failed && !loading) item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text("Einige Bereiche konnten nicht geladen werden.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { generation++ }) { Text("Erneut versuchen") }
            }
        }
        if (!loading && snapshot == HomeSnapshot()) item {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("Deine Sammlung ist noch leer", fontWeight = FontWeight.SemiBold)
                Text("Sobald Medien im Tromuwelle-Server indiziert sind, erscheinen sie hier.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { generation++ }) { Text("Aktualisieren") }
            }
        }
    }
}

@Composable
private fun StreamRail(
    title: String, entries: List<LibraryEntry>, session: TromuwelleSession,
    onOpen: (LibraryEntry) -> Unit, onMore: (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            if (onMore != null) TextButton(onClick = onMore) { Text("Alle") }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(entries.take(16), key = { it.kind.name + ":" + it.id }) { entry ->
                StreamPosterCard(session, entry, { onOpen(entry) },
                    Modifier.width(if (entry.isVideo()) 206.dp else 158.dp),
                    wide = entry.isVideo())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamVideoDetails(session: TromuwelleSession, entry: LibraryEntry,
                       onDismiss: () -> Unit, onPlay: () -> Unit,
                       onInfo: () -> Unit, onDownload: () -> Unit,
                       onFavorite: () -> Unit, onCast: () -> Unit, favorite: Boolean) {
    val context = LocalContext.current
    val resumeMs = remember(entry.id, session.server, session.username) {
        context.getSharedPreferences("favorites", 0).getLong(
            "resume:" + session.server + ":" + session.username + ":FILE:" + entry.id, 0L)
    }
    val canResume = resumeMs >= 30_000 &&
        (entry.duration <= 0 || resumeMs < entry.duration * 1000L - 20_000)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)) {
            AuthenticatedArtwork(session, entry,
                Modifier.fillMaxWidth().height(206.dp).clip(RoundedCornerShape(18.dp)))
            Text(entry.title, style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold)
            Text(listOfNotNull(
                entry.year.takeIf { it > 0 }?.toString(),
                entry.height.takeIf { it > 0 }?.let { "${it}p" },
                entry.duration.takeIf { it > 0 }?.let { "${it / 60} min" }
            ).joinToString(" · ").ifBlank { "Deine Mediathek" },
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (canResume && entry.duration > 0) {
                LinearProgressIndicator(
                    progress = { (resumeMs.toFloat() / (entry.duration * 1000f))
                        .coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(5.dp),
                    color = MaterialTheme.colorScheme.primary)
                Text("Zuletzt bei " + (resumeMs / 60_000L) + " Min. gestoppt",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onPlay, modifier = Modifier.fillMaxWidth()) {
                Icon(if (canResume) Icons.Rounded.PlayCircle else Icons.Rounded.PlayArrow,
                    contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (canResume) "Film fortsetzen" else "Jetzt abspielen")
            }
            OutlinedButton(onClick = onCast, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Cast, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Auf Cast-Gerät abspielen")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = onFavorite) {
                    Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null)
                    Spacer(Modifier.width(5.dp))
                    Text(if (favorite) "Gemerkt" else "Merken")
                }
                TextButton(onClick = onInfo) {
                    Icon(Icons.Rounded.Info, null)
                    Spacer(Modifier.width(5.dp))
                    Text("Details")
                }
                TextButton(onClick = onDownload) {
                    Icon(Icons.Rounded.Download, null)
                    Spacer(Modifier.width(5.dp))
                    Text("Download")
                }
            }
        }
    }
}
