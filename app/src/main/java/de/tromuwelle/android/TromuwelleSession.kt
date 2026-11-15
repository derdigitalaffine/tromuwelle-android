package de.tromuwelle.android

import android.app.Application
import okhttp3.JavaNetCookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Shared HTTP stack: API, HLS manifests, segments and music use the same session. */
class TromuwelleApplication : Application() {
    val session by lazy { TromuwelleSession(this) }
}

class TromuwelleSession(private val application: Application) {
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(JavaNetCookieJar(cookies))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(65, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build()
    private val prefs = application.getSharedPreferences("connection", 0)
    var server: String = prefs.getString("server", "") ?: ""
        private set
    var username: String = prefs.getString("username", "") ?: ""
        private set

    /** Do not accept plain HTTP, URL credentials or paths that could reroute API calls. */
    fun validateServer(input: String): String = normalizeServerAddress(input)

    fun login(inputServer: String, inputUsername: String, password: String) {
        require(password.isNotBlank()) { "Bitte das Passwort eingeben." }
        val base = validateServer(inputServer)
        cookies.cookieStore.removeAll()
        val payload = JSONObject().put("username", inputUsername.trim()).put("password", password).toString()
        call(base, "/api/auth/login", payload)
        require(cookies.cookieStore.cookies.isNotEmpty()) { "Keine gültige Sitzung vom Server erhalten." }
        server = base
        username = inputUsername.trim()
        prefs.edit().putString("server", base).putString("username", username).apply()
    }

    fun logout() {
        if (server.isNotBlank()) try { call(server, "/api/auth/logout", "{}") } catch (_: Exception) { }
        cookies.cookieStore.removeAll()
    }

    fun get(path: String): String = call(server, path)
    fun url(path: String): String = server.trimEnd('/') + path
    fun query(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun call(base: String, path: String, json: String? = null): String {
        require(base.startsWith("https://") && path.startsWith("/")) { "HTTPS-Verbindung erforderlich" }
        val request = Request.Builder().url(base.trimEnd('/') + path)
            .apply {
                if (json != null) post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            }.build()
        client.newCall(request).execute().use { response ->
            val content = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = try { JSONObject(content).optString("detail") } catch (_: Exception) { "" }
                throw ServerException(response.code, detail.ifBlank { "Serverfehler HTTP ${response.code}" })
            }
            return content
        }
    }
}

class ServerException(val status: Int, message: String): Exception(message)
