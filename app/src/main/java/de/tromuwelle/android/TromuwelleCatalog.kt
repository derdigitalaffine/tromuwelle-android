package de.tromuwelle.android

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import org.json.JSONArray
import org.json.JSONObject

enum class LibrarySection(val label: String) { TV("Fernsehen"), RADIO("Radio"), MUSIC("Musik"), MEDIA("Mediathek") }
enum class MusicBrowse(val label: String) { TRACKS("Titel"), ARTISTS("Interpreten"), ALBUMS("Alben") }
enum class EntryKind { TV, RADIO, TRACK, ARTIST, ALBUM, FOLDER, FILE }

data class LibraryEntry(
    val id: String,
    val title: String,
    val description: String = "",
    val kind: EntryKind,
    val path: String = "",
    val duration: Long = 0L,
    val height: Int = 0,
    val audio: Boolean = false
) {
    fun playable() = kind in listOf(EntryKind.TV, EntryKind.RADIO, EntryKind.TRACK, EntryKind.FILE)
    fun isVideo() = kind == EntryKind.TV || (kind == EntryKind.FILE && !audio)

    fun mediaItem(session: TromuwelleSession, transcode: Boolean = false, quality: Int = 1080): MediaItem {
        val relative = when (kind) {
            EntryKind.TV -> "/hls/${session.query(id)}/master.m3u8"
            EntryKind.RADIO -> "/api/radio/stream/${session.query(id)}"
            EntryKind.TRACK -> "/api/music/stream/${session.query(id)}"
            EntryKind.FILE -> "/api/media/file?path=${session.query(path)}" +
                if (transcode && !audio) "&transcode=true&height=$quality" else ""
            else -> error("Dieses Element kann nicht abgespielt werden.")
        }
        val mime = when(kind) {
            EntryKind.TV -> MimeTypes.APPLICATION_M3U8
            EntryKind.RADIO -> MimeTypes.AUDIO_MPEG
            EntryKind.TRACK -> when (path.substringAfterLast('.', "").lowercase()) {
                "flac" -> MimeTypes.AUDIO_FLAC
                "m4a", "aac" -> MimeTypes.AUDIO_MP4
                "ogg", "opus" -> MimeTypes.AUDIO_OGG
                "wav" -> MimeTypes.AUDIO_WAV
                else -> MimeTypes.AUDIO_MPEG
            }
            EntryKind.FILE -> if (audio) when(path.substringAfterLast('.', "").lowercase()) {
                "flac" -> MimeTypes.AUDIO_FLAC
                "ogg", "opus" -> MimeTypes.AUDIO_OGG
                "wav" -> MimeTypes.AUDIO_WAV
                "m4a", "aac" -> MimeTypes.AUDIO_MP4
                else -> MimeTypes.AUDIO_MPEG
            } else if(transcode) MimeTypes.VIDEO_MP4 else when(path.substringAfterLast('.', "").lowercase()) {
                "mkv" -> "video/x-matroska"
                "webm" -> MimeTypes.VIDEO_WEBM
                "ts", "m2ts" -> MimeTypes.VIDEO_MP2T
                else -> MimeTypes.VIDEO_MP4
            }
            else -> null
        }
        return MediaItem.Builder()
            .setMediaId(kind.name + ":" + id)
            .setUri(session.url(relative))
            .setMimeType(mime)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(description).build())
            .build()
    }
}

data class LibraryPage(val items: List<LibraryEntry>, val total: Int, val path: String = "")

class TromuwelleCatalog(private val session: TromuwelleSession) {
    fun load(section: LibrarySection, search: String = "", folder: String = "",
             browse: MusicBrowse = MusicBrowse.TRACKS, artist: String = "",
             album: String = "", offset: Int = 0): LibraryPage {
        return when (section) {
            LibrarySection.TV -> {
                val arr = JSONArray(session.get("/api/channels"))
                val all = arr.entries { obj ->
                    LibraryEntry(obj.optString("id"), obj.optString("name"), if(obj.optBoolean("hd")) "HD" else "", EntryKind.TV)
                }
                LibraryPage(all.filter { it.title.contains(search, ignoreCase = true) }, all.size)
            }
            LibrarySection.RADIO -> {
                val arr = JSONObject(session.get("/api/radio/stations")).optJSONArray("stations") ?: JSONArray()
                val all = arr.entries { obj ->
                    LibraryEntry(obj.optString("id"), obj.optString("name"), obj.optString("genre"), EntryKind.RADIO)
                }
                LibraryPage(all.filter { it.title.contains(search, ignoreCase = true) || it.description.contains(search, ignoreCase = true) }, all.size)
            }
            LibrarySection.MUSIC -> {
                val endpoint = when(browse) {
                    MusicBrowse.TRACKS -> "/api/music/tracks"
                    MusicBrowse.ARTISTS -> "/api/music/artists"
                    MusicBrowse.ALBUMS -> "/api/music/albums"
                }
                val query = "?limit=100&offset=$offset&q=${session.query(search)}" +
                    (if(artist.isNotBlank() && browse != MusicBrowse.ARTISTS) "&artist_id=${session.query(artist)}" else "") +
                    (if(album.isNotBlank() && browse == MusicBrowse.TRACKS) "&album_id=${session.query(album)}" else "")
                val obj = JSONObject(session.get(endpoint + query))
                val arr = obj.optJSONArray("items") ?: JSONArray()
                LibraryPage(arr.entries { entry ->
                    when(browse) {
                        MusicBrowse.TRACKS -> LibraryEntry(entry.optString("id"), entry.optString("title"),
                            listOf(entry.optString("artist"), entry.optString("album")).filter { it.isNotBlank() }.joinToString(" · "),
                            EntryKind.TRACK, entry.optString("path"), entry.optLong("duration"))
                        MusicBrowse.ARTISTS -> LibraryEntry(entry.optString("id"), entry.optString("name"),
                            "${entry.optInt("albums")} Alben · ${entry.optInt("tracks")} Titel", EntryKind.ARTIST)
                        MusicBrowse.ALBUMS -> LibraryEntry(entry.optString("id"), entry.optString("name"),
                            entry.optString("artist") + " · ${entry.optInt("tracks")} Titel", EntryKind.ALBUM)
                    }
                }, obj.optInt("total", arr.length()))
            }
            LibrarySection.MEDIA -> {
                val path = if(search.isNotBlank())
                    "/api/media/search?q=${session.query(search)}&limit=100&offset=$offset"
                else "/api/media/list?path=${session.query(folder)}"
                val obj = JSONObject(session.get(path))
                val arr = obj.optJSONArray("items") ?: JSONArray()
                LibraryPage(arr.entries { entry ->
                    val isFolder = entry.optBoolean("directory", false)
                    val mediaPath = entry.optString("path")
                    LibraryEntry(mediaPath, entry.optString("name"), when {
                        isFolder -> "Ordner"
                        entry.optInt("height") > 0 -> "${entry.optInt("height")}p"
                        entry.optBoolean("audio") -> "Audio"
                        else -> "Video"
                    }, if(isFolder) EntryKind.FOLDER else EntryKind.FILE, mediaPath,
                        entry.optLong("duration"), entry.optInt("height"), entry.optBoolean("audio"))
                }, obj.optInt("total", arr.length()), obj.optString("path", folder))
            }
        }
    }
}

private inline fun JSONArray.entries(toEntry: (JSONObject) -> LibraryEntry): List<LibraryEntry> =
    (0 until length()).mapNotNull { index ->
        val obj = optJSONObject(index) ?: return@mapNotNull null
        toEntry(obj).takeIf { it.id.isNotBlank() }
    }
