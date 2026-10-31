# Issue #30 – Offline-HUD-Benchmark: Nachweis und Grenzen

**Stand:** synthetisches, vollständig lokales JUnit-Test-Harness auf PR #32. **Keine echten Gameplay-Screenshots**, keine echte HUD-Erkennungsrate und kein S24-Ultra-Gerätetest. Es werden weder Cloudmodelle noch kostenpflichtige APIs benötigt.

## Was tatsächlich geprüft wird

- `HudPerceptionBenchmarkTest` führt deterministische, von Testautoren vorgegebene OCR-Token mit normierten Bildschirmpositionen durch `LocalHudPerception.parseCandidates` und `gateForPhase`.
- Die zwölf synthetischen Fälle enthalten Vor-Match, Ergebnis, Matchbeginn, Planung, Augmentierung, Kampf, spätes Spiel, irreführende Zeichen, unlesbare Felder, konkurrierende Tokens und einen HP-Ground-Truth-Wert.
- Pro Fall und Feld werden **Ground Truth**, **erwartete Ablehnung**, **tatsächlich ausgegebener Wert** und **Urteil** ausgegeben: `KORREKT`, `FALSCH_...` oder `ABGELEHNT_...`.
- Ein falscher, nicht leerer Wert gegenüber vorhandenem Label zählt **1 FP und 1 FN**. Null-Ausgabe mit vorhandenem Label zählt FN; Null-Ausgabe ohne lesbares Label ist eine korrekte Ablehnung.
- Das Protokoll weist TP, FP, FN und TN pro Feld sowie Precision = TP/(TP+FP) und Recall = TP/(TP+FN) **mit Zähler und Nenner** aus. Ein Nenner von 0 ergibt `N/A`, **nicht 100 %**.
- Die Spalte „Spielphase“ wird als manuelles/injiziertes **vorheriges Phasenlabel** gezeigt, nicht als unabhängig erkannte Phase. Deshalb **keine Precision/Recall für Spielphase**.
- Eigene HP werden derzeit absichtlich **immer abgelehnt**, da eine verifizierte HUD-Position fehlt; der synthetische HP-Fall zeigt den entsprechenden FN und Precision `N/A`. **Keine HP-Erkennungsquote behaupten.**
- Mehrdeutige Stage-, Level- oder Gold-Kandidaten am erwarteten Ort werden abgelehnt statt nach der OCR-Positionsheuristik einen möglicherweise falschen Wert auszuwählen. Das kann mehr Ablehnungen erzeugen; der Effekt auf echte Bilder ist unbekannt.

Die Kennzahlen der Suite sind **ausschließlich interne Tests der Kandidatenlogik**; sie messen weder OCR-Qualität noch Precision/Recall in TFT-Partien. Die absoluten synthetischen Werte dürfen nicht als reale Trefferquote kommuniziert werden.

## Reproduzieren (Java 17, Gradle 8.9, Android SDK 35)

```bash
gradle --no-daemon testDebugUnitTest
gradle --no-daemon lintDebug
gradle --no-daemon assembleDebug
```

Bei lokal fehlendem Android SDK gilt nur die Android-CI als möglicher Buildnachweis. Im CI-Lauf auf dem **aktuellen PR-HEAD** die drei Schritte getrennt prüfen und das konkrete Datum/Commit dokumentieren. Grüne Tests bestätigen **nur Build, Regeln und die deterministische Suite**. Ein alter CI-Lauf auf einem früheren Commit reicht nicht als Freigabe.

## Späterer Einsatz echter, manuell gelabelter Aufnahmen

1. Nur **eigene** TFT-Mobile-Aufnahmen oder ausdrücklich genehmigtes Material verwenden. Niemals fremde Screenshots aus dem Internet kopieren.
2. Jede Aufnahme lokal kennzeichnen: `case_id`, Zeit/Set, Gerätemodus, `phase`, `stage`, `level`, `gold`, `hp`; je Feld den Status `lesbar`, `unlesbar` oder `mehrdeutig` sowie das **manuell geprüfte** Ground-Truth-Label erfassen. Nichtlesbare Felder als `null` auswerten und erwartete Ablehnung dokumentieren. Keine KI-Schätzung als Wahrheit übernehmen.
3. Bilddateien lokal **außerhalb des Git-Repositorys** speichern; Namen, Nutzerkonto, Chat, Benachrichtigungen und sonstige private Informationen vor Verarbeitung entfernen. Einwilligung dokumentieren und Aufnahmen nicht ohne ausdrückliche Zustimmung hochladen. Keine privaten Bildpfade, Screenshotdaten, Secrets oder API-Schlüssel in GitHub-Issues und Logs.
4. Zur tatsächlichen Qualitätsmessung muss ein **separater lokaler Replay-Lauf mit echten Bildern durch den vollständigen OCR-Pfad `observe()`** erfolgen (oder ein gleichwertiger Geräte-/Instrumentierungstest). Die synthetische `parseCandidates`-Suite ist dafür kein Ersatz. Echte Vorhersagen mit den manuellen Labels je Feld und je Phase vergleichen; Ablehnungen, FP, FN und Nenner protokollieren.
5. Testfälle gezielt streuen: Lobby, neuer Match, Planung, Kampf, Augment, Shop, XP-Kaufbutton, HUD-Verdeckung, Bildunschärfe, Ergebnis, Partiewechsel. Eigene HP gegen Timer und Gegner-HP disambiguieren. Separate Geräteprüfung für Android-17-MediaProjection, Erkennungslatenz, Wärme, Akku und Freigabeabbrüche.

**Nächster messbarer Schritt:** Issue #31 mit explizit freiwilligen, lokal gespeicherten und manuell gelabelten S24-Ultra-Bildern. Bis dahin keine reale Erkennungsquote, keine Gerätefreigabe und keine APK-Veröffentlichung.

## Schutzrahmen und Rückfall

- Keine neuen Dependencies, keine Änderungen an `applicationId`, Release-Signing, Secrets, bezahlten APIs, Release-Workflows oder automatischem Merge.
- Die konservative Konflikt-Ablehnung kann zu zusätzlichen False Negatives führen. Rückfall: die zwei kleinen Perception-Änderungen revertieren, falls echte und einvernehmlich gelabelte Replays eine Qualitätsverschlechterung nachweisen.
- Projekt- und Riot-Policy aus `AGENTS.md` beachten: das Testframework legitimiert keine dynamische Ingame-Handlungsberatung.
