# JARVIS – verbindliche Arbeitsregeln für Code-Assistenten

## Projektziel und Scope
Native deutsche TFT-JARVIS-Android-App für Samsung Galaxy S24 Ultra.
Aktuelle Quellbasis steht auf `main`; Architektur und offene Aufgaben:
`docs/AUDIT-093.md`, `docs/OFFLINE-REPLAY-094.md`,
`docs/DEEP-RESEARCH-095.md`, GitHub Issue #18.

**Wichtig:** Eine erfolgreiche CI-Prüfung belegt nur Build- und
Regressionseigenschaften. Sie ist kein Nachweis für präzise
TFT-Ingame-Erkennung, Android-17-Langzeitstabilität oder Riot-Freigabe.
Riot verbietet nach seiner aktuellen TFT-Policy bestimmte dynamische
Echtzeitinformationen und direkte Handlungsvorgaben. Priorisiert
zugelassene Vorbereitung/Review, Datenvalidierung und Offline-Tests.

## Agentenrollen (falls ein KI-Coding-Agent verbunden wird)
- **Bugjäger:** Reproduzierbare Defekte finden, kleinste Regressionstests
  vor einer Korrektur schreiben; Android Lifecycle, MediaProjection,
  Speicher, OCR, Match-Reset, falsche Fakten prüfen.
- **Perception/Test:** Offline-Fixtures mit gekennzeichneter Herkunft;
  misst precision/recall und falsch positive HUD-/Phase-Ausgaben.
  Keine Erfolgsquote aus synthetischen Bildern auf echte Partien übertragen.
- **Fakten/Set-Daten:** deutsche Namen/IDs und TFT-Version nachhalten;
  Champion/Items/Traits deterministisch prüfen; keine erfundenen Fakten.
- **Reviewer:** Risiko, Datensicherheit, Lizenz, Signing, API-Kosten,
  Android-Lint und mögliche Regressionsfehler vor Merge prüfen.

## Strikte Schutzregeln
1. **Niemals automatisch nach main mergen**, keine Release-Tags erstellen,
   keine APKs ungeprüft veröffentlichen. Vorschläge als Pull Requests.
2. Release-`applicationId` bleibt `com.pete.tftjarvis`. Nach Freigabe:
   monoton steigender `versionCode`; gleicher verschlüsselter,
   dauerhaft hinterlegter Signaturschlüssel. Keinen Keystore erzeugen
   oder Release-Signing-Workflow überschreiben.
3. Keine Secrets, Screenshots, Prompts, Access-Tokens oder private
   Modelldaten in GitHub Issues, Logs und Beispiel-Dateien schreiben.
4. Kein Code/Modellgewicht aus Drittprojekten ohne explizite
   Lizenzprüfung übernehmen. Projektkonzepte dürfen inspirieren.
5. Für jede Änderung: eindeutige Fehlerhypothese, reproduzierbarer
   Test, gewünschtes und tatsächliches Verhalten, CI-Beleg,
   Risiken und Rückfallstrategie.
6. **Ohne Nutzerfreigabe keine kostenpflichtigen Modellaufrufe.**
   Budgetloser Offline-Modus und GitHub Free zuerst.
7. Neue Abhängigkeiten nur mit plausibel gemessenem Nutzen bezüglich
   APK-Größe, Batterie, Startzeit, Speicher und Wartung.
8. Unverifizierte Spieldaten als *unbestätigt* kennzeichnen;
   nie KI-Konfidenz mit gemessener Erkennungsqualität gleichsetzen.

## Prüfungen
Auf jedem PR laufen bereits Android-Unit-Tests, Lint, Debug-Build
und signierter CI-Testbuild (separater Wegwerf-Schlüssel).
Zusätzlich: nächtliche lokale Tests, wöchentlicher CodeQL-Scan
und monatliche Dependabot-PRs. Das kostenlose Prüfnetz führt
**keine selbstständigen KI-Codeänderungen** aus.

Gradle-Aufrufe (lokal mit Java 17, Gradle 8.9, Android SDK 35):
```bash
gradle --no-daemon testDebugUnitTest
gradle --no-daemon lintDebug
gradle --no-daemon assembleDebug
```

Bei Fehlern keine grünen Ergebnisse behaupten. In Pull Requests
exakt benennen, welche Befunde technisch verifiziert und welche auf
dem S24 Ultra noch ungetestet sind.
