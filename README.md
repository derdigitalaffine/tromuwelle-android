# Tromuwelle Android

Nativ implementierter Android-Client für das **private Hauptrepository Tromuwelle**. Kotlin, Jetpack Compose, Material 3, Media3/ExoPlayer, MediaSessionService und ein gemeinsamer authentifizierter OkHttp-Transport – **keine WebView**.

**Funktionsabgleich:** [docs/feature-parity.md](docs/feature-parity.md) (Hauptrepo-Stand `2a85fdf17996`).

## Funktionen

- **Live-TV:** Multicast- und Playlist-Sender, Suche, A–Z/HD-Sortierung, Favoriten, adaptiver HLS-Player, Videovollbild, PiP, Live-Kante, maximale Videoauflösung, Tonspuren und Untertitel.
- **Internetradio:** Stationen und Genres, Favoriten, zuletzt gehörte Stationen; Audio über Media3 mit Hintergrundwiedergabe und Mediensteuerung. Administratoren können Stationen anlegen, ändern, löschen und M3U/PLS-Dateien importieren.
- **Musik:** Interpreten → Alben → Titel, Suche, Seitenladen, Cover, Queue, Shuffle, Titel-/Listenwiederholung, Lautstärke, Geschwindigkeit, gespeicherte Position und Originaldownload.
- **Mediathek:** Ordner und Index, Suche, Sortierung und Auflösungsfilter, Poster/Vorschaubilder, technische Metadaten, native externe Textuntertitel, Originalstream oder serverseitig kompatibles MP4 mit Auflösung und Startzeit, Download über Android-Dokumentauswahl.
- **Private 18+-Filme:** Bewusst separat zu öffnende Bibliothek mit serverseitigem `category=adult`, Facetten/Tags, Poster, Titelsuche, technischen Angaben und administratorseitiger Tag-Bearbeitung. Der Server entscheidet stets über Zugriffsrechte.
- **Verwaltung:** URL-/Datei-TV-Playlists, Radio, HLS-Puffer und Maximalauflösung, Mediathekpfad, Medienquellen inkl. Typ und Schutz, Benutzer/Rollen/Ordnerrechte, Musik-/Videoindex und klassischer Passwortschutz für Altinstallationen.
- **Konto:** Administrator-Ersteinrichtung mit Setup-Token, Passwortwechsel, Subsonic-App-Passwort und dedizierte API-Schlüssel inklusive Widerruf.
- **Sicherheit:** HTTPS-Pflicht, sichere Cookie-Authentifizierung für API, HLS, Cover und Streamdateien, in Android Keystore per AES-GCM verschlüsselte Cookies und serverseitige Überprüfung nach App-Neustart, kein gespeichertes Kontopasswort.
- **Android:** minSdk 26 (Android 8.0), Material Hell/Dunkel, MediaSession-Hintergrund-Audio, Benutzersitzungen, Dateiauswahl über SAF.

## Verbindung

1. Einen Tromuwelle-Server mit HTTPS und einem Benutzerkonto bereitstellen.
2. Vollständigen HTTPS-Origin eingeben (z. B. `https://server.example.net:8097`), Nutzername und Passwort.
3. Für private Caddy-CA: CA-Zertifikat **nach unabhängigem Fingerprint-Abgleich** auf dem Android-Gerät als Benutzer-CA installieren. Das öffentliche Zertifikat ist unter `https://<host>:8097/api/certificate` verfügbar. Einstellungen für CA-Zertifikate hängen von der Android-Version ab. **Die TLS-Validierung wird nicht deaktiviert.**
4. Bei einer unkonfigurierten Installation zuerst „Server / Ersteinrichtung prüfen“ und anschließend Administrator mit mindestens 12-stelligem Passwort und Setup-Token anlegen.
5. Bei gültiger Session stellt die App den Login beim nächsten Start automatisch wieder her; der Server bestätigt zuvor die aktuelle Berechtigung.

## APK

- [GitHub Releases](https://github.com/derdigitalaffine/tromuwelle-android/releases): installierbare Beta.
- [GitHub Actions](https://github.com/derdigitalaffine/tromuwelle-android/actions/workflows/android.yml): Testlauf, Lint und Debug-APK-Artefakt.
- Die Beta-APK wird mit dem **temporären GitHub-Runner-Debugschlüssel** signiert. Eine neue Beta aus einem anderen Runner ist daher gegebenenfalls **nicht als Update installierbar**; vor Installation App-Daten exportieren und die alte Beta deinstallieren. Für aktualisierbare Production Releases wird ein dauerhaft sicher verwahrter Signaturschlüssel benötigt.
- Kein Play-Store-Release und keine Zusage zur Lauffähigkeit aller Codecs/Serverkonfigurationen ohne Gerätetest.

## Entwicklung

Voraussetzungen: JDK 17, Android SDK 35, Gradle 8.11.1.

```sh
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Buildausgabe: `app/build/outputs/apk/debug/app-debug.apk`. `release/vX.Y.Z-beta.N` löst nach bestandenem CI-Lauf einen GitHub-Pre-Release mit APK aus. Referenz `AGENTS.md`, Issues #1–#3.

## Grenzen vor stabiler 1.0

- Noch keine auf realen Geräten mit echten Zugangsdaten dokumentierten End-to-End-Tests für Caddy-CA, HLS-Spuren, Fernseh-PiP, 4K/HEVC, Berechtigungsänderungen, Medien-Transcoding und Langzeit-Hintergrundwiedergabe.
- Transcodierte MP4-Live-Ausgaben erlauben keinen normalen Byte-Range-Seek; die Startzeit erzeugt stattdessen einen neuen Stream. Offline-Musikbibliothek, Chromecast/Google Cast, native Chromecast-Fernbedienung und geräteübergreifende Synchronisation sind nicht Teil der aktuellen Tromuwelle-API.
- Externe EPG/XMLTV und Subsonic-/Jellyfin-Kompatibilität bleiben Serverfunktionen. Die App nutzt die eigene API statt einen vollständigen Subsonic-Server zu emulieren. Anlegen neuer Host-Mounts und Encoder-Gerätetreiberkonfiguration bleiben bewusst serverseitige Administrationsaufgaben.
