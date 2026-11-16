package de.tromuwelle.android

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The standard Cast device picker is intentional: enabling Android 13's
 * system output switcher without the MediaTransferReceiver/remote-session
 * integration can make media routes appear connected without a Cast session.
 */
class TromuCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setShowSystemOutputSwitcherOnCastIconClick(false)
            .setEnableReconnectionService(true)
            .setResumeSavedSession(true)
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
                else -> null // TV requires a credential-free rewritten HLS manifest.
            }
        }
    }
}

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
        if (BuildConfig.FLAVOR == "automotive") null else
            runCatching { CastContext.getSharedInstance(context) }.getOrNull()
    }
    var castSession by remember(cast) {
        mutableStateOf(cast?.sessionManager?.currentCastSession)
    }
    var message by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    var mediaRevision by remember { mutableIntStateOf(0) }
    var remotePosition by remember { mutableLongStateOf(0L) }
    var remoteDuration by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    val remote = castSession?.takeIf { it.isConnected }?.remoteMediaClient
    val active = castSession?.isConnected == true && remote != null
    val deviceName = castSession?.castDevice?.friendlyName.orEmpty()
    val source = CastSource.fromEntry(entry)

    // Cast sessions survive the composable; event callbacks must be tied to
    // its lifecycle, not a one-second polling loop.
    DisposableEffect(cast) {
        if (cast == null) return@DisposableEffect onDispose { }
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarting(session: CastSession) {
                message = "Verbindung zum Cast-Gerät wird hergestellt …"
                failure = false
            }
            override fun onSessionStarted(session: CastSession, sessionId: String) {
                castSession = session
                message = "Gerät verbunden. Du kannst jetzt Medien übertragen."
                failure = false
            }
            override fun onSessionStartFailed(session: CastSession, error: Int) {
                castSession = null; pendingUrl = null; busy = false
                message = "Cast-Verbindung fehlgeschlagen. Prüfe WLAN und Google Play-Dienste."
                failure = true
            }
            override fun onSessionEnding(session: CastSession) {
                pendingUrl = null
                busy = false
            }
            override fun onSessionEnded(session: CastSession, error: Int) {
                castSession = null; pendingUrl = null; busy = false
                message = "Cast-Verbindung beendet."
                failure = false
            }
            override fun onSessionResuming(session: CastSession, sessionId: String) {
                message = "Cast-Verbindung wird wiederhergestellt …"
                failure = false
            }
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                castSession = session
                message = "Cast-Verbindung wiederhergestellt."
                failure = false
            }
            override fun onSessionResumeFailed(session: CastSession, error: Int) {
                castSession = null; pendingUrl = null; busy = false
                message = "Cast-Verbindung konnte nicht wiederhergestellt werden."
                failure = true
            }
            override fun onSessionSuspended(session: CastSession, reason: Int) {
                castSession = null; pendingUrl = null; busy = false
                message = "Verbindung unterbrochen. Cast versucht die Wiederherstellung."
                failure = true
            }
        }
        cast.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
        castSession = cast.sessionManager.currentCastSession
        onDispose {
            cast.sessionManager.removeSessionManagerListener(listener, CastSession::class.java)
        }
    }

    DisposableEffect(remote) {
        if (remote == null) return@DisposableEffect onDispose { }
        val callback = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() { mediaRevision++ }
            override fun onMetadataUpdated() { mediaRevision++ }
            override fun onMediaError(error: com.google.android.gms.cast.MediaError) {
                pendingUrl = null; busy = false
                failure = true
                message = "Der Fernseher konnte die Datei nicht abspielen. " +
                    "Prüfe Codec, Cast-LAN-Zugriff und Zertifikat."
                mediaRevision++
            }
        }
        val progress = RemoteMediaClient.ProgressListener { position, duration ->
            if (!scrubbing) remotePosition = position.coerceAtLeast(0L)
            remoteDuration = duration.coerceAtLeast(0L)
        }
        remote.registerCallback(callback)
        remote.addProgressListener(progress, 1000L)
        remote.requestStatus()
        onDispose {
            remote.unregisterCallback(callback)
            remote.removeProgressListener(progress)
        }
    }

    // A successful LOAD request means only that the receiver accepted a
    // command, NOT that it fetched/decoded the media. Stop local sound only
    // after receiver playback for this exact ticket is confirmed.
    LaunchedEffect(mediaRevision, pendingUrl, remote) {
        val pending = pendingUrl ?: return@LaunchedEffect
        val current = remote ?: return@LaunchedEffect
        val info = current.mediaStatus?.mediaInfo
        if (info?.contentId == pending && current.isPlaying) {
            player?.pause()
            pendingUrl = null
            busy = false
            failure = false
            message = "Wiedergabe läuft auf " + deviceName.ifBlank { "dem Cast-Gerät" } + "."
        }
    }
    LaunchedEffect(pendingUrl, remote) {
        val requested = pendingUrl ?: return@LaunchedEffect
        delay(30_000L)
        if (pendingUrl == requested) {
            pendingUrl = null; busy = false
            failure = true
            message = "Cast hat den Stream noch nicht gestartet. " +
                "Die Wiedergabe bleibt auf diesem Gerät. Prüfe, ob der Fernseher " +
                "den Server per HTTPS oder über den Cast-LAN-Proxy erreichen kann."
        }
    }
    val statusLabel = when {
        !active -> "Kein Cast-Gerät verbunden"
        remote?.isPlaying == true -> "Wiedergabe auf dem Fernseher"
        remote?.isBuffering == true -> "Fernseher puffert den Stream …"
        remote?.isPaused == true -> "Wiedergabe am Fernseher pausiert"
        remote?.hasMediaSession() == true -> "Medienempfänger bereit"
        else -> "Bereit zur Übertragung"
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 650.dp)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (active) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Chromecast", style = MaterialTheme.typography.headlineSmall)
                Text(if (active) deviceName.ifBlank { "Cast-Gerät" } else
                    "Google TV, Chromecast und Cast-Lautsprecher",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (active) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text(statusLabel, modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (cast == null) {
            Text("Google Cast ist auf diesem Gerät nicht verfügbar. " +
                "Auf Android Automotive wird die Senderfunktion nicht angeboten.",
                color = MaterialTheme.colorScheme.error)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Wiedergabegerät", style = MaterialTheme.typography.titleMedium)
                    Text(if (active) "Zum Wechseln auf das Cast-Symbol tippen" else
                        "Gerät im selben Netzwerk auswählen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AndroidView(factory = { ctx ->
                    MediaRouteButton(ctx).apply {
                        CastButtonFactory.setUpMediaRouteButton(ctx, this)
                    }
                }, modifier = Modifier.size(56.dp))
            }
            HorizontalDivider()
            Text("Medien übertragen", style = MaterialTheme.typography.titleMedium)
            Text(entry?.title ?: "Starte zuerst Musik, Podcast, Radio oder einen Film.",
                style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            Button(enabled = active && source != null && !busy,
                onClick = {
                    val chosen = source ?: return@Button
                    val castClient = cast.sessionManager.currentCastSession?.remoteMediaClient
                    if (castClient == null) {
                        message = "Bitte zuerst ein Cast-Gerät verbinden."
                        failure = true
                        return@Button
                    }
                    scope.launch {
                        busy = true
                        failure = false
                        message = "Sicheren Medienlink vorbereiten …"
                        try {
                            val ticket = withContext(Dispatchers.IO) {
                                JSONObject(session.post("/api/cast/ticket",
                                    JSONObject().put("kind", chosen.kind)
                                        .put("identifier", chosen.identifier).toString()))
                            }
                            val rawPath = ticket.getString("url")
                            val mediaUrl = resolveCastStreamUrl(
                                rawPath, session.url(rawPath), ticket.optString("lan_url"))
                            val mime = ticket.getString("mime")
                            require(mime.matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+"))) {
                                "Ungültiger Medientyp des Cast-Streams."
                            }
                            val currentSession = cast.sessionManager.currentCastSession
                            require(currentSession?.isConnected == true &&
                                currentSession.remoteMediaClient === castClient) {
                                "Cast-Verbindung wurde während des Ladens unterbrochen."
                            }
                            val info = MediaInfo.Builder(mediaUrl)
                                .setStreamType(if (chosen.kind == "radio")
                                    MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
                                .setContentType(mime)
                                .setMetadata(MediaMetadata(
                                    if (chosen.kind == "video") MediaMetadata.MEDIA_TYPE_MOVIE
                                    else MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                                    putString(MediaMetadata.KEY_TITLE, entry?.title.orEmpty())
                                    putString(MediaMetadata.KEY_SUBTITLE, entry?.description.orEmpty())
                                })
                                .apply {
                                    if (chosen.kind != "radio" && (entry?.duration ?: 0L) > 0L)
                                        setStreamDuration(entry!!.duration * 1000L)
                                }
                                .build()
                            val originalTitle = player?.mediaMetadata?.title?.toString()
                            val resumePosition = if (chosen.kind != "radio" &&
                                originalTitle == entry?.title)
                                (player?.currentPosition ?: 0L).coerceAtLeast(0L) else 0L
                            val request = MediaLoadRequestData.Builder()
                                .setMediaInfo(info)
                                .setAutoplay(true)
                                .setCurrentTime(resumePosition)
                                .build()
                            pendingUrl = mediaUrl
                            message = "Receiver lädt den Stream. Lokale Wiedergabe bleibt aktiv …"
                            castClient.load(request).setResultCallback { outcome ->
                                if (pendingUrl != mediaUrl) return@setResultCallback
                                if (outcome.status.isSuccess) {
                                    message = "Cast lädt die Medien. Wiedergabestart wird bestätigt …"
                                    mediaRevision++
                                } else {
                                    pendingUrl = null
                                    busy = false
                                    failure = true
                                    message = "Cast konnte den Stream nicht öffnen (Status " +
                                        outcome.status.statusCode + "). Prüfe Erreichbarkeit und Codec."
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            busy = false
                            pendingUrl = null
                            failure = true
                            message = e.message ?: "Cast konnte nicht gestartet werden."
                        }
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp),
                    strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Icon(Icons.Rounded.Cast, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (busy) "Wird übertragen …" else "Auf Fernseher abspielen")
            }
            if (active && remote?.hasMediaSession() == true) {
                HorizontalDivider()
                Text("Fernbedienung", style = MaterialTheme.typography.titleMedium)
                val playing = remote.isPlaying
                if (!remote.isLiveStream && remoteDuration > 0L) {
                    Slider(value = remotePosition.toFloat()
                        .coerceIn(0f, remoteDuration.toFloat()),
                        onValueChange = { scrubbing = true; remotePosition = it.toLong() },
                        onValueChangeFinished = {
                            remote.seek(remotePosition.coerceIn(0L, remoteDuration))
                            scrubbing = false
                        },
                        valueRange = 0f..remoteDuration.toFloat())
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatMediaTime(remotePosition),
                            style = MaterialTheme.typography.labelSmall)
                        Text(formatMediaTime(remoteDuration),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(onClick = {
                        if (playing) remote.pause() else remote.play()
                    }, modifier = Modifier.size(52.dp)) {
                        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (playing) "Cast pausieren" else "Cast starten")
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = {
                        remote.stop()
                        pendingUrl = null
                        busy = false
                        message = "Wiedergabe am Fernseher angehalten."
                        failure = false
                    }) {
                        Icon(Icons.Rounded.Stop, contentDescription = null)
                        Spacer(Modifier.width(5.dp))
                        Text("Stop")
                    }
                }
                OutlinedButton(onClick = {
                    cast.sessionManager.endCurrentSession(true)
                    player?.play()
                    onLocal()
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.PhoneAndroid, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Auf diesem Gerät fortsetzen")
                }
            }
            if (source == null) {
                Text(when {
                    entry?.kind == EntryKind.TV ->
                        "Live-TV benötigt einen für Cast umgeschriebenen HLS-Stream."
                    entry?.id?.startsWith("ext:") == true ->
                        "Externe Subsonic-Server benötigen eine eigene sichere Cast-Freigabe."
                    else -> "Starte zuerst kompatible Musik, Radio, Podcast oder Video."
                }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (message.isNotBlank()) {
                Surface(color = if (failure) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp)) {
                    Text(message, Modifier.fillMaxWidth().padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failure) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Text("Cast-Geräte laden Dateien direkt vom Server. Für HTTPS brauchen sie " +
                "ein öffentlich vertrauenswürdiges Zertifikat; die Android-Zertifikatsfreigabe " +
                "wird nicht mit dem Fernseher geteilt. Alternativ kann der optionale " +
                "Cast-LAN-Proxy (Port 8098) auf einem vertrauenswürdigen Heimnetz " +
                "aktiviert werden. Der Proxy nutzt kurzlebige, mediengebundene Tickets, " +
                "keine Benutzerpasswörter oder Cookies.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onLocal, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Speaker, contentDescription = null)
            Spacer(Modifier.width(7.dp))
            Text("Audioausgabe und Bluetooth auswählen")
        }
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Schließen")
        }
    }
}
