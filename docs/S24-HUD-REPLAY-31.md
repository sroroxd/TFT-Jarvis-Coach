# Issue #31 – S24 Ultra: lokale HUD-Replay-Vorbereitung (ohne echte Bilder)

**Status: Vorbereitung, nicht validiert.** Dieses Dokument enthält ein manuelles Erfassungsprotokoll für TFT Mobile auf einem Samsung Galaxy S24 Ultra. Es wurden **keine** echten Screenshots erfasst oder ausgewertet; Precision, Recall, Latenz und Gerätestabilität sind damit **nicht** belegt. Baut auf dem synthetischen Benchmark aus Issue #30 / PR #32 auf, ersetzt ihn nicht.

## Sicherheit und Einwilligung

- Nur eigene TFT-Mobile-Aufnahmen oder ausdrücklich zulässig bereitgestellte Szenen. Aufnahme und lokale Verarbeitung erst nach ausdrücklicher Zustimmung der betroffenen Personen.
- Vor Test und Weitergabe Kontonamen, Chats, Benachrichtigungen und andere personenbezogene Angaben ausblenden oder vollständig entfernen. Keine Cloud-OCR, keine Uploads zu GitHub, keine externen Bilder oder APIs ohne ausdrückliche Freigabe.
- **Nie** Screenshots, private Labels, API-Schlüssel oder Gerätelogs ins öffentliche Repository committen. Lokaler, ignorierter Speicherort: `replay-private/`. Die öffentliche CSV unter `docs/s24-hud-labels.template.csv` ist **nur eine leere Vorlage**.
- TFT-/Riot-Policy sowie `AGENTS.md` bleiben verbindlich. Diese Tests sind Offline-Qualitätsmessungen, keine Freigabe dynamischer Live-Handlungsempfehlungen.

## Aufnahme – reproduzierbare Testfälle

1. Auf dem S24 Ultra eine feste Ausrichtung und Bildschirmauflösung wählen; Displaygröße, TFT-Version/Set, Android-Version, Bildschirmauflösung und App-Version separat **lokal** dokumentieren. Keine relevanten Einstellungen zwischen Fällen verändern, außer dies ist die bewusst getestete Variation.
2. Lokalen Ordner `replay-private/images/` anlegen. Für eigene Bildschirmfotos konsistente, neutrale Dateinamen wie `S24-001.png` verwenden. Nicht im Git-Repository veröffentlichen. Screenshots möglichst mit ausgeschaltetem JARVIS-Overlay aufnehmen; Overlay-Fälle gesondert kennzeichnen.
3. Mindestens diese Situationen **als eigene lokale Fälle** erfassen: Lobby/Vor-Match; Beginn einer neuen Partie; Planung; Kampf; Augment-Auswahl; Shop und 4-Gold-XP-Schaltfläche; Overlay/verdecktes HUD; HP vs. Timer/Gegner-HP; unscharfer/teilweise abgeschnittener Frame; Ergebnis; neue Partie nach Ergebnis; vollständig fehlender Frame/Freigabeabbruch. Für jeden Fall einen eigenen `case_id` wählen. Bei fehlendem Frame existiert bewusst keine Bilddatei.
4. **Vor Sichtung der OCR-Ausgaben** für jedes Bild manuell `phase`, `stage`, eigenes `level`, `gold` und **eigene** `hp` kennzeichnen. Werte ausschließlich eintragen, wenn im Bild tatsächlich lesbar: `READABLE`, `UNREADABLE`, `AMBIGUOUS` oder `ABSENT`; bei allen außer `READABLE` den Wert leer lassen.
5. Die leere Vorlage nach `replay-private/labels.csv` kopieren und dort ausfüllen. `case_id` entspricht dem Bildnamen ohne Erweiterung. Kein automatisches Generieren von Ground-Truth-Labels durch ein KI-Modell. `phase` ist ein **manuelles Label**, keine von `gateForPhase` autonom erkannte Spielphase.
6. Auf freiwilliger, lokaler Testbasis den vollständigen tatsächlichen OCR-/HUD-Pfad mit **demselben Frame** ausführen, Ausgabe je Feld getrennt sichern und mit den manuell festgelegten Labels vergleichen. Der bestehende JUnit-Harness mit synthetischen `Candidate`-Tokens ist **keine** Messung des realen ML-Kit-OCR. Für echtes `observe()` muss die Geräteinstrumentierung/Replay-Integration gesondert implementiert und mit CI überprüft werden.

## Datenformat und Messregeln

`docs/s24-hud-labels.template.csv` hat eine Zeile je Fall und pro Feld zwei Spalten `<feld>_status` / `<feld>_value`. `captured_at_local` ist ein lokaler ISO-8601-Zeitstempel. `consent_confirmed` / `private_data_redacted` müssen vor einer tatsächlichen Auswertung `true` sein. Die CSV enthält **keine** Dateipfade oder persönlichen Daten.

- **Erwartete Ablehnung:** `UNREADABLE`, `AMBIGUOUS` und `ABSENT` → keine als sicher ausgegebene Vorhersage; `READABLE` → manuell bestätigtes Soll-Label.
- **Korrekt:** Ausgabe entspricht lesbarem Soll-Label. **Falsch:** Ausgabe widerspricht dem lesbaren Soll-Label oder behauptet einen Wert für nicht lesbare/abwesende Felder. **Abgelehnt:** keine Ausgabe; bei lesbarem Soll-Label ein False Negative, andernfalls korrekte Ablehnung.
- Falscher nichtleerer Wert bei lesbarem Soll-Label: **FP und FN**. Kennzahlen: `Precision = TP/(TP+FP)`, `Recall = TP/(TP+FN)`. Nenner 0 → `N/A`. Zähler und Nenner immer pro Feld ausweisen; keine gemischte Gesamtquote.
- Eine Phase nur dann als **tatsächlich erkannt** auswerten, wenn sie von einer unabhängigen Erkennung stammt. Der übergebene `lastPhase`-Wert ist **keine Vorhersage**. HP wird aktuell mangels verifizierter eigener HUD-Position nicht erkannt und muss als Ablehnung erfasst werden.
- Werte des synthetischen Benchmarks und echte Screenshot-OCR-Messwerte streng getrennt berichten. Fehlender Frame, ältere Zwischenspeicherwerte, XP-Kaufpreis, HP/Timer-Verwechslung und Match-Reset als eigene Fehlerklassen erfassen.

## Lokaler CSV-Auswerter (implementiert; kein OCR-Export)

- Programm: `tools/hud_replay_report.py` (nur Python-Standardbibliothek); Tests: `tools/test_hud_replay_report.py` mit künstlichen In-Memory-Fällen. Es werden keine Bilder gelesen und keine Netzwerkverbindungen hergestellt.
- Erwartete Vorhersagen: öffentliche, **leere** Schema-Vorlage `docs/s24-hud-predictions.template.csv` mit `case_id,stage,level,gold,hp,phase,phase_source`.
- `phase_source` muss `INDEPENDENT`, `INJECTED` oder `NONE` sein. Bei `INJECTED` (z. B. `lastPhase` aus vorherigem Frame) wird die Phase **niemals** als Erkennung bewertet. Bei `NONE` muss die Phase leer sein. `INDEPENDENT` bezeichnet nur eine behauptete unabhängige Phase; Herkunft ist durch den Nutzer/Instrumentierung zu verifizieren.
- Gleiche `case_id`-Menge in beiden lokalen CSV-Dateien ist erforderlich; fehlende oder doppelte Fälle werden abgelehnt. Fehlende Zustimmung/PII-Bereinigung und widersprüchliche Labels brechen die Auswertung ab; die Standardausgabe enthält **nur aggregierte Zähler**, keine IDs, Werte oder lokalen Dateipfade.
- Die Zahlen in der Ausgabe stammen ausschließlich aus den übergebenen CSV-Dateien. Der Auswerter kann **nicht** prüfen, ob die Vorhersagen wirklich per ML-Kit aus demselben Screenshot stammen; er ist daher ausdrücklich **kein** vollständiger Bild-zu-OCR-Replay und kein eigenständiger Nachweis einer realen Erkennungsquote.

Im Repository-Root lokal ausführen (nicht in GitHub Actions mit privaten Daten):

```bash
python3 -m unittest discover -s tools -p 'test_hud_replay_report.py' -v
mkdir -p replay-private
cp docs/s24-hud-labels.template.csv replay-private/labels.csv
cp docs/s24-hud-predictions.template.csv replay-private/predictions.csv
# Beide Dateien ausschließlich lokal mit echten, manuell bestätigten Fällen
# und separat auf dem S24 Ultra ermittelten OCR-Ausgaben befüllen.
python3 tools/hud_replay_report.py \
  --labels replay-private/labels.csv \
  --predictions replay-private/predictions.csv
```

**Noch zu implementieren:** Ein eigener **lokaler, debug-only** Instrumentierungs-/Replayschritt, der mit ausdrücklicher Zustimmung den echten ML-Kit-`observe()`-Pfad auf die gelabelten Bilder anwendet und die Spalten in `predictions.csv` ausfüllt, ohne Cloud-/GitHub-Upload. Bis dahin sind sämtliche realen OCR-/Gerätemetriken **unbekannt**.

## Abnahme und nächste Arbeit

- Lokale, manuell gelabelte Testfälle mit belegter Einwilligung und bereinigten privaten Daten vorhanden; keinerlei öffentliche Screenshot-Datei.
- Reproduzierbarer **lokaler** Bild-zu-OCR-Lauf einschließlich Originalbild, vorliegendem Screenshotzeitpunkt und Feldvorhersagen; bis dahin **keine echte Messquote**.
- Pro Feld korrekt/falsch/abgelehnt, False Positives, Precision/Recall samt Nennern, schwierige Fehlertypen dokumentiert.
- Samsung-/Android-17-Beschränkungen separat auf dem **echten Gerät** dokumentieren: MediaProjection, Freigabeabbruch, Wiederanlauf, Latenz, Wärme/Akku. Kein CI-Erfolg ersetzt den Gerätetest.
- Änderungen an Erkennungslogik nur in kleinen Folge-PRs mit Regressionsfällen. Kein Auto-Merge, Release, kostenpflichtiger API-Aufruf oder Signaturwechsel.

**Abhängigkeit:** Diese Vorbereitung bleibt ein Draft und darf nicht in `main` integriert werden, bevor PR #32 (Issue #30) geprüft und freigegeben ist. Ohne reale, freiwillig gelieferte Daten können wir nur den Ablauf vorbereiten, keine Erkennungsqualität behaupten.
