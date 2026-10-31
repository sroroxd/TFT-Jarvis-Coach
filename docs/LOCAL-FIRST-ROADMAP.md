# JARVIS LOCAL FIRST – Plan und erster Baustein

Ziel: ein natürlicher deutschsprachiger Assistent auf dem Galaxy S24 Ultra,
ohne aktive ChatGPT-App, API-Schlüssel, Cloudkontingente und nutzungsabhängige Kosten.

Priorität:
1. Sichere lokale Modellverwaltung (dieser Entwicklungszweig):
   GGUF-Version/Header prüfen, Importe auf 3 GiB begrenzen, abgebrochene
   Downloads/Importe verwerfen, erst nach Prüfung atomar veröffentlichen.
2. Echte ARM64-On-Device-Inferenz: eine geprüfte, versionierte Runtime
   auf Basis von llama.cpp oder gleichwertig, zusammen mit einem
   kleinen quantisierten Modell nach Prüfung von Lizenz und Laufzeit.
   Danach Flugmodus-Texttest mit mindestens fünf Kontext-Folgefragen.
3. Deutsche kontinuierliche Offline-Spracherkennung mit VAD statt
   Android-SpeechRecognizer-Neustart, die den Code-10-Fehler auslösen.
4. Sichere Android-Aktionen, App-Lebenszyklus, Unterbrechung der Sprache,
   Akustik/Echo-Tests und Ressourcenverbrauch (RAM/Akku/Thermik).
5. Geräteabnahme: 10 Minuten deutsches Gespräch, fünf Folgefragen,
   Offlinebetrieb, kein ungewolltes Mikrofonsignal im Hintergrund.

Dieser Commit ist NUR das Backend für den sicheren GGUF-Import und
Unit-Tests. Es existiert damit noch KEINE lokale Textinferenz und
KEINE neue lauffähige Offline-KI-APK. Die Importfunktion bleibt in der
Benutzeroberfläche verborgen, bis die echte Inferenzruntime verfügbar
und auf einem kompatiblen Gerät geprüft ist. Bestehende Cloud-Anbieter
bleiben freiwillig und unverändert.

Achtung: Ein gültiger GGUF-Header beweist nicht, dass ein ganzes
Modell kompatibel, sicher oder vollständig ist; die native Runtime
muss es vor der Inferenz selbst prüfen. Keine Modell-Downloads ohne
Einwilligung; keine API-/Billing-Automatik.

Technische Referenz: https://github.com/ggml-org/llama.cpp/tree/master/examples/llama.android
