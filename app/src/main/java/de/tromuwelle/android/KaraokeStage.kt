package de.tromuwelle.android

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private val stageNight = Color(0xFF0A0C19)
private val stageViolet = Color(0xFFBAA4FF)
private val stageMuted = Color(0xFFBDC1D8)

/** Stage owns its mixer across layout changes: full screen is opt-in, never default. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KaraokeStage(
    session: TromuwelleSession,
    player: Player?,
    song: KaraokeSong,
    artifact: KaraokeVersion,
    onClose: () -> Unit,
    onFinished: () -> Unit,
    onTransport: (Boolean?, Double?) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val view = LocalView.current
    val api = remember(session) { KaraokeApi(session) }
    val engine = remember(session.server, artifact.id, player) {
        KaraokeAudioEngine(context, session, song, artifact, player)
    }
    val lease = remember(artifact.id) { UUID.randomUUID().toString() }
    var fullscreen by remember(artifact.id) { mutableStateOf(false) }
    var studio by remember(artifact.id) { mutableStateOf(false) }
    var keepAwake by remember(artifact.id) { mutableStateOf(true) }
    var lyricScale by remember(artifact.id) { mutableFloatStateOf(1f) }
    var position by remember(artifact.id) { mutableLongStateOf(0L) }
    var duration by remember(artifact.id) { mutableLongStateOf((song.duration * 1000).toLong()) }
    var playing by remember(artifact.id) { mutableStateOf(false) }
    var ready by remember(artifact.id) { mutableStateOf(false) }
    var lyrics by remember(artifact.id) { mutableStateOf<List<KaraokeLine>>(emptyList()) }
    var error by remember(artifact.id) { mutableStateOf("") }
    var scrub by remember(artifact.id) { mutableFloatStateOf(-1f) }
    var volume by remember(artifact.id) { mutableFloatStateOf(.85f) }
    var tempo by remember(artifact.id) { mutableFloatStateOf(1f) }
    var pitch by remember(artifact.id) { mutableIntStateOf(0) }
    val faders = remember(artifact.id) { mutableStateMapOf<String, Float>().apply {
        for (name in engine.availableFaders) this[name] = if (name == "vocals") 0f else 1f
    } }
    val frame = remember(lyrics, position) { karaokeFrame(lyrics, position / 1000.0) }
    var completed by remember(artifact.id) { mutableStateOf(false) }
    val stageActive = rememberUpdatedState(onFinished)
    val stageTransport = rememberUpdatedState(onTransport)

    LaunchedEffect(engine) {
        try {
            withContext(Dispatchers.IO) { api.playback(artifact.id, lease) }
            lyrics = withContext(Dispatchers.IO) { api.lyrics(song.id) }
            engine.prepare()
            ready = true
            engine.play()
            playing = true
            stageTransport.value(true, 0.0)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Karaoke-Titel konnte nicht gestartet werden." }
        var ticks = 0
        while (isActive) {
            delay(150L)
            if (ready) {
                engine.synchronize()
                position = engine.positionMs
                duration = engine.durationMs
                playing = engine.playing
                if (engine.ended && !completed) {
                    completed = true
                    stageActive.value()
                }
            }
            ticks++
            if (ticks >= 100) {
                ticks = 0
                try { withContext(Dispatchers.IO) { api.playback(artifact.id, lease) } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = "Die Serververbindung ist unterbrochen: " +
                    (e.message ?: "Lease konnte nicht erneuert werden.") }
            }
        }
    }
    DisposableEffect(engine) {
        onDispose {
            engine.stop()
            engine.release()
            // Release independently of the composition scope being cancelled.
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { api.release(artifact.id, lease) }
            }
        }
    }
    DisposableEffect(view, keepAwake, playing) {
        val previous = view.keepScreenOn
        view.keepScreenOn = keepAwake && playing
        onDispose { view.keepScreenOn = previous }
    }
    BackHandler(fullscreen) { fullscreen = false }
    BackHandler(!fullscreen) { onClose() }

    val screen: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(stageNight, Color(0xFF17132D), Color(0xFF07151C))))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.GraphicEq, null, tint = stageViolet)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, color = Color.White,
                        fontWeight = FontWeight.ExtraBold, maxLines = 1,
                        style = MaterialTheme.typography.titleMedium)
                    Text(song.artist + "  ·  " + artifact.profile + " Stems",
                        color = stageMuted, style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { fullscreen = !fullscreen }) {
                    Icon(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                        contentDescription = if (fullscreen) "Vollbild verlassen" else "Vollbild aktivieren",
                        tint = Color.White)
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = "Karaoke-Player beenden",
                        tint = Color.White)
                }
            }
            if (!ready) LinearProgressIndicator(Modifier.fillMaxWidth(), color = stageViolet)
            if (error.isNotBlank()) Text(error, color = Color(0xFFFFB5B5),
                style = MaterialTheme.typography.bodySmall)
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Brush.radialGradient(listOf(
                        Color(0xFF353065), Color(0xFF141A2D), stageNight))),
                contentAlignment = Alignment.Center
            ) {
                if (lyrics.isEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Mic, contentDescription = null,
                            tint = stageViolet, modifier = Modifier.size(60.dp))
                        Spacer(Modifier.height(14.dp))
                        Text("Bühne bereit", color = Color.White,
                            style = MaterialTheme.typography.headlineSmall)
                        Text("Für diesen Song liegen noch keine synchronisierten Lyrics vor.",
                            color = stageMuted, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp))
                    }
                } else Column(
                    Modifier.fillMaxWidth().padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(19.dp)
                ) {
                    Text(frame.previous?.text.orEmpty(), color = stageMuted.copy(alpha = .64f),
                        textAlign = TextAlign.Center, maxLines = 2,
                        style = MaterialTheme.typography.bodyMedium)
                    AnimatedContent(targetState = frame.current?.text ?: "",
                        label = "Karaoke-Liedtext",
                        transitionSpec = {
                            (fadeIn() + slideInVertically { it / 5 }) togetherWith
                                (fadeOut() + slideOutVertically { -it / 5 })
                        }) { shown ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (shown.isNotBlank()) {
                                val active = frame.current
                                val words = active?.words.orEmpty()
                                if (words.isNotEmpty()) {
                                    Row(Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                        words.forEach { word ->
                                            Text(word.text, color = if (position / 1000.0 >= word.start)
                                                stageViolet else Color.White,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = (27f * lyricScale).sp)
                                        }
                                    }
                                } else Text(shown, color = Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = (29f * lyricScale).sp,
                                    lineHeight = (36f * lyricScale).sp,
                                    textAlign = TextAlign.Center)
                            } else if (frame.countdown != null) {
                                Text("${frame.countdown}", color = stageViolet,
                                    fontSize = (46f * lyricScale).sp,
                                    fontWeight = FontWeight.ExtraBold)
                                Text("Instrumentalpause", color = stageMuted)
                            } else if (frame.outro) {
                                Icon(Icons.Rounded.AutoAwesome, null,
                                    tint = stageViolet, modifier = Modifier.size(38.dp))
                                Text("Instrumental-Outro", color = stageMuted)
                            } else Text("♪", color = stageViolet, fontSize = 42.sp)
                        }
                    }
                    Text(frame.next?.text.orEmpty(), color = stageViolet.copy(alpha = .74f),
                        fontSize = (18f * lyricScale).sp, textAlign = TextAlign.Center,
                        maxLines = 2)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Slider(value = if (scrub >= 0) scrub else position.toFloat()
                    .coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                    onValueChange = { scrub = it },
                    onValueChangeFinished = {
                        if (scrub >= 0) {
                            engine.seek(scrub.toLong())
                            stageTransport.value(null, scrub / 1000.0)
                        }
                        scrub = -1f
                    }, valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                    colors = SliderDefaults.colors(thumbColor = stageViolet,
                        activeTrackColor = stageViolet))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(karaokeClock(position), color = stageMuted)
                    Text(karaokeClock(duration), color = stageMuted)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    engine.seek((engine.positionMs - 10_000L).coerceAtLeast(0L))
                    stageTransport.value(null, engine.positionMs / 1000.0)
                }, enabled = ready) {
                    Icon(Icons.Rounded.Replay10, "10 Sekunden zurück", tint = Color.White)
                }
                FilledIconButton(onClick = {
                    if (engine.playing) engine.pause() else engine.play()
                    playing = engine.playing
                    stageTransport.value(playing, engine.positionMs / 1000.0)
                }, enabled = ready,
                    modifier = Modifier.size(60.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = stageViolet, contentColor = stageNight)) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (playing) "Pause" else "Wiedergabe")
                }
                IconButton(onClick = {
                    engine.seek((engine.positionMs + 10_000L).coerceAtMost(duration))
                    stageTransport.value(null, engine.positionMs / 1000.0)
                }, enabled = ready) {
                    Icon(Icons.Rounded.Forward10, "10 Sekunden vor", tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = onFinished, enabled = ready) {
                    Text("Nächster Song", color = stageViolet)
                    Icon(Icons.Rounded.SkipNext, null, tint = stageViolet)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.VolumeUp, null, tint = stageMuted)
                Slider(value = volume, onValueChange = { volume = it; engine.setVolume(it) },
                    modifier = Modifier.weight(1f), valueRange = 0f..1f,
                    colors = SliderDefaults.colors(thumbColor = stageViolet,
                        activeTrackColor = stageViolet))
                TextButton(onClick = { studio = !studio }) {
                    Icon(Icons.Rounded.Tune, null, tint = stageViolet)
                    Text(if (studio) "Regie aus" else "Regie", color = stageViolet)
                }
            }
            if (studio) Column(
                Modifier.fillMaxWidth().heightIn(max = 230.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("STUDIO & BÜHNE", color = stageViolet, fontWeight = FontWeight.Bold)
                engine.availableFaders.forEach { name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(when (name) {
                            "vocals" -> "Gesang"; "drums" -> "Schlagzeug"
                            "bass" -> "Bass"; "guitar" -> "Gitarre"
                            "piano" -> "Klavier"; "other" -> "Weitere"
                            else -> "Instrumental"
                        }, color = Color.White, modifier = Modifier.width(110.dp),
                            style = MaterialTheme.typography.bodySmall)
                        Slider(value = faders[name] ?: 1f,
                            onValueChange = {
                                faders[name] = it
                                engine.setGain(name, it)
                            }, valueRange = 0f..1.5f,
                            modifier = Modifier.weight(1f))
                    }
                }
                Text("Tempo ${"%.2f".format(tempo)}×", color = stageMuted)
                Slider(value = tempo, onValueChange = {
                    tempo = it; engine.setTempo(it)
                }, valueRange = .7f..1.3f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tonhöhe ${if (pitch > 0) "+" else ""}$pitch", color = stageMuted,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = { pitch = (pitch - 1).coerceAtLeast(-5); engine.setPitch(pitch) }) {
                        Icon(Icons.Rounded.Remove, "Tonhöhe senken", tint = Color.White)
                    }
                    IconButton(onClick = { pitch = (pitch + 1).coerceAtMost(5); engine.setPitch(pitch) }) {
                        Icon(Icons.Rounded.Add, "Tonhöhe erhöhen", tint = Color.White)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Lyrics-Größe", color = stageMuted, modifier = Modifier.width(110.dp))
                    Slider(value = lyricScale, onValueChange = { lyricScale = it },
                        valueRange = .8f..1.5f, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bühnenbildschirm wach halten", color = Color.White,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall)
                    Switch(checked = keepAwake, onCheckedChange = { keepAwake = it })
                }
                if (engine.multiStem) Text(
                    "6-Stem-Studio: Für den Mehrspur-Mix muss die Bühne geöffnet bleiben. " +
                        "2-Stem-Versionen laufen über den Android-Hintergrundplayer.",
                    color = stageMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (fullscreen) {
        Dialog(onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            screen()
        }
    } else screen()
}

private fun karaokeClock(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) / 1000L)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
