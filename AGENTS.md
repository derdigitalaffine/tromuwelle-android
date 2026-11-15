# Tromuwelle Android – Agentenorganisation
Referenz: EinsatzPulse AGENTS.md und docs/development-process.md (nur lesend ausgewertet).
## Dauerhafte Steuerung
Issues #1 Produktsteuerung, #2 Delivery/Triage, #3 Agentenorganisation bleiben offen. Genau ein NOW-Scope. Vor jedem Slice main, PRs, CI und Issues prüfen.
## Rollen
Product/UX: native TV-, Radio-, Musik- und Medienbibliothek. Android: Compose, Media3, Lifecycle, Background. API/Security: Tromuwelle-Backend-Verträge, Sessions, TLS. QA/Release: Tests, Emulator, APK-Artefakte.
## Loop
Observe → Triage → Plan → Implement → Test → Review → Merge → Replan. Kleine vertikale Slices; keine unbelegten Done-Aussagen.
## Guardrails
Keine Zugangsdaten in Git, keine TLS-Bypasses, kein WebView-Ersatz, kein stilles Verschlucken von API-Fehlern. Backend ist autoritativ für Rechte. Reconnect, Prozessende, MediaSession, Audio-Fokus, Android TV/Tablet und Qualitätsswitches testen.
## Done
Native Funktion mit realem Server verifiziert, Tests und Gradle-Build grün, CI-APK verfügbar, Dokumentation entspricht main, PR gemergt.
