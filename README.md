# TFT JARVIS Coach

## JARVIS: Entwicklungsziel und verbindliche Reihenfolge

Das Primärziel ist **eine eigenständige deutsche, local-first JARVIS-Sprach-App** für das Galaxy S24 Ultra. Natürliche wechselseitige Gespräche und echte lokale KI kommen **vor** TFT-Spezialfunktionen. Das vorhandene TFT-Coaching ist ein späterer optionaler Skill. Ein CI-Build bestätigt noch keine funktionierende Geräte-KI oder Live-Konversation.

**[JARVIS-Masterplan und objektive Abnahme](docs/JARVIS-MASTER-SEQUENCE.md)** · [Tracking-Issue #57](https://github.com/sroroxd/TFT-Jarvis-Coach/issues/57)

Aktive Reihenfolge: **Offline-Text-KI → Offline-Freisprechen → Gesprächsunterbrechung/Kontext → lokale Skills → optional TFT → S24-Freigabe**. Aktuelle Experiment-/Testzweige und echte Nachweise stehen im Masterplan; keine automatische Release-Freigabe.

Deutschsprachiger Android-Prototyp mit lokaler Bildschirmaufnahme, HUD-Erkennung,
einem optionalen KI-Auswerter sowie einem schwebenden Bedienfeld.

## APK und Updates

Aktuelle, **einzeln herunterladbare APKs**:
https://github.com/sroroxd/TFT-Jarvis-Coach/releases

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
