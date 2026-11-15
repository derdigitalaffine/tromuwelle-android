package de.tromuwelle.android

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Android Auto / Automotive media browser. Only audio is browsable in a car while
 * driving. Movies and TV are deliberately excluded from this library.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class TromuwellePlaybackService : MediaLibraryService() {
    private var library: MediaLibrarySession? = null
    private val jobs: ListeningExecutorService =
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(3))

    override fun onCreate() {
        super.onCreate()
        val app = application as TromuwelleApplication
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(app.session.client)))
            .build()
        val openApp = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or
                PendingIntent.FLAG_UPDATE_CURRENT)
        library = MediaLibrarySession.Builder(this, player, CarBrowseCallback(app))
            .setSessionActivity(openApp).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = library

    override fun onDestroy() {
        library?.let { session -> session.player.release(); session.release() }
        library = null
        jobs.shutdownNow()
        super.onDestroy()
    }

    private inner class CarBrowseCallback(private val app: TromuwelleApplication)
        : MediaLibrarySession.Callback {

        private fun node(id: String, title: String, playable: Boolean = false): MediaItem =
            MediaItem.Builder().setMediaId(id)
                .setMediaMetadata(MediaMetadata.Builder()
                    .setTitle(title).setIsBrowsable(!playable).setIsPlayable(playable).build())
                .build()

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(node("car:root", "Tromuwelle"), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            jobs.submit<LibraryResult<ImmutableList<MediaItem>>> {
                try {
                    val all = children(parentId, page, pageSize)
                    LibraryResult.ofItemList(all, params)
                } catch (_: Exception) {
                    LibraryResult.ofItemList(emptyList(), params)
                }
            }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(
                node(mediaId, "Musik", mediaId.startsWith("car:play:")), null))

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<List<MediaItem>> =
            jobs.submit<List<MediaItem>> {
                mediaItems.mapNotNull { item ->
                    when {
                        item.localConfiguration != null -> {
                            // Only our in-app controller may submit resolved URIs.
                            if (controller.packageName == packageName) item else null
                        }
                        item.mediaId.startsWith("car:play:") -> resolve(item.mediaId)
                        else -> null
                    }
                }
            }

        private fun resolve(id: String): MediaItem? {
            val value = id.removePrefix("car:play:")
            if (value.startsWith("radio:")) {
                val station = value.removePrefix("radio:")
                if (!station.matches(Regex("[A-Za-z0-9_-]{1,80}"))) return null
                return LibraryEntry(station, "Radio", kind = EntryKind.RADIO)
                    .mediaItem(app.session)
            }
            if (value.startsWith("music:")) {
                if (!app.session.restoreVerified()) return null
                val songId = value.removePrefix("music:")
                val song = TromuwelleApi(app.session).json("/api/music/track/" +
                    app.session.query(songId))
                return LibraryEntry(songId, song.optString("title"), song.optString("artist"),
                    EntryKind.TRACK, song.optString("path")).mediaItem(app.session)
            }
            if (value.startsWith("external:")) {
                val parts = value.removePrefix("external:").split(":", limit = 2)
                if (parts.size != 2) return null
                val source = SubsonicSourceStore(app).all().firstOrNull { it.id == parts[0] }
                    ?: return null
                return SubsonicClient(source).mediaItem(SubsonicClient(source).song(parts[1]))
            }
            return null
        }

        private fun carSong(id: String, title: String, artist: String = ""): MediaItem =
            MediaItem.Builder().setMediaId("car:play:" + id)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title)
                    .setArtist(artist).setIsPlayable(true).setIsBrowsable(false).build())
                .build()

        private fun objects(arr: JSONArray?): List<JSONObject> =
            if (arr == null) emptyList() else
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }

        private fun children(parent: String, page: Int, size: Int): List<MediaItem> {
            require(page >= 0) { "Ungültige Seitenangaben." }
            val limit = size.coerceIn(1, 300)
            val offset = page.toLong() * limit
            if (offset > 100_000L) return emptyList()
            val main = app.session.restoreVerified()
            val store = SubsonicSourceStore(app)
            if (parent == "car:root") {
                val entries = mutableListOf<MediaItem>()
                if (main) {
                    entries += node("car:radio", "Internetradio")
                    entries += node("car:music", "Tromuwelle Musik")
                }
                if (store.all().isNotEmpty()) entries += node("car:external", "Andere Musikserver")
                return entries
            }
            if (parent == "car:external") return store.all().map {
                node("car:external:" + it.id, it.name)
            }
            if (parent.startsWith("car:external:")) {
                val remainder = parent.removePrefix("car:external:")
                val parts = remainder.split(":")
                val source = store.all().firstOrNull { it.id == parts.first() }
                    ?: return emptyList()
                val client = SubsonicClient(source)
                if (parts.size == 1) return listOf(
                    node(parent + ":artists", "Interpreten"),
                    node(parent + ":albums", "Alben"),
                    node(parent + ":songs", "Titel"),
                    node(parent + ":playlists", "Playlists")
                )
                return when (parts[1]) {
                    "artists" -> client.artists().map {
                        node(parent.removeSuffix(":artists") + ":artist:" + it.optString("id"),
                            it.optString("name"))
                    }
                    "artist" -> client.albums(parts.getOrNull(2).orEmpty()).map {
                        node("car:external:" + source.id + ":album:" + it.optString("id"),
                            it.optString("name"))
                    }
                    "albums" -> client.albums(offset = offset.toInt()).take(limit).map {
                        node("car:external:" + source.id + ":album:" + it.optString("id"),
                            it.optString("name"))
                    }
                    "album" -> client.songs(albumId = parts.getOrNull(2).orEmpty()).map {
                        carSong("external:" + source.id + ":" + it.optString("id"),
                            it.optString("title"), it.optString("artist"))
                    }
                    "songs" -> client.songs(offset = offset.toInt()).take(limit).map {
                        carSong("external:" + source.id + ":" + it.optString("id"),
                            it.optString("title"), it.optString("artist"))
                    }
                    "playlists" -> client.playlists().map {
                        node("car:external:" + source.id + ":playlist:" + it.optString("id"),
                            it.optString("name"))
                    }
                    "playlist" -> {
                        val arr = client.playlist(parts.getOrNull(2).orEmpty()).optJSONArray("entry")
                        objects(arr).map {
                            carSong("external:" + source.id + ":" + it.optString("id"),
                                it.optString("title"), it.optString("artist"))
                        }
                    }
                    else -> emptyList()
                }
            }
            if (!main) return emptyList()
            val api = TromuwelleApi(app.session)
            return when {
                parent == "car:radio" -> objects(api.radioStations()).map {
                    carSong("radio:" + it.optString("id"), it.optString("name"),
                        it.optString("genre"))
                }
                parent == "car:music" -> listOf(
                    node("car:music:albums", "Alben"),
                    node("car:music:tracks", "Titel"),
                    node("car:music:playlists", "Meine Playlists"))
                parent == "car:music:albums" -> objects(api.json(
                    "/api/music/albums?offset=" + offset + "&limit=" + limit)
                    .optJSONArray("items")).map {
                    node("car:album:" + it.optString("id"), it.optString("name"))
                }
                parent.startsWith("car:album:") -> objects(api.json(
                    "/api/music/tracks?album_id=" + app.session.query(parent.removePrefix("car:album:")) +
                        "&limit=300").optJSONArray("items")).map {
                    carSong("music:" + it.optString("id"), it.optString("title"),
                        it.optString("artist"))
                }
                parent == "car:music:tracks" -> objects(api.json(
                    "/api/music/tracks?offset=" + offset + "&limit=" + limit)
                    .optJSONArray("items")).map {
                    carSong("music:" + it.optString("id"), it.optString("title"),
                        it.optString("artist"))
                }
                parent == "car:music:playlists" -> objects(api.musicPlaylists()).map {
                    node("car:playlist:" + it.optInt("id"), it.optString("name"))
                }
                parent.startsWith("car:playlist:") -> objects(api.musicPlaylist(
                    parent.removePrefix("car:playlist:").toInt()).optJSONArray("items")).map {
                    carSong("music:" + it.optString("id"), it.optString("title"),
                        it.optString("artist"))
                }
                else -> emptyList()
            }
        }
    }
}
