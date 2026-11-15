package de.tromuwelle.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * Contract-matched facade for the private Tromuwelle FastAPI backend.
 * Permissions are enforced server-side; never rely on UI roles for authorization.
 */
class TromuwelleApi(private val session: TromuwelleSession) {
    fun json(path: String) = JSONObject(session.get(path))
    fun array(path: String) = JSONArray(session.get(path))
    fun query(value: String) = session.query(value)

    fun radioStations() = json("/api/radio/stations").optJSONArray("stations") ?: JSONArray()
    fun saveRadio(id: String? = null, name: String, url: String, genre: String, logo: String, country: String = ""): JSONObject {
        val body = JSONObject().put("name", name.trim()).put("url", url.trim())
            .put("genre", genre.trim()).put("logo", logo.trim()).put("country", country.trim().uppercase()).toString()
        return JSONObject(if (id.isNullOrBlank()) session.post("/api/radio/stations", body)
            else session.put("/api/radio/stations/${query(id)}", body))
    }
    fun importRadioCatalog(countries: Collection<String>, maximum: Int): JSONObject =
        jsonPost("/api/radio/catalog/import", JSONObject()
            .put("countries", countries.toJsonArray()).put("maximum", maximum).toString())
    fun deleteRadio(id: String) = session.delete("/api/radio/stations/${query(id)}")
    fun importRadio(text: String) =
        jsonPost("/api/radio/import", JSONObject().put("content", text).toString())

    fun playlists(): JSONArray = array("/api/playlists")
    fun addPlaylist(name: String, url: String) = jsonPost(
        "/api/playlists", JSONObject().put("name", name).put("url", url).toString()
    )
    fun uploadPlaylist(name: String, bytes: ByteArray) = JSONObject(
        session.upload("/api/playlists/upload?name=${query(name)}", bytes, "text/plain; charset=utf-8")
    )
    fun renamePlaylist(id: String, name: String) =
        JSONObject(session.patch("/api/playlists/${query(id)}", JSONObject().put("name", name).toString()))
    fun refreshPlaylist(id: String) = jsonPost("/api/playlists/${query(id)}/refresh")
    fun deletePlaylist(id: String) = session.delete("/api/playlists/${query(id)}")

    fun settings() = json("/api/settings")
    fun saveStreamSettings(buffer: Int, maxHeight: Int) =
        JSONObject(session.put("/api/settings", JSONObject().put("buffer_seconds", buffer)
            .put("max_height", maxHeight).toString()))
    fun saveMediaPath(path: String) =
        JSONObject(session.put("/api/settings/media", JSONObject().put("media_path", path).toString()))
    fun saveRoots(roots: JSONArray) =
        JSONObject(session.put("/api/settings/media-roots", JSONObject().put("roots", roots).toString()))

    fun mediaIndex() = json("/api/media/index")
    fun startMediaIndex() = jsonPost("/api/media/index")
    fun rebuildAdultCategories() = jsonPost("/api/media/adult/reindex")
    fun stopMediaIndex() = JSONObject(session.delete("/api/media/index"))
    fun musicQuality() = json("/api/music/quality")
    fun musicPlaylists() = json("/api/music/playlists").optJSONArray("items") ?: JSONArray()
    fun musicPlaylist(id: Int) = json("/api/music/playlists/$id")
    fun createMusicPlaylist(name: String): JSONObject =
        jsonPost("/api/music/playlists", JSONObject().put("name", name).toString())
    fun renameMusicPlaylist(id: Int, name: String): JSONObject = JSONObject(
        session.put("/api/music/playlists/$id", JSONObject().put("name", name).toString()))
    fun saveMusicPlaylistItems(id: Int, ids: Collection<String>): JSONObject = JSONObject(
        session.put("/api/music/playlists/$id/items", JSONObject().put("track_ids", ids.toJsonArray()).toString()))
    fun deleteMusicPlaylist(id: Int) = session.delete("/api/music/playlists/$id")
    fun musicLyrics(songId: String) = json("/api/music/lyrics/${query(songId)}")
    fun musicStatus() = json("/api/music/status")
    fun startMusicScan() = jsonPost("/api/music/scan")
    fun stopMusicScan() = JSONObject(session.delete("/api/music/scan"))

    fun users() = json("/api/users").optJSONArray("users") ?: JSONArray()
    fun createUser(username: String, password: String, role: String, roots: JSONArray) = jsonPost(
        "/api/users", JSONObject().put("username", username).put("password", password)
            .put("role", role).put("roots", roots).toString()
    )
    fun updateUser(id: Int, role: String, roots: JSONArray) = JSONObject(session.put(
        "/api/users/$id", JSONObject().put("role", role).put("roots", roots).toString()))
    fun deleteUser(id: Int) = session.delete("/api/users/$id")
    fun changePassword(id: Int, password: String, previous: String = "") = JSONObject(session.put(
        "/api/users/$id/password", JSONObject().put("password", password)
            .put("current_password", previous).toString()
    ))

    fun apiKeys() = json("/api/account/api-keys").optJSONArray("keys") ?: JSONArray()
    fun addApiKey(label: String) = jsonPost(
        "/api/account/api-keys", JSONObject().put("label", label).toString())
    fun deleteApiKey(id: Int) = session.delete("/api/account/api-keys/$id")
    fun subsonicCredential(password: String, rotate: Boolean = false): JSONObject =
        jsonPost(if (rotate) "/api/account/subsonic/rotate" else "/api/account/subsonic/credential",
            JSONObject().put("password", password).toString())

    fun mediaMetadata(path: String) = json("/api/media/enrichment?path=${query(path)}")
    fun mediaInfo(path: String) = json("/api/media/info?path=${query(path)}")
    fun adultTaxonomy(): JSONArray =
        json("/api/media/adult/taxonomy").optJSONArray("items") ?: JSONArray()
    fun saveAdultTaxonomy(id: Int, name: String, kind: String,
                          aliases: Collection<String>, enabled: Boolean): JSONObject {
        val payload = JSONObject().put("name", name).put("kind", kind)
            .put("aliases", aliases.toJsonArray()).put("enabled", enabled).toString()
        return if (id == 0) jsonPost("/api/media/adult/taxonomy", payload)
            else JSONObject(session.put("/api/media/adult/taxonomy/$id", payload))
    }
    fun deleteAdultTaxonomy(id: Int) = session.delete("/api/media/adult/taxonomy/$id")
    fun adultFacets(): JSONArray =
        json("/api/media/adult/facets").optJSONArray("facets") ?: JSONArray()
    fun updateAdultTags(path: String, tags: JSONArray) = JSONObject(session.put(
        "/api/media/adult/tags?path=${query(path)}", JSONObject().put("tags", tags).toString()
    ))
    fun legacyProtection(enabled: Boolean, password: String, scope: String) = jsonPost(
        "/api/auth/protection", JSONObject().put("enabled", enabled)
            .put("password", password).put("scope", scope).toString())
    fun health() = json("/api/health")
    private fun jsonPost(path: String, body: String = "{}") = JSONObject(session.post(path, body))
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun Collection<String>.toJsonArray(): JSONArray = JSONArray().also { arr -> forEach { arr.put(it) } }
