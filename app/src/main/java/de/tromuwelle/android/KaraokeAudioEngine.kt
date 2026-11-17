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
 * A 2-stem instrumental uses the existing MediaLibraryService / MediaSession,
 * so playback persists when the activity is backgrounded or the screen locks.
 * A 6-stem arrangement needs six simultaneous decoders; those are scoped to
 * the visible stage and explicitly released on exit.
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
    private val names: List<String> = if (artifact.profile == "6")
        listOf("drums", "bass", "guitar", "piano", "other", "vocals")
    else listOf("vocals")
    private val mainStem: String = if (artifact.profile == "6") "drums" else "instrumental"
    private var loudness = .85f
    private var tempo = 1f
    private var pitch = 1f
    private var initialized = false
    val multiStem: Boolean get() = artifact.profile == "6"
    val availableFaders: List<String> get() = if (multiStem) names
        else listOf("instrumental", "vocals")

    fun prepare() {
        if (initialized) return
        initialized = true
        if (!multiStem) {
            val controller = requireNotNull(primary) { "Der Media3-Player ist noch nicht bereit." }
            val media = MediaItem.Builder()
                .setMediaId("KARAOKE:" + artifact.id)
                .setUri(api.stemUrl(artifact.id, "instrumental"))
                .setMimeType("audio/mp4")
                .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title)
                    .setArtist(song.artist).build())
                .build()
            controller.setMediaItem(media)
            controller.prepare()
            controller.volume = loudness
        } else primary?.pause()
        val factory = DefaultMediaSourceFactory(OkHttpDataSource.Factory(session.client))
        names.forEach { stem ->
            val player = ExoPlayer.Builder(context)
                .setMediaSourceFactory(factory)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                    multiStem && stem == mainStem)
                .setHandleAudioBecomingNoisy(true)
                .build()
            player.setMediaItem(MediaItem.Builder().setUri(api.stemUrl(artifact.id, stem))
                .setMimeType("audio/mp4").build())
            player.volume = loudness * (gains[stem] ?: 1f)
            player.prepare()
            players[stem] = player
            if (multiStem && stem == mainStem) {
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        // The leading decoder owns audio focus. Followers must
                        // not continue if focus is lost, playback stalls or an
                        // incoming call pauses the leading stem.
                        players.filterKeys { it != mainStem }.values.forEach {
                            if (isPlaying) it.play() else it.pause()
                        }
                    }
                })
            }
        }
    }

    fun play() {
        if (multiStem) {
            players.values.forEach { it.play() }
        } else {
            primary?.play()
            players.values.forEach { it.play() }
        }
    }

    fun pause() {
        if (!multiStem && primary?.currentMediaItem?.mediaId == "KARAOKE:" + artifact.id)
            primary.pause()
        players.values.forEach { it.pause() }
    }

    fun stop() {
        pause()
        if (!multiStem && primary?.currentMediaItem?.mediaId == "KARAOKE:" + artifact.id) {
            primary.stop()
            primary.clearMediaItems()
        }
    }

    fun release() {
        players.values.forEach { it.release() }
        players.clear()
    }

    fun seek(positionMs: Long) {
        val at = positionMs.coerceAtLeast(0L)
        if (!multiStem) primary?.seekTo(at)
        players.values.forEach { it.seekTo(at) }
    }

    fun setGain(stem: String, gain: Float) {
        gains[stem] = gain.coerceIn(0f, 1.5f)
        if (stem == "instrumental" && !multiStem) {
            primary?.volume = (loudness * gains.getValue(stem)).coerceIn(0f, 1f)
        } else players[stem]?.volume =
            (loudness * gains.getValue(stem)).coerceIn(0f, 1f)
    }

    fun setVolume(value: Float) {
        loudness = value.coerceIn(0f, 1f)
        if (!multiStem) primary?.volume = (loudness * gains.getValue("instrumental")).coerceIn(0f, 1f)
        players.forEach { (name, player) ->
            player.volume = (loudness * gains.getValue(name)).coerceIn(0f, 1f)
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

    private fun updatePlaybackParameters() {
        val params = PlaybackParameters(tempo, pitch)
        if (!multiStem) primary?.playbackParameters = params
        players.values.forEach { it.playbackParameters = params }
    }

    val playing: Boolean get() = if (multiStem) players[mainStem]?.isPlaying == true
        else primary?.isPlaying == true
    val positionMs: Long get() = (if (multiStem) players[mainStem]?.currentPosition
        else primary?.currentPosition)?.coerceAtLeast(0L) ?: 0L
    val durationMs: Long get() {
        val ms = if (multiStem) players[mainStem]?.duration else primary?.duration
        return ms?.takeIf { it > 0 && it != C.TIME_UNSET }
            ?: (song.duration * 1000.0).toLong().coerceAtLeast(0L)
    }
    val ended: Boolean get() = (if (multiStem) players[mainStem]?.playbackState
        else primary?.playbackState) == Player.STATE_ENDED

    /** Keep independently decoded stems in lockstep without re-seeking every frame. */
    fun synchronize() {
        if (!playing) return
        val reference = positionMs
        players.forEach { (name, player) ->
            if (!multiStem || name != mainStem) {
                if (player.playbackState == Player.STATE_READY &&
                    kotlin.math.abs(player.currentPosition - reference) > 160L) {
                    player.seekTo(reference)
                }
            }
        }
    }
}
