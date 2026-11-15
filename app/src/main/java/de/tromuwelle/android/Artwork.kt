package de.tromuwelle.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest

/** Fetches authenticated Tromuwelle covers/posters with the same cookie jar as Media3. */
@Composable
fun AuthenticatedArtwork(session: TromuwelleSession, entry: LibraryEntry, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val loader = remember(context, session) {
        ImageLoader.Builder(context).okHttpClient(session.client).build()
    }
    if (entry.artwork.isBlank()) {
        Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center) {
            Text(when(entry.kind) {
                EntryKind.TV -> "TV"; EntryKind.RADIO -> "♫"; EntryKind.TRACK -> "♪"
                EntryKind.ARTIST -> "♬"; EntryKind.ALBUM -> "▤"; EntryKind.FOLDER -> "▸"
                EntryKind.FILE -> "▶"
            })
        }
    } else {
        val url = if(entry.artwork.startsWith("https://")) entry.artwork else session.url(entry.artwork)
        AsyncImage(model = ImageRequest.Builder(context).data(url).crossfade(true).build(),
            imageLoader = loader,
            contentScale = ContentScale.Crop,
            contentDescription = entry.title,
            modifier = modifier)
    }
}
