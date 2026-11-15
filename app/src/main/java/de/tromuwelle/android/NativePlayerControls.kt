package de.tromuwelle.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Native equivalent of TV quality, language, subtitles and music transport controls. */
@Composable
fun NativePlayerControls(player: Player, isLive: Boolean, isAudio: Boolean,
                         canSeek: Boolean = true,
                         onFullscreen: () -> Unit = {}, onPip: () -> Unit = {}) {
    var duration by remember(player) { mutableLongStateOf(0L) }
    var position by remember(player) { mutableLongStateOf(0L) }
    var volume by remember(player) { mutableFloatStateOf(player.volume) }
    var selectedQuality by remember { mutableIntStateOf(0) }
    var trackVersion by remember { mutableIntStateOf(0) }
    var trackDialog by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var repeat by remember { mutableIntStateOf(player.repeatMode) }
    var shuffle by remember { mutableStateOf(player.shuffleModeEnabled) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) { trackVersion++ }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (isActive) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            repeat = player.repeatMode
            shuffle = player.shuffleModeEnabled
            delay(650)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (duration > 0 && !isLive && canSeek) {
            Slider(value = position.toFloat().coerceIn(0f, duration.toFloat()),
                onValueChange = { position = it.toLong() },
                onValueChangeFinished = { player.seekTo(position) },
                valueRange = 0f..duration.toFloat())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatMediaTime(position), style = MaterialTheme.typography.labelSmall)
                Text(formatMediaTime(duration), style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (player.mediaItemCount > 1) {
                TextButton(onClick = { player.seekToPreviousMediaItem() }) { Text("⏮") }
            }
            Button(onClick = { if(player.isPlaying) player.pause() else player.play() }) {
                Text(if (player.isPlaying) "Pause" else "Wiedergabe")
            }
            if (player.mediaItemCount > 1) {
                TextButton(onClick = { player.seekToNextMediaItem() }) { Text("⏭") }
            }
            if (isLive) {
                OutlinedButton(onClick = { player.seekToDefaultPosition() }) { Text("● Live") }
            } else if (duration > 0 && canSeek) {
                OutlinedButton(onClick = { player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L)) }) {
                    Text("−10 s")
                }
                OutlinedButton(onClick = { player.seekTo((player.currentPosition + 10_000L).coerceAtMost(duration)) }) {
                    Text("+10 s")
                }
            }
            if (!isAudio) {
                TextButton(onClick = onFullscreen) { Text("Vollbild") }
                TextButton(onClick = onPip) { Text("PiP") }
            }
        }

        if (!canSeek && !isLive && !isAudio) {
            Text("Kompatibler MP4-Livestream: für Positionssprünge unten eine neue Startzeit wählen.",
                style = MaterialTheme.typography.bodySmall)
        }
        if (isAudio) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = shuffle, onClick = {
                    shuffle = !shuffle; player.shuffleModeEnabled = shuffle
                }, label = { Text("Zufall") })
                FilterChip(selected = repeat != Player.REPEAT_MODE_OFF, onClick = {
                    repeat = when(repeat) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    player.repeatMode = repeat
                }, label = { Text(when(repeat) {
                    Player.REPEAT_MODE_OFF -> "Wiederholen aus"
                    Player.REPEAT_MODE_ONE -> "Titel wiederholen"
                    else -> "Liste wiederholen"
                }) })
            }
        } else if(isLive) {
            Text("HLS-Qualität (maximal)", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 360, 480, 720, 1080, 1440, 2160).forEach { height ->
                    FilterChip(selected = selectedQuality == height, onClick = {
                        selectedQuality = height
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setMaxVideoSize(Int.MAX_VALUE, if(height == 0) Int.MAX_VALUE else height)
                            .build()
                    }, label = { Text(if(height == 0) "Auto" else height.toString() + "p") })
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Lautstärke", style = MaterialTheme.typography.labelMedium)
            Slider(modifier = Modifier.weight(1f), value = volume,
                onValueChange = { volume = it; player.volume = it })
            TextButton(onClick = { trackDialog = true }) { Text("Ton / Text") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                FilterChip(selected = speed == s, onClick = {
                    speed = s; player.setPlaybackSpeed(s)
                }, label = { Text(s.toString() + "×") })
            }
        }
    }

    if (trackDialog) {
        // trackVersion ensures Compose reads new groups after a manifest/track change.
        val groups = remember(player, trackVersion) { player.currentTracks.groups }
        AlertDialog(onDismissRequest = { trackDialog = false },
            title = { Text("Tonspuren und Untertitel") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val audio = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    val subtitles = groups.filter { it.type == C.TRACK_TYPE_TEXT }
                    Text("Ton", style = MaterialTheme.typography.titleSmall)
                    if (audio.isEmpty()) Text("Keine wählbaren Tonspuren.")
                    audio.forEach { group ->
                        for (i in 0 until group.length) {
                            val f = group.getTrackFormat(i)
                            TextButton(onClick = {
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                                    .build()
                                trackDialog = false
                            }) {
                                Text((f.language ?: "Original") + " · " + (f.label ?: "Spur " + (i + 1)) +
                                    if(group.isTrackSelected(i)) " ✓" else "")
                            }
                        }
                    }
                    HorizontalDivider()
                    Text("Untertitel", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                        trackDialog = false
                    }) { Text("Aus") }
                    if(subtitles.isEmpty()) Text("Keine wählbaren Untertitel.")
                    subtitles.forEach { group ->
                        for (i in 0 until group.length) {
                            val f = group.getTrackFormat(i)
                            TextButton(onClick = {
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                                    .build()
                                trackDialog = false
                            }) { Text((f.language ?: "Unbekannt") + " · " + (f.label ?: "Untertitel " + (i + 1))) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { trackDialog = false }) { Text("Fertig") } })
    }
}

private fun formatMediaTime(ms: Long): String {
    val seconds = (ms / 1000L).coerceAtLeast(0L)
    return if (seconds >= 3600L) "%d:%02d:%02d".format(seconds / 3600L, (seconds / 60L) % 60L, seconds % 60L)
    else "%d:%02d".format(seconds / 60L, seconds % 60L)
}
