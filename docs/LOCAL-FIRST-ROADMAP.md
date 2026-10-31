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

## Import-Härtung (31.10.2026)
Die Importlogik weist jetzt GGUF-Dateien unter 1 MiB zurück (einschließlich zuvor akzeptierter 24-Byte-Scheinmodelle), berechnet beim Import einen SHA-256-Fingerabdruck und gibt Dateigröße/Hash für spätere nachvollziehbare Modellidentifikation zurück. JUnit-Tests prüfen diese Fälle und dass fehlgeschlagene Importe bestehende Modelle erhalten. **Achtung:** Auch eine 1-MiB-Datei mit gültigem Header kann gefälscht sein. Der Import ist keine GGUF-Vollvalidierung und kein Lizenznachweis. Vor dem Freischalten der Nutzeroberfläche sind vollständige native Modellprüfung und reale Inferenz zwingend.

### Runtime-Gate (noch offen)
Eine installierbare arm64-v8a-Runtime mit reproduzierbar gepinntem llama.cpp-Quellstand, verifizierter Upstream-Lizenz, Android-NDK/CMake-Toolchain, überprüfter Modell-Lizenz und Modell-SHA-256 fehlt weiterhin. Ohne diese Komponenten darf weder die importierte Datei als lauffähiges LLM gelten noch eine neue Offline-KI-APK beworben werden. Nächster Integrationsschritt: isolierter JNI-Adapter mit nativer Token-Generierung, Modell-Laden/Entladen und Interrupt; anschließend CI-Build für arm64-v8a, Offline-Texttest und fünf Folgefragen auf S24 Ultra. Kein automatischer Modelldownload.


## Native ARM64-Prototyp – 31.10.2026 (noch nicht auf S24 nachgewiesen)

- Native JNI-Brücke für llama.cpp (CPU, arm64-v8a) und Gradle externalNativeBuild/CMake eingebaut. Upstream-Tag **b6000**, [Upstream-Quelle](https://github.com/ggml-org/llama.cpp/tree/b6000), [MIT-Lizenz](https://github.com/ggml-org/llama.cpp/blob/b6000/LICENSE). Der Build bezieht den **Quellcode nur beim Entwickler-/CI-Build**, nicht bei App-Nutzung; CMake/Git benötigen während des Builds Netzwerk. Für vollständige Reproduzierbarkeit muss nach CI-Erfolg zusätzlich der exakte Upstream-Commit-SHA statt des Tags festgehalten werden. Keine API, keine Modell-Downloads durch die App.
- Neue explizite Testoberfläche OFFLINE-KI TESTLABOR: Android-Dateiauswahl, privater GGUF-Import, SHA-256 vor atomarer Veröffentlichung, native Modellprüfung, Textgenerierung und bis zu fünf gespeicherte Folgefragen im flüchtigen Kontext, Stop und Löschen. **Experimentell: kein Nachweis eines erfolgreichen nativen Builds, kein Gerätetest und keine echte Offline-Textantwort.**
- Zunächst **nur ein geprüftes Modell** zugelassen, um falsche/inkompatible Gewichte auszuschließen: [Qwen/Qwen2.5-0.5B-Instruct-GGUF](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF), [Q4_K_M-Datei](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/blob/main/qwen2.5-0.5b-instruct-q4_k_m.gguf), ca. **491 MB**, **Apache-2.0**, SHA-256 **74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db** (Angaben von offizieller Modellseite geprüft am 31.10.2026). Nur der Nutzer darf die Datei selbst beschaffen und per Android-Dokumentauswahl importieren; JARVIS lädt nichts automatisch herunter. Die Lizenz- und SHA-Angaben ersetzen keinen Laufzeitnachweis.
- CPU-only als nachvollziehbare Baseline: 4 Threads, 2048 Kontext, maximal 256 neue Tokens. Der native Code begrenzt Prompt und Kontext, akzeptiert Interrupt, entlädt Speicher. Der Chat nutzt vorläufig ein **einfaches rollenmarkiertes Promptformat**, noch nicht das modellnative Qwen-Chat-Template. Vor Freigabe auf das Qwen-Template umstellen und fünf echte Folgefragen samt deutschen Antworten dokumentieren.
- **Risiken:** Ein Build kann wegen Toolchain, llama.cpp-API oder fehlendem Git-Zugang scheitern; native Modellladung kann auf dem S24 RAM/Thermik belasten. Keine automatische Wiederaufnahme nach Hintergrundwechsel. Offline-ASR/VAD, TTS-Unterbrechung, Geräteaktionen, Batteriemessung noch nicht umgesetzt.
- Freigaberegel: erst CI/CodeQL erfolgreich, dann Debug-APK-Binärprüfung, dann expliziter S24-Flugmodus-Test mit sechs Fragen und fünf Kontext-Folgefragen; vorher nicht als funktionierende KI kennzeichnen, nicht mergen.
