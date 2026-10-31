# JARVIS – Android-Assistent (TFT optional/Legacy)

Das Hauptziel ist ein **eigenständiger, möglichst kostenloser persönlicher Android-Assistent** mit lokalen Funktionen und natürlicher deutscher Sprache. Die frühere TFT-Coach-Funktion ist eine separate, bislang nicht freigegebene Entwicklungsrichtung – **nicht** Voraussetzung für JARVIS.

## APK-Downloads und Releases

| Quelle | Zweck | Wichtig |
|---|---|---|
| **[GitHub Releases](https://github.com/sroroxd/TFT-Jarvis-Coach/releases)** | Dauerhaft signierte APKs und eindeutig gekennzeichnete Beta-Vorversionen | Vorversionen sind keine Geräteabnahme |
| **[GitHub Actions](https://github.com/sroroxd/TFT-Jarvis-Coach/actions)** | Automatische Test-Builds mit zeitlich begrenzten ZIP-Artefakten | Debug-Signaturen und App-IDs können abweichen |
| **[Release-Notizen](docs/releases/0.9.21-beta7.md)** | Inhalt, Testschritte und Grenzen der aktuellen Beta | Kein Modell in der APK, kein verifizierter Live-Sprachchat |
| **[Linkregister](docs/GITHUB-ACTIONS-ARTEFAKTE.md)** | Unterschied zwischen Actions-Runs, Artefakt-IDs, ZIP und direkter Release-APK | Kein erfundener Download-Link |

**Aktuelle Entwicklung:** 0.9.20 (native lokale Text-KI als experimenteller Prototyp). Geplante signierte Testveröffentlichung: **0.9.21-beta7**, nach erfolgreicher GitHub-Release-CI. Modellgewichte werden nicht mitgeliefert; eine tatsächliche Offline-Generierung muss auf dem Galaxy S24 Ultra erst belegt werden.

**Status des Freisprechens:** Die ältere Android-Sprachdiensterkennung kann auf dem S24 Ultra den Fehler **Android-Code 10** (zu viele Starts) auslösen. Der kontinuierliche Offline-Sprachkern und zuverlässige Unterbrechbarkeit sind noch nicht abgenommen. Keine Vollfunktionalität behaupten.

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
