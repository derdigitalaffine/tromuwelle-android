package de.tromuwelle.android

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Mirrors backend/mediatheken.py; the backend owns source validity and catalog caching. */
internal class PublicMediathekenApi(private val session: TromuwelleSession) {
    fun providers(): List<String> {
        val array = JSONObject(session.get("/api/mediatheken/providers")).optJSONArray("items")
            ?: JSONArray()
        return (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
    }

    fun status(): JSONObject = JSONObject(session.get("/api/mediatheken/status"))
        .optJSONObject("catalog") ?: JSONObject()

    fun updateCatalog(action: String): JSONObject {
        require(action in setOf("refresh", "cancel"))
        return JSONObject(session.post("/api/mediatheken/$action"))
    }

    fun search(q: String, channel: String, sort: String, minDuration: Int,
               offset: Int, limit: Int = 48): JSONObject {
        val path = "/api/mediatheken/search?q=${session.query(q)}" +
            "&channel=${session.query(channel)}&sort=${session.query(sort)}" +
            "&direction=desc&min_duration=$minDuration&offset=$offset&limit=$limit"
        return JSONObject(session.get(path))
    }
}

internal fun publicMediathekenUrl(item: JSONObject): String? =
    listOf("url_video_hd", "url_video", "url_video_low")
        .firstNotNullOfOrNull { key ->
            val link = item.optString(key)
            if (secureMediathekVideoUrl(link)) link else null
        }

/** Avoid cleartext, credential-in-URL and non-media schemes in a public player. */
internal fun secureMediathekVideoUrl(value: String): Boolean = try {
    val uri = URI(value)
    uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.rawFragment == null
} catch (_: Exception) { false }

internal fun publicMediathekMediaItem(item: JSONObject): MediaItem? {
    val url = publicMediathekenUrl(item) ?: return null
    val contentType = if (url.substringBefore("?").endsWith(".m3u8", true))
        MimeTypes.APPLICATION_M3U8 else null
    return MediaItem.Builder()
        .setMediaId("MEDIATHEK:" + item.optString("id")
            .ifBlank { item.optString("channel") + ":" + item.optString("title") })
        .setUri(url)
        .setMimeType(contentType)
        .setMediaMetadata(MediaMetadata.Builder()
            .setTitle(item.optString("title"))
            .setArtist(item.optString("channel"))
            .build())
        .build()
}

@Composable
fun StreamPublicMediatheken(
    session: TromuwelleSession,
    onBack: () -> Unit,
    onPlay: (JSONObject, MediaItem) -> Unit
) {
    val api = remember(session) { PublicMediathekenApi(session) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("") }
    var providerMenu by remember { mutableStateOf(false) }
    var providers by remember { mutableStateOf<List<String>>(emptyList()) }
    var sort by remember { mutableStateOf("timestamp") }
    var minDuration by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var cache by remember { mutableStateOf("") }
    var catalog by remember { mutableStateOf<JSONObject?>(null) }
    var catalogBusy by remember { mutableStateOf(false) }
    var catalogError by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(session) {
        try {
            providers = withContext(Dispatchers.IO) { api.providers() }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Search works even when provider metadata is unavailable. */ }
        try {
            catalog = withContext(Dispatchers.IO) { api.status() }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Optional catalog status should not block search. */ }
    }
    LaunchedEffect(query, provider, sort, minDuration, page, refresh) {
        delay(if (query.isNotEmpty()) 420 else 0)
        loading = true
        error = ""
        try {
            val result = withContext(Dispatchers.IO) {
                api.search(query.trim().take(150), provider, sort, minDuration, page * 48)
            }
            val array = result.optJSONArray("items") ?: JSONArray()
            items = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            total = result.optInt("total", items.size)
            cache = result.optString("cache")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            error = e.message ?: "Mediatheken konnten nicht geladen werden."
            items = emptyList()
        } finally { loading = false }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Zurück")
            }
            Column(Modifier.weight(1f)) {
                Text("Öffentlich-rechtliche Mediatheken",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text("ARD · ZDF · ARTE · MediathekView",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { refresh++ }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Neu laden")
            }
        }
        if (catalog?.optBoolean("ready") == true) {
            Text("${catalog?.optInt("count") ?: 0} Titel im Serverkatalog",
                modifier = Modifier.padding(horizontal = 17.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (session.account.isAdmin) {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("MediathekView-Katalog",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold)
                    if (catalog?.optBoolean("running") == true) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("${catalog?.optInt("processed") ?: 0} Titel verarbeitet",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !catalogBusy &&
                            catalog?.optBoolean("running") != true,
                            onClick = {
                                scope.launch {
                                    catalogBusy = true; catalogError = ""
                                    try {
                                        catalog = withContext(Dispatchers.IO) {
                                            api.updateCatalog("refresh")
                                        }
                                    } catch (e: Exception) {
                                        catalogError = e.message ?: "Aktualisierung fehlgeschlagen."
                                    } finally { catalogBusy = false }
                                }
                            }) { Text("Filmliste aktualisieren") }
                        if (catalog?.optBoolean("running") == true) {
                            TextButton(enabled = !catalogBusy, onClick = {
                                scope.launch {
                                    catalogBusy = true; catalogError = ""
                                    try {
                                        catalog = withContext(Dispatchers.IO) {
                                            api.updateCatalog("cancel")
                                        }
                                    } catch (e: Exception) {
                                        catalogError = e.message ?: "Abbruch fehlgeschlagen."
                                    } finally { catalogBusy = false }
                                }
                            }) { Text("Abbrechen") }
                        }
                    }
                    if (catalogError.isNotBlank()) Text(catalogError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        OutlinedTextField(value = query, onValueChange = { query = it.take(150); page = 0 },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotBlank()) IconButton(onClick = { query = ""; page = 0 }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Suche löschen")
                }
            }, placeholder = { Text("Sendung, Film oder Thema suchen") },
            shape = RoundedCornerShape(18.dp), singleLine = true)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                FilterChip(selected = provider.isNotBlank(), onClick = { providerMenu = true },
                    label = { Text(provider.ifBlank { "Alle Sender" }) },
                    trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null) })
                DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                    DropdownMenuItem(text = { Text("Alle Sender") }, onClick = {
                        provider = ""; page = 0; providerMenu = false
                    })
                    providers.forEach { channel ->
                        DropdownMenuItem(text = { Text(channel) }, onClick = {
                            provider = channel; page = 0; providerMenu = false
                        })
                    }
                }
            }
            FilterChip(selected = sort == "timestamp", onClick = {
                sort = "timestamp"; page = 0
            }, label = { Text("Neueste") })
            FilterChip(selected = sort == "title", onClick = {
                sort = "title"; page = 0
            }, label = { Text("Titel") })
            FilterChip(selected = minDuration == 1200, onClick = {
                minDuration = if (minDuration == 1200) 0 else 1200; page = 0
            }, label = { Text("Ab 20 Min.") })
            FilterChip(selected = minDuration == 5400, onClick = {
                minDuration = if (minDuration == 5400) 0 else 5400; page = 0
            }, label = { Text("Ab 90 Min.") })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 17.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("$total Treffer", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (cache == "stale") Text("Offline-Katalog",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error)
            if (loading) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
        }
        if (error.isNotBlank()) {
            Text(error, modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { refresh++ },
                modifier = Modifier.padding(horizontal = 16.dp)) { Text("Erneut versuchen") }
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(255.dp), modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.optString("id").ifBlank {
                it.optString("channel") + ":" + it.optString("title")
            } }) { entry ->
                val item = publicMediathekMediaItem(entry)
                Card(colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(15.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.fillMaxWidth().height(100.dp).background(
                            Brush.linearGradient(listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.surface))),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.LiveTv, contentDescription = null,
                                modifier = Modifier.size(42.dp),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                        Text(entry.optString("channel").ifBlank { "Mediathek" },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Text(entry.optString("title").ifBlank { "Ohne Titel" },
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(entry.optString("topic"), maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall)
                        Text(entry.optString("description"), maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (entry.optLong("duration") > 0L) Text(
                            "${entry.optLong("duration") / 60} Minuten",
                            style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Button(enabled = item != null, onClick = {
                                item?.let { onPlay(entry, it) }
                            }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Ansehen")
                            }
                            val website = entry.optString("website")
                            if (safePublicWebsite(website)) {
                                OutlinedButton(onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW,
                                        Uri.parse(website)))
                                }) { Text("Original") }
                            }
                        }
                    }
                }
            }
        }
        if (total > 48) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center) {
                IconButton(enabled = page > 0 && !loading, onClick = { page-- }) {
                    Icon(Icons.Rounded.ChevronLeft, contentDescription = "Vorherige Seite")
                }
                Text("Seite ${page + 1} / ${((total - 1) / 48) + 1}",
                    style = MaterialTheme.typography.labelMedium)
                IconButton(enabled = !loading && (page + 1) * 48 < total && page < 2082,
                    onClick = { page++ }) {
                    Icon(Icons.Rounded.ChevronRight, contentDescription = "Nächste Seite")
                }
            }
        }
    }
}

internal fun safePublicWebsite(value: String): Boolean = try {
    val uri = URI(value)
    uri.scheme?.lowercase() in listOf("https", "http") &&
        !uri.host.isNullOrBlank() && uri.rawUserInfo == null
} catch (_: Exception) { false }
