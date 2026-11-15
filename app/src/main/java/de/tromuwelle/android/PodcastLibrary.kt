package de.tromuwelle.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** All paths/fields mirror backend/app.py's /api/podcasts endpoint contract. */
class TromuPodcasts(private val session: TromuwelleSession) {
    private val root = "/api/podcasts"
    fun feeds(): List<JSONObject> = JSONObject(session.get("$root/feeds"))
        .optJSONArray("items").asObjects()
    fun episodes(feedId: String = "", search: String = "", status: String = "all",
                 offset: Int = 0, limit: Int = 60): Pair<List<JSONObject>, Int> {
        val path = "$root/episodes?feed_id=${session.query(feedId)}&q=${session.query(search)}" +
            "&status=${session.query(status)}&limit=$limit&offset=$offset"
        val response = JSONObject(session.get(path))
        return response.optJSONArray("items").asObjects() to response.optInt("total")
    }
    fun directory(q: String): List<JSONObject> =
        JSONObject(session.get("$root/directory?q=${session.query(q)}"))
            .optJSONArray("items").asObjects()
    fun subscribe(url: String) {
        require(url.startsWith("https://") || url.startsWith("http://")) {
            "Eine gültige öffentliche RSS- oder Atom-URL eingeben."
        }
        session.post("$root/feeds", JSONObject().put("url", url).toString())
    }
    fun remove(feedId: String) { session.delete("$root/feeds/${session.query(feedId)}") }
    fun refresh(feedId: String = "") {
        session.post("$root/refresh?feed_id=${session.query(feedId)}")
    }
    fun download(id: String) { session.post("$root/episodes/${session.query(id)}/download") }
    fun progress(id: String, positionSeconds: Double, completed: Boolean = false) {
        session.put("$root/episodes/${session.query(id)}/progress",
            JSONObject().put("position", positionSeconds.coerceIn(0.0, 1_000_000.0))
                .put("completed", completed).toString())
    }
    fun transcript(id: String, offset: Int = 0, limit: Int = 80): JSONObject =
        JSONObject(session.get("$root/episodes/${session.query(id)}/transcript?offset=$offset&limit=$limit"))
    fun mediaItem(episode: JSONObject): MediaItem {
        val id = episode.getString("id")
        val mime = episode.optString("mime")
        return MediaItem.Builder()
            .setMediaId("PODCAST:$id")
            .setUri(session.url("$root/episodes/${session.query(id)}/stream?codec=original"))
            .setMimeType(if (mime.startsWith("audio/mpeg")) MimeTypes.AUDIO_MPEG
                else if (mime.contains("mp4") || mime.contains("aac")) MimeTypes.AUDIO_MP4
                else if (mime.contains("ogg")) MimeTypes.AUDIO_OGG
                else null)
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(episode.optString("title"))
                .setArtist(episode.optString("feed_title"))
                .build())
            .build()
    }
}

private fun JSONArray?.asObjects(): List<JSONObject> = if (this == null) emptyList()
    else (0 until length()).mapNotNull { optJSONObject(it) }

private fun episodeArtwork(episode: JSONObject): String {
    val value = episode.optString("image").ifBlank { episode.optString("feed_image") }
    return value.takeIf { it.startsWith("https://") } ?: ""
}

private fun podcastEntry(data: JSONObject, isFeed: Boolean): LibraryEntry =
    LibraryEntry(data.optString("id"),
        data.optString("title"),
        if (isFeed) "${data.optInt("episodes")} Folgen"
        else data.optString("feed_title"),
        if (isFeed) EntryKind.ALBUM else EntryKind.TRACK,
        artwork = (if (isFeed) data.optString("image") else episodeArtwork(data))
            .takeIf { it.startsWith("https://") } ?: "",
        duration = if (isFeed) 0 else data.optLong("duration"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamPodcasts(session: TromuwelleSession, onBack: () -> Unit,
                   onPlay: (JSONObject) -> Unit) {
    val api = remember(session) { TromuPodcasts(session) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("shows") }
    var feeds by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var episodes by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var selectedFeed by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var directoryQuery by remember { mutableStateOf("") }
    var directoryResults by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var rss by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("all") }
    var offset by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    var removeFeed by remember { mutableStateOf<JSONObject?>(null) }

    fun action(success: String, work: () -> Unit) {
        scope.launch {
            busy = true; message = ""
            try {
                withContext(Dispatchers.IO) { work() }
                message = success
                retry++
            } catch (e: Exception) {
                message = e.message ?: "Aktion fehlgeschlagen."
            } finally { busy = false }
        }
    }
    LaunchedEffect(retry) {
        try { feeds = withContext(Dispatchers.IO) { api.feeds() } }
        catch (e: Exception) { message = e.message ?: "Podcasts nicht erreichbar." }
    }
    LaunchedEffect(tab, selectedFeed, search, status, offset, retry) {
        if (tab != "episodes") return@LaunchedEffect
        busy = true
        try {
            val data = withContext(Dispatchers.IO) {
                api.episodes(selectedFeed, search.trim(), status, offset)
            }
            episodes = if (offset == 0) data.first else episodes + data.first
            total = data.second
        } catch (e: Exception) { message = e.message ?: "Folgen konnten nicht geladen werden." }
        finally { busy = false }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (selectedFeed.isNotBlank() && tab == "episodes") {
                    selectedFeed = ""; tab = "shows"
                } else onBack()
            }) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Zurück") }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Podcasts", fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.headlineSmall)
                Text("${feeds.size} abonnierte Sendungen", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { action("Aktualisierung gestartet.") { api.refresh() } }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Podcasts aktualisieren")
            }
        }
        Row(Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple("shows", "Sendungen", Icons.Rounded.LibraryMusic),
                Triple("episodes", "Folgen", Icons.Rounded.Headphones),
                Triple("directory", "Entdecken", Icons.Rounded.Search)).forEach { (key, label, icon) ->
                FilterChip(selected = tab == key, onClick = {
                    tab = key; if (key == "episodes") { selectedFeed = ""; offset = 0 }
                }, leadingIcon = { Icon(icon, null, modifier = Modifier.size(17.dp)) },
                    label = { Text(label) })
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotBlank()) {
            Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                color = if (message.contains("fehl") || message.contains("nicht"))
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        when (tab) {
            "shows" -> {
                if (feeds.isEmpty() && !busy) {
                    Column(Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Noch keine Podcasts abonniert", fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge)
                        Text("Finde neue Sendungen oder füge einen RSS-Feed hinzu.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { tab = "directory" }) { Text("Podcasts entdecken") }
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(feeds.chunked(2)) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { feed ->
                                Column(Modifier.weight(1f)) {
                                    StreamPosterCard(session, podcastEntry(feed, true),
                                        onClick = {
                                            selectedFeed = feed.optString("id")
                                            tab = "episodes"; offset = 0
                                        }, modifier = Modifier.fillMaxWidth())
                                    TextButton(onClick = { removeFeed = feed }) {
                                        Icon(Icons.Rounded.DeleteOutline, null,
                                            modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Abo entfernen")
                                    }
                                    if (feed.optString("last_error").isNotBlank()) {
                                        Text(feed.optString("last_error"), maxLines = 2,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            "directory" -> {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Podcasts entdecken", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                    OutlinedTextField(value = directoryQuery,
                        onValueChange = { directoryQuery = it.take(100) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Sendung oder Thema suchen") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) })
                    Button(enabled = directoryQuery.isNotBlank() && !busy,
                        onClick = {
                            scope.launch {
                                busy = true; message = ""
                                try { directoryResults = withContext(Dispatchers.IO) {
                                    api.directory(directoryQuery.trim())
                                } } catch (e: Exception) {
                                    message = e.message ?: "Verzeichnissuche fehlgeschlagen."
                                } finally { busy = false }
                            }
                        }) { Text("Im Verzeichnis suchen") }
                    OutlinedTextField(value = rss, onValueChange = { rss = it.take(2048) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("RSS- oder Atom-Feed-URL") }, singleLine = true)
                    OutlinedButton(enabled = rss.isNotBlank() && !busy, onClick = {
                        action("Feed wird hinzugefügt.") { api.subscribe(rss.trim()) }
                        rss = ""
                    }) {
                        Icon(Icons.Rounded.Add, null)
                        Text("Feed abonnieren")
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                    items(directoryResults, key = { it.optString("url") }) { item ->
                        val already = feeds.any { it.optString("url") == item.optString("url") }
                        Row(Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.optString("title"), fontWeight = FontWeight.Bold)
                                Text(item.optString("author"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(enabled = !already && !busy, onClick = {
                                action("Abonnement wird eingerichtet.") {
                                    api.subscribe(item.optString("url"))
                                }
                            }) { Text(if (already) "Abonniert" else "Abonnieren") }
                        }
                        HorizontalDivider()
                    }
                }
            }
            else -> {
                val feed = feeds.firstOrNull { it.optString("id") == selectedFeed }
                if (feed != null) Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(feed.optString("title"), fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.titleLarge)
                    Text(feed.optString("description"), maxLines = 2,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(search, { search = it.take(200); offset = 0 },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    label = { Text("Folgen durchsuchen") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) })
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("all" to "Alle", "unplayed" to "Nicht gehört",
                        "downloaded" to "Heruntergeladen", "played" to "Gehört")) { (key, title) ->
                        FilterChip(selected = status == key, onClick = {
                            status = key; offset = 0
                        }, label = { Text(title) })
                    }
                }
                Text("$total Folgen", Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
                    items(episodes, key = { it.optString("id") }) { episode ->
                        val percent = if (episode.optDouble("duration") > 0)
                            (episode.optDouble("position") / episode.optDouble("duration"))
                                .toFloat().coerceIn(0f, 1f) else 0f
                        Column(Modifier.fillMaxWidth().clickable { onPlay(episode) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AuthenticatedArtwork(session, podcastEntry(episode, false),
                                    Modifier.size(63.dp).clip(RoundedCornerShape(14.dp)))
                                Column(Modifier.weight(1f)) {
                                    Text(episode.optString("title"), fontWeight = FontWeight.Bold,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(episode.optString("feed_title"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(listOfNotNull(
                                        episode.optInt("duration").takeIf { it > 0 }?.let { "${it / 60} Min." },
                                        if (episode.optInt("completed") == 1) "Gehört" else null,
                                        if (episode.optString("transcript_url").isNotBlank())
                                            "Transkript im Player" else null
                                    ).joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { onPlay(episode) }) {
                                    Icon(Icons.Rounded.PlayCircleFilled,
                                        contentDescription = "Folge abspielen",
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            if (percent > 0f) LinearProgressIndicator(
                                progress = { percent }, modifier = Modifier.fillMaxWidth()
                                    .padding(top = 7.dp).height(3.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(enabled = !busy, onClick = {
                                    action("Serverdownload gestartet.") {
                                        api.download(episode.optString("id"))
                                    }
                                }) { Text("Auf Server laden") }
                            }
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                    if (!busy && episodes.size < total) item {
                        TextButton(onClick = { offset += 60 },
                            modifier = Modifier.fillMaxWidth()) {
                            Text("Weitere Folgen (${episodes.size} / $total)")
                        }
                    }
                }
            }
        }
    }
    removeFeed?.let { feed ->
        AlertDialog(onDismissRequest = { removeFeed = null },
            title = { Text("Abonnement entfernen?") },
            text = { Text("Die Sendung „${feed.optString("title")}“ und ihre vom Server " +
                "geladenen Episodendateien werden entfernt.") },
            confirmButton = { Button(onClick = {
                val id = feed.optString("id")
                removeFeed = null
                action("Abonnement entfernt.") { api.remove(id) }
            }) { Text("Entfernen") } },
            dismissButton = { TextButton(onClick = { removeFeed = null }) {
                Text("Abbrechen")
            } })
    }
}

/** Large podcast transcripts belong in the player, never on the show/episode list. */
@Composable
fun StreamPodcastTranscript(session: TromuwelleSession, episode: JSONObject,
                            player: Player?, onClose: () -> Unit) {
    val api = remember(session) { TromuPodcasts(session) }
    val id = episode.optString("id")
    var offset by remember(id) { mutableIntStateOf(0) }
    var transcript by remember(id, offset) { mutableStateOf<JSONObject?>(null) }
    var error by remember(id, offset) { mutableStateOf("") }
    var activeSeconds by remember { mutableDoubleStateOf(0.0) }
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(id, offset) {
        error = ""; transcript = null
        try { transcript = withContext(Dispatchers.IO) { api.transcript(id, offset) } }
        catch (e: Exception) { error = e.message ?: "Transkript nicht verfügbar." }
    }
    LaunchedEffect(player, id) {
        while (isActive) {
            activeSeconds = if (player?.currentMediaItem?.mediaId == "PODCAST:$id")
                player.currentPosition.coerceAtLeast(0L) / 1000.0 else 0.0
            delay(750)
        }
    }
    val cues = transcript?.optJSONArray("items").asObjects()
    // Cues contain start/end in seconds; plain-text transcripts have no timestamps.
    val activeIndex = cues.indexOfLast { it.has("start") && !it.isNull("start") &&
        it.optDouble("start") <= activeSeconds }
    LaunchedEffect(activeSeconds, follow, transcript) {
        if (!follow || cues.isEmpty() || activeIndex < 0) return@LaunchedEffect
        val last = cues.lastOrNull()?.optDouble("end", -1.0) ?: -1.0
        if (transcript?.optBoolean("has_more") == true &&
            last > 0 && activeSeconds > last + 2) offset += 80
    }
    Column(Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.ArrowBack, "Zum Player") }
            Column(Modifier.weight(1f)) {
                Text("Transkript", fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge)
                Text(episode.optString("title"), maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilterChip(selected = follow, onClick = { follow = !follow },
                label = { Text("Live folgen") })
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (transcript == null && error.isBlank())
            LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(11.dp)) {
            items(cues.size) { index ->
                val cue = cues[index]
                val highlighted = index == activeIndex
                Text(cue.optString("text"), modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .clickable(enabled = !cue.isNull("start") && cue.has("start")) {
                        if (player?.currentMediaItem?.mediaId == "PODCAST:$id") {
                            player.seekTo((cue.optDouble("start") * 1000).toLong().coerceAtLeast(0L))
                        }
                    }.padding(11.dp),
                    style = if (highlighted) MaterialTheme.typography.titleMedium
                            else MaterialTheme.typography.bodyLarge,
                    color = if (highlighted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(enabled = offset > 0, onClick = { offset = (offset - 80).coerceAtLeast(0) }) {
                Text("Frühere")
            }
            Text("${offset + 1} – ${offset + cues.size} / ${transcript?.optInt("total") ?: 0}",
                style = MaterialTheme.typography.labelSmall)
            OutlinedButton(enabled = transcript?.optBoolean("has_more") == true,
                onClick = { offset += 80 }) { Text("Weitere") }
        }
    }
}
