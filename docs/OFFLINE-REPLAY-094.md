# JARVIS 0.9.4 – Offline-Szenariotests und Smart-Auto-Fehlererholung

**Ausgangsbasis:** signierte v0.9.3-beta4. Neuer Build: v0.9.4-beta5,
`versionCode` 34, unveränderte `applicationId` und Signatur.

## Prüfziel

Ohne laufende TFT-Partie oder API-Zugang wird die automatische Bildauswahl
über deterministische, synthetische **24 × 14 Helligkeits-Fingerprints** getestet.
Die Fingerprints enthalten keine Screenshots oder privaten Bildinhalte.

`SmartFrameDetectorOfflineTest` reproduziert:
1. Ein stabiler Vor-Match-Bildschirm löst eine Analyse aus.
2. Unveränderter Bildschirm löst auch bei langen Wartezeiten **keine** weiteren
   Cloud-Aufrufe aus.
3. Ein stabiler Übergang vom Vor-Match-Bildschirm zu einem deutlich geänderten
   Spielbild löst eine erneute Analyse aus.
4. Kurzzeitige Animation, die zum vorherigen Bild zurückkehrt, erzeugt keinen
   zusätzlichen Aufruf.
5. Fehlgeschlagene Anfrage mit unverändertem Bild wird mit Abstand
   **höchstens zweimal** automatisch wiederholt. Danach erfolgt eine Pause
   bis zur nächsten substanziellen Bildschirmänderung.
6. Gemini-/OpenAI-Rate-Limit darf einen vorgemerkten Versuch nicht still
   verbrauchen; er wird erst nach Ende der Sperre versucht.
7. Nach erfolgreicher Antwort wird die Retry-Sperre zurückgesetzt.
8. Ungültige Fingerprints werden nicht als analysierter Bildschirm übernommen.

## Behobener technischer Fehler

`SmartFrameDetector.discardPendingAnalysis()` setzte bislang nur einen
allgemeinen Bewegungsmerker. Wenn KI-Aufrufe bei einem unveränderten Bildschirm
fehlschlugen, wurde `VOR_MATCH` in einer nachfolgenden Beobachtung mit
normalen Änderungsgrenzen verglichen. Mangels Bildänderung kam danach kein
weiterer automatischer Versuch. Die neue Fehlererholung unterscheidet
**unveränderte, zuvor fehlgeschlagene Bilder** vom normalen stabilen Zustand
und begrenzt Wiederholungen.

## Grenzen

Die Tests messen ausschließlich **Trigger-Logik**, keine Qualität der
Bilderkennung. Es gibt weiterhin keine quantifizierte Präzision/Recall für
Stage, Gold, HP, Units, Items oder Augments. Es fehlen Replay-Fixtures aus
realen, einvernehmlich bereitgestellten Spielbildern. Kein behaupteter
Praxistest auf dem Samsung Galaxy S24 Ultra, keine Riot-Zulassung.

Beim nächsten echten Gerätetest gezielt kontrollieren: Matchstart,
Planung/Kampf-Wechsel, Augment-Auswahl, API-429/503, Bildverlust,
Doppelanalysen und Wärme-/Akkuverbrauch. Die Diagnoseanzeige (Overlay ⋮)
liefert datensparsame Latenz- und Fehlerzähler.
