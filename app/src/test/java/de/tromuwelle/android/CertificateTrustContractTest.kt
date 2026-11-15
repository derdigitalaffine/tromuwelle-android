package de.tromuwelle.android

import org.junit.Assert.*
import org.junit.Test

class CertificateTrustContractTest {
    @Test fun fingerprintUsesStandardColonSeparatedSha256() {
        assertEquals(
            "BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:" +
                "B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD",
            tlsSha256("abc".toByteArray(Charsets.UTF_8))
        )
    }

    @Test fun tlsScopeIsOneHostAndPort() {
        assertEquals("radio.example.net" to 8097,
            tlsHostAndPort("https://RADIO.example.net:8097"))
        assertEquals("music.example.net" to 443,
            tlsHostAndPort("https://music.example.net/subsonic"))
    }

    @Test fun tlsScopeRejectsInsecureHostsAndEmbeddedSecrets() {
        for (input in listOf("http://music.example.net", "https://u:p@music.example.net",
            "https://music.example.net/?token=secret", "https://music.example.net/#secret",
            "https://music.example.net:0", "not-an-address")) {
            try {
                tlsHostAndPort(input)
                fail("Unzulässige URL: " + input.take(32))
            } catch (_: IllegalArgumentException) {
                // Only proper HTTPS origins may get pin exceptions.
            }
        }
    }
}
