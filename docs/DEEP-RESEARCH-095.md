# JARVIS Deep Research 2 – technische Analyse und Vergleich
Stand: 24.10.2026 | Baseline: v0.9.4-beta5 (versionCode 34).
Zielgerät: Samsung Galaxy S24 Ultra, native Android-App.

## 1. Eigener Code – nachgeprüfter Stand

- MediaProjection (Android, MediaProjection Callback.onStop), ImageReader RGBA_8888,
  ML Kit Text Recognition on-device, wenige feste TFT-HUD-Regions, 24x14-Luma-Fingerprint,
  optionale Gemini/OpenAI-Antworten, MatchState/EvidenceLedger,
  permanent signierte direkte APK-Updates.
- Keine belegte numerische OCR-Genauigkeit für Level/Gold/HP/Phase/Units.
- Fehlender onCapturedContentResize-Callback für Android 14+ war eine konkrete
  Schwachstelle: bei Rotation oder App-Window-Sharing passt die Capture-Oberfläche
  sonst nicht mehr zur tatsächlichen Screenshot-Geometrie.
- Fehlendes robustes „capability negotiation“ für Einzelfreigabe/Vollbild und
  echtes Hardware-Replay. Mit 0.9.5 beginnt die Capture-Geometrie-Absicherung.

## 2. Externe Projekte – was technisch übertragbar ist

| Projekt | Architektur / Stärken | Einschränkungen | Urteil |
| --- | --- | --- | --- |
| [TFT-Copilot](https://github.com/ZhongliangGuo/TFT-Copilot) | Lokale ONNX-Bilderkennung, getrennte Unit-/Star-/Item-Modelle, strukturierte Board-Ergebnisse, explizite ungelöste Felder, deterministische Trait-Prüfung, per-Set-Datenpack und Updater | Python/FastAPI statt Android; feste **1920×1080**-Screenshots, eigene Screenshot-Daten/Trainingscode fehlen; nichtkommerziell lizenziert | **Beste Architekturvorlage**, keine Code- oder Gewichtskopie |
| [tft-ai-coach](https://github.com/HamieXD/tft-ai-coach) | Tauri/Rust, Gemini, Overlay und UX für Desktop; MIT-Lizenz | Desktop/Windows, sehr kleines Projekt mit weitgehend unüberprüften Eigenbehauptungen zu Geschwindigkeit und „Vanguard safe“ | Einzelne UX-Ideen; keine Sicherheit/Genauigkeit daraus ableiten |
| [cope-overlay](https://github.com/waxsway/cope-overlay) | OpenCV-Templates, manuelle Kalibrierung, Overlay, SQLite-Metadaten | Windows, alte Set-17-Seeds, keine geeignete Android-Runtime; aggressive Gegner-/Echtzeit-Scouting-Funktionen widersprechen Riot-Richtlinien | **Nur** ROI-Kalibrierungsmethodik als Inspiration |
| [tft-overlay](https://github.com/kristoffeys/tft-overlay) | Architektur-/Policy-Dokumentation, mobiles Bildmaterial via ADB, Phasenplanung | Laut Projektstatus überwiegend Planung und noch kein fertiges Vision-System | Policy-Check und Testplanung, keine direkt nutzbare Vision-Komponente |

Bei Drittcode vor Übernahme Herkunft, Lizenz, Datensatz, Laufzeit, Gerät und
geeignete Evaluationsbilder prüfen; keine fremden Modellgewichte blind in
die APK kopieren.

## 3. Entscheidende Architekturverbesserung

**Zielbild (für separate Vorbereitung / Nachanalyse):**

1. Aktuelles TFT-Set mit offiziellen **deutschen** Namen und Paketversion
   aus einer versionierbaren CommunityDragon-Datenquelle.
2. Offline-Wahrnehmung: HUD-Feld als `{wert, region, confidence, frameId,
   timestamp, source, validated}`; kleine Modelle nur nach repräsentativen,
   rechtlich nutzbaren Trainings-/Testbeispielen.
3. Jede auf Daten prüfbare Eigenschaft wird deterministisch gegengeprüft
   (Champion existiert im Set, Item-Komponenten, Trait-Breakpoints,
   Shop-Cost, Augment-ID). KI darf **nicht** die maßgebliche Faktenquelle sein.
4. Bewertung/Erklärung mit Quellen und erkennbaren Unsicherheiten. Wenn
   OCR unsicher ist, keinen fiktiven sicheren Wert behaupten.
5. Testmetriken auf echten, freigegebenen Screenshots und Offline-Replays:
   precision/recall per HUD-Feld, Confusion Matrix, Fehlalarme,
   CPU/Memory/Latenz/thermische Dauerlast, Kosten pro 30 Minuten.
6. Separate, freiwillige Aufnahme-/Review-Funktion mit manueller Zustimmung,
   lokaler Speicherung und klarer Löschfunktion.

## 4. Recherche der Plattform- und Modellschnittstellen

- Android-Dokumentation: ab Android 14 kann Freigabe von App-Fenstern,
  Rotation oder Fenstergrößenwechsel die Captured-Content-Größe ändern.
  `MediaProjection.Callback.onCapturedContentResize` meldet die tatsächliche
  Größe; **VirtualDisplay UND Surface** müssen entsprechend angepasst werden.
  Auf `onStop` Ressourcen schließen und jede neue Freigabe separat autorisieren.
- [Android-Doku](https://developer.android.com/media/grow/media-projection)
- [CommunityDragon-Asset-Doku](https://github.com/CommunityDragon/Docs/blob/master/assets.md):
  JSON pro Locale; Version/Konsistenz selbst prüfen, keine garantierte API.
- [ONNX Runtime Android](https://onnxruntime.ai/docs/tutorials/mobile/):
  prinzipiell möglich, aber nur mit einem geeigneten Modell, Datensatz,
  Leistungsmessung und akzeptabler APK-Größe. **Jetzt keine neue
  Runtime/50-MB-Modellgewichte ohne Nutzenmessung installieren.**
- API-Modelle geprüft anhand [OpenAI](https://developers.openai.com/api/docs/models)
  (`gpt-6-luna` verfügbar) und [Gemini](https://ai.google.dev/gemini-api/docs/models)
  (`gemini-3.8-flash` verfügbar). Nicht spekulativ ersetzen.

## 5. Riot-Richtlinien: Risikobegrenzung

[Offizielle TFT-Developer-Policy](https://developer.riotgames.com/docs/tft):
dynamische Echtzeitinformationen und Apps mit direkten Spielvorgaben werden
als nicht genehmigt beschrieben, ebenso Gegner-Scouting. **„Read-only screen
capture“ allein begründet keine Policy-Konformität.** Ein produktiver,
Ranked-geeigneter Veröffentlichungspfad erfordert eine gesonderte Prüfung
mit Riot. Erlaubte und technisch nützliche Schwerpunktbereiche:
statische Vorbereitung, Metadaten, Historie, Vor-/Nachanalyse.

## 6. Prioritäten nach Kosten/Nutzen und Risiko

P0: stabile Android-Capture-Geometrie, keine stillen fehlerhaften HUD-Koordinaten
bei Rotation, App-Window-Sharing oder Capture-Ende; dafür 0.9.5-Fix.
P1: eigene HUD-Regions validieren (Level/Gold/Stage), robustes
„unsicher/unresolved“, selbständig getestete Fixture-Suite.
P1: deterministische Datenbasis per TFT-Set und deutsche IDs/Namen mit
exaktem Versionsstempel.
P2: Vorbereitungs- und Nachanalyse-Modus, Datenschutz/Replay-UX,
testbasierte Entscheidungskalibrierung.
P3: On-device ONNX-Unit-/Item-Modelle erst bei geeigneten Bildern und
nach gemessener Präzision/Recall, Akku- und APK-Impact.

## 7. Ehrliche Verifikation

Grüne JUnit-/Lint-/CI-Ergebnisse sichern den **Code-Build** ab, nicht
Game-Recognition oder Richtlinienkonformität. 0.9.5 enthält keine
TFT-spezifischen ONNX-Gewichte, keine proprietären Datensätze und
keine behauptete echte Live-Spielsitzung. Die bestehende signierte
APK-Update-Kette und bisherigen Settings bleiben erhalten.
