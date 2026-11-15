package de.tromuwelle.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
 import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.delay

/**
 * Same fallback chain as the Tromuwelle web library:
 * poster sidecar -> deterministic video thumbnail -> placeholder.
 * Folder art uses /api/media/folder-thumbnail. All server requests share the
 * verified TLS/cookie client; no anonymous image bypass and no video download.
 */
fun artworkCandidates(entry: LibraryEntry, encode: (String) -> String): List<String> {
    val primary = entry.artwork.takeIf { it.startsWith("/") || it.startsWith("https://") }
    return when {
        entry.kind == EntryKind.FOLDER && entry.path.isNotBlank() ->
            listOf("/api/media/folder-thumbnail?path=" + encode(entry.path))
        entry.kind == EntryKind.FILE && entry.isVideo() && entry.path.isNotBlank() -> {
            val encoded = encode(entry.path)
            listOf(
                "/api/media/artwork?kind=poster&path=$encoded",
                "/api/media/thumbnail?path=$encoded"
            )
        }
        primary != null -> listOf(primary)
        else -> emptyList()
    }
}

private fun entryArtworkIcon(entry: LibraryEntry): ImageVector = when (entry.kind) {
    EntryKind.TV -> Icons.Rounded.LiveTv
    EntryKind.RADIO -> Icons.Rounded.Radio
    EntryKind.TRACK -> Icons.Rounded.MusicNote
    EntryKind.ARTIST -> Icons.Rounded.Person
    EntryKind.ALBUM -> Icons.Rounded.Album
    EntryKind.FOLDER -> Icons.Rounded.Folder
    EntryKind.FILE -> if (entry.audio) Icons.Rounded.GraphicEq else Icons.Rounded.Movie
}

@Composable
private fun ArtworkPlaceholder(entry: LibraryEntry, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        Icon(entryArtworkIcon(entry), contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize(0.36f))
    }
}

@Composable
fun AuthenticatedArtwork(session: TromuwelleSession, entry: LibraryEntry,
                         modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val loader = session.artworkLoader
    val candidates = remember(entry.id, entry.path, entry.artwork, entry.kind) {
        artworkCandidates(entry, session::query)
    }
    var candidate by remember(candidates) { mutableIntStateOf(0) }
    var retries by remember(candidates, candidate) { mutableIntStateOf(0) }
    var failureSignal by remember(candidates, candidate) { mutableIntStateOf(0) }
    var loading by remember(candidates, candidate) { mutableStateOf(true) }

    if (candidate >= candidates.size) {
        ArtworkPlaceholder(entry, modifier)
        return
    }
    val path = candidates[candidate]
    val url = if (path.startsWith("https://")) path else session.url(path)

    // Thumbnail generation can take seconds and concurrently return HTTP 503.
    // Retry with bounded backoff; do not hammer two FFmpeg render slots.
    LaunchedEffect(failureSignal, candidate, candidates) {
        if (failureSignal == 0) return@LaunchedEffect
        // Missing poster artwork returns HTTP 404 and must immediately fall
        // through to an FFmpeg thumbnail; it isn't a temporary render failure.
        if (path.contains("/api/media/artwork?")) {
            candidate++
            retries = 0
            failureSignal = 0
        } else if (retries < 3 && path.contains("/api/media/")) {
            delay(750L * (1 shl retries))
            retries++
        } else {
            candidate++
            retries = 0
            failureSignal = 0
        }
    }
    Box(modifier) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(url)
                .crossfade(160)
                // The server's thumb cache itself is versioned by mtime/size.
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .setParameter("attempt", retries, memoryCacheKey = null)
                .build(),
            imageLoader = loader,
            contentScale = ContentScale.Crop,
            contentDescription = entry.title,
            modifier = Modifier.fillMaxSize(),
            onLoading = { loading = true },
            onSuccess = { loading = false },
            onError = {
                loading = false
                failureSignal++
            }
        )
        if (loading) CircularProgressIndicator(
            modifier = Modifier.align(Alignment.Center),
            color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
        if (candidate >= candidates.size) ArtworkPlaceholder(entry, Modifier.fillMaxSize())
    }
}
