package de.tromuwelle.android

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.mediarouter.app.MediaRouteButton
import androidx.media3.common.Player
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Native Google Cast default receiver; no unregistered custom receiver app ID. */
class TromuCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setShowSystemOutputSwitcherOnCastIconClick(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

data class CastSource(val kind: String, val identifier: String) {
    companion object {
        fun fromEntry(entry: LibraryEntry?): CastSource? {
            if (entry == null || entry.id.startsWith("ext:")) return null
            return when {
                entry.id.startsWith("podcast:") -> CastSource(
                    "podcast", entry.id.removePrefix("podcast:"))
                entry.kind == EntryKind.RADIO -> CastSource("radio", entry.id)
                entry.kind == EntryKind.TRACK -> CastSource("music", entry.id)
                entry.kind == EntryKind.FILE && !entry.audio ->
                    CastSource("video", entry.path)
                else -> null // TV needs a credential-free rewritten HLS manifest.
            }
        }
    }
}

/** A Cast receiver makes independent HTTPS requests; Android TLS pins/cookies do not transfer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastOutputSheet(
    session: TromuwelleSession,
    entry: LibraryEntry?,
    player: Player?,
    onClose: () -> Unit,
    onLocal: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cast = remember(context) {
        runCatching { CastContext.getSharedInstance(context) }.getOrNull()
    }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var connectedTick by remember { mutableIntStateOf(0) }
    // Cast SDK updates sessions asynchronously after the platform route picker closes.
    LaunchedEffect(cast) {
        while (isActive) {
            delay(1000)
            connectedTick++
        }
    }
    val castSession = remember(cast, connectedTick) {
        cast?.sessionManager?.currentCastSession
    }
    val active = castSession?.isConnected == true
    val deviceName = castSession?.castDevice?.friendlyName.orEmpty()
    val source = CastSource.fromEntry(entry)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CastConnected, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Auf Fernseher streamen", style = MaterialTheme.typography.headlineSmall)
                Text(if (active) "Verbunden mit $deviceName"
                    else "Chromecast, Google TV und Cast-Lautsprecher",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("Deine lokalen Audiogeräte und Bluetooth-Kopfhörer wählst du über " +
            "„Audioausgabe“; Cast sendet den Stream direkt vom Tromuwelle-Server.",
            style = MaterialTheme.typography.bodySmall)
        if (cast == null || BuildConfig.FLAVOR == "automotive") {
            Text("Cast ist auf diesem Gerät nicht verfügbar. Google Play-Dienste " +
                "und ein unterstütztes Android-Handy sind für die Google-Cast-Senderfunktion erforderlich.",
                color = MaterialTheme.colorScheme.error)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("1. Cast-Gerät auswählen", style = MaterialTheme.typography.titleMedium)
                    Text(if (active) "Gerät ausgewählt" else "Tippe auf das Cast-Symbol",
                        style = MaterialTheme.typography.bodySmall)
                }
                AndroidView(
                    factory = { ctx ->
                        MediaRouteButton(ctx).apply {
                            CastButtonFactory.setUpMediaRouteButton(ctx, this)
                        }
                    }, modifier = Modifier.size(58.dp))
                IconButton(onClick = { connectedTick++ }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Verbindung aktualisieren")
                }
            }
            HorizontalDivider()
            Text("2. Wiedergabe übertragen",
                style = MaterialTheme.typography.titleMedium)
            Text(entry?.title ?: "Wähle zuerst einen Titel, Podcast, Film oder Radiosender.",
                maxLines = 2, style = MaterialTheme.typography.bodyMedium)
            Button(enabled = active && source != null && !busy,
                onClick = {
                    val chosen = source ?: return@Button
                    val liveClient = cast?.sessionManager?.currentCastSession
                        ?.remoteMediaClient
                    if (liveClient == null) {
                        message = "Bitte zuerst ein Cast-Gerät verbinden."; return@Button
                    }
                    scope.launch {
                        busy = true
                        message = ""
                        try {
                            val result = withContext(Dispatchers.IO) {
                                JSONObject(session.post("/api/cast/ticket",
                                    JSONObject().put("kind", chosen.kind)
                                        .put("identifier", chosen.identifier).toString()))
                            }
                            val rawPath = result.getString("url")
                            require(rawPath.startsWith("/api/cast/stream/")) {
                                "Ungültiger Wiedergabelink des Servers."
                            }
                            val optionalLan = result.optString("lan_url")
                            val mediaUrl = if (optionalLan.isNotBlank()) {
                                // Only the server's expressly enabled LAN bridge may return
                                // a cleartext URL. It carries ONLY a random media grant,
                                // never an account cookie or an authenticated API request.
                                val candidate = Uri.parse(optionalLan)
                                val ip = candidate.host.orEmpty()
                                val validIp = ip.matches(
                                    Regex("""(?:[0-9]{1,3}\.){3}[0-9]{1,3}"""))
                                require(candidate.scheme == "http" && candidate.port == 8098 &&
                                    candidate.encodedPath == rawPath && validIp) {
                                    "Ungültiger LAN-Cast-Link vom Server."
                                }
                                optionalLan
                            } else session.url(rawPath)
                            val mime = result.getString("mime")
                            val castMetadata = MediaMetadata(if (chosen.kind == "video")
                                MediaMetadata.MEDIA_TYPE_MOVIE
                                else MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                                putString(MediaMetadata.KEY_TITLE, entry?.title.orEmpty())
                                putString(MediaMetadata.KEY_SUBTITLE, entry?.description.orEmpty())
                            }
                            val remoteInfo = MediaInfo.Builder(mediaUrl)
                                .setStreamType(if (chosen.kind == "radio")
                                    MediaInfo.STREAM_TYPE_LIVE
                                    else MediaInfo.STREAM_TYPE_BUFFERED)
                                .setContentType(mime)
                                .setMetadata(castMetadata)
                                .apply {
                                    if (chosen.kind != "radio" && (entry?.duration ?: 0) > 0)
                                        setStreamDuration(entry!!.duration * 1000)
                                }.build()
                            val request = MediaLoadRequestData.Builder()
                                .setMediaInfo(remoteInfo)
                                .setAutoplay(true)
                                .setCurrentTime(if (chosen.kind == "video" || chosen.kind == "radio")
                                    0L else (player?.currentPosition ?: 0L).coerceAtLeast(0L))
                                .build()
                            // Cast SDK requires main-thread operations; suspend I/O finished above.
                            liveClient.load(request).setResultCallback { feedback ->
                                message = if (feedback.status.isSuccess) {
                                    player?.pause()
                                    "Wiedergabe an $deviceName übertragen."
                                } else {
                                    "Cast-Gerät konnte den Stream nicht öffnen. " +
                                    "Prüfe Netzwerk, unterstützte Codecs und HTTPS-Zertifikat."
                                }
                            }
                        } catch (e: Exception) {
                            message = e.message ?: "Cast konnte nicht gestartet werden."
                        } finally {
                            busy = false
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Cast, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (busy) "Cast-Link wird vorbereitet …" else "Jetzt auf Cast abspielen")
            }
            if (active) OutlinedButton(onClick = {
                cast?.sessionManager?.endCurrentSession(true)
                connectedTick++
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Cast-Verbindung beenden")
            }
            if (source == null) {
                Text(if (entry?.kind == EntryKind.TV) "Live-TV benötigt einen Cast-kompatiblen " +
                    "HLS-Stream; dieser Modus ist noch nicht freigegeben."
                    else if (entry?.id?.startsWith("ext:") == true)
                    "Externes Subsonic-Streaming benötigt eine eigene, gesicherte Cast-Freigabe."
                    else "Starte zuerst eine kompatible Wiedergabe.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall)
            Text("Cast-Geräte müssen den Tromuwelle-Server selbst erreichen können. " +
                "Über HTTPS brauchen sie ein öffentlich vertrauenswürdiges Zertifikat; " +
                "die lokale Android-Zertifikatsfreigabe reicht dafür nicht. " +
                "Alternativ gibt es im Server einen ausdrücklich aktivierbaren LAN-Proxy " +
                "(Port 8098), der nur zeitlich begrenzte Medienlinks bereitstellt. " +
                "Der LAN-Stream ist unverschlüsselt und sollte nur in einem vertrauenswürdigen WLAN " +
                "verwendet werden. Kein Passwort und kein Session-Cookie werden übertragen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onLocal, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Speaker, null)
            Spacer(Modifier.width(8.dp))
            Text("Lautsprecher und Bluetooth wählen")
        }
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Schließen")
        }
    }
}
