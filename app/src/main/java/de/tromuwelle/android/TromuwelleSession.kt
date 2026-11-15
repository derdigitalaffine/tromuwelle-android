package de.tromuwelle.android

import android.app.Application
import android.net.Uri
import okhttp3.JavaNetCookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Shared verified-TLS client: API, cover-art, HLS manifests and segments all use the same session. */
class TromuwelleApplication : Application() {
    val tlsTrust by lazy { CertificateTrustStore(this) }
    val session by lazy { TromuwelleSession(this) }

    override fun onCreate() {
        super.onCreate()
        TlsTrustRegistry.store = tlsTrust
    }
}

data class Account(val id: Int = 0, val username: String = "", val role: String = "viewer",
                   val managed: Boolean = false, val roots: List<String> = emptyList()) {
    val isAdmin: Boolean get() = role == "admin"
}

class TromuwelleSession(private val application: Application) {
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    val client: OkHttpClient = TlsTrustRegistry.configure(OkHttpClient.Builder())
        .cookieJar(JavaNetCookieJar(cookies))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(65, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build()

    private val prefs = application.getSharedPreferences("connection", 0)
    private val vault = SecureSessionStore(application)
    var server: String = prefs.getString("server", "") ?: ""
        private set
    var username: String = prefs.getString("username", "") ?: ""
        private set
    var account: Account = Account()
        private set
    private var restoredCookie = false

    init {
        vault.load()?.let { record ->
            try {
                val origin = normalizeServerAddress(record.optString("server"))
                require(origin == server) { "Serveradresse hat sich geändert." }
                val jar = record.optJSONArray("cookies") ?: org.json.JSONArray()
                val now = System.currentTimeMillis()
                for (i in 0 until jar.length()) {
                    val value = jar.getJSONObject(i)
                    val remaining = (value.optLong("expires_at") - now) / 1000L
                    if (remaining < 1) continue
                    val cookie = HttpCookie(value.getString("name"), value.getString("value")).apply {
                        domain = value.optString("domain").ifBlank { URI(origin).host }
                        path = value.optString("path", "/")
                        secure = value.optBoolean("secure")
                        isHttpOnly = value.optBoolean("http_only")
                        maxAge = remaining
                    }
                    cookies.cookieStore.add(URI(origin), cookie)
                    restoredCookie = true
                }
            } catch (_: Exception) {
                cookies.cookieStore.removeAll()
                vault.clear()
            }
        }
    }

    /** A stored cookie never grants UI access before fresh backend validation. */
    fun restoreVerified(): Boolean {
        if (!restoredCookie || server.isBlank()) return false
        return try {
            val result = JSONObject(get("/api/auth/status"))
            if (!result.optBoolean("authenticated")) {
                forgetLocal()
                false
            } else {
                account = result.account(username)
                true
            }
        } catch (_: Exception) { false }
    }

    private fun forgetLocal() {
        cookies.cookieStore.removeAll()
        vault.clear()
        restoredCookie = false
        account = Account()
    }

    fun validateServer(input: String): String = normalizeServerAddress(input)

    fun login(inputServer: String, inputUsername: String, password: String) {
        require(password.isNotBlank()) { "Bitte ein Passwort eingeben." }
        val origin = validateServer(inputServer)
        cookies.cookieStore.removeAll()
        val login = JSONObject().put("username", inputUsername.trim()).put("password", password)
        val result = JSONObject(request(origin, "/api/auth/login", "POST", login.toString()))
        finishLogin(origin, inputUsername, result)
    }

    fun setup(inputServer: String, inputUsername: String, password: String, token: String,
              legacyPassword: String = "") {
        require(inputUsername.trim().length in 3..40 && password.length >= 12) {
            "Benutzername mindestens 3 Zeichen, Passwort mindestens 12 Zeichen."
        }
        val origin = validateServer(inputServer)
        cookies.cookieStore.removeAll()
        val payload = JSONObject().put("username", inputUsername.trim()).put("password", password)
            .put("setup_token", token).put("legacy_password", legacyPassword)
        val result = JSONObject(request(origin, "/api/auth/setup", "POST", payload.toString()))
        finishLogin(origin, inputUsername, result)
    }

    private fun finishLogin(origin: String, name: String, json: JSONObject) {
        require(json.optBoolean("authenticated") && cookies.cookieStore.cookies.isNotEmpty()) {
            "Der Server hat keine gültige Sitzung ausgestellt."
        }
        server = origin
        username = name.trim()
        account = json.account(username)
        prefs.edit().putString("server", origin).putString("username", username).apply()
        restoredCookie = true
        try { vault.save(origin, username, cookies.cookieStore.cookies) }
        catch (_: Exception) { /* Keystore may be unavailable; in-memory session remains valid. */ }
    }

    fun status(inputServer: String): JSONObject = JSONObject(request(
        validateServer(inputServer), "/api/auth/status", "GET", null
    ))

    fun logout() {
        if (server.isNotBlank()) {
            try { request(server, "/api/auth/logout", "POST", "{}") } catch (_: Exception) { }
        }
        forgetLocal()
    }

    fun persistCurrentCookies() {
        try { vault.save(server, username, cookies.cookieStore.cookies) } catch (_: Exception) { }
    }
    fun get(path: String): String = request(server, path, "GET", null)
    fun post(path: String, json: String = "{}"): String = request(server, path, "POST", json)
    fun put(path: String, json: String): String = request(server, path, "PUT", json)
    fun patch(path: String, json: String): String = request(server, path, "PATCH", json)
    fun delete(path: String): String = request(server, path, "DELETE", null)
    fun upload(path: String, bytes: ByteArray, mime: String): String {
        require(bytes.size <= 12_000_000) { "Datei zu groß (maximal 12 MB)." }
        return runRequest(server, path, "POST", bytes, mime)
    }
    fun query(value: String): String = URLEncoder.encode(value, "UTF-8")
    fun url(path: String): String {
        require(path.startsWith("/") && !path.startsWith("//")) { "Ungültiger API-Pfad." }
        return server.trimEnd('/') + path
    }

    /** Stream to SAF without buffering potentially large movies in memory. Call only on Dispatchers.IO. */
    fun saveTo(path: String, uri: Uri) {
        val output = application.contentResolver.openOutputStream(uri)
            ?: error("Zieldatei kann nicht geöffnet werden.")
        output.use { stream ->
            val request = Request.Builder().url(url(path)).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw ServerException(response.code, "Download HTTP ${response.code}")
                response.body?.byteStream()?.use { it.copyTo(stream) } ?: error("Leere Datei vom Server.")
            }
        }
    }

    private fun request(base: String, path: String, method: String, json: String?): String {
        val bytes = json?.toByteArray(Charsets.UTF_8)
        return runRequest(base, path, method, bytes, "application/json; charset=utf-8")
    }

    private fun runRequest(base: String, path: String, method: String,
                           bytes: ByteArray?, mime: String): String {
        require(base.startsWith("https://") && path.startsWith("/") && !path.startsWith("//")) {
            "HTTPS-Verbindung erforderlich."
        }
        val body = bytes?.toRequestBody(mime.toMediaType())
        val request = Request.Builder().url(base.trimEnd('/') + path)
            .method(method, body).build()
        client.newCall(request).execute().use { response ->
            val content = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = try { JSONObject(content).optString("detail") } catch (_: Exception) { "" }
                throw ServerException(response.code, detail.ifBlank { "HTTP ${response.code}" })
            }
            return content
        }
    }
}

private fun JSONObject.account(fallbackUsername: String): Account {
    val managed = optBoolean("managed")
    val u = optJSONObject("user")
    val roots = u?.optJSONArray("roots")
    return Account(u?.optInt("id") ?: 0, u?.optString("username") ?: fallbackUsername,
        if (managed) u?.optString("role") ?: "viewer" else "admin", managed,
        if (roots != null) (0 until roots.length()).map { roots.optString(it) } else emptyList())
}

class ServerException(val status: Int, message: String) : Exception(message)
