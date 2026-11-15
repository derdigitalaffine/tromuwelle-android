package de.tromuwelle.android

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Server sidecar subtitles are surfaced as native Media3 text tracks. */
fun mediaWithSidecarSubtitles(session: TromuwelleSession, media: MediaItem,
                              path: String, metadata: JSONObject): MediaItem {
    val captions = metadata.optJSONArray("subtitles") ?: JSONArray()
    val configurations = captions.objects().mapNotNull { track ->
        val format = track.optString("format")
        if (format !in listOf("srt", "vtt", "ass", "ssa")) return@mapNotNull null
        val file = track.optString("path")
        if (file.isBlank()) return@mapNotNull null
        val link = "/api/media/subtitle?path=" + session.query(path) +
            "&track=" + session.query(file)
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(session.url(link)))
            .setMimeType(MimeTypes.TEXT_VTT)
            .setLanguage(track.optString("code", "und"))
            .setLabel(track.optString("language", "Untertitel"))
            .setSelectionFlags(if (track.optBoolean("forced")) C.SELECTION_FLAG_FORCED else 0)
            .build()
    }
    return if (configurations.isEmpty()) media else
        media.buildUpon().setSubtitleConfigurations(configurations).build()
}

@Composable
fun MediaInfoDialog(session: TromuwelleSession, entry: LibraryEntry, isPrivate: Boolean,
                    onDismiss: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val api = remember(session) { TromuwelleApi(session) }
    var metadata by remember(entry.path) { mutableStateOf<JSONObject?>(null) }
    var info by remember(entry.path) { mutableStateOf<JSONObject?>(null) }
    var tags by remember(entry.path) { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(entry.path) {
        loading = true
        try {
            metadata = withContext(Dispatchers.IO) { api.mediaMetadata(entry.path) }
            info = withContext(Dispatchers.IO) { api.mediaInfo(entry.path) }
            val array = metadata?.optJSONArray("manual_tags") ?: JSONArray()
            tags = (0 until array.length()).joinToString(", ") { array.optString(it) }
        } catch (e: Exception) { error = e.message ?: "Metadaten nicht verfügbar." }
        finally { loading = false }
    }

    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(metadata?.optString("title")?.takeIf { it.isNotBlank() } ?: entry.title) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(entry.path, style = MaterialTheme.typography.bodySmall)
                metadata?.let { data ->
                    if(data.optInt("year") > 0) Text("Jahr: " + data.optInt("year"))
                    val genres = data.optJSONArray("genres") ?: JSONArray()
                    if(genres.length() > 0) Text("Genres: " +
                        (0 until genres.length()).joinToString(", ") { genres.optString(it) })
                    val subtitles = data.optJSONArray("subtitles") ?: JSONArray()
                    if(subtitles.length() > 0) {
                        Text("Untertitel", style = MaterialTheme.typography.titleSmall)
                        subtitles.objects().forEach { subtitle ->
                            Text("• " + subtitle.optString("language") + " (" +
                                subtitle.optString("format") + ")")
                        }
                    }
                }
                info?.let { data ->
                    Text("Auflösung: " + data.optInt("width") + " × " + data.optInt("height"))
                    if (data.optString("video_codec").isNotBlank())
                        Text("Videocodec: " + data.optString("video_codec"))
                    if (data.optString("audio_codec").isNotBlank())
                        Text("Audiocodec: " + data.optString("audio_codec"))
                }
                if (session.account.isAdmin && isPrivate) {
                    OutlinedTextField(tags, { tags = it },
                        label = { Text("Tags, durch Kommas getrennt") }, modifier = Modifier.fillMaxWidth())
                    Text("Manuelle Tags verändern nur die Bibliotheksmetadaten.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if(error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (session.account.isAdmin && isPrivate) {
                Button(enabled = !loading, onClick = {
                    loading = true
                    scope.launch {
                        try {
                            val array = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                                .distinct().toJsonArray()
                            withContext(Dispatchers.IO) { api.updateAdultTags(entry.path, array) }
                            onChanged(); onDismiss()
                        } catch (e: Exception) { error = e.message ?: "Tags nicht gespeichert." }
                        finally { loading = false }
                    }
                }) { Text("Tags speichern") }
            } else TextButton(onClick = onDismiss) { Text("Fertig") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Schließen") } })
}
