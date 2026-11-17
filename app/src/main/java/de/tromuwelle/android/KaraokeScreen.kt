package de.tromuwelle.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private enum class KaraokeView(val label: String) {
    READY("Singbereit"), PREPARE("Vorbereiten"), PARTY("Party-Regie")
}
private data class KaraokeSetSong(
    val song: KaraokeSong, val version: KaraokeVersion, val queueId: Long = System.nanoTime()
)
private val karaokeAccent = Color(0xFF8C65DF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KaraokeScreen(session: TromuwelleSession, player: Player?, onBack: () -> Unit) {
    val api = remember(session) { KaraokeApi(session) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf(KaraokeView.READY) }
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var profile by remember { mutableStateOf("all") }
    var sort by remember { mutableStateOf("recent") }
    var offset by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var tracks by remember { mutableStateOf<List<KaraokeSong>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf<KaraokeSetSong?>(null) }
    val solo = remember { mutableStateListOf<KaraokeSetSong>() }
    var partyList by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var partyId by remember { mutableStateOf("") }
    var partyName by remember { mutableStateOf("Meine Karaoke-Party") }
    var singer by remember { mutableStateOf(session.account.username.ifBlank { "Sänger" }) }
    var partyData by remember { mutableStateOf<JSONObject?>(null) }
    var invite by remember { mutableStateOf("") }
    var activePartyItem by remember { mutableStateOf("") }
    var confirmEnd by remember { mutableStateOf(false) }
    val party = partyData?.optJSONObject("party")
    val partyItems = partyData?.optJSONArray("items")?.objectsKaraoke().orEmpty()

    fun action(block: suspend () -> Unit) {
        scope.launch {
            if (busy) return@launch
            busy = true
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "Aktion fehlgeschlagen." }
            finally { busy = false }
        }
    }

    fun openSong(song: KaraokeSong, preferred: String? = null, partyItem: String = "") {
        action {
            val versions = withContext(Dispatchers.IO) { api.versions(song.id) }
            val version = versions.firstOrNull { it.id == preferred }
                ?: versions.firstOrNull { it.profile == preferred }
                ?: versions.firstOrNull { it.profile == "2" }
                ?: versions.firstOrNull()
            if (version == null) {
                notice = "Für diesen Song liegt noch keine fertige Karaoke-Version vor."
            } else {
                activePartyItem = partyItem
                active = KaraokeSetSong(song, version)
                notice = ""
            }
        }
    }

    fun queueSong(song: KaraokeSong, preferred: String? = null) {
        action {
            val versions = withContext(Dispatchers.IO) { api.versions(song.id) }
            val version = versions.firstOrNull { it.profile == preferred }
                ?: versions.firstOrNull { it.profile == "2" }
                ?: versions.firstOrNull()
            if (version != null) {
                if (solo.size >= 100) solo.removeAt(0)
                solo.add(KaraokeSetSong(song, version))
                notice = "„${song.title}“ steht jetzt in deiner Setliste."
            } else notice = "Bitte bereite den Song zuerst vor."
        }
    }

    fun refreshParty(id: String) {
        scope.launch {
            try {
                partyData = withContext(Dispatchers.IO) { api.party(id) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (partyId == id) notice = e.message ?: "Party nicht verfügbar."
            }
        }
    }

    LaunchedEffect(session.server, session.username) {
        try {
            status = withContext(Dispatchers.IO) { api.status() }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { notice = e.message ?: "Karaoke-Server nicht erreichbar." }
        while (isActive) {
            delay(5000)
            try {
                val oldCompleted = status?.optJSONArray("jobs")?.objectsKaraoke()
                    .orEmpty().filter { it.optString("status") == "completed" }
                    .map { it.optString("id") }.toSet()
                val next = withContext(Dispatchers.IO) { api.status() }
                val newCompleted = next.optJSONArray("jobs")?.objectsKaraoke()
                    .orEmpty().filter { it.optString("status") == "completed" }
                    .map { it.optString("id") }.toSet()
                status = next
                if ((newCompleted - oldCompleted).isNotEmpty()) refresh++
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Avoid replacing content with a transient polling error. */ }
        }
    }
    LaunchedEffect(view, query, profile, sort, offset, refresh, status?.optBoolean("enabled")) {
        if (view == KaraokeView.PARTY || status?.optBoolean("enabled") != true) return@LaunchedEffect
        delay(250)
        loading = true
        try {
            val result = withContext(Dispatchers.IO) {
                if (view == KaraokeView.READY) api.ready(query, profile, sort, offset)
                else api.tracks(query, offset)
            }
            tracks = karaokeSongs(result)
            total = result.optInt("total", tracks.size)
            notice = ""
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { notice = e.message ?: "Songliste konnte nicht geladen werden." }
        finally { loading = false }
    }
    LaunchedEffect(view, session.server, session.username, refresh) {
        if (view != KaraokeView.PARTY || status?.optBoolean("enabled") != true) return@LaunchedEffect
        try {
            partyList = withContext(Dispatchers.IO) { api.sessions().objectsKaraoke() }
            if (partyId.isBlank() && partyList.isNotEmpty()) partyId = partyList[0].optString("id")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { notice = e.message ?: "Partys konnten nicht geladen werden." }
    }
    LaunchedEffect(view, partyId, status?.optBoolean("enabled")) {
        if (view != KaraokeView.PARTY || partyId.isBlank() ||
            status?.optBoolean("enabled") != true) return@LaunchedEffect
        while (isActive) {
            try {
                partyData = withContext(Dispatchers.IO) { api.party(partyId) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                notice = e.message ?: "Party nicht verfügbar."
                if (e is ServerException && (e.status == 403 || e.status == 404)) {
                    partyId = ""
                    partyData = null
                    break
                }
            }
            delay(2500)
        }
    }

    BackHandler(active == null) { onBack() }
    if (active != null) {
        val stage = active!!
        key(stage.queueId) {
            KaraokeStage(session, player, stage.song, stage.version,
            onClose = {
                val item = activePartyItem
                val id = partyId
                active = null
                activePartyItem = ""
                if (item.isNotBlank() && id.isNotBlank()) scope.launch {
                    try { withContext(Dispatchers.IO) {
                        api.controlParty(id, JSONObject().put("playing", false)
                            .put("current_item", "").put("position", 0))
                    } } catch (e: Exception) {
                        notice = e.message ?: "Party-Steuerung nicht erreichbar."
                    }
                }
            },
            onFinished = {
                val item = activePartyItem
                val partyForDone = partyId
                val nextPending = if (party?.optString("mode") == "auto")
                    partyItems.firstOrNull { it.optString("state") == "pending" &&
                        it.optString("id") != item } else null
                val index = solo.indexOfFirst { it.queueId == stage.queueId }
                active = if (item.isBlank() && index >= 0)
                    solo.getOrNull(index + 1) else null
                activePartyItem = ""
                if (item.isNotBlank() && partyForDone.isNotBlank()) {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                api.setlistAction(partyForDone, item, "done")
                            }
                            if (nextPending != null) {
                                val song = KaraokeSong(nextPending.optString("track_id"),
                                    nextPending.optString("title"),
                                    nextPending.optString("artist"), "", 0.0)
                                val nextArtifactId = nextPending.optString("artifact_id")
                                val version = withContext(Dispatchers.IO) {
                                    api.versions(song.id).firstOrNull { it.id == nextArtifactId }
                                } ?: error("Nächste Karaoke-Version nicht mehr verfügbar.")
                                withContext(Dispatchers.IO) {
                                    api.setlistAction(partyForDone,
                                        nextPending.optString("id"), "play")
                                }
                                activePartyItem = nextPending.optString("id")
                                active = KaraokeSetSong(song, version)
                            } else {
                                withContext(Dispatchers.IO) {
                                    api.controlParty(partyForDone,
                                        JSONObject().put("playing", false)
                                            .put("current_item", "").put("position", 0))
                                }
                            }
                        } catch (e: Exception) {
                            notice = e.message ?: "Nächster Song konnte nicht gestartet werden."
                        }
                    }
                }
            },
            onTransport = { playing, position ->
                if (activePartyItem.isNotBlank() && partyId.isNotBlank()) {
                    val id = partyId
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val body = JSONObject()
                                if (playing != null) body.put("playing", playing)
                                if (position != null) body.put("position", position)
                                api.controlParty(id, body)
                            }
                        } catch (e: Exception) { notice = e.message ?: "Regie nicht erreichbar." }
                    }
                }
            })
        }
        return
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Zurück") }
            Column(Modifier.weight(1f)) {
                Text("Karaoke", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold)
                Text("Deine Bühne. Deine Setliste. Deine Party.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { refresh++ }) {
                Icon(Icons.Rounded.Refresh, "Aktualisieren")
            }
        }
        if (status == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status?.optBoolean("enabled") == false) {
            Surface(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(20.dp)) {
                    Icon(Icons.Rounded.MicOff, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Karaoke ist auf diesem Server deaktiviert.",
                        fontWeight = FontWeight.Bold)
                    Text("Der Administrator kann das optionale Karaoke-Modul aktivieren. " +
                        "Alle anderen Musikfunktionen bleiben verfügbar.")
                }
            }
        }
        if (notice.isNotBlank()) {
            Surface(Modifier.padding(horizontal = 14.dp).fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(notice, Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { notice = "" }) {
                        Icon(Icons.Rounded.Close, "Hinweis schließen")
                    }
                }
            }
        }
        if (status?.optBoolean("enabled") == true) {
            Row(Modifier.horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                KaraokeView.entries.forEach { tab ->
                    FilterChip(selected = view == tab, onClick = {
                        if (view != tab) {
                            view = tab
                            query = ""
                            offset = 0
                        }
                    }, label = { Text(tab.label) },
                        leadingIcon = { Icon(when (tab) {
                            KaraokeView.READY -> Icons.Rounded.LibraryMusic
                            KaraokeView.PREPARE -> Icons.Rounded.AutoAwesome
                            KaraokeView.PARTY -> Icons.Rounded.Groups
                        }, null) })
                }
            }
            if (view != KaraokeView.PARTY) {
                if (view == KaraokeView.PREPARE) {
                    val jobs = status?.optJSONArray("jobs")?.objectsKaraoke().orEmpty().take(12)
                    Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                        shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(12.dp).heightIn(max = 175.dp)
                            .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Server-Vorbereitung", fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f))
                                val online = status?.optBoolean("worker_online") == true
                                Text(if (online) "Worker online" else "Worker offline",
                                    color = if (online) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            if (jobs.isEmpty()) Text("Noch keine Vorbereitungsaufträge.",
                                style = MaterialTheme.typography.bodySmall)
                            jobs.forEach { job ->
                                Row(Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(job.optString("kind") + " · " +
                                            job.optString("profile") + " Stems · " +
                                            job.optString("status"),
                                            style = MaterialTheme.typography.labelMedium)
                                        Text(job.optString("message"),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 2)
                                    }
                                    if (job.optString("status") in listOf(
                                            "queued", "running", "waiting_resources")) {
                                        TextButton(onClick = {
                                            action {
                                                withContext(Dispatchers.IO) {
                                                    api.cancelJob(job.optString("id"))
                                                    status = api.status()
                                                }
                                            }
                                        }) { Text("Stoppen") }
                                    }
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(value = query,
                    onValueChange = { query = it; offset = 0 },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotBlank()) IconButton(onClick = {
                        query = ""; offset = 0
                    }) { Icon(Icons.Rounded.Close, "Suche löschen") } },
                    singleLine = true,
                    label = { Text("Songs, Interpreten oder Alben suchen") })
                Row(Modifier.horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (view == KaraokeView.READY) {
                        listOf("all" to "Alle Stems", "2" to "2 Stems",
                            "6" to "6 Stems").forEach { (key, label) ->
                            FilterChip(selected = profile == key, onClick = {
                                profile = key; offset = 0
                            }, label = { Text(label) })
                        }
                        listOf("recent" to "Neu", "title" to "Titel",
                            "artist" to "Interpret", "played" to "Zuletzt gesungen")
                            .forEach { (key, label) ->
                                FilterChip(selected = sort == key, onClick = {
                                    sort = key; offset = 0
                                }, label = { Text(label) })
                            }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("$total Songs", Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (loading) CircularProgressIndicator(Modifier.size(20.dp),
                        strokeWidth = 2.dp)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (tracks.isEmpty() && !loading) item {
                        KaraokeEmpty(if (view == KaraokeView.READY)
                            "Noch keine Karaoke-Songs vorbereitet."
                        else "Keine Titel gefunden.")
                    }
                    items(tracks, key = { it.id }) { song ->
                        KaraokeSongCard(session, song,
                            prepared = view == KaraokeView.READY,
                            onPlay = { version -> openSong(song, version) },
                            onQueue = { version -> queueSong(song, version) },
                            onPrepare = { mode ->
                                action {
                                    val result = withContext(Dispatchers.IO) { api.job(song.id, mode) }
                                    notice = when (result.optString("status")) {
                                        "ready" -> "Die Karaoke-Version ist bereits fertig."
                                        else -> "Auftrag gestartet: Die KI berechnet den Song auf dem Server."
                                    }
                                    refresh++
                                }
                            },
                            onParty = { version ->
                                if (partyId.isBlank()) notice =
                                    "Erstelle zuerst eine Party in der Party-Regie."
                                else action {
                                    val artifact = withContext(Dispatchers.IO) {
                                        api.versions(song.id).firstOrNull { it.profile == version }
                                    } ?: error("Karaoke-Version nicht verfügbar.")
                                    withContext(Dispatchers.IO) {
                                        api.addParty(partyId, song, artifact, singer.trim())
                                    }
                                    notice = "„${song.title}“ zur Party-Setliste hinzugefügt."
                                }
                            })
                    }
                    item {
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { offset = (offset - 24).coerceAtLeast(0) },
                                enabled = offset > 0) { Text("Zurück") }
                            Text("${if (total == 0) 0 else offset + 1}–${(offset + tracks.size).coerceAtMost(total)} / $total",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.align(Alignment.CenterVertically))
                            TextButton(onClick = { offset += 24 },
                                enabled = offset + tracks.size < total && !loading) {
                                Text("Weiter")
                            }
                        }
                    }
                }
                if (solo.isNotEmpty()) {
                    var expanded by remember { mutableStateOf(false) }
                    Surface(tonalElevation = 6.dp, shadowElevation = 4.dp,
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
                        Column {
                            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.QueueMusic, null, tint = karaokeAccent)
                                Text(" Solo-Setliste · ${solo.size}", Modifier.weight(1f)
                                    .padding(start = 10.dp), fontWeight = FontWeight.Bold)
                                Icon(if (expanded) Icons.Rounded.KeyboardArrowDown
                                    else Icons.Rounded.KeyboardArrowUp, null)
                            }
                            if (expanded) Column(Modifier.heightIn(max = 190.dp)
                                .verticalScroll(rememberScrollState())) {
                                solo.forEachIndexed { index, item ->
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Text("${index + 1}.", Modifier.width(28.dp))
                                        Text(item.song.title, Modifier.weight(1f), maxLines = 1)
                                        IconButton(onClick = {
                                            activePartyItem = ""; active = item
                                        }) { Icon(Icons.Rounded.PlayArrow, "Singen") }
                                        IconButton(onClick = {
                                            if (index > 0) {
                                                val current = solo.removeAt(index)
                                                solo.add(index - 1, current)
                                            }
                                        }, enabled = index > 0) {
                                            Icon(Icons.Rounded.ArrowUpward, "Nach oben")
                                        }
                                        IconButton(onClick = { solo.removeAt(index) }) {
                                            Icon(Icons.Rounded.Close, "Entfernen")
                                        }
                                    }
                                }
                                TextButton(onClick = {
                                    if (solo.isNotEmpty()) active = solo.first()
                                }, modifier = Modifier.fillMaxWidth()) { Text("Setliste starten") }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        Card(shape = RoundedCornerShape(20.dp)) {
                            Column(Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Party-Regie", style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold)
                                Text("Gäste wünschen Songs über einen QR-Code. Du steuerst " +
                                    "die Setliste und die Bühne.",
                                    style = MaterialTheme.typography.bodyMedium)
                                OutlinedTextField(value = partyName,
                                    onValueChange = { partyName = it.take(100) },
                                    label = { Text("Neue Party") },
                                    modifier = Modifier.fillMaxWidth())
                                Button(onClick = {
                                    action {
                                        val created = withContext(Dispatchers.IO) {
                                            api.createParty(partyName.trim().ifBlank { "Karaoke-Party" })
                                        }
                                        partyId = created.optString("id")
                                        invite = created.optString("invite")
                                        partyData = null
                                        refresh++
                                        notice = "Deine Party wurde erstellt."
                                    }
                                }, enabled = !busy) {
                                    Icon(Icons.Rounded.Add, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Party starten")
                                }
                                if (partyList.isNotEmpty()) {
                                    Text("Deine Partys", fontWeight = FontWeight.Bold)
                                    partyList.forEach { entry ->
                                        FilterChip(selected = partyId == entry.optString("id"),
                                            onClick = {
                                                partyId = entry.optString("id")
                                                partyData = null
                                                invite = ""
                                            }, label = { Text(entry.optString("title")) })
                                    }
                                }
                            }
                        }
                    }
                    if (party != null) {
                        item {
                            Card(shape = RoundedCornerShape(20.dp)) {
                                Column(Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(party.optString("title"),
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.ExtraBold)
                                            Text("${partyItems.size} Songs · ${party.optString("mode")}",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.labelMedium)
                                        }
                                        IconButton(onClick = { refreshParty(partyId) }) {
                                            Icon(Icons.Rounded.Refresh, "Party aktualisieren")
                                        }
                                    }
                                    OutlinedTextField(value = singer,
                                        onValueChange = { singer = it.take(80) },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Sängername für neue Songwünsche") })
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        FilterChip(selected = party.optString("mode") == "manual",
                                            onClick = {
                                                action {
                                                    withContext(Dispatchers.IO) {
                                                        api.controlParty(partyId,
                                                            JSONObject().put("mode", "manual"))
                                                    }
                                                    refreshParty(partyId)
                                                }
                                            }, label = { Text("Manuelle Regie") })
                                        FilterChip(selected = party.optString("mode") == "auto",
                                            onClick = {
                                                action {
                                                    withContext(Dispatchers.IO) {
                                                        api.controlParty(partyId,
                                                            JSONObject().put("mode", "auto"))
                                                    }
                                                    refreshParty(partyId)
                                                }
                                            }, label = { Text("Automatisch") })
                                    }
                                    Button(onClick = { view = KaraokeView.READY; offset = 0 },
                                        modifier = Modifier.fillMaxWidth()) {
                                        Text("Fertige Songs zur Party hinzufügen")
                                    }
                                    OutlinedButton(onClick = {
                                        action {
                                            invite = withContext(Dispatchers.IO) {
                                                api.invite(partyId).optString("invite")
                                            }
                                        }
                                    }, modifier = Modifier.fillMaxWidth()) {
                                        Icon(Icons.Rounded.QrCode, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Einladungs-QR erzeugen")
                                    }
                                    if (invite.isNotBlank()) {
                                        val link = session.url(
                                            "/karaoke-guest.html?party=${session.query(partyId)}&invite=${session.query(invite)}")
                                        val bitmap = remember(link) { karaokeQr(link) }
                                        if (bitmap != null) {
                                            Image(bitmap.asImageBitmap(), "QR-Code für Partygäste",
                                                modifier = Modifier.align(Alignment.CenterHorizontally)
                                                    .size(208.dp).background(Color.White)
                                                    .padding(9.dp))
                                        }
                                        Text("Gäste scannen den Code und geben Songwünsche ein.",
                                            style = MaterialTheme.typography.bodySmall)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick = {
                                                val clipboard = context.getSystemService(
                                                    Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clipboard.setPrimaryClip(ClipData.newPlainText(
                                                    "Karaoke-Party", link))
                                                notice = "Einladungslink kopiert."
                                            }) {
                                                Icon(Icons.Rounded.ContentCopy, null)
                                                Text("Kopieren")
                                            }
                                            OutlinedButton(onClick = {
                                                val intent = Intent(Intent.ACTION_SEND)
                                                    .setType("text/plain")
                                                    .putExtra(Intent.EXTRA_TEXT, link)
                                                context.startActivity(Intent.createChooser(intent,
                                                    "Karaoke-Party teilen"))
                                            }) {
                                                Icon(Icons.Rounded.Share, null)
                                                Text("Teilen")
                                            }
                                        }
                                    }
                                    TextButton(onClick = { confirmEnd = true },
                                        modifier = Modifier.fillMaxWidth()) {
                                        Text("Party beenden",
                                            color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                        if (partyItems.isEmpty()) item {
                            KaraokeEmpty("Noch keine Songwünsche. " +
                                "Füge Songs aus der Karaoke-Bibliothek hinzu.")
                        }
                        items(partyItems, key = { it.optString("id") }) { item ->
                            Card(shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.Mic, null, tint = karaokeAccent)
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(item.optString("title"),
                                                fontWeight = FontWeight.Bold, maxLines = 2)
                                            Text("${item.optString("artist")} · " +
                                                "${item.optString("singer")} · ${item.optString("state")}",
                                                style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                    Row(Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                        if (item.optString("state") != "done") {
                                            Button(onClick = {
                                                val id = partyId
                                                val itemId = item.optString("id")
                                                val song = KaraokeSong(item.optString("track_id"),
                                                    item.optString("title"),
                                                    item.optString("artist"), "", 0.0)
                                                val artifactId = item.optString("artifact_id")
                                                action {
                                                    val versions = withContext(Dispatchers.IO) {
                                                        api.versions(song.id)
                                                    }
                                                    val version = versions.firstOrNull {
                                                        it.id == artifactId
                                                    } ?: error("Karaoke-Version nicht mehr verfügbar.")
                                                    withContext(Dispatchers.IO) {
                                                        api.setlistAction(id, itemId, "play")
                                                    }
                                                    activePartyItem = itemId
                                                    active = KaraokeSetSong(song, version)
                                                }
                                            }) {
                                                Icon(Icons.Rounded.PlayArrow, null)
                                                Text("Auf Bühne")
                                            }
                                            listOf("up" to Icons.Rounded.KeyboardArrowUp,
                                                "down" to Icons.Rounded.KeyboardArrowDown,
                                                "done" to Icons.Rounded.Check,
                                                "remove" to Icons.Rounded.Delete)
                                                .forEach { (operation, icon) ->
                                                    OutlinedIconButton(onClick = {
                                                        action {
                                                            withContext(Dispatchers.IO) {
                                                                api.setlistAction(partyId,
                                                                    item.optString("id"), operation)
                                                            }
                                                            refreshParty(partyId)
                                                        }
                                                    }) {
                                                        Icon(icon, operation)
                                                    }
                                                }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Text("Automatische Aktualisierung alle 2,5 Sekunden.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (confirmEnd) AlertDialog(onDismissRequest = { confirmEnd = false },
        title = { Text("Party wirklich beenden?") },
        text = { Text("Alle Einladungen und Gastzugänge dieser Party werden ungültig.") },
        confirmButton = {
            TextButton(onClick = {
                confirmEnd = false
                action {
                    withContext(Dispatchers.IO) { api.closeParty(partyId) }
                    partyId = ""; partyData = null; invite = ""; refresh++
                    notice = "Party beendet."
                }
            }) { Text("Beenden") }
        }, dismissButton = {
            TextButton(onClick = { confirmEnd = false }) { Text("Abbrechen") }
        })
}

@Composable
private fun KaraokeSongCard(
    session: TromuwelleSession, song: KaraokeSong, prepared: Boolean,
    onPlay: (String) -> Unit, onQueue: (String) -> Unit,
    onPrepare: (String) -> Unit, onParty: (String) -> Unit
) {
    var selected by remember(song.id, prepared) {
        mutableStateOf(if ("2" in song.ready) "2" else song.ready.firstOrNull() ?: "2")
    }
    val entry = remember(song.id) {
        LibraryEntry(song.id, song.title, song.artist, EntryKind.TRACK,
            duration = song.duration.toLong(),
            artwork = "/api/music/cover/" + session.query(song.id))
    }
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AuthenticatedArtwork(session, entry,
                    Modifier.size(84.dp).clip(RoundedCornerShape(14.dp)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.titleMedium)
                    Text(song.artist.ifBlank { "Unbekannter Interpret" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(song.album, maxLines = 1,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val variants = if (prepared) song.ready else listOf("2", "6")
                variants.forEach { mode ->
                    FilterChip(selected = selected == mode, onClick = { selected = mode },
                        label = { Text("$mode Stems") })
                }
            }
            if (prepared) {
                Button(onClick = { onPlay(selected) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Mic, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Jetzt singen")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onQueue(selected) },
                        modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.QueueMusic, null)
                        Spacer(Modifier.width(5.dp))
                        Text("Setliste")
                    }
                    OutlinedButton(onClick = { onParty(selected) },
                        modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Groups, null)
                        Spacer(Modifier.width(5.dp))
                        Text("Party")
                    }
                }
            } else {
                OutlinedButton(onClick = { onPrepare(selected) },
                    modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.AutoAwesome, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Karaoke vorbereiten")
                }
                if (song.ready.isNotEmpty()) {
                    Text("Bereits fertig: ${song.ready.joinToString { "$it Stems" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { onPlay(song.ready.first()) }) {
                        Text("Vorhandene Version öffnen")
                    }
                }
            }
        }
    }
}

@Composable
private fun KaraokeEmpty(message: String) {
    Column(Modifier.fillMaxWidth().padding(30.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.MicNone, null,
            modifier = Modifier.size(48.dp), tint = karaokeAccent)
        Spacer(Modifier.height(14.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Generate an invitation QR locally; tokens do not go to a third-party QR service. */
private fun karaokeQr(value: String): Bitmap? = try {
    val matrix: BitMatrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 256, 256)
    Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until 256) for (x in 0 until 256) {
            setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK
                else android.graphics.Color.WHITE)
        }
    }
} catch (_: Exception) { null }
