package de.tromuwelle.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

fun openSubsonicLyricLines(data: JSONObject): List<LyricLine> {
    val result = data.optJSONObject("lyricsList")?.optJSONArray("structuredLyrics")
        ?.optJSONObject(0) ?: return emptyList()
    val lines = result.optJSONArray("line") ?: return emptyList()
    return (0 until lines.length()).mapNotNull { index ->
        val line = lines.optJSONObject(index) ?: return@mapNotNull null
        val text = line.optString("value")
        if (text.isBlank()) null else LyricLine(line.optLong("start").coerceAtLeast(0L), text)
    }.sortedBy { it.timeMs }
}

@Composable
fun ExternalSubsonicLyricsDialog(source: SubsonicSource, song: JSONObject,
                                player: Player?, onClose: () -> Unit) {
    var result by remember(source.id, song.optString("id")) { mutableStateOf<JSONObject?>(null) }
    var plain by remember(source.id, song.optString("id")) { mutableStateOf("") }
    var error by remember(source.id, song.optString("id")) { mutableStateOf("") }
    var synced by remember { mutableStateOf(true) }
    var timeMs by remember { mutableLongStateOf(0L) }
    val listState = rememberLazyListState()
    val lines = remember(result) { result?.let(::openSubsonicLyricLines) ?: emptyList() }
    val active = lines.indexOfLast { it.timeMs <= timeMs }.coerceAtLeast(0)
    LaunchedEffect(source.id, song.optString("id")) {
        val client = SubsonicClient(source)
        try {
            result = withContext(Dispatchers.IO) { client.lyrics(song.optString("id")) }
            val texts = result?.optJSONObject("lyricsList")?.optJSONArray("structuredLyrics")
                ?.optJSONObject(0)
            plain = texts?.optString("value").orEmpty()
            error = ""
        } catch (_: Exception) {
            try {
                val result = withContext(Dispatchers.IO) {
                    client.call("getLyrics", mapOf("artist" to song.optString("artist"),
                        "title" to song.optString("title")))
                }
                plain = result.optJSONObject("lyrics")?.optString("value").orEmpty()
                error = ""
            } catch (e: Exception) {
                error = e.message ?: "Keine Lyrics vom Server verfügbar."
            }
        }
    }
    LaunchedEffect(player, synced) {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            if (synced && player?.currentMediaItem?.mediaId ==
                "ext:" + source.id + ":" + song.optString("id")) {
                timeMs = player.currentPosition
            }
            delay(180L)
        }
    }
    LaunchedEffect(active, synced, result) {
        if (synced && lines.isNotEmpty()) listState.animateScrollToItem(active)
    }
    AlertDialog(onDismissRequest = onClose, title = { Text(song.optString("title")) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("OpenSubsonic-Musikserver: " + source.name,
                    style = MaterialTheme.typography.labelSmall)
                if(result == null && error.isBlank() && plain.isBlank())
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                if(lines.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Synchronisierte Zeile")
                        Spacer(Modifier.weight(1f))
                        Switch(checked = synced, onCheckedChange = { synced = it })
                    }
                    LazyColumn(Modifier.heightIn(max = 420.dp), state = listState,
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(lines.size) { index ->
                            Text(lines[index].text,
                                style = if (synced && index == active)
                                    MaterialTheme.typography.titleLarge else
                                    MaterialTheme.typography.bodyLarge,
                                color = if (synced && index == active)
                                    MaterialTheme.colorScheme.primary else
                                    MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    Column(Modifier.heightIn(max = 410.dp).verticalScroll(rememberScrollState())) {
                        Text(plain.ifBlank { error.ifBlank { "Keine Lyrics gefunden." } })
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onClose) { Text("Schließen") } })
}
