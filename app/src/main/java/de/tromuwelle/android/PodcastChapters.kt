package de.tromuwelle.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class PodcastChapter(val startMs: Long, val title: String)

internal fun podcastChapters(data: JSONObject): List<PodcastChapter> {
    val array = data.optJSONArray("items") ?: return emptyList()
    return (0 until array.length()).mapNotNull { idx ->
        val chapter = array.optJSONObject(idx) ?: return@mapNotNull null
        val time = chapter.optDouble("start", Double.NaN)
        if (!time.isFinite() || time < 0.0 || time > 1_000_000.0) null
        else PodcastChapter((time * 1000).toLong(),
            chapter.optString("title").ifBlank { "Kapitel ${idx + 1}" })
    }.sortedBy { it.startMs }.distinctBy { it.startMs }
}

internal fun chapterIndexAt(chapters: List<PodcastChapter>, timeMs: Long): Int =
    chapters.indexOfLast { it.startMs <= timeMs }

/** Podcasting 2.0 chapters are rendered inside the existing native music/podcast sheet. */
@Composable
fun StreamPodcastChapters(session: TromuwelleSession, episode: JSONObject,
                          player: Player) {
    val id = episode.optString("id")
    var chapters by remember(id) { mutableStateOf<List<PodcastChapter>>(emptyList()) }
    var loading by remember(id) { mutableStateOf(true) }
    var error by remember(id) { mutableStateOf("") }
    var dialog by remember(id) { mutableStateOf(false) }
    var position by remember(id) { mutableLongStateOf(0L) }
    var refresh by remember(id) { mutableIntStateOf(0) }
    val isCurrent = player.currentMediaItem?.mediaId == "PODCAST:$id"

    LaunchedEffect(session, id, refresh) {
        loading = true; error = ""
        try {
            val data = withContext(Dispatchers.IO) {
                TromuPodcasts(session).chapters(id)
            }
            chapters = podcastChapters(data)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Kapitel nicht verfügbar." }
        finally { loading = false }
    }
    LaunchedEffect(player, id, dialog) {
        while (isActive) {
            if (player.currentMediaItem?.mediaId == "PODCAST:$id")
                position = player.currentPosition.coerceAtLeast(0L)
            delay(if (dialog) 450L else 1100L)
        }
    }
    val active = chapterIndexAt(chapters, position)
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.FormatListNumbered, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text("Podcast-Kapitel", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                Text(if (loading) "Kapitel werden geladen …"
                    else if (chapters.isEmpty()) "Noch keine Kapitelmarken verfügbar"
                    else "${chapters.size} Kapitel · ${chapters.getOrNull(active)?.title ?: "Einleitung"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (chapters.isNotEmpty()) IconButton(onClick = {
                val prev = if (active < 0) 0
                    else if (position > chapters[active].startMs + 3_000L) active
                    else (active - 1).coerceAtLeast(0)
                if (isCurrent) player.seekTo(chapters[prev].startMs)
            }, enabled = isCurrent) {
                Icon(Icons.Rounded.SkipPrevious, "Vorheriges Kapitel")
            }
            if (chapters.isNotEmpty()) IconButton(onClick = {
                chapters.getOrNull(active + 1)?.let {
                    if (isCurrent) player.seekTo(it.startMs)
                }
            }, enabled = isCurrent && active < chapters.lastIndex) {
                Icon(Icons.Rounded.SkipNext, "Nächstes Kapitel")
            }
        }
        if (chapters.isNotEmpty()) {
            OutlinedButton(onClick = { dialog = true },
                modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.List, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Alle Kapitel ansehen und anspringen")
            }
        } else if (!loading) {
            if (error.isNotBlank()) Text(error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { refresh++ }) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
                Spacer(Modifier.width(5.dp))
                Text("Kapitel erneut laden")
            }
        }
    }
    if (dialog) {
        AlertDialog(onDismissRequest = { dialog = false },
            title = { Text("Kapitel · ${chapters.size}") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(chapters, key = { _, chapter -> chapter.startMs }) { index, chapter ->
                        Surface(onClick = {
                            if (isCurrent) {
                                player.seekTo(chapter.startMs)
                                dialog = false
                            }
                        }, enabled = isCurrent,
                            color = if (index == active)
                                MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("${index + 1}", color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(chapter.title,
                                        fontWeight = if (index == active) FontWeight.Bold
                                            else FontWeight.Normal, maxLines = 3,
                                        overflow = TextOverflow.Ellipsis)
                                    Text(formatMediaTime(chapter.startMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialog = false }) { Text("Schließen") }
            })
    }
}
