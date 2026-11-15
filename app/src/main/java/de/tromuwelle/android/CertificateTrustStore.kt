package de.tromuwelle.android

import android.content.Context
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Explicitly approved, per-host TLS certificate trust.
 *
 * There is deliberately NO "trust any certificate" switch for requests.
 * A permissive trust manager exists ONLY in the unauthenticated, TLS-handshake-only
 * fingerprint inspection routine. No HTTP data or credentials cross that connection.
 */
data class InspectedCertificate(
    val hostname: String,
    val port: Int,
    val sha256: String,
    val subject: String,
    val issuer: String,
    val expires: Long
) {
    val key: String get() = "$hostname:$port"
}

fun tlsSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString(":") { "%02X".format(it.toInt() and 255) }

fun tlsHostAndPort(address: String): Pair<String, Int> {
    val uri = try { URI(address.trim()) }
        catch (_: Exception) { throw IllegalArgumentException("Ungültige HTTPS-Adresse.") }
    require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null) {
        "Bitte eine HTTPS-Adresse ohne Benutzername, Passwort oder Parameter eingeben."
    }
    val port = if (uri.port < 0) 443 else uri.port
    require(port in 1..65535) { "Ungültiger HTTPS-Port." }
    return uri.host.lowercase() to port
}

class CertificateTrustStore(context: Context) {
    private val prefs = context.getSharedPreferences(
        "approved_tls_certificates_v1", Context.MODE_PRIVATE)

    private fun entry(host: String, port: Int) = "pin:" + host.lowercase() + ":" + port
    fun fingerprint(host: String, port: Int): String? =
        prefs.getString(entry(host, port), null)

    fun accept(inspected: InspectedCertificate) {
        require(inspected.hostname.isNotBlank() && inspected.port in 1..65535)
        require(inspected.sha256.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}"))) {
            "Ungültiger Zertifikatsfingerabdruck."
        }
        prefs.edit().putString(entry(inspected.hostname, inspected.port),
            inspected.sha256).commit()
    }

    fun revoke(address: String) {
        val (host, port) = tlsHostAndPort(address)
        prefs.edit().remove(entry(host, port)).commit()
    }

    fun existing(address: String): String? {
        val (host, port) = tlsHostAndPort(address)
        return fingerprint(host, port)
    }

    /** A pinned leaf is accepted only if it exactly matches a previously approved SHA-256. */
    private fun hasPinnedLeaf(leaf: X509Certificate): Boolean {
        val hash = tlsSha256(leaf.encoded)
        return prefs.all.any { (key, value) ->
            key.startsWith("pin:") && value == hash
        }
    }

    private fun isPinnedForHostname(hostname: String, cert: X509Certificate): Boolean {
        val hash = tlsSha256(cert.encoded)
        // A host can expose several explicitly approved HTTPS ports.
        return prefs.all.any { (key, value) ->
            key.startsWith("pin:" + hostname.lowercase() + ":") && value == hash
        }
    }

    fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as java.security.KeyStore?)
        val platform = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                platform.checkClientTrusted(chain, authType)
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    platform.checkServerTrusted(chain, authType)
                } catch (failure: CertificateException) {
                    if (chain.isEmpty() || !hasPinnedLeaf(chain.first())) throw failure
                }
            }
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trust), SecureRandom())
        val systemHostname = HttpsURLConnection.getDefaultHostnameVerifier()
        return builder.sslSocketFactory(sslContext.socketFactory, trust)
            .hostnameVerifier(HostnameVerifier { hostname, session ->
                systemHostname.verify(hostname, session) ||
                    runCatching {
                        isPinnedForHostname(hostname,
                            session.peerCertificates.first() as X509Certificate)
                    }.getOrDefault(false)
            })
    }

    /**
     * Read the public server certificate without making an HTTP request.
     * It is NOT trusted yet. A separate explicit user confirmation is mandatory.
     * Call only off the Android main/UI thread.
     */
    fun inspect(address: String): InspectedCertificate {
        val (host, port) = tlsHostAndPort(address)
        val inspectionOnly = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        }
        val tls = SSLContext.getInstance("TLS")
        tls.init(null, arrayOf(inspectionOnly), SecureRandom())
        Socket().use { plain ->
            plain.connect(InetSocketAddress(host, port), 6000)
            plain.soTimeout = 6000
            (tls.socketFactory.createSocket(plain, host, port, true) as SSLSocket).use { ssl ->
                ssl.soTimeout = 6000
                ssl.startHandshake()
                val leaf = ssl.session.peerCertificates.first() as X509Certificate
                return InspectedCertificate(host, port, tlsSha256(leaf.encoded),
                    leaf.subjectX500Principal.name, leaf.issuerX500Principal.name,
                    leaf.notAfter.time)
            }
        }
    }
}

/** Global trust configuration, shared by API, Coil, Media3 audio/video and Subsonic. */
object TlsTrustRegistry {
    @Volatile var store: CertificateTrustStore? = null
    fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        store?.configure(builder) ?: builder
}
