package de.tromuwelle.android

import org.junit.Assert.*
import org.junit.Test

class TlsConnectionDiagnosticTest {
    @Test fun internalErrorIsNotDescribedAsCertificateTrust() {
        val msg = tlsConnectionDiagnostic(
            IllegalStateException("TLS1_ALERT_INTERNAL_ERROR error:100000438"))
        assertTrue(msg.contains("SNI"))
        assertTrue(msg.contains("Caddy"))
        assertTrue(msg.contains("0A000438"))
        assertTrue(msg.contains("allein"))
    }

    @Test fun opensslStyleErrorRecognized() {
        val msg = tlsConnectionDiagnostic(
            IllegalArgumentException("SSL routines::tlsv1 alert internal error"))
        assertTrue(msg.contains("TLS-Handshake"))
    }

    @Test fun explainsCertValidationSeparately() {
        val msg = tlsConnectionDiagnostic(
            IllegalArgumentException("Trust anchor for certification path not found"))
        assertTrue(msg.contains("SHA-256"))
        assertFalse(msg.contains("SNI"))
    }

    @Test fun unknownErrorIsNotLost() {
        assertEquals("Test error", tlsConnectionDiagnostic(IllegalStateException("Test error")))
    }
}
