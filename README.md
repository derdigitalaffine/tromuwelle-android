# Tromuwelle Android
Native Android-Anwendung in Kotlin/Jetpack Compose mit Media3. Kein WebView.
## Build
JDK 17 und Android SDK 35 installieren, dann `gradle :app:assembleDebug` ausführen. GitHub Actions stellt die Debug-APK als Build-Artefakt bereit.
## Stand
Erster vertikaler Slice: HTTPS-Login, Sender-/Radio-/Musiklisten, nativer Media3-Player. Noch nicht produktionsreif: Sessionpersistenz, Medienbibliothek, robuste Audio-Background-Wiedergabe, Streaming-Cookies in Media3, vollständige Musik-JSON-Vertragsprüfung, TV-Start-Lifecycle, Tests und Signierung. Für Tromuwelle mit privater Caddy-CA muss das CA-Zertifikat auf Android vertrauenswürdig eingebunden werden; TLS wird nicht umgangen.
## Architektur
Server ist autoritativ für Rechte und Streamzugriff. Keine geheimen Zugangsdaten im Repository. AGENTS.md beschreibt die aus EinsatzPulse übernommene Delivery-Organisation.
