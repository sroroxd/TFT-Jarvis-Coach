# JARVIS × Hugging Face — 0.9.15 Testvorschau

## Umfang
Diese Änderung ergänzt in der eigenständigen Android-JARVIS-App (Basis PR #47)
**Hugging Face Inference Providers** als dritten freiwilligen Text-KI-Anbieter.
Der feste HTTPS-Endpunkt ist \`https://router.huggingface.co/v1/chat/completions\`.
Beim Setup kann die HF-Modell-ID gewählt werden (Vorgabe
\`Qwen/Qwen3-32B\`; Verfügbarkeit nicht geprüft). Auch ein optionaler
Provider-Suffix wie \`openai/gpt-oss-120b:groq\` wird akzeptiert.

- HF-Token im bestehenden Android-Keystore mit AES-GCM geschützt.
- Kein Token in GitHub, Logging, Beispieldateien oder Fehlerausgaben.
- Explizite Zustimmung für jeden Anbieter; gesprochene Cloud-Anfragen
  verlangen zusätzlich eine eigene Freisprech-Einwilligung.
- Höchstens vier vorherige Gesprächsrunden im RAM; „KONTEXT LÖSCHEN“
  setzt den Kurzzeitkontext zurück.
- Begrenzung auf maximal zwölf automatische KI-Sprachanfragen pro
  App-Sitzung und vorhandene Abstandsprüfung.
- Keine automatischen Anbieterwechsel, keine Modell-Werkzeugaktionen,
  keine fortlaufende Hintergrund-API-Nutzung. Lokale Befehle offline.
- HTTP-Abbruch und Größenlimits gegen unkontrollierte Antworten.

## Nicht versprochen
Hugging Face liefert **keinen fertigen Jarvis**, kein automatisch
kostenfreies Modell-Hosting und keine Android-SpeechRecognizer-Reparatur.
Der Provider wird für Textinferenz verwendet. Android-Spracherkennung
und TextToSpeech bleiben separat. Das Mikrofonausfall-/Barge-in-Problem
aus PR #47 bleibt ausdrücklich als S24-Ultra-Abnahmekriterium offen.
Kein vollständiger Duplex-Sprachchat, kein On-Device-LLM, kein
Langzeitgedächtnis, kein autonomer Gerätezugriff und keine HF-MCP-Tools
in dieser Version.

**Kosten:** Auch frei lizenzierte Modelle können kostenpflichtige
Inference-Kontingente benötigen. Die App hat kein verbindliches
Euro-Hardlimit. Vor Freigabe auf dem Gerät die Kontingente und
Billing-Einstellungen des persönlichen Hugging-Face-Kontos prüfen.
Es wird ohne Benutzerfreigabe kein externer Anbieter angerufen.

## Reproduzierbarer Gerätetest (Galaxy S24 Ultra)
1. Die isolierte **Debug-Vorschau** \`com.pete.tftjarvis.jarvishf\`
   aus dem erfolgreich abgeschlossenen CI-Lauf installieren. Nicht
   die veröffentlichte Release-App überschreiben.
2. „KI EINRICHTEN“ → „Hugging Face“ → eigenes HF-Token mit der passenden
   Inference-Berechtigung eingeben, Chatmodell-ID wählen und zustimmen.
3. Manuell „KI FRAGEN“: „Antworte genau: Verbindung funktioniert.“
   Textantwort und, sofern Android-TTS funktioniert, Sprachausgabe prüfen.
4. „Was habe ich eben gefragt?“: Kontext sollte berücksichtigt werden.
5. „KONTEXT LÖSCHEN“ und die gleiche Frage wiederholen: keine erfundene
   Erinnerung an den gelöschten Verlauf.
6. Eine absichtlich fehlerhafte Modell-ID (z. B. URL) eingeben:
   Speicherung muss abgelehnt werden, ohne dass eine Anfrage erfolgt.
7. Freisprechen aktivieren und die **gesonderte** Cloud-Einwilligung
   einschalten. Fünf Fragen und drei Unterbrechungen prüfen. Bei Fehler
   Android-Fehlercode dokumentieren, niemals den Token fotografieren.
8. KI VERWALTEN → KI DEAKTIVIEREN, danach lokale Befehle erneut prüfen.
9. App schließen: Mikrofon und laufende KI-Anfragen müssen stoppen.

## Technische Abnahme
- \`JarvisHuggingFaceClientTest\`: Format, gekürzter Verlauf, sichere
  Modell-IDs, Antwortauswertung, vorzeitiger Abbruch und leere Eingabe.
- \`:jarvis-core:test\`, \`testDebugUnitTest\`, \`lintDebug\`,
  \`assembleDebug\` auf exakt demselben Commit.
- Erzeugte APK auf Paket, Signatur, Archivintegrität und Startbarkeit
  prüfen; automatisierte Checks sind **kein Gerätefunktionstest**.
- Fehlermeldungen für 401/403, 402, 429, ungültige Modell-ID und
  Dienstfehler. Keine automatische erneute kostenpflichtige Anfrage.
- Rollback: Die separate Debug-App deinstallieren. PR #47, andere
  Test-APKs und die aktuelle Release-App bleiben unangetastet.

## Referenzen
- https://huggingface.co/docs/inference-providers/index
- https://huggingface.co/docs/inference-providers/en/pricing
- https://huggingface.co/docs/hub/security-tokens
