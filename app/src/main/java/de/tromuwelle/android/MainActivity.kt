package de.tromuwelle.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    private val http = OkHttpClient.Builder().cookieJar(JavaNetCookieJar(cookieManager))
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private lateinit var player: ExoPlayer
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        setContent { MaterialTheme { TromuwelleApp(http, player) } }
    }
    override fun onDestroy() { player.release(); super.onDestroy() }
}

data class Entry(val title: String, val url: String)

@Composable
fun TromuwelleApp(http: OkHttpClient, player: ExoPlayer) {
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    var entries by remember { mutableStateOf(emptyList<Entry>()) }
    var error by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    fun load(section: Int) {
        tab = section
        scope.launch {
            try {
                val path = when(section) { 0 -> "/api/channels"; 1 -> "/api/radio/stations"; else -> "/api/music/tracks?limit=100" }
                val json = withContext(Dispatchers.IO) { request(http, server, path) }
                val array = when(section) {
                    0 -> JSONArray(json)
                    1 -> JSONObject(json).getJSONArray("stations")
                    else -> { val obj = JSONObject(json); obj.optJSONArray("tracks") ?: obj.optJSONArray("items") ?: JSONArray() }
                }
                entries = (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    val id = o.optString("id")
                    val title = o.optString("name", o.optString("title", id))
                    val pathToPlay = when(section) {
                        0 -> "/hls/$id/master.m3u8"
                        1 -> "/api/radio/stream/$id"
                        else -> "/api/music/stream/$id"
                    }
                    Entry(title, server.trimEnd('/') + pathToPlay)
                }
                error = ""
            } catch (e: Exception) { error = e.message ?: "Fehler beim Laden" }
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Tromuwelle", style = MaterialTheme.typography.headlineMedium)
        if (!connected) {
            OutlinedTextField(server, { server = it }, label = { Text("Server (https://host:8097)") }, singleLine = true)
            OutlinedTextField(username, { username = it }, label = { Text("Benutzername") }, singleLine = true)
            OutlinedTextField(password, { password = it }, label = { Text("Passwort") }, singleLine = true)
            Button(onClick = {
                scope.launch {
                    try {
                        require(server.startsWith("https://")) { "HTTPS-Serveradresse erforderlich" }
                        val body = JSONObject().put("username", username).put("password", password).toString()
                        withContext(Dispatchers.IO) { request(http, server, "/api/auth/login", body) }
                        connected = true; password = ""; load(0)
                    } catch (e: Exception) { error = e.message ?: "Anmeldung fehlgeschlagen" }
                }
            }) { Text("Verbinden") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("TV", "Radio", "Musik").forEachIndexed { i, label ->
                    Button(onClick = { load(i) }) { Text(label) }
                }
            }
            if (playing) {
                AndroidView(factory = { context -> PlayerView(context).apply { this.player = player } },
                    modifier = Modifier.fillMaxWidth().height(220.dp))
            }
            LazyColumn {
                items(entries) { entry ->
                    TextButton(onClick = {
                        player.setMediaItem(MediaItem.fromUri(entry.url))
                        player.prepare(); player.play(); playing = true
                    }, modifier = Modifier.fillMaxWidth()) { Text(entry.title) }
                }
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

private fun request(client: OkHttpClient, base: String, path: String, body: String? = null): String {
    val builder = Request.Builder().url(base.trimEnd('/') + path)
    if (body != null) builder.post(RequestBody.create(MediaType.parse("application/json"), body))
    client.newCall(builder.build()).execute().use { response ->
        if (!response.isSuccessful) error("HTTP ${response.code}")
        return response.body?.string() ?: error("Leere Antwort")
    }
}
