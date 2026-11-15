# Tromuwelle Android

Nativ implementierter Android-Client für das **private Hauptrepository Tromuwelle**. Kotlin, Jetpack Compose, Material 3, Media3/ExoPlayer, MediaSessionService und ein gemeinsamer authentifizierter OkHttp-Transport – **keine WebView**.

**Funktionsabgleich:** [docs/feature-parity.md](docs/feature-parity.md) (Hauptrepo-Stand `086e8eb69f96`).

## Funktionen

- **Live-TV:** Multicast- und Playlist-Sender, Suche, A–Z/HD-Sortierung, Favoriten, adaptiver HLS-Player, Videovollbild, PiP, Live-Kante, maximale Videoauflösung, Tonspuren und Untertitel.
- **Internetradio:** Stationen nach Land (🇩🇪 🇳🇱 🇦🇹 🇧🇪 🇱🇺), Genre und zuletzt gehörten Sendern, Favoriten, MP3/AAC/Opus und 48–320 kbit/s serverseitige Wandlung mit Media3-Hintergrundwiedergabe. Administratoren können Stationen bearbeiten, M3U/PLS importieren und den serverseitigen Online-Katalog länderweise automatisch importieren.
- **Musik:** Interpreten → Alben → Titel, Suche, Cover, Queue, Shuffle, Titel-/Listenwiederholung, Lautstärke, Geschwindigkeit, gespeicherte Position und Originaldownload. Persönliche serverseitige Playlists inklusive Erstellen/Umbenennen/Löschen, Tracks hinzufügen, entfernen, umsortieren und abspielen. Qualitätsstatistik, Codec/Bitrate/Lossless-Anzeige sowie Sortier-/Qualitätsfilter. Lyrics werden nur auf ausdrücklichen Abruf vom Server geladen.
- **Mediathek:** Ordner und Index, Suche, Sortierung und Auflösungsfilter, Poster/Vorschaubilder, technische Metadaten, native externe Textuntertitel, Originalstream oder serverseitig kompatibles MP4 mit Auflösung und Startzeit, Download über Android-Dokumentauswahl.
- **Private 18+-Filme:** Bewusst separat zu öffnende Bibliothek mit serverseitigem `category=adult`, Facetten/Tags, Poster, Titelsuche, technischen Angaben und administratorseitiger Tag-Bearbeitung und zentral verwalteten Genre-/Schlagwortdefinitionen samt Synonymen. Der Server entscheidet stets über Zugriffsrechte.
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

## APK – v0.3.0-beta.1

Es gibt zwei separat installierbare Varianten:
- **`tromuwelle-android-v0.3.0-beta.1.apk`** – Android-Handy und **Android Auto (Audio)** über Media3-Medienbibliothek: Radio, Tromuwelle-Musik, Musik-Playlists, externe Subsonic-Quellen, Headunit-Browsing und Sprachsuche.
- **`tromuwelle-automotive-video-v0.3.0-beta.1.apk`** – **Android Automotive OS** mit videofähiger Oberfläche für **geparkte Fahrzeuge**. Die fahrzeugseitigen UX-Sperren bleiben aktiv; die App pausiert Video bei Verlassen der sichtbaren Oberfläche. Nicht für Video-Projektion mit Android Auto.

Video auf Android Auto ist **nicht** verfügbar. Dieser Unterschied ist eine Plattformbeschränkung, kein fehlender Schalter. Diese APKs verwenden unterschiedliche Anwendungs-IDs; die AAOS-Version ersetzt nicht die Handy-Version.

### Externe OpenSubsonic-Server

Im Startbildschirm „Andere Subsonic-Musikserver“ oder nach Anmeldung „Weitere Musikserver“ wählen.
- Beliebige kompatible HTTPS-Server (z. B. **Navidrome, Airsonic, Gonic**) mit eigenem Nutzerkonto konfigurieren, Verbindung mit `ping` prüfen; bis zu 20 Quellen.
- Daten werden **nur auf dem Gerät AES-GCM-verschlüsselt im Android Keystore** gespeichert. Kein Klartext-Passwort in Preferences und kein TLS-Bypass.
- Künstler, Alben, Songs, Suche, Playlists, Server-Favoriten, Hinzufügen/Entfernen von Titeln, Downloads per Android-Dokumentauswahl, Cover-Artwork und OpenSubsonic-Lyrics mit optionaler Zeitsynchronisierung.
- Anmeldetoken für die API werden als **MD5(passwort + zufälliger Salt)** gemäß Subsonic-Standard in die HTTPS-URLs aufgenommen. Sie sind bei dieser Protokollvariante technisch abgreifbare Zugangstoken; nur vertrauenswürdige Server verwenden und HTTPS-Zertifikate prüfen.
- Subsonic-Funktionen unterscheiden sich je nach Serverimplementierung. Erweiterte Lyrics-, Playlist- und Favoritenoperationen benötigen die entsprechenden unterstützten API-Endpunkte. Bei Serverfehlern werden die Meldungen angezeigt.

### Auto und Automotive OS

Die **Android-Auto-Handyvariante** stellt ausschließlich Audio über `MediaLibraryService` bereit – Navigationsbaum mit Radio, Musik, Playlists und den externen Musikservern. Sprachsuche berücksichtigt nur Audio. Eingeschränkte Fahrzeugansichten zeigen keine Mediathek- oder 18+-Filme.

Die **AAOS-Video-Variante** verwendet `android:appCategory="video"`, enthält keine `distractionOptimized`-Freigabe und setzt auf die Fahrsperren des Fahrzeugs. Die App erlaubt kein Video während der Fahrt. Ein Video-Test im Android-Automotive-Emulator und auf einem geeigneten realen Fahrzeugsystem ist vor der Veröffentlichung im Play Store zwingend.



- [GitHub Releases](https://github.com/derdigitalaffine/tromuwelle-android/releases): installierbare Beta.
- [GitHub Actions](https://github.com/derdigitalaffine/tromuwelle-android/actions/workflows/android.yml): Testlauf, Lint und Debug-APK-Artefakt.
- Die Beta-APK wird mit dem **temporären GitHub-Runner-Debugschlüssel** signiert. Eine neue Beta aus einem anderen Runner ist daher gegebenenfalls **nicht als Update installierbar**; vor Installation App-Daten exportieren und die alte Beta deinstallieren. Für aktualisierbare Production Releases wird ein dauerhaft sicher verwahrter Signaturschlüssel benötigt.
- Kein Play-Store-Release und keine Zusage zur Lauffähigkeit aller Codecs/Serverkonfigurationen ohne Gerätetest.

## Entwicklung

Voraussetzungen: JDK 17, Android SDK 35, Gradle 8.11.1.

```sh
gradle :app:testMobileDebugUnitTest :app:testAutomotiveDebugUnitTest :app:lintMobileDebug :app:lintAutomotiveDebug :app:assembleMobileDebug :app:assembleAutomotiveDebug
```

Buildausgabe: `app/build/outputs/apk/mobile/debug/app-mobile-debug.apk` und `app/build/outputs/apk/automotive/debug/app-automotive-debug.apk`. `release/vX.Y.Z-beta.N` löst nach bestandenem CI-Lauf einen GitHub-Pre-Release mit APK aus. Referenz `AGENTS.md`, Issues #1–#3.

## Grenzen vor stabiler 1.0

- Noch keine auf realen Geräten mit echten Zugangsdaten dokumentierten End-to-End-Tests für Caddy-CA, HLS-Spuren, Fernseh-PiP, 4K/HEVC, Berechtigungsänderungen, Medien-Transcoding und Langzeit-Hintergrundwiedergabe.
- Transcodierte MP4-Live-Ausgaben erlauben keinen normalen Byte-Range-Seek; die Startzeit erzeugt stattdessen einen neuen Stream. Offline-Musikbibliothek, Chromecast/Google Cast, native Chromecast-Fernbedienung und geräteübergreifende Synchronisation sind nicht Teil der aktuellen Tromuwelle-API.
- Externe EPG/XMLTV und Subsonic-/Jellyfin-Kompatibilität bleiben Serverfunktionen. Die App nutzt die eigene API statt einen vollständigen Subsonic-Server zu emulieren. Anlegen neuer Host-Mounts und Encoder-Gerätetreiberkonfiguration bleiben bewusst serverseitige Administrationsaufgaben.

## Umfang des Abgleichs

Die detaillierte Tabelle in [docs/feature-parity.md](docs/feature-parity.md) dokumentiert vorhandene Funktionen und reale Lücken. „Webapp-Funktion vorhanden“ bedeutet nicht, dass jedes Browserdetail 1:1 als eigener Android-Bildschirm dupliziert werden muss: Serververwaltung, Streaming-Hardware, Kodi/Jellyfin/Subsonic-Server-APIs und FFmpeg werden bewusst auf dem Server ausgeführt.

Wichtig für Installationen: Bestehende Beta-Versionen sind mit wechselnden GitHub-Runner-Debugschlüsseln signiert; ein Überspielen ist häufig nicht möglich. Eigene Android-App-Daten beim Löschen gehen verloren. Für echte Updatefähigkeit eine **dauerhafte Produktionssignatur** in einem geschützten GitHub-Secret bereitstellen und damit einen Release-Automationsschritt aufbauen. Builds im Workflow sind noch nicht Play-Store-zertifiziert.
