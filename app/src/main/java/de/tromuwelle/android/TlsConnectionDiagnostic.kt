package de.tromuwelle.android

/**
 * Distinguish a peer-side TLS handshake abort from certificate trust errors.
 * No credentials are disclosed in diagnostics, and the actual certificate checks
 * are never bypassed.
 */
fun tlsConnectionDiagnostic(failure: Throwable): String {
    val messages = generateSequence(failure) { it.cause }.take(8)
        .mapNotNull { it.message }.toList()
    val joined = messages.joinToString(" | ").lowercase()
    return when {
        listOf("tls1_alert_internal_error", "tlsv1 alert internal error",
            "0a000438", "100000438", "alert internal error").any { it in joined } ->
            "Der HTTPS-Server hat den TLS-Handshake abgebrochen " +
            "(TLS1_ALERT_INTERNAL_ERROR / 0A000438). " +
            "Bei Caddy passiert das häufig, wenn für die aufgerufene IP oder den " +
            "Hostnamen kein Zertifikat ausgewählt werden kann (SNI). " +
            "Prüfe HTTPS-Host, Port und die Caddy-Konfiguration. " +
            "Das Freigeben eines selbstsignierten Zertifikats allein kann " +
            "diesen serverseitigen Fehler nicht beheben."
        "certificate" in joined || "certpathvalidator" in joined ||
            "trust anchor" in joined || "unknown ca" in joined ->
            "Das Serverzertifikat wird nicht vertraut oder passt nicht zum Host. " +
            "Prüfe den SHA-256-Fingerabdruck über einen unabhängigen Weg und " +
            "gib nur das richtige Zertifikat frei. " +
            "Details: " + messages.firstOrNull().orEmpty().take(200)
        "connection refused" in joined ->
            "Keine Verbindung zum HTTPS-Port. Serveradresse, Port, " +
            "Firewall und laufenden Caddy-Container überprüfen."
        else -> messages.firstOrNull()?.take(300) ?: "Die Serververbindung ist fehlgeschlagen."
    }
}
