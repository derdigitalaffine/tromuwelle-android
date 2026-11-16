package de.tromuwelle.android

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Backend-authorized US ATC/public-safety feeds; directory links are NOT audio. */
internal class AirwavesApi(private val session: TromuwelleSession) {
    fun enabled(): Boolean = JSONObject(session.get("/api/airwaves/config")).optBoolean("enabled")
    fun status(): JSONObject = JSONObject(session.get("/api/airwaves/status"))
    fun feeds(kind: String, q: String, state: String, offset: Int): JSONObject =
        JSONObject(session.get("/api/airwaves/feeds?kind=${session.query(kind)}" +
            "&q=${session.query(q)}&state=${session.query(state)}&limit=40&offset=$offset"))
    fun airports(q: String, state: String, offset: Int): JSONObject =
        JSONObject(session.get("/api/airwaves/airports?q=${session.query(q)}" +
            "&state=${session.query(state)}&limit=40&offset=$offset"))
    fun directory(q: String, state: String, offset: Int): JSONObject =
        JSONObject(session.get("/api/airwaves/atc-directory?q=${session.query(q)}" +
            "&state=${session.query(state)}&limit=40&offset=$offset"))
    fun airport(ident: String): JSONObject =
        JSONObject(session.get("/api/airwaves/airports/${session.query(ident)}"))
    fun aircraft(ident: String): JSONObject =
        JSONObject(session.get("/api/airwaves/airports/${session.query(ident)}/aircraft"))
    fun refreshAirports() { session.post("/api/airwaves/airports/refresh") }
}

internal fun airwavesRadioEntry(feed: JSONObject): LibraryEntry =
    LibraryEntry(id = "airwaves:" + feed.optInt("id"),
        title = feed.optString("name"),
        description = listOf(
            if (feed.optString("kind") == "fire") "US-Feuerwehrfunk" else "US-Flugfunk",
            feed.optString("city"), feed.optString("state")
        ).filter { it.isNotBlank() }.joinToString(" · "),
        kind = EntryKind.RADIO)

private fun JSONArray?.airwavesObjects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

@Composable
fun StreamAirwaves(
    session: TromuwelleSession,
    onBack: () -> Unit,
    onPlay: (LibraryEntry) -> Unit
) {
    val context = LocalContext.current
    val api = remember(session) { AirwavesApi(session) }
    var enabled by remember { mutableStateOf<Boolean?>(null) }
    var tab by remember { mutableStateOf("atc") }
    var query by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var selectedAirport by remember { mutableStateOf<JSONObject?>(null) }
    var aircraft by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var aircraftNote by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(session) {
        try {
            enabled = withContext(Dispatchers.IO) { api.enabled() }
            if (enabled == true) status = withContext(Dispatchers.IO) { api.status() }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Flugfunk-Modul nicht erreichbar." }
    }
    LaunchedEffect(tab, query, state, page, enabled, refresh) {
        if (enabled != true) return@LaunchedEffect
        delay(if (query.isNotBlank()) 360L else 0L)
        loading = true; error = ""
        try {
            val result = withContext(Dispatchers.IO) {
                when (tab) {
                    "airports" -> api.airports(query.trim(), state.trim(), page * 40)
                    "directory" -> api.directory(query.trim(), state.trim(), page * 40)
                    else -> api.feeds(tab, query.trim(), state.trim(), page * 40)
                }
            }
            rows = result.optJSONArray("items").airwavesObjects()
            total = result.optInt("total", rows.size)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { rows = emptyList(); error = e.message ?: "Daten nicht erreichbar." }
        finally { loading = false }
    }
    LaunchedEffect(selectedAirport?.optString("ident")) {
        val ident = selectedAirport?.optString("ident").orEmpty()
        if (ident.isBlank()) return@LaunchedEffect
        try {
            val detail = withContext(Dispatchers.IO) { api.airport(ident) }
            selectedAirport = detail
            val data = withContext(Dispatchers.IO) { api.aircraft(ident) }
            aircraft = data.optJSONArray("aircraft").airwavesObjects()
            aircraftNote = data.optString("message").ifBlank {
                if (data.optBoolean("stale")) "Zwischengespeicherte Positionen"
                else data.optString("source").ifBlank { "OpenSky ADS-B" }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { aircraftNote = e.message ?: "Luftlage nicht erreichbar." }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (selectedAirport != null) selectedAirport = null else onBack() }) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Zurück")
            }
            Column(Modifier.weight(1f)) {
                Text("Flug- & Einsatzfunk", fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.titleLarge)
                Text("ATC · US-Feuerwehr · Flughafenverzeichnis",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { selectedAirport = null; refresh++ }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Aktualisieren")
            }
        }
        if (enabled == null && error.isBlank()) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (enabled == false) {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(18.dp)) {
                Text("Der Administrator hat Flug- & Einsatzfunk auf dem Server deaktiviert.",
                    modifier = Modifier.padding(18.dp))
            }
        }
        if (selectedAirport != null) {
            val airport = selectedAirport!!
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(airport.optString("name"),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold)
                Text("${airport.optString("ident")} · ${airport.optString("city")} · " +
                    airport.optString("state"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                val lat = airport.optDouble("lat", Double.NaN)
                val lon = airport.optDouble("lon", Double.NaN)
                if (lat.isFinite() && lon.isFinite()) {
                    AirwavesAirportMap(lat, lon, aircraft, Modifier.fillMaxWidth())
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=12/$lat/$lon")))
                    }) {
                        Icon(Icons.Rounded.Map, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Flughafen auf OpenStreetMap")
                    }
                }
                Text("Funkfrequenzen",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                val frequencies = airport.optJSONArray("frequencies").airwavesObjects()
                if (frequencies.isEmpty())
                    Text("Keine Frequenzen erfasst.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                frequencies.forEach { freq ->
                    ListItem(headlineContent = {
                        Text(freq.optString("description").ifBlank {
                            freq.optString("type") })
                    }, supportingContent = { Text(freq.optString("type")) },
                        trailingContent = {
                            Text("${freq.optDouble("mhz")} MHz",
                                fontWeight = FontWeight.SemiBold)
                        })
                    HorizontalDivider()
                }
                Text("Start- und Landebahnen",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                airport.optJSONArray("runways").airwavesObjects().forEach { runway ->
                    Text("${runway.optString("le_ident")} / ${runway.optString("he_ident")} · " +
                        "${runway.optInt("length_ft")} ft · ${runway.optString("surface")}",
                        style = MaterialTheme.typography.bodyMedium)
                }
                Text("Luftlage · OpenSky",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(aircraftNote.ifBlank { "Luftlage wird geladen …" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                aircraft.take(60).forEach { plane ->
                    ListItem(headlineContent = {
                        Text(plane.optString("callsign").ifBlank { plane.optString("icao24") })
                    }, supportingContent = {
                        Text("ICAO ${plane.optString("icao24")} · " +
                            "${plane.optInt("altitude_m")} m")
                    }, trailingContent = {
                        Text("${plane.optInt("speed_ms")} m/s")
                    })
                }
            }
        } else if (enabled == true) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("atc" to "ATC", "fire" to "Feuerwehr",
                    "airports" to "Flughäfen", "directory" to "ATC-Verzeichnis")
                    .forEach { (id, title) ->
                        FilterChip(selected = tab == id, onClick = {
                            tab = id; page = 0
                        }, label = { Text(title) })
                    }
            }
            OutlinedTextField(value = query,
                onValueChange = { query = it.take(100); page = 0 },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = ""; page = 0 }) {
                        Icon(Icons.Rounded.Close, "Suche löschen")
                    }
                }, placeholder = { Text("Sender, Flughafen, ICAO oder Stadt") },
                singleLine = true, shape = RoundedCornerShape(16.dp))
            OutlinedTextField(value = state, onValueChange = {
                state = it.uppercase().take(2).filter(Char::isLetter)
                page = 0
            }, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                leadingIcon = { Icon(Icons.Rounded.Public, null) },
                placeholder = { Text("US-Bundesstaat (optional, z. B. CA)") },
                singleLine = true)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("$total Treffer", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (loading) CircularProgressIndicator(Modifier.size(18.dp),
                    strokeWidth = 2.dp)
            }
            if (error.isNotBlank()) Text(error,
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.error)
            LazyColumn(Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(rows, key = { row ->
                    tab + ":" + row.optString("id").ifBlank { row.optString("ident") }
                }) { row ->
                    val isFeed = tab == "atc" || tab == "fire"
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (tab == "fire") Icons.Rounded.LocalFireDepartment
                                else if (tab == "airports") Icons.Rounded.Flight
                                else Icons.Rounded.SettingsInputAntenna, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp))
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(row.optString("name").ifBlank {
                                    row.optString("airport_name") },
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(listOf(row.optString("city"),
                                    row.optString("state"),
                                    row.optString("ident").ifBlank {
                                        row.optString("airport_ident") })
                                    .filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2)
                            }
                            if (isFeed) {
                                FilledIconButton(onClick = {
                                    onPlay(airwavesRadioEntry(row))
                                }) {
                                    Icon(Icons.Rounded.PlayArrow, "Funkstream abspielen")
                                }
                            } else if (tab == "airports") {
                                IconButton(onClick = { selectedAirport = row }) {
                                    Icon(Icons.Rounded.ChevronRight, "Flughafen ansehen")
                                }
                            } else {
                                val href = row.optString("directory_url")
                                OutlinedButton(enabled = safePublicWebsite(href), onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW,
                                        Uri.parse(href)))
                                }) { Text("Verzeichnis") }
                            }
                        }
                    }
                }
                if (rows.isEmpty() && !loading && error.isBlank()) {
                    item {
                        Text(if (tab == "atc" || tab == "fire")
                            "Noch keine für Streaming freigegebenen Funkquellen. " +
                                "Das externe ATC-Verzeichnis enthält Suchverweise, keine freien Streams."
                        else "Keine Einträge gefunden.",
                            modifier = Modifier.padding(15.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center) {
                IconButton(enabled = page > 0 && !loading, onClick = { page-- }) {
                    Icon(Icons.Rounded.ChevronLeft, "Vorherige Seite")
                }
                Text("${page + 1} / ${((total.coerceAtLeast(1) - 1) / 40) + 1}",
                    style = MaterialTheme.typography.labelMedium)
                IconButton(enabled = !loading && (page + 1) * 40 < total,
                    onClick = { page++ }) {
                    Icon(Icons.Rounded.ChevronRight, "Nächste Seite")
                }
            }
        }
    }
}
