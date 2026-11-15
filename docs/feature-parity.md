# Funktionsabgleich: privates Tromuwelle-main → Android

Referenz: `derdigitalaffine/tromuwelle` `2a85fdf17996379b916ec434d558cf301c677fd2`, read-only analysiert am 15.11.2026. Ziel: `derdigitalaffine/tromuwelle-android` main.

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
| Radio-Favoriten, letzte Sender, Genres | Ja | Lokal pro Konto; automatische Wiederverbindung noch eingeschränkt |
| Musik Interpreten/Alben/Titel/Suche/Pagination | Ja | `/api/music/*` mit `items/total` aus Hauptrepo |
| Musik-Cover, Playlist/Queue, Shuffle/Repeat, Resume | Ja | MediaSessionService, Coil mit Session-Cookies, lokale Hörposition |
| Musik-Scanner und Status | Ja | Start, Stopp, manueller Status |
| Ordner-Mediathek, Suche, Indexierung | Ja | Medienquellen/Index-API, Suche und Filter |
| Film-Poster, NFO-Metadaten und Untertitel | Ja | Artwork, `/api/media/enrichment`, sidecar WebVTT über Media3 |
| Original / kompatibles MP4 / Auflösung / Seek | Ja mit Einschränkung | Serverstart `start=`; kein gewöhnlicher Seek während transcodierter Pipe |
| Private 18+-Bibliothek, Tags, Genre/Qualität | Ja | Separate Navigation, `category=adult`, freigegebene Quellen |
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
- `/api/radio/stations`, `/api/radio/stream/{id}`, `/api/radio/import`
- `/api/music/tracks|artists|albums`, `/api/music/stream/{id}`, `/api/music/cover/{id}`, `/api/music/status|scan`
- `/api/media/list|search|file|info|enrichment|thumbnail|artwork|subtitle|index`, `/api/media/adult/facets|tags`

## Technische Abnahme

1. Android CI grün: Kotlin-Compiler, JVM-Tests, Android-Lint und APK-Paketierung
2. Lokaler Netzwerk-/CA-Test mit echter Serveradresse
3. Viewer und Administrator gegen denselben Server testen (API 401/403 und 18+-Trennung)
4. HLS-Wiedergabe mit Tonspuren/Untertiteln, PiP, Audiofokus und Hintergrund-Audio
5. Medienoriginal, inkompatibler Codec, Servertranscoding mit Zeitstart, Header-/Cookie-Rotation
6. App-Prozess beenden → Keystore-Wiederherstellung → Berechtigungsprüfung → Abmeldung
7. Mit stabilem Keystore dauerhaft signierte, aktualisierbare APK für produktiven Einsatz
