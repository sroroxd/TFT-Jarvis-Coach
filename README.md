# TFT JARVIS Coach

Deutschsprachiger Android-Prototyp mit lokaler Bildschirmaufnahme, HUD-Erkennung,
einem optionalen KI-Auswerter sowie einem schwebenden Bedienfeld.

## APK und Updates

**Veröffentlichte, direkt installierbare APKs:**
https://github.com/sroroxd/TFT-Jarvis-Coach/releases

**CI-Testdateien und fehlgeschlagene Downloads** sind getrennt erfasst:
[GitHub Actions, Artefakte und Linkregister](docs/GITHUB-ACTIONS-ARTEFAKTE.md).
Ein erfolgreicher APK-Build bedeutet nicht automatisch, dass ein GitHub Release verfügbar ist.

Ab `v0.9.0-beta1` werden Release-APKs mit derselben dauerhaften Signatur
veröffentlicht. `v0.9.3-beta4` erhöht den Android-`versionCode` auf 33.
Ein Update über eine ältere **signierte 0.9.x-Release-APK** ist dadurch möglich,
ohne die App zuvor deinstallieren zu müssen. Ein Wechsel von alten Debug-APKs
(z. B. 0.8.2) kann einmalig eine Neuinstallation erfordern.

Die Signatur-Einrichtung ist bereits erfolgt. Der dauerhafte Schlüssel liegt
nur **passwortverschlüsselt** im Repository; der echte Keystore wird nicht
als Klartext veröffentlicht. Hinweise: [APK-Updates](docs/APK-UPDATES.md).

## Aktueller Entwicklungsstand

[Technischer Audit und offene Punkte 0.9.3](docs/AUDIT-093.md).

Mit 0.9.3 hinzugekommen: S24-Ultra-kalibrierte eigene Level-/Gold-Regionen,
vorsichtigere Vertrauensbewertung, gespeichertes Stumm, Statusanzeige, neue
Partie, einzelne wartende manuelle Analyse, verbesserter Timeout-/Reset-Schutz
und vorhersehbare Behandlung beschädigter Gemini-Antworten.

**Grenzen:** Der erfolgreiche Build und simulierte Regressionstests ersetzen
keine echten TFT-Aufnahmen auf dem Galaxy S24 Ultra. Lokale HP-Erkennung,
ROI-Genauigkeit und Phase-Transitionen müssen erst empirisch validiert werden.

**Wichtiger Richtlinienhinweis:** Laut
[Riots offizieller TFT-Developer-Policy](https://developer.riotgames.com/docs/tft)
sind Apps mit dynamischen Echtzeit-Informationen oder direkter Vorgabe von
Spielerentscheidungen nicht genehmigte Anwendungsfälle. Ein Android-Build
oder die GitHub-Veröffentlichung bedeuten keine Riot-Freigabe. Besonders
geeignet sind statische Vorbereitung und nachträgliche Spielanalyse.
