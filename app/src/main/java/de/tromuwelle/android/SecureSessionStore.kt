package de.tromuwelle.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpCookie
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-bound Android Keystore AES-GCM; only revocable session cookies (never passwords)
 * are persisted. Decryption failures and stale sessions are treated as logged out.
 */
class SecureSessionStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("protected_session", Context.MODE_PRIVATE)
    private val alias = "tromuwelle-session-aes-v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        val current = store.getKey(alias, null)
        if (current is SecretKey) return current
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    fun save(server: String, username: String, cookies: List<HttpCookie>) {
        val issued = System.currentTimeMillis()
        val array = JSONArray()
        for (cookie in cookies) {
            if (cookie.maxAge <= 0) continue
            array.put(JSONObject()
                .put("name", cookie.name)
                .put("value", cookie.value)
                .put("domain", cookie.domain ?: "")
                .put("path", cookie.path ?: "/")
                .put("secure", cookie.secure)
                .put("http_only", cookie.isHttpOnly)
                .put("expires_at", issued + cookie.maxAge * 1000L))
        }
        if (array.length() == 0) return
        val payload = JSONObject().put("server", server).put("username", username)
            .put("cookies", array).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val combined = cipher.iv + cipher.doFinal(payload)
        prefs.edit().putString("state", Base64.encodeToString(combined, Base64.NO_WRAP)).apply()
    }

    fun load(): JSONObject? {
        val encrypted = prefs.getString("state", null) ?: return null
        return try {
            val combined = Base64.decode(encrypted, Base64.DEFAULT)
            require(combined.size > 28) { "Ungültiger Sitzungszustand" }
            val iv = combined.copyOfRange(0, 12)
            val cipherText = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            JSONObject(String(cipher.doFinal(cipherText), Charsets.UTF_8))
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun clear() { prefs.edit().remove("state").apply() }
}
