# JARVIS — verbindliche Zielarchitektur (30.10.2026)

## Produktentscheidung
Wir entwickeln **eine einzige Android-Anwendung namens JARVIS**, orientiert am Funktionsprinzip von Tony Starks Assistenten: natürliche Sprache, Kontext, Werkzeuge, koordinierte Aufträge, proaktive Hinweise und spezialisierte Fähigkeiten. Ziel ist ein nützlicher, kontrollierbarer persönlicher Assistent — **keine Behauptung**, dass Filmfunktionen wie allgegenwärtige Gerätehoheit bereits existieren.

**Der vorhandene TFT-Coach ist kein architektonisches Fundament und keine Startvoraussetzung.** Er bleibt als Referenz/Regressionstests erhalten. Wir übernehmen Funktionen nur bei konkretem Nutzen und nach Tests; TFT wird **optional** als späterer Skill integriert.

## Was übernehmen, was nicht?
| Aus dem bisherigen Repository | Entscheidung | Begründung |
|---|---|---|
| Android-App/Build/Signaturprozess | Wiederverwenden, soweit kompatibel | Bewährte Entwicklungs- und Updatebasis; dennoch separates Beta-Paket |
| SpeechController / Android TTS / Mute | Adapter wiederverwenden, Stimme später verbessern | Sprachpipeline existiert, filmähnliche Stimme noch nicht erreicht |
| Android Keystore für API-Zugänge | Prinzip und geprüfte Implementierung übernehmen | Keine Schlüssel im Quelltext oder Logs |
| Overlay / MediaProjection / Bildalter- und Abbruchschutz | Als optionalen Bildschirm-Skill übernehmen | Nur nach aktiver Bildschirmfreigabe; kein dauernder Screenshot-Zwang |
| TFT HUD / MatchState / EvidenceLedger / ROI | **Nicht** in den Kern übernehmen | Strikt auf TFT begrenzter Spezialskill |
| TFT-Prompts / Auto-Analyse / vorgefertigte Coaching-Regeln | **Nicht** für den allgemeinen JARVIS verwenden | Kein generischer Assistent, keine ständigen Bild-API-Kosten |
| QA2-Wiederholungstest | **Nicht wiederholen**, soweit bestätigt | Sprachansage, Homescreen, explizites Vorlesen bereits bestätigt; andere Geräte-/Gameplay-Qualität bleibt offen |

## Architektur: lokal zuerst, Cloud nur bei echtem Mehrwert
1. **Android Shell:** Startscreen **JARVIS**, Mikrofontaste, Textfeld, Status, Einstellungen, Berechtigungen und optional Overlay. Keine Bildschirmfreigabe zum normalen Start erforderlich.
2. **Eingabe:** zuerst Push-to-talk und Texteingabe; automatische Spracherkennung/Wake Word später nach Latenz-/Akku-/Datenschutztest. Eingaben werden mit Herkunft, Zeitstempel und Abbruch-ID versehen.
3. **Core / Router:** entscheidet deterministisch für bekannte lokale Befehle. Für offene Aufgaben kann ein konfigurierbarer KI-Dienst Werkzeuge auswählen; keine KI-Anfrage für einfache Schalter.
4. **Capability-Schnittstelle:** jede Funktion hat eindeutigen Namen, Fähigkeiten/Berechtigungen, erforderliche Eingaben, strukturierte Ergebnisse, Fehler und Cancel. Der Kern kennt keine TFT-spezifischen Klassen.
5. **Tools/Skills:** lokale App-Aktionen (Mute, Status, Timer falls freigegeben), Recherche/KI mit optionalen Diensten; später Kalender, Notizen, Erinnerungen, Dateiverwaltung, TFT-Bildschirm-Skill.
6. **Kontext:** kurzlebiger Gesprächskontext von bewusst gespeicherter persönlicher Erinnerung trennen; Daten einsehbar, korrigierbar und löschbar; TFT-Matchspeicher bleibt vollständig getrennt.
7. **Ausgabe:** eine konsistente Sprach- und Textantwort; Unterbrechung, Stumm, Lautstärke und Streamingstatus. Natürliche deutsche Stimme durch vergleichbare, nutzungsrechtlich zulässige Engine ersetzen.
8. **Auftragssystem:** Aufgaben besitzen Status (geplant/läuft/wartet/erledigt/fehlgeschlagen/abgebrochen), Fortschritt, Zeitlimits und nachvollziehbares Ergebnis; keine behauptete Ausführung ohne tatsächlichen Tool-Erfolg.
9. **Sicherheit:** Berechtigungen nach Funktion. Vor externen, kostenpflichtigen, löschenden oder sonst irreversiblen Aktionen Bestätigung; Geheimnisse im Android Keystore; keine privaten Bildschirmbilder in Quellcode/Protokollen.
10. **Betrieb:** **kein eigener Server erforderlich** für lokale Steuerung. Optionaler Cloud-KI-Dienst für Sprache/Reasoning/Suche. Offline-Basisbefehle müssen auch ohne Internet funktionieren.

## Priorisierte Phasen und objektive Akzeptanz
**P0 — unabhängiger JARVIS-Kern:** neue App-Bezeichnung/Startseite; Textbefehle `Hilfe`, `Status`, `Stumm`, `Laut`, `Abbrechen` lokal; Capability-Registry, Router, Fehler-/Abbruchmodell. **Erfolg:** startbar ohne TFT-/Screen-Capture-Freigabe; keine API-Aufrufe für lokale Befehle; Android-Build und Unit-Tests grün.

**P1 — Sprachassistent:** Push-to-talk, Sprache-zu-Text, Text-zu-Sprache, schnelle Antworten, Unterbrechung; gemessene Latenzen; identische Fähigkeiten per Text und Sprache. **Erfolg:** auf S24 Ultra reproduzierbar; Berechtigungen werden verständlich angefordert; kein ungewolltes Hot-Mic.

**P2 — Werkzeug-Assistent:** konfigurierbarer KI-Provider; Skill-Auswahl und Auftragsstatus; mindestens zwei reale allgemeine Aufgaben mit bestätigtem Tool-Ergebnis, keine Scheinaktionen. **Erfolg:** klare Zustimmung bei kritischen Handlungen; API-Kosten und Ausfälle nachvollziehbar.

**P3 — optionale Fähigkeiten:** Android-Screen-Skill und TFT-Skill nur bei expliziter Aktivierung; keine Kontextvermischung. TFT-Live-Coaching erst nach Policy-/Gameplay-/Bildalter-Prüfung; keine ungesicherte Riot-Freigabe behaupten.

**P4 — proaktiv:** wählbare Erinnerungen/Automationen und später Wake Word erst nach eigener Bewertung von Datenschutz, Akku, Kosten und Zuverlässigkeit.

## Entwicklung und Release
- QA2 `#43` bleibt **isolierter Testzweig**, niemals ungeprüft mergen. Sein positives Ergebnis belegt die Vorlesefunktion, nicht JARVIS-Alltagstauglichkeit.
- Eigenständiger Arbeitszweig `feature/unified-jarvis-core-20261030` auf `main`.
- Unnötige Bestandteile dürfen entfallen; bestehende funktionsfähige Android-Komponenten müssen **nicht** blind übernommen werden.
- Für jeden Meilenstein: Code, Unit-/CI-Tests, konkrete Funktionsprobe, APK/Commit/Hash und getrennte S24-Abnahme; kein "fertig" allein aufgrund grüner CI.
- Für eine unverwechselbare Sprachausgabe eine rechtmäßig nutzbare Stimme verwenden; keine identische Filmsprecher-Stimme ohne Rechte.

## Unmittelbarer nächster Implementierungsschritt
`JarvisCapability` + `JarvisRouter` + lokale Basisbefehle als Android-unabhängige Java-Komponenten implementieren und testen. Danach Android-Startseite auf JARVIS statt TFT konfigurieren, TFT-Grenze als optionalen Adapter vorbereiten; erst anschließend Spracherkennung hinzufügen.
