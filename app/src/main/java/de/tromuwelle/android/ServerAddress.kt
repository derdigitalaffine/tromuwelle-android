package de.tromuwelle.android

import java.net.URI

/** Strictly use TLS and a root origin, never a URL carrying userinfo or an API redirect. */
fun normalizeServerAddress(input: String): String {
    val uri = try { URI(input.trim().trimEnd('/')) } catch (_: Exception) {
        throw IllegalArgumentException("Ungültige Serveradresse")
    }
    require(uri.scheme.equals("https", true) && uri.host != null &&
        uri.userInfo == null && uri.query == null && uri.fragment == null &&
        (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")) {
        "Bitte eine HTTPS-Adresse ohne Pfad oder Zugangsdaten eingeben."
    }
    return "https://" + uri.rawAuthority
}
