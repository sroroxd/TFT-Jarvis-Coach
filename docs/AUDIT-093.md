# TFT JARVIS 0.9.3 – Zwischenbilanz und technische Abnahme

Stand: 24. Oktober 2026. Ausgangsbasis: v0.9.2-beta3 auf main.
Zielgerät: Samsung Galaxy S24 Ultra, Android; **keine** Behauptung über
tatsächliche Ingame-Genauigkeit ohne Tests mit Spielbildern/Hardware.

## Bestandsaufnahme und Planabgleich

| Subsystem / Anforderung | Stand 0.9.3 | Beleg / verbleibende Aufgabe |
| --- | --- | --- |
| Native Bildschirmfreigabe | implementiert | Android MediaProjection / ScreenCaptureService; Hardware-Lifecycle/Rotation nicht verifiziert |
| Overlay und Start/Stop | implementiert | MainActivity, ScreenCaptureService |
| Automatische Bildauswahl | implementiert, heuristisch | SmartFrameDetector nutzt Luma-Fingerprints; event/ROI-Testfixtures fehlen |
| Screenshot-Frische, Reset-Schutz | implementiert | FrameFreshnessGate, Generation beim Beginn der Aufnahme |
| Lokale HUD-OCR | teilweise | ML Kit, eigene Level-/Gold-Badge-Regionen; echte Präzision noch nicht gemessen |
| Eigene Lebenspunkte | **nicht lokal zuverlässig** | ungeankerte OCR am Timer entfernt; Cloud-Werte bleiben zunächst unbestätigt |
| Board/Shop/Augment-Zoom | rudimentär | TftRoiSelector + Cloud-Bild; keine quantifizierte OCR-/Objekterkennung |
| KI-Auswertung | implementiert | Gemini/OpenAI; Netzwerklatenz, Kosten, Fehlerklassen variieren |
| Match-Memory | implementiert, konservativ | MatchState, EvidenceLedger; HW-Replays, neue Spielrunden noch testen |
| Sprachansagen | implementiert | Android TTS, Stumm-Schaltung persistent; Headsettests fehlen |
| Manuelle Anfragen während laufender Analyse | nachgerüstet | ManualRequestLatch hält max. einen Folgeauftrag für neues Bild |
| Menü Auto/Stumm/Neue Partie/Status | nachgerüstet | Overlay; Datenschutz-zentrierte Diagnosezähler |
| Direkte updatefähige APK | implementiert | permanent signierter GitHub-Release, versionCode 33 |
| Vorher-/Nachher-Training und Review | **noch nicht implementiert** | benötigt getrennte UX, Einwilligung, Datenspeicherung und Datensatz |
| Explizite Frame-IDs und durchgehendes Trace-Logging | **teilweise** | Aufnahmezeit, Generation, anonyme Zähler; keine vollständige Replay-Pipeline |
| Meta/Set-Aktualisierung | **offen** | Hardcodierte Patch-Behauptung in der UI entfernt; Datenquelle und Versionsstrategie fehlen |
| Ranked Top-1-Optimierung | **nicht nachgewiesen** | Keine gemessene Strategie-/Winrate-Verbesserung oder S24-Ultra-Ranked-Abnahme |

## Nachgewiesene Defekte und Korrekturen

1. **Level 2 wurde als Level 4 angezeigt.** Auf einem früheren TFT-Mobile
   Screenshot traf das OCR-Fenster den danebenliegenden EP-Kaufpreis 4.
   Level und Gold werden nur noch in kalibrierten eigenen HUD-Regionen akzeptiert.
   Unit-Tests prüfen die bekannten Beispielkoordinaten.
2. **Falsche hohe HUD-Sicherheit.** Ein Cloud-Wert allein kann Level, Gold,
   HP oder Stage nicht mehr als HOCH bestätigt setzen. Überhöhte Level können
   durch zwei übereinstimmende Beobachtungen unter Unsicherheitsmarkierung
   korrigiert werden.
3. **Unsichere HP-Erkennung.** Der obere Bildschirm-Timer war kein belegter
   HP-Anker. Diese lokale HP-Heuristik wurde entfernt.
4. **Manueller Trigger verloren.** Während KI-/OCR-Arbeit werden weitere
   manuelle Auslöser zusammengefasst und anschließend an einem neu aufgenommenen
   Bild abgearbeitet. Keine unbegrenzte Anfragewarteschlange.
5. **Veralteter Timeout konnte eine neue Bildaufnahme abbrechen.**
   Zeitlimits werden jetzt nur für die korrespondierende Capture-ID akzeptiert.
6. **Reset während OCR/Netzwerk.** Der Generationstoken stammt jetzt bereits
   vom BEGINN der Aufnahme, nicht erst vom späteren Cloud-Aufruf.
7. **Unkontrollierte API-Fehlertexte.** Unerwartete HTTP-/JSON-Details erscheinen
   nicht mehr ungekürzt im Overlay; defekte Gemini-Antworthüllen lösen einen
   definierten Fehler aus.
8. **Fehlende Bedienung.** Persistentes Stumm, Status und neue Partie ergänzt;
   datensparsame Zähler für Bilder, OCR-/KI-Dauer, Requests und Fehlerklassen.
9. **Veraltete Versionsbeschreibung.** Fester Patch-/Set-Hinweis in der
   Oberfläche entfernt; keine unbewiesene Aktualität mehr vortäuschen.

## Prüfkriterien

CI muss bestehen: `testDebugUnitTest`, `lintDebug`,
`assembleDebug`, signierter `assembleRelease`-Testbuild, signiertes APK
verifizieren, alte veröffentlichte APK per SHA-256/Signatur/Manifest prüfen.
Produktivworkflow muss exakt die dauerhafte Signatur verwenden und
`JARVIS-0.9.3-beta4.apk` als *einzelne* Datei veröffentlichen.
`applicationId=com.pete.tftjarvis`, `versionCode=33`.

## Offen und bewusst nicht als „fertig“ deklariert

- Quantitative Erkennungsraten für Level, Gold, HP, Shop, Units, Items,
  Augments, Phase/Match-Ende auf echten consented S24-Screenshots.
- MediaProjection-Neustart, Android 17, Hintergrundbetrieb, Rotation,
  Screen Lock, Akku, Wärme, TTS über Bluetooth.
- Anpassung von HUD-Koordinaten bei weiteren TFT-Versionen/Auflösungen.
- Dauerhafte offline Replay-/Benchmark-Fixtures ohne private Bilddaten im Repo.
- Automatische Erkennung von Runde/Phase mit belastbarer Ground Truth,
  ROI-Trigger und Cloud-Anrufbegrenzung.
- Keine personenbezogenen Screenshots, Secrets oder Prompt-Inhalte in Logs.
- **Riot-Risiko:** Die [offizielle TFT Developer Policy](https://developer.riotgames.com/docs/tft)
  bezeichnet dynamische Echtzeit-Informationen und direkt vorgeschriebene
  Spielerentscheidungen als nicht genehmigte Anwendungsfälle. Statische
  Vorbereitung und Match-Review sind deutlich besser geeignet.
  Ein erfolgreicher Android-Build ist keine Riot-Freigabe.

## Nächster sicherer Ausbauschritt ohne Live-Spieltest

Offline-Testfixtures (mit Zustimmung) und ein separater Vorbereitungs-/
Nachanalyse-Modus; danach kalibrierte Erkennungsmetriken und robuste
Phasenwechsel mit datensparsamer Telemetrie. Änderungen erst nach Tests
und Signaturprüfung veröffentlichen.
