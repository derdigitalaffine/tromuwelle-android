package de.tromuwelle.android

import java.net.URI

/** One-time Cast grant URLs are bearer tokens; never expose arbitrary API paths. */
internal fun resolveCastStreamUrl(
    ticketPath: String, secureUrl: String, lanUrl: String
): String {
    require(ticketPath.matches(Regex("/api/cast/stream/[A-Za-z0-9_-]{20,120}"))) {
        "Cast-Link des Servers ist ungültig."
    }
    val remote = URI(secureUrl)
    require(remote.scheme.equals("https", true) &&
        !remote.host.isNullOrBlank() && remote.rawUserInfo == null &&
        remote.rawQuery == null && remote.rawFragment == null &&
        remote.rawPath == ticketPath) {
        "Cast benötigt einen gültigen HTTPS-Serverlink."
    }
    if (lanUrl.isBlank()) return secureUrl
    val candidate = URI(lanUrl)
    val chunks = candidate.host?.split(".").orEmpty()
    val ip = if (chunks.size == 4 && chunks.all { it.matches(Regex("[0-9]{1,3}")) })
        chunks.map { it.toInt() } else emptyList()
    val privateLan = ip.size == 4 && ip.all { it in 0..255 } &&
        (ip[0] == 10 || (ip[0] == 172 && ip[1] in 16..31) ||
            (ip[0] == 192 && ip[1] == 168))
    require(candidate.scheme.equals("http", true) && candidate.port == 8098 &&
        privateLan && candidate.rawUserInfo == null && candidate.rawQuery == null &&
        candidate.rawFragment == null && candidate.rawPath == ticketPath) {
        "Der Cast-LAN-Link ist ungültig oder nicht auf das private Netzwerk beschränkt."
    }
    // Explicitly provided backend-side proxy only; no Android credentials in URL.
    return lanUrl
}
