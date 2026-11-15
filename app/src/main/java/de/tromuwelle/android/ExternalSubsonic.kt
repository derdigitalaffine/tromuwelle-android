package de.tromuwelle.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import android.net.Uri
import java.net.URI
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A different HTTPS OpenSubsonic / Subsonic server, independent of Tromuwelle. */
data class SubsonicSource(val id: String, val name: String, val address: String,
                          val user: String, val password: String)

fun subsonicOrigin(raw: String): String {
    val input = raw.trim().trimEnd('/')
    require(input.length <= 500 && !input.contains('\\') && !input.contains('\n')) {
        "Ungültige Serveradresse."
    }
    val uri = URI(input)
    require(uri.scheme?.lowercase() == "https" && !uri.host.isNullOrBlank()
        && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
        "Bitte eine HTTPS-Adresse ohne Zugangsdaten oder Parameter eingeben."
    }
    val path = uri.rawPath.orEmpty().trimEnd('/')
        .removeSuffix("/rest").trimEnd('/')
    return URI("https", null, uri.host, uri.port, path.ifBlank { null }, null, null)
        .toASCIIString().trimEnd('/')
}

/** Sensitive passwords never enter unencrypted preferences, backups or logs. */
class SubsonicSourceStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("external_subsonic_v1", Context.MODE_PRIVATE)
    private val alias = "tromuwelle-external-subsonic-aes-v1"

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        val saved = store.getKey(alias, null)
        if (saved is SecretKey) return saved
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }
    @Synchronized fun all(): List<SubsonicSource> {
        val value = preferences.getString("payload", null) ?: return emptyList()
        return try {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(),
                GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val array = JSONArray(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { obj ->
                    SubsonicSource(obj.getString("id"), obj.getString("name"),
                        obj.getString("address"), obj.getString("user"), obj.getString("password"))
                }
            }
        } catch (_: Exception) {
            // Inaccessible AndroidKeyStore entries must not lead to plaintext fallback.
            emptyList()
        }
    }
    @Synchronized fun save(source: SubsonicSource) {
        require(source.name.trim().length in 1..80 && source.user.isNotBlank()
            && source.password.isNotBlank()) { "Name, Benutzer und Passwort sind erforderlich." }
        val safe = source.copy(address = subsonicOrigin(source.address))
        val list = all().filterNot { it.id == safe.id } + safe
        require(list.size <= 20) { "Maximal 20 Musikserver möglich." }
        val array = JSONArray()
        list.forEach { row -> array.put(JSONObject()
            .put("id", row.id).put("name", row.name).put("address", row.address)
            .put("user", row.user).put("password", row.password)) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val output = cipher.iv + cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        preferences.edit().putString("payload", Base64.encodeToString(output, Base64.NO_WRAP)).apply()
    }
    @Synchronized fun remove(id: String) {
        val list = all().filterNot { it.id == id }
        val array = JSONArray()
        list.forEach { row -> array.put(JSONObject().put("id", row.id)
            .put("name", row.name).put("address", row.address)
            .put("user", row.user).put("password", row.password)) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        preferences.edit().putString("payload",
            Base64.encodeToString(cipher.iv + cipher.doFinal(array.toString().toByteArray()),
                Base64.NO_WRAP)).apply()
    }
    fun newSource(name: String, address: String, user: String, password: String) =
        SubsonicSource(UUID.randomUUID().toString(), name.trim(), subsonicOrigin(address),
            user.trim(), password)
}

/** HTTPS-only Subsonic v1.16.1 JSON transport with salted token auth. */
class SubsonicClient(val source: SubsonicSource) {
    private val http = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false).build()
    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun md5(value: String) = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun url(operation: String, args: Map<String,String> = emptyMap()): String {
        require(operation.matches(Regex("[A-Za-z0-9]+"))) { "Unzulässige Subsonic-Operation." }
        val saltBytes = ByteArray(18)
        SecureRandom().nextBytes(saltBytes)
        val salt = Base64.encodeToString(saltBytes, Base64.NO_WRAP or Base64.URL_SAFE)
            .trimEnd('=')
        val params = linkedMapOf("u" to source.user, "t" to md5(source.password + salt),
            "s" to salt, "v" to "1.16.1", "c" to "tromuwelle-android", "f" to "json")
        args.forEach { (k, v) ->
            require(k.matches(Regex("[a-zA-Z][a-zA-Z0-9]*")) &&
                k !in setOf("u", "t", "s", "v", "c", "f")) {
                "Ungültiger oder reservierter Parameter."
            }
            params[k] = v
        }
        return subsonicOrigin(source.address) + "/rest/" + operation + ".view?" +
            params.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
    }

    private fun response(request: Request): JSONObject {
        http.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Musikserver HTTP " + response.code }
            val result = JSONObject(response.body?.string().orEmpty())
                .getJSONObject("subsonic-response")
            if (result.optString("status") != "ok") {
                val detail = result.optJSONObject("error")?.optString("message")
                    .orEmpty().ifBlank { "Subsonic-Anfrage fehlgeschlagen." }
                throw IllegalStateException(detail)
            }
            return result
        }
    }
    fun call(operation: String, args: Map<String,String> = emptyMap()): JSONObject =
        response(Request.Builder().get().url(url(operation, args)).build())

    /** Mutations are POST requests; authentication remains token+salt. */
    fun mutate(operation: String, args: List<Pair<String,String>>): JSONObject {
        val body = FormBody.Builder().also { form ->
            args.forEach { (key,value) ->
                require(key.matches(Regex("[A-Za-z][A-Za-z0-9]*"))) { "Ungültiger Parameter." }
                form.add(key, value)
            }
        }.build()
        return response(Request.Builder().url(url(operation)).post(body).build())
    }
    fun createPlaylist(name: String) =
        mutate("createPlaylist", listOf("name" to name))
    fun deletePlaylist(id: String) =
        mutate("deletePlaylist", listOf("id" to id))
    fun addPlaylistSong(playlistId: String, songId: String) =
        mutate("updatePlaylist", listOf("playlistId" to playlistId,
            "songIdToAdd" to songId))
    fun removePlaylistIndex(playlistId: String, index: Int) =
        mutate("updatePlaylist", listOf("playlistId" to playlistId,
            "songIndexToRemove" to index.toString()))
    fun starSong(songId: String, starred: Boolean) =
        mutate(if (starred) "star" else "unstar", listOf("id" to songId))
    fun lyrics(songId: String) =
        call("getLyricsBySongId", mapOf("id" to songId))
    fun downloadTo(context: Context, songId: String, destination: Uri) {
        val output = context.contentResolver.openOutputStream(destination)
            ?: error("Datei kann nicht erstellt werden.")
        output.use { target ->
            val request = Request.Builder().get().url(url("download", mapOf("id" to songId))).build()
            http.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "Download HTTP " + response.code }
                val bytes = response.body?.byteStream() ?: error("Leere Datei.")
                bytes.use { it.copyTo(target) }
            }
        }
    }
    fun ping() = call("ping")
    fun artists(): List<JSONObject> {
        val sections = call("getArtists").optJSONObject("artists")
            ?.optJSONArray("index") ?: JSONArray()
        return (0 until sections.length()).flatMap { section ->
            val list = sections.optJSONObject(section)?.optJSONArray("artist") ?: JSONArray()
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        }
    }
    fun albums(artistId: String = "", offset: Int = 0): List<JSONObject> {
        if (artistId.isNotBlank()) {
            val artist = call("getArtist", mapOf("id" to artistId)).optJSONObject("artist")
            return jsonObjects(artist?.optJSONArray("album"))
        }
        val result = call("getAlbumList2", mapOf("type" to "alphabeticalByName",
            "size" to "100", "offset" to offset.toString()))
        return jsonObjects(result.optJSONObject("albumList2")?.optJSONArray("album"))
    }
    fun songs(query: String = "", albumId: String = "", offset: Int = 0): List<JSONObject> =
        if (albumId.isNotBlank()) {
            jsonObjects(call("getAlbum", mapOf("id" to albumId))
                .optJSONObject("album")?.optJSONArray("song"))
        } else if (query.isBlank()) {
            // Empty search3 queries are not portable between Subsonic servers.
            jsonObjects(call("getRandomSongs", mapOf("size" to "100"))
                .optJSONObject("randomSongs")?.optJSONArray("song"))
        } else {
            jsonObjects(call("search3", mapOf("query" to query, "songCount" to "100",
                "songOffset" to offset.toString()))
                .optJSONObject("searchResult3")?.optJSONArray("song"))
        }
    fun song(id: String): JSONObject = call("getSong", mapOf("id" to id)).getJSONObject("song")
    fun playlists(): List<JSONObject> =
        jsonObjects(call("getPlaylists").optJSONObject("playlists")?.optJSONArray("playlist"))
    fun playlist(id: String): JSONObject =
        call("getPlaylist", mapOf("id" to id)).getJSONObject("playlist")
    fun coverUrl(id: String): String = url("getCoverArt", mapOf("id" to id, "size" to "300"))
    fun streamUrl(id: String): String = url("stream", mapOf("id" to id))
    fun mediaItem(song: JSONObject): MediaItem {
        val id = song.getString("id")
        return MediaItem.Builder().setMediaId("ext:" + source.id + ":" + id)
            .setUri(streamUrl(id))
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(song.optString("title"))
                .setArtist(song.optString("artist"))
                .setAlbumTitle(song.optString("album"))
                .build()).build()
    }
    private fun jsonObjects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}
