# Funktionsabgleich: privates Tromuwelle-main → Android

Referenz: `derdigitalaffine/tromuwelle` `086e8eb69f964681544b66663e61e6cd9d6e3195`, read-only analysiert am 15.11.2026. Ziel: `derdigitalaffine/tromuwelle-android` main.

| Bereich / Feature im Hauptrepo | Native Android-Umsetzung | Hinweise |
|---|---|---|
| HTTPS-Login, persönliche Benutzer und erster Administrator | Ja | Setup-Token, Login, Logout; serverbestätigte Wiederherstellung verschlüsselter Session |
| Benutzer/Ordnerrechte | Ja | Admin-Benutzerverwaltung, Rollen, zugewiesene Ordner; Server autoritativ |
| Altes gemeinsames Passwort / Schutzbereiche | Ja | Verwaltungsreiter Sicherheit nur bei Altinstallation |
| Caddy-CA | Teilweise | Benutzer-CA wird systemseitig anerkannt; Import und unabhängige Vertrauensprüfung bewusst außerhalb App |
| Multicast/URL-/Upload-TV-Playlists | Ja | Sender per API; URL/M3U hochladen, aktualisieren, umbenennen, entfernen |
| TV-HLS und adaptive Qualitäten | Ja | Media3 HLS mit Auto-/Maximalauflösung |
| TV-Audiospuren, Textuntertitel, Live-Kante | Ja | Native Media3-Spurauswahl, Live-Sprung; echte Streamvarianten noch geräteprüfen |
| TV-Favoriten, Suche, Sortierung | Ja | Benutzerspezifisch auf dem Gerät |
| Bild-in-Bild und Vollbild | Ja | Android PiP und eigener Vollbild-Player; Gerätereaktion noch prüfen |
| Radio-Liste/Import/CRUD | Ja | Admin-Operationen gemäß `/api/radio/*` |
| Radio-Favoriten, letzte Sender, Genres | Ja | Lokal pro Konto, begrenzter Reconnect |
| Radio-Länder, automatischer Katalogimport | Ja | DE, NL, AT, BE, LU; Admin-Import `/api/radio/catalog/import` |
| Radio-MP3/AAC/Opus und wählbare Bitraten | Ja | Serverseitiges Transcoding mit 48–320 kbit/s |
| Musik Interpreten/Alben/Titel/Suche/Pagination | Ja | `/api/music/*` mit `items/total` aus Hauptrepo |
| Musik-Cover, Queue, Shuffle/Repeat, Resume | Ja | MediaSessionService, Coil mit Session-Cookies, lokale Hörposition |
| Persönliche Musik-Playlists / Änderung und Reihenfolge | Ja | Serverpersistenz; nur für persönliche Konten |
| Musik Qualitätsmetadaten, Sortierung, Lossless und Bitrate | Ja | `/api/music/quality`, Filter `sort/min_bitrate/lossless` |
| Lyrics auf Abruf | Ja | `/api/music/lyrics/{song_id}` – kein Massendownload |
| Musik-Scanner und Status | Ja | Start, Stopp, manueller Status |
| Ordner-Mediathek, Suche, Indexierung | Ja | Medienquellen/Index-API, Suche und Filter |
| Film-Poster, NFO-Metadaten und Untertitel | Ja | Artwork, `/api/media/enrichment`, sidecar WebVTT über Media3 |
| Original / kompatibles MP4 / Auflösung / Seek | Ja mit Einschränkung | Serverstart `start=`; kein gewöhnlicher Seek während transcodierter Pipe |
| Private 18+-Bibliothek, Tags, Genre/Qualität | Ja | Separate Navigation, `category=adult`, freigegebene Quellen |
| 18+-Tag-Taxonomie und Synonyme | Ja | Admin-CRUD `/api/media/adult/taxonomy` |
| Benutzer-/Kontopasswörter, Subsonic-App-Password | Ja | Reauthentisierung, Erneuerung, kein Klartext auf Datenträger |
| API-Keys erstellen, anzeigen, widerrufen | Ja | Neue Schlüssel nur einmal sichtbar |
| M3U/EPG/XMLTV für Jellyfin und OpenSubsonic `/rest` | Serverseitig | Keine Duplizierung auf Android nötig |
| GPU-Encoder/Host-Mounts/FFmpeg-Failsafe | Serverseitig | Android nutzt die fertigen Streams, keine Host-Administration |
| Chromecast/Google Cast | Offen | Zusatzfeature, nicht Teil des bestehenden Hauptrepo-Funktionsumfangs |
| Echte Android-Geräte- und Server-End-to-End-Tests | Offen | CI-Gradle-Tests ersetzen keine Geräteverifikation |
| Dauerhafter App-Release-Signaturschlüssel | Offen | GitHub-Debugsignatur, siehe README |

Die Zeilen mit „Ja“ bezeichnen **implementierte Funktionswege**, nicht eine bereits belegte Realgerät-Freigabe. CI-Kompatibilität wird anhand jedes Commits separat bestätigt.

## Verträge, die aktiv gegen das Backend abgeglichen wurden

- `/api/auth/status`, `/api/auth/setup`, `/api/auth/login`, `/api/auth/logout`, `/api/auth/protection`, `/api/users/*`, `/api/account/*`
- `/api/channels`, `/hls/{id}/master.m3u8`, `/api/playlists/*`, `/api/settings*`
- `/api/radio/stations`, `/api/radio/stream/{id}` mit `bitrate/codec`, `/api/radio/import`, `/api/radio/catalog/import`
- `/api/music/tracks|artists|albums`, `/api/music/quality`, `/api/music/playlists/*`, `/api/music/lyrics/{id}`, `/api/music/stream/{id}`, `/api/music/cover/{id}`, `/api/music/status|scan`
- `/api/media/list|search|file|info|enrichment|thumbnail|artwork|subtitle|index`, `/api/media/adult/facets|tags|taxonomy`

## Technische Abnahme

1. Android CI grün: Kotlin-Compiler, JVM-Tests, Android-Lint und APK-Paketierung
2. Lokaler Netzwerk-/CA-Test mit echter Serveradresse
3. Viewer und Administrator gegen denselben Server testen (API 401/403 und 18+-Trennung)
4. HLS-Wiedergabe mit Tonspuren/Untertiteln, PiP, Audiofokus und Hintergrund-Audio
5. Medienoriginal, inkompatibler Codec, Servertranscoding mit Zeitstart, Header-/Cookie-Rotation
6. App-Prozess beenden → Keystore-Wiederherstellung → Berechtigungsprüfung → Abmeldung
7. Mit stabilem Keystore dauerhaft signierte, aktualisierbare APK für produktiven Einsatz

## Erweiterungen v0.3.0-beta.1

| Webapp / Plattform | Android | Stand |
|---|---|---|
| Externe, eigenständige Subsonic-Server | Ja, zusätzlich | Quellen mit eigenem HTTPS-Login und Gerätverschlüsselung, nicht nur Tromuwelle-Subsonic |
| Externe Künstler-, Alben-, Titellisten und Suche | Ja | GetArtists, GetArtist, GetAlbumList2, Search3, GetAlbum |
| Externe Playlists und Favoriten | Ja | Browsing, Erstellen, Löschen, Song hinzufügen/entfernen, Star/Unstar. Playlist-Umbenennen/Drag-Order bei Fremdservern nicht garantiert |
| Externe Musik-Cover und Downloads | Ja | OpenSubsonic getCoverArt/download über HTTPS |
| Eigene und externe synchronisierte Lyrics | Ja | LRC / strukturierte Lyrics, aktiver Text, ein-/ausschaltbare Markierung |
| Android Auto Headunit | Audio | MediaLibraryService, Musik und Radio, keine Videotitel |
| Android Auto Video | **Nicht unterstützt** | Android-Auto-Plattform untersagt Video-Apps als Projektion |
| Android Automotive OS – Video | Parked-only | Zweites APK-Flavor mit appCategory video und OS-Fahrsperren, Geräteprüfung ausstehend |
| Android Auto-Sprachsuche | Ja | Audio-Suche, MediaStore MEDIA_PLAY_FROM_SEARCH |
| Eigenes Video-Dialogfenster statt Player oberhalb langer Listen | Ja | Bibliothek bleibt im Hintergrund und an ihrer Scrollposition |
| 18+-Kategorien vollständig neu zuweisen | Ja | `POST /api/media/adult/reindex`; UI mit Status und Zählern |
| Android MediaBrowser kompatible Wiedergabe | Ja | Media3 MediaLibraryService und MediaBrowserService-Adapter |
| Echte DHU-/AAOS-Emulator-/Fahrzeugtests | **Offen** | GitHub CI baut und testet beide Varianten, testet kein Fahrzeug |
| Server- und plattformübergreifende Fortschritts-/Favoritensynchronisation | Teilweise | Lokale Favoriten und Fortschritt; externe Stars serverseitig |
