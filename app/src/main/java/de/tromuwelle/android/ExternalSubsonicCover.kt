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
 import coil.compose.AsyncImage
import okhttp3.OkHttpClient
import coil.request.ImageRequest
import org.json.JSONObject

/** Cache the salted cover URL across Compose recompositions to avoid needless requests. */
@Composable
fun ExternalSubsonicCover(source: SubsonicSource?, item: JSONObject,
                          modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val loader = (context.applicationContext as TromuwelleApplication)
        .subsonicArtworkLoader
    val artworkId = item.optString("coverArt")
    val url = remember(source?.id, item.optString("id"), artworkId) {
        if (source == null || artworkId.isBlank()) "" else
            SubsonicClient(source).coverUrl(artworkId)
    }
    if (url.isBlank()) {
        Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center) {
            Text("♪", style = MaterialTheme.typography.titleLarge)
        }
    } else {
        AsyncImage(model = ImageRequest.Builder(context).data(url).crossfade(true).build(),
            imageLoader = loader,
            contentDescription = item.optString("title").ifBlank { item.optString("name") },
            contentScale = ContentScale.Crop,
            modifier = modifier)
    }
}
