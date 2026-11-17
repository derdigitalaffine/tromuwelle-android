package de.tromuwelle.android

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * Plays a 2-stem instrumental through the existing MediaLibraryService, or
 * six native Media3 stems on the visible stage. Independent Android audio
 * renderers have no shared sample clock: we therefore wait for ALL decoders,
 * stop the whole arrangement on rebuffering and continuously align the stem
 * timelines instead of starting six asynchronous play() requests.
 *
 * Methods are called on the player application (main) thread.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class KaraokeAudioEngine(
    private val context: Context,
    private val session: TromuwelleSession,
    private val song: KaraokeSong,
    private val artifact: KaraokeVersion,
    private val primary: Player?
) {
    private val api = KaraokeApi(session)
    private val players = linkedMapOf<String, ExoPlayer>()
    private val gains = mutableMapOf(
        "vocals" to 0f, "instrumental" to 1f, "drums" to 1f,
        "bass" to 1f, "guitar" to 1f, "piano" to 1f, "other" to 1f
    )
    private val names = if (artifact.profile == "6")
        listOf("drums", "bass", "guitar", "piano", "other", "vocals")
    else listOf("vocals")
    private val mainStem = if (artifact.profile == "6") "drums" else "instrumental"
    private val mediaId = "KARAOKE:" + artifact.id
    private var loudness = .85f
    private var tempo = 1f
    private var pitch = 1f
    private var vocalOffsetMs = 0L
    private val correctionRates = mutableMapOf<String, Float>()
    private var initialized = false
    private var released = false
    private var requestedPlayback = false
    private var internalPause = false
    private var reconciling = false

    val multiStem: Boolean get() = artifact.profile == "6"
    val availableFaders: List<String> get() = if (multiStem) names
        else listOf("instrumental", "vocals")

    private val leader: Player? get() = if (multiStem) players[mainStem]
        else primary?.takeIf { it.currentMediaItem?.mediaId == mediaId }
    private fun followers(): Map<String, ExoPlayer> = if (multiStem)
        players.filterKeys { it != mainStem } else players

    private val leaderListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                requestedPlayback = false
                pauseFollowers()
            } else reconcile()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) reconcile()
            else {
                pauseFollowers()
                // User/system paused the MediaSession outside this stage.
                // Never override an external pause or an audio-focus decision.
                val master = leader
                if (requestedPlayback && !internalPause &&
                    master?.playbackState == Player.STATE_READY &&
                    master.playWhenReady.not()) requestedPlayback = false
            }
        }
    }
    private val followerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) { reconcile() }
    }

    fun prepare() {
        if (initialized || released) return
        if (!multiStem) {
            val controller = requireNotNull(primary) {
                "Der Media3-Hintergrundplayer ist noch nicht bereit."
            }
            controller.addListener(leaderListener)
            controller.setMediaItem(MediaItem.Builder().setMediaId(mediaId)
                .setUri(api.stemUrl(artifact.id, "instrumental"))
                .setMimeType("audio/mp4")
                .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title)
                    .setArtist(song.artist).build())
                .build())
            controller.volume = loudness * gains.getValue("instrumental")
            controller.prepare()
        } else {
            primary?.pause()
        }
        val source = DefaultMediaSourceFactory(
            OkHttpDataSource.Factory(session.client))
        names.forEach { stem ->
            val decoder = ExoPlayer.Builder(context)
                .setMediaSourceFactory(source)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                    multiStem && stem == mainStem)
                .setHandleAudioBecomingNoisy(true)
                .build()
            players[stem] = decoder
            decoder.addListener(if (multiStem && stem == mainStem)
                leaderListener else followerListener)
            decoder.setMediaItem(MediaItem.Builder()
                .setUri(api.stemUrl(artifact.id, stem))
                .setMimeType("audio/mp4").build())
            decoder.volume = (loudness * gains.getValue(stem)).coerceIn(0f, 1f)
            decoder.prepare()
        }
        initialized = true
        updatePlaybackParameters()
    }

    private fun allReady(): Boolean {
        val master = leader ?: return false
        return initialized && players.size == names.size &&
            master.playbackState == Player.STATE_READY &&
            players.values.all { it.playbackState == Player.STATE_READY }
    }

    private fun pauseFollowers() {
        followers().values.forEach { if (it.playWhenReady) it.pause() }
    }

    private fun pauseForBuffering() {
        internalPause = true
        try {
            leader?.let { if (it.playWhenReady) it.pause() }
            pauseFollowers()
        } finally {
            internalPause = false
        }
    }

    /** A buffer barrier: a fast decoder never runs ahead of a late HTTP stem. */
    private fun reconcile() {
        if (!initialized || released || reconciling || !requestedPlayback) return
        reconciling = true
        try {
            if (!allReady()) {
                pauseForBuffering()
                return
            }
            val master = leader ?: return
            if (!master.playWhenReady) {
                alignFollowers(master.currentPosition, onlyHard = true)
                if (!allReady()) return
                // The decoders are already READY: issue the play commands in
                // one barrier turn instead of waiting one UI polling interval
                // before the backing vocals begin.
                followers().forEach { (stem, decoder) ->
                    if (stem != "vocals" || master.currentPosition >= vocalOffsetMs)
                        decoder.play()
                }
                master.play()
            }
            if (!master.isPlaying) {
                pauseFollowers()
                return
            }
            followers().forEach { (stem, decoder) ->
                if (stem == "vocals" && master.currentPosition < vocalOffsetMs) {
                    if (decoder.playWhenReady) decoder.pause()
                } else if (!decoder.playWhenReady) {
                    val target = targetPosition(stem, master.currentPosition)
                    if (kotlin.math.abs(decoder.currentPosition - target) >= 60L)
                        decoder.seekTo(target)
                    if (decoder.playbackState == Player.STATE_READY) decoder.play()
                }
            }
            if (!allReady()) pauseForBuffering()
        } finally {
            reconciling = false
        }
    }

    private fun targetPosition(stem: String, masterMs: Long): Long =
        (masterMs - if (stem == "vocals") vocalOffsetMs else 0L)
            .coerceAtLeast(0L)

    private fun alignFollowers(masterMs: Long, onlyHard: Boolean) {
        followers().forEach { (stem, decoder) ->
            if (decoder.playbackState != Player.STATE_READY) return@forEach
            val action = stemSyncAdjustment(masterMs, decoder.currentPosition,
                if (stem == "vocals") vocalOffsetMs else 0L)
            if (action.seekToMs != null) {
                decoder.seekTo(action.seekToMs)
                correctionRates[stem] = 1f
                applyParameters(stem, decoder)
            } else if (!onlyHard) {
                val previous = correctionRates[stem] ?: 1f
                if (kotlin.math.abs(previous - action.rate) >= .003f) {
                    correctionRates[stem] = action.rate
                    applyParameters(stem, decoder)
                }
            }
        }
    }

    fun play() {
        if (released || !initialized) return
        requestedPlayback = true
        reconcile()
    }

    fun pause() {
        requestedPlayback = false
        pauseForBuffering()
        correctionRates.clear()
        updatePlaybackParameters()
    }

    fun stop() {
        pause()
        if (!multiStem && primary?.currentMediaItem?.mediaId == mediaId) {
            primary.stop()
            primary.clearMediaItems()
        }
    }

    fun release() {
        if (released) return
        released = true
        if (!multiStem) primary?.removeListener(leaderListener)
        players.forEach { (stem, player) ->
            player.removeListener(if (multiStem && stem == mainStem)
                leaderListener else followerListener)
            player.release()
        }
        players.clear()
        correctionRates.clear()
    }

    fun seek(positionMs: Long) {
        if (!initialized || released) return
        val at = positionMs.coerceAtLeast(0L)
        val resume = requestedPlayback
        pauseForBuffering()
        if (!multiStem) leader?.seekTo(at)
        players.forEach { (stem, decoder) ->
            decoder.seekTo(targetPosition(stem, at))
        }
        correctionRates.clear()
        updatePlaybackParameters()
        requestedPlayback = resume
        reconcile()
    }

    fun setGain(stem: String, gain: Float) {
        if (stem !in gains) return
        gains[stem] = gain.coerceIn(0f, 1.5f)
        if (stem == "instrumental" && !multiStem) {
            leader?.volume = (loudness * gains.getValue(stem)).coerceIn(0f, 1f)
        } else players[stem]?.volume =
            (loudness * gains.getValue(stem)).coerceIn(0f, 1f)
    }

    fun setVolume(value: Float) {
        loudness = value.coerceIn(0f, 1f)
        if (!multiStem) leader?.volume =
            (loudness * gains.getValue("instrumental")).coerceIn(0f, 1f)
        players.forEach { (stem, player) ->
            player.volume = (loudness * gains.getValue(stem)).coerceIn(0f, 1f)
        }
    }

    /** Positive values make vocals audible later than the instrumental track. */
    fun setVocalOffsetMs(value: Int) {
        vocalOffsetMs = value.coerceIn(-400, 400).toLong()
        if (initialized && !released) {
            correctionRates.remove("vocals")
            players["vocals"]?.let { applyParameters("vocals", it) }
            if (playing) synchronize()
        }
    }

    fun setTempo(value: Float) {
        tempo = value.coerceIn(.7f, 1.3f)
        updatePlaybackParameters()
    }

    fun setPitch(semitones: Int) {
        pitch = Math.pow(2.0, semitones.coerceIn(-5, 5) / 12.0).toFloat()
        updatePlaybackParameters()
    }

    private fun applyParameters(stem: String, player: Player) {
        player.playbackParameters = PlaybackParameters(
            tempo * (correctionRates[stem] ?: 1f), pitch)
    }
    private fun updatePlaybackParameters() {
        if (!multiStem) leader?.playbackParameters = PlaybackParameters(tempo, pitch)
        players.forEach { (stem, player) -> applyParameters(stem, player) }
    }

    val playing: Boolean get() = leader?.isPlaying == true
    val buffering: Boolean get() = requestedPlayback && !playing
    val positionMs: Long get() = leader?.currentPosition?.coerceAtLeast(0L) ?: 0L
    val durationMs: Long get() {
        val duration = leader?.duration
        return duration?.takeIf { it > 0 && it != C.TIME_UNSET }
            ?: (song.duration * 1000.0).toLong().coerceAtLeast(0L)
    }
    val ended: Boolean get() = leader?.playbackState == Player.STATE_ENDED

    /** Called by the stage every 150 ms; corrections never touch the master clock. */
    fun synchronize() {
        if (!initialized || released || !requestedPlayback) return
        if (!allReady()) {
            pauseForBuffering()
            return
        }
        reconcile()
        val master = leader ?: return
        if (!master.isPlaying) return
        followers().forEach { (stem, decoder) ->
            if (stem == "vocals" && master.currentPosition < vocalOffsetMs) return@forEach
            if (!decoder.isPlaying) return@forEach
            val action = stemSyncAdjustment(master.currentPosition,
                decoder.currentPosition, if (stem == "vocals") vocalOffsetMs else 0L)
            if (action.seekToMs != null) {
                decoder.seekTo(action.seekToMs)
                correctionRates[stem] = 1f
                applyParameters(stem, decoder)
            } else {
                val previous = correctionRates[stem] ?: 1f
                if (kotlin.math.abs(previous - action.rate) >= .003f) {
                    correctionRates[stem] = action.rate
                    applyParameters(stem, decoder)
                }
            }
        }
    }
}
