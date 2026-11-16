# Funktionsabgleich: privates Tromuwelle-main → Android

Referenz: `derdigitalaffine/tromuwelle` `086e8eb69f964681544b66663e61e6cd9d6e3195`, read-only analysiert am 15.11.2026. Ziel: `derdigitalaffine/tromuwelle-android` main.

| Bereich / Feature im Hauptrepo | Native Android-Umsetzung | Hinweise |
|---|---|---|
| HTTPS-Login, persönliche Benutzer und erster Administrator | Ja | Setup-Token, Login, Logout; serverbestätigte Wiederherstellung verschlüsselter Session |
| Benutzer/Ordnerrechte | Ja | Admin-Benutzerverwaltung, Rollen, zugewiesene Ordner; Server autoritativ |
| Altes gemeinsames Passwort / Schutzbereiche | Ja | Verwaltungsreiter Sicherheit nur bei Altinstallation |
| Caddy-CA und selbstsignierte Zertifikate | Ja, mit Freigabe | Android-Benutzer-CA weiterhin unterstützt; alternativ manuell bestätigte Leaf-SHA-256-Pins und Widerruf direkt in Tromuwelle- und Subsonic-Verbindungsdialogen |
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

## v0.3.1-beta.1: TLS und Legacy-Login

| Teilfunktion | Implementierung | Sicherheit |
|---|---|---|
| Bestandsserver mit gemeinsamem Passwort | Legacy-Login mit leerem Benutzernamen | Kein fiktiver Standardbenutzer `admin`; Server ignoriert `username` in dieser Phase |
| Erkennung Nutzerverwaltung | `/api/auth/status` `managed` | Benutzername wird erst bei bestehender Kontenverwaltung benötigt |
| Selbstsigniertes HTTPS-Serverzertifikat | Im Login und im externen Subsonic-Editor prüfbar | Nur unauthentifizierte TLS-Handshake-Inspektion; **explizite Bestätigung** des angezeigten SHA-256-Fingerabdrucks |
| Android normaler TLS-Vertrauenspfad | Vorhanden | System- und Benutzer-CAs bleiben aktiv |
| Zertifikatswechsel | Warnung / erneutes Prüfen | Kein automatischer TOFU-Trust ohne Nutzereinwilligung |
| Widerruf manuell vertrauter Zertifikate | Ja | Pro Serveradresse/Port; neue Verbindungen werden ohne Pin geprüft |
| Video/Audio/Radio/Artwork, Fremd-Subsonic | Gemeinsame TLS-Trust-Konfiguration | Keine globale TLS-Deaktivierung |
| Lokale private CA bei automatischer Leaf-Erneuerung | Via Android-Benutzer-CA | In-App-CA-Import steht nicht zur Verfügung; Android-Einstellungen verwenden |

**Offen:** Die neue Zertifikatsfreigabe ist auf realen Android-Versionen und mit einem konkreten Caddy-Server/Zertifikatswechsel zu testen. Sie ersetzt keine unabhängige Verifizierung des ersten Fingerabdrucks.

## TLS-Handshake-Hotfix v0.3.2-beta.1

- Neue Fehlerklassifikation `tlsConnectionDiagnostic` für `TLS1_ALERT_INTERNAL_ERROR` / `0A000438` und Android-BoringSSL-Code `100000438`, getrennt von `certificate verify failed` / unbekannter CA und `connection refused`.
- Login, Serverprüfung, Zertifikatsinspektion und externe Subsonic-Verbindung zeigen den Nutzerinnen und Nutzern umsetzbare Hinweise, statt einen TLS-Alert fälschlich als Passwort- oder Vertrauensfehler auszulegen.
- Serverseitiger Gegenpart: Hauptrepo Caddyfile `default_sni` mit `TROMUWELLE_CERT_IP`; muss auf dem **laufenden** Server übernommen und neu gestartet werden.
- Keine unsicheren Verbindungen, keine globale `trustAll`-Ausnahme. Die optionale Zertifikatsfreigabe bleibt explizit und auf SHA-256-Fingerabdruck begrenzt.
- Neuer JVM-Test `TlsConnectionDiagnosticTest` deckt OpenSSL- und Android-Fehlerbezeichnungen ab.

## Native Redesign- und Podcast-Abgleich v0.4.0-beta.1

| Bereich | Android-Funktionsweg | Echte Server-API |
|---|---|---|
| Home-Rails und Cover | Neue Startseite, Poster und Alben | Kanäle, Radio, Musik und normale Medien-Suche |
| Musik und Playlists | Covergrid, Songliste, NativePlayerControls und serverseitige Lyrics | /api/music/* |
| Videos und Ordner | Raster, Details, Original/Kompatibel, Metadaten, Download | /api/media/* |
| Radio und Live-TV | Eigene Tab-Navigation, Sortierung, Player | /api/radio/stations, /api/channels |
| Globale Suche | Musik, Radio, TV, reguläre Videos; 18+ ausgeschlossen | /api/music/*, /api/media/search, /api/radio/stations, /api/channels |
| Podcasts-Sendungen und Entdecken | RSS-Abos, Feed-Detail und Suche | /api/podcasts/feeds, /api/podcasts/directory |
| Podcast-Folgen | Filtern, Blättern, Abspielen, Fortschritt und Download auf Server | /api/podcasts/episodes und /progress, /stream, /download |
| Podcast-Transkripte | 80er-Seiten im Player, Zeitmarkierungen, Scroll-Follow, Springen | /api/podcasts/episodes/{id}/transcript |
| Sicherheit, Verwaltung | Legacy- und Nutzerlogin, Adminfunktionen, selbstsignierte Zertifikat-Pins | /api/auth/*, /api/users/* |
| Android Auto | Audiomedienbibliothek, nicht Video-Projektion | MediaLibraryService |
| Android Automotive OS | Video nur bei Fahrzeugstillstand | Plattformbeschränkungen bleiben bestehen |

Offen: echte Gerätetests, Podcast-E2E mit einem realen Feed, SNI/Caddy-TLS mit Realgerät und dauerhaft gültige Release-Signatur. Die Tabelle beschreibt implementierte Pfade, keine Hardware-Zertifizierung.

## Streaming-UI, Thumbnail, Audio und Cast v0.5.0-beta.1

| Funktion | Backend/Android-Integration | Einschränkung |
|---|---|---|
| Film-Poster → Videostandbild | /api/media/artwork, /api/media/thumbnail, Coil-HTTPS-Transport | HTTP 503 bei laufender Servergenerierung: begrenzter Retry |
| Medienordner-Vorschaubild | /api/media/folder-thumbnail für autorisierte Roots | Ordner ohne indizierte Filme zeigen Fallback |
| Startseite: Hero/Weiter ansehen/Podcasts | Echte Katalogdaten, lokale Wiedergabeposition, /api/podcasts/episodes | Private 18+-Medien ausgeschlossen |
| Mini-Player mit Cover & Fortschritt | AuthenticatedArtwork, Media3-Player | Kein Remote-Cast-State im Mini-Player |
| Interner Ausgang/Bluetooth/USB/HDMI | AudioManager GET_DEVICES_OUTPUTS, ExoPlayer setPreferredAudioDevice | Routenpräferenz ist keine Garantie; Bluetooth-Berechtigung Android 12+ |
| Cast-Geräteauswahl | Google Cast Framework 22.3.1, Default Media Receiver | Play-Dienste und erreichbarer Receiver erforderlich |
| Cast eigene Musik/Podcasts/Radio/Videos | POST /api/cast/ticket, GET /api/cast/stream/{token}, 4h-Ticket, kompatibles MP3/MP4 | Live-TV/HLS und externe OpenSubsonic ausgenommen |
| Cast mit privater Caddy-CA | Optionales Backend-Profil cast-lan, Port 8098, zeitlich begrenzter Einzeltitel-Link | Bewusst unsichere LAN-Übertragung; standardmäßig deaktiviert |
| Podcast-Admin-Cacheverwaltung | /api/podcasts/status, /api/podcasts/settings: days/MB/delete_played | Administratorrecht, separates podcast_cache-Docker-Volume |
| Gerätetest Cast/Audio/Thumbnail | GitHub CI baut, unit-testet und lintet Code | Echte Geräte und Server nicht im CI verfügbar |

## v0.6.0-beta.1 – Smart Discovery und UX-Abnahme

| Bereich | Native Integration | Datenquelle und Grenzen |
|---|---|---|
| Adaptive Navigation | Tablet-NavigationRail; Bottom-Navigation auf Handy/kurzem Querformat | Laufende Display-Breite und -Höhe, kein zusätzlicher SDK-Zwang |
| Globale Live-Suche | Debounce, Relevanzsortierung, Medien-Kategorien und Podcastfolgen | Parallel/API-getrieben, 18+-Medien ausgeschlossen |
| Suchverlauf | Letzte acht manuelle Suchanfragen, löschbar, nach Server+Nutzer getrennt | Nur lokale Android-Preferences; kein Cloud-Tracking |
| Library-Filter | Compact Search, Reset, Qualitäts- und Bitraten-Drawer | Backend-Filter bleiben maßgebend |
| Home-Performance | Parallel geladene Podcast-/Musik-/Radio-/Video-/TV-Bereiche | Teilfehler isoliert, keine erfundenen Resultate |
| Podcasts fortsetzen | Server-Podcastpositionen und direkter Start aus Home | Erfordert aktuelle serverseitige subscriptions/progress |
| Radiostationen zuletzt gehört | Gesamtes erlaubtes Senderverzeichnis statt nur 18 Startsender | Lokal gespeicherte Stations-IDs, Server prüft Zugriffe |
| Spielerlebnis | Großes Cover, Queue-Vorschau, direkte Cast/Bluetooth-Ausgabe, Filmlaufzeit und Fortsetzen | Media3 + serverseitige Streams |
| Private Bibliothek | Kein Eintrag der aktiven privaten Wiedergabe in öffentlicher Home oder Mini-Player außerhalb Private | Kein globales Erwachsenen-Content-Discovering |
| Podcast-Cover | Adaptive 2–5-Spalten, Directory-/Feed-Bilder, asynchrones Suchen | RSS/Apple-Directory-Cover nur via HTTPS |
| Hardwareabnahme | Android/AAOS Debug-APKs, JVM-Tests und Android-Lint in CI | Google Cast, Bluetooth-Route, Bildschirmgrößen und reale TLS/FFmpeg-Endpunkte vor Ort testen |

## v0.6.1-beta.1: Performance, Suche und Bibliotheken

- Kategoriesuche spart nicht benötigte API-Abfragen; Wechsel des Suchfilters entfernt veraltete Resultate, abgebrochene Coroutines werden nicht als Verbindungsfehler fehlklassifiziert.
- Posterbibliotheken als echtes adaptives LazyVerticalGrid mit stabilen Medien-IDs und eigener Full-Width-Nachladen-Zeile; weniger UI-Neuberechnungen beim Paging.
- Authentifizierte Cover behalten Cookie-/TLS-Transport, Session-Cache-Trennung und Fallback-Retry, mit gemerkten Coil Requests und nicht animiertem Platzhalter für geringere Rendering-Last.
- JVM-Test für die Serverbereichs-Auswahl; messbare FPS-/Netzwerk-Werte und echte Geräte-/Serverprüfungen stehen weiter aus.

## v0.6.2-beta.1: externe Musikbibliotheken und Player

- OpenSubsonic-Bibliotheksnavigation mit In-Flight-Abbruch, monotonen Request-Tokens und UI-gebundener Antwortzuordnung; bei Server-/Seitenwechsel können alte Daten nicht mehr als neue Seite erscheinen.
- Adaptive manuelle Pagination für `getAlbumList2` und `search3` nur bei echten Offset-Abfragen; `getRandomSongs`, `getArtist`, `getAlbum` und Playlists werden nicht irrtümlich mehrfach angehängt.
- Debounced Suche, klare Zufallstitel-Aktualisierung, Zeilenaktionen als Icons, stabile LazyColumn-Schlüssel und kompakte Beschriftungen.
- Reuse eines TLS-geprüften OkHttp-Clients für alle Subsonic-Netzwerkanfragen (ohne Cookies) sowie Coil-Request-Memoization mit credential-abhängigen URL-Schlüsseln.
- Media3 Player-Control mit zugänglichen Material-Icons, aktualisiertem Statuslistener, interaktivem Scrubbing und ruhigerem Polling bei Pause.
- CI: Mobile und Automotive Unit-Tests, Lints und APKs; kein realer Geräte- oder Streaming-Langzeittest.

## v0.6.3-beta.1: Player-Recovery und Ressourcen

- Radio: transient-only Retry bei Netzwerkausfällen, ausgewählten HTTP-5xx/429 mit bounded backoff; keine automatischen Retries bei 401/403/404 oder Codecfehlern
- Playback-Generation verhindert verspätete Film-Metadaten-/Untertitelantworten nach Titel- oder Kontowechsel; Radio-Retry-Jobs werden auf neuer Session/Titelauswahl gecancelt
- Globale Musiksuche garantiert valide Queue für Treffer, die noch nicht zur aktuellen Musikliste gehören
- Bibliotheksabruf cancelt superseded Jobs und verwendet unveränderliche Filter-Snapshots
- Podcast-Progress und Resume-Preferences schreiben nur bei signifikanten Änderungen, Audio-Mini-Player pollt langsamer bei Pause
- Mobile Audio-Sheet zeigt Radiofehler und erneuten Versuch; reduzierte Artwork-Höhe auf kurzen Displays
- JVM-Regression für Fehlerklassifikation, Playlist-/Radio-Generationsabgleich und Progress-Schreibschwellen; Realgerät- und Server-Dauertests offen

## v0.7.0-beta.1: Cast, Farben und modernes Login

- Cast-SDK-Callbacks statt permanenter Polling-Abfragen; Media-Status/Fehler, Wiedergabe und Fernbedienung sind mit echter Receiver-Session gekoppelt
- Lokale Wiedergabe wird erst nach nachgewiesenem Remote-Playback des spezifischen Tickets pausiert, nie nur nach LOAD-Acknowledgement
- Cast-Sender verwendet den regulären MediaRoute-Picker auf Android 13+ und behält ReconnectionService und gespeicherte Sessions bei; kein impliziter System-Output-Switcher ohne MediaTransferReceiver
- Bearer-grant URLs werden ausschließlich unter /api/cast/stream/ mit HTTPS oder servergenerierter privater IPv4-Brücke Port 8098 akzeptiert; keine Credentials im Cast-Link
- Material 3: globaler Theme-Store mit fünf Paletten, Hell/Dunkel/System, gespeicherte Auswahl, Material You ab Android 12
- Hoher Kontrast und responsives Login/Connect inkl. Zertifikatsprüfung, Legacy-Passwortmodus und Initialsetup
- Media3: sichtbares Buffering, bessere Ton/Untertitel-Auswahl, aktualisierter Lautstärkestatus; responsive Mini-Player-Farben
- QA: JVM-Tests für Cast-URL-Regeln und Theme-Werte; Mobile+Automotive Builds, Lint, Unit-Tests; Cast-Geräte/Accessibility-Messungen nicht behauptet
