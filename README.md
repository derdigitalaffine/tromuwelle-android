# Tromuwelle Android

Ein nativer Android-Client für [Tromuwelle](https://github.com/derdigitalaffine/tromuwelle), umgesetzt in **Kotlin, Jetpack Compose und AndroidX Media3**. Keine WebView und kein HTML-Player.

## Funktionen (Beta)

- **Fernsehen:** Kanalliste, authentifizierte HLS-Wiedergabe mit Media3, Video-Vollbild und Android Bild-in-Bild.
- **Radio:** Sender, Genre, Stream des Tromuwelle-Servers; Audiofokus und Hintergrundwiedergabe über MediaSessionService.
- **Musik:** Suche, Interpreten, Alben, Titel und Wiedergabeliste mit vorherigem/nächstem Titel.
- **Mediathek:** Serverberechtigungen berücksichtigen, Ordner öffnen, Medienindex durchsuchen, Originaldateien oder serverseitiges MP4-Transcoding mit 480/720/1080/2160p wählen.
- **Bedienung:** Material 3 mit Hell-/Dunkelmodus, Favoriten pro Gerät, Suchfunktion, Lade-/Fehleranzeigen.
- **Sicherheit:** HTTPS-Pflicht, Authentifizierung per API, Session-Cookies auch für HLS und Media3, keine Klartextpasswörter auf dem Gerät, keine Netzwerk-TLS-Bypasses.

## Verbindung

1. Im Tromuwelle-Server einen Benutzer anlegen; die API muss von Android erreichbar sein.
2. In der App die volle HTTPS-Origin eingeben, z. B. `https://server.example.net:8097`, Benutzername und Passwort.
3. Falls der Server eine **private Caddy-CA** verwendet, das vertrauenswürdige CA-Zertifikat über `https://<server>/api/certificate` beziehen und **nach unabhängiger Prüfung des Fingerprints** als Benutzer-CA auf dem Android-Gerät installieren. In Android ist das je nach Hersteller unter **Sicherheit → Zertifikat installieren → CA-Zertifikat** zu finden. Android bietet für diesen Import eine Sicherheitswarnung.
4. Erneut verbinden. Zertifikatsprüfungen bleiben aktiv. Auf Android erlaubt die App hierfür sowohl System- als auch vom Benutzer installierte vertrauenswürdige CAs.

Die App speichert lediglich die letzte Serveradresse und den Benutzernamen; das Passwort und die Session-Cookies werden **nicht dauerhaft gespeichert**. Nach Prozessneustart ist eine neue Anmeldung erforderlich.

## APK herunterladen

- Nach erfolgreichen CI-Builds: GitHub Actions → **Android APK** → **tromuwelle-debug-apk**.
- Markierte Vorabversionen: GitHub **Releases**. Die enthaltene APK ist **debug-signiert**, aber installierbar (Android 8.0+). Sie darf **nicht** als produktiv signierter Play-Store-Build ausgegeben werden.
- Installation am Android-Gerät: APK öffnen, ggf. Installation aus dieser Quelle explizit freigeben.

## Entwicklung

- **JDK 17**, **Android SDK 35**, **Gradle 8.11.1**.
- `gradle :app:testDebugUnitTest :app:assembleDebug`
- Ausgabe: `app/build/outputs/apk/debug/app-debug.apk`
- GitHub Actions baut und testet auf jedem Push. Eine `release/v...`-Branch veröffentlicht nur nach erfolgreichem Build eine Beta mit APK-Anhang.
- Agentenorganisation und Release-Gates: [AGENTS.md](AGENTS.md) und Issues #1–#3.

## Bekannte Grenzen

Der CI-Build und lokale Unit-Tests ersetzen keine Ende-zu-Ende-Tests auf einem **realen Android-Gerät gegen einen realen Tromuwelle-Server**. Vor allem private CA, Hintergrundwiedergabe, benutzerspezifische Rechte, Video-Codecs (HEVC/4K), Bild-in-Bild und seekbares Transcoding müssen gerätespezifisch geprüft werden. Server-Transcoding erzeugt eine neue HTTP-Ausgabe; der Qualitätswechsel startet die Wiedergabe aktuell neu. Der Android-Client bietet noch kein Chromecast, Offline-Downloads, EPG, Radio-/Musikverwaltung oder Zertifikat-Pinning.
