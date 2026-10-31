# JARVIS – Master-Reihenfolge und Modulstruktur (31.10.2026)

> **Verbindlicher Entwicklungsfahrplan für die künftige Arbeit**, sobald dieser Dokumentations-PR freigegeben ist. Tracking: [Issue #57](https://github.com/sroroxd/TFT-Jarvis-Coach/issues/57). Produktziel ist ein eigenständiger deutscher Android-JARVIS auf Samsung Galaxy S24 Ultra, mit natürlichem wechselseitigem Sprachdialog. **TFT ist optionaler Skill, nicht das Fundament.**

## Produktdefinition und harte Grenzen

Eine eigene JARVIS-App soll ohne parallel geöffnetes ChatGPT oder TFT laufen, sich ohne Wakeword **nach explizitem Einschalten des Freisprechmodus** natürlich ansprechen lassen, gesprochene Antworten liefern, Folgefragen verstehen und sich unterbrechen lassen. **Keine automatische API-Abrechnung.** Lokal ausführbare Grundfunktionen und lokales Modell haben Vorrang. Ein optionaler Cloud-KI-Provider wird erst nach transparenter Nutzerentscheidung über Netz-/Kostenrisiko und separate Freigabe eingeschaltet; eine ChatGPT-Plus-Mitgliedschaft ist **kein** kostenloser OpenAI-API-Schlüssel.

Nicht behaupten, dass ein 0,5B-Modell die Qualität großer Cloudmodelle erreicht; Verständlichkeit, Latenz, Qualität und Gerätespeicher sind messbare Abnahmekriterien. Keine versteckte Hintergrundaufnahme. Gerätefunktionen benötigen gezielte Berechtigungen, riskante Aktionen eine Bestätigung. Daten und Modelle bleiben privat; keine ungefragten Downloads.

## Zielarchitektur – getrennte Verantwortlichkeiten

```text
JARVIS Android Shell (Activity, UI, Berechtigungen, Lifecycle)
  ├─ Input: Texteingabe / kontinuierliches lokales ASR + VAD (nach Opt-in)
  ├─ Dialog-Orchestrator: Zustände, Kontext, Abbruch-ID, Echo/Barge-in
  │     ├─ jarvis-core: lokaler deterministischer Capability-Router
  │     ├─ Offline LLM: GGUF-Speicher → gepinnte llama.cpp/JNI-Runtime
  │     ├─ optionaler KI-Provider: ausdrücklich aktivieren, Kosten-Gate
  │     └─ Skills: lokale Aktionen → später opt-in Bildschirm/TFT
  └─ Output: Text + lokale TTS, Stumm, Unterbrechung, Fehlerdiagnose
         │
         └─ Gerätelabor: CI/CodeQL + S24-Tests + lokale Metriken
```

**Grenzen, die im Code erzwungen werden sollen:**

- `jarvis-core` bleibt Android-, Modellanbieter-, Netzwerk- und TFT-unabhängig. Kein direkter Aufruf von ScreenCapture, MatchState oder TFT-spezifischer Logik.
- Android-Shell startet ohne TFT, MediaProjection oder API-Schlüssel; optionaler TFT-Skill darf den Hauptdialog nie blockieren.
- Der Dialogmanager besitzt **eine einzige Quelle für den Gesprächszustand**: `IDLE → LISTENING → THINKING → SPEAKING`, mit `CANCELLING`, `ERROR`, `STOPPED`. Keine parallelen, unkontrollierten ASR-/TTS-/Modellstarts.
- Explizite Nutzeraktion startet Freisprechen. Beim Hintergrundwechsel werden Mikrofon, Sprecher und Modelljob entsprechend Nutzer-/Berechtigungseinstellung sicher gestoppt. Unvollständige/veraltete Antworten dürfen nicht nach einem Abbruch gesprochen werden.
- Lokale Befehle bleiben offline deterministisch; für offene Fragen benötigt JARVIS eine **wirklich laufende** lokale Modellinferenz oder einen bewusst gewählten Provider. Keine „KI eingerichtet“-Erfolgsmeldung allein nach Speichern eines Tokens.
- Nutzer kontrolliert Gesprächskontext, sensible Erinnerung und Logs; kein automatischer Import von ChatGPT-Erinnerungen und keine nicht belegte Synchronisation.

## Verifizierter Ausgangspunkt und offene Lücken

| Teil | Quellstand in `main` | Noch **nicht** bestätigt |
| --- | --- | --- |
| Kern | Eigenständiges Java-Modul `jarvis-core`, Capability-Router und Unit-Tests vorhanden | Allgemeine Android-Shell vollständig von TFT entkoppelt |
| Lokale KI | `JarvisGgufModelStore`, `JarvisOfflineEngine`, llama.cpp JNI/arm64, `JarvisOfflineChatActivity` und Modellimport-Labor vorhanden; ältere [PR #51](https://github.com/sroroxd/TFT-Jarvis-Coach/pull/51) gemergt | Erfolgreich geladene lizenzierte GGUF **und echte sinnvolle Offline-Antworten auf dem S24** |
| Sprache | Android-`SpeechRecognizer` mit Startbegrenzung/Code-10-Cooldown aus [PR #49](https://github.com/sroroxd/TFT-Jarvis-Coach/pull/49); TTS und experimentelle Barge-in-Logik | Dauerhafte Offline-Streaming-ASR, akustische Echo-Unterdrückung, verlässlich unterbrechbarer Live-Chat |
| TFT | HUD-Logik und getrennte [PR #32](https://github.com/sroroxd/TFT-Jarvis-Coach/pull/32)/[Draft #56](https://github.com/sroroxd/TFT-Jarvis-Coach/pull/56) | Echte HUD-/OCR-Trefferquote auf S24; HP-/Phasen-Erkennung |
| Release | Android-CI, CodeQL und Test-APK-Prüfpfad vorhanden | Neuer, in allen Funktionen auf S24 abgenommener Produktions-Release |

**Regel:** Ein erfolgreicher GitHub-Build ist weder Gerätebeleg noch LLM-Inferenznachweis. Ältere Dokumente `docs/JARVIS-CORE-ARCHITECTURE.md`, `docs/LOCAL-FIRST-ROADMAP.md` und PRs #44/#45/#46 bleiben als Historie/Prototypen erhalten. Dieser Plan konsolidiert ihre **Reihenfolge**, überschreibt keine historischen Ergebnisse.

## Umsetzung – zwingende Reihenfolge

### G0 – stabile Basis (Bestandsaufnahme, Architektur-Vertrag)

Bestehenden `jarvis-core` und Android-App-Grenze als verbindlich ansehen; noch keine Refaktorierung des produktiven Paketnamens. Für Tests weiter separate Debug-App-ID, bestehende Release-Signatur und `applicationId=com.pete.tftjarvis` nicht verändern. Den Core isoliert testen; Android Unit/Lint/APK sowie CodeQL auf jedem Integrations-PR prüfen.

**Gate:** Start ohne TFT-/Bildschirmfreigabe; Basis-Textbefehle offline, reproduzierbare CI. **Status:** teilweise bereits umgesetzt; Haupt-App-Shell noch nicht vollständig getrennt.

### G1 – echte lokale **Text-KI** (**JETZT PRIORITÄT 1**; [Issue #58](https://github.com/sroroxd/TFT-Jarvis-Coach/issues/58))

Vor weiteren Voice-Funktionen **ein** kleines, lizenziertes und SHA-verifiziertes GGUF im bestehenden Offline-Labor laden. Gepinnten nativen ARM64-Build, realen Token-Output, korrekten Qwen-Chat-Template-/Stop-Token-Pfad sowie Abbruch/Unload prüfen. Kein ungefragter Download; Nutzer importiert die Modell-Datei selbst. Bei qualitativ ungenügender 0,5B-Baseline kompatible größere Modelle nur nach RAM-/Latenztest bewerten.

**Gate:** Auf S24 Ultra im Flugmodus **6 echte deutsche Fragen, davon 5 zusammenhängende Folgefragen**, nachvollziehbare Antworten, Abbruch und erneutes Laden; RAM, Ladezeit und Antwortlatenz dokumentieren. Bei Fehlern kein KI-Erfolgssignal. **Ohne G1 keine Behauptung funktionierender kostenloser JARVIS-KI.**

### G2 – kontinuierliche lokale **Spracheingabe**

`SpeechRecognizer` ist One-Shot und die 8-Sekunden-Sperre aus 0.9.16 nur ein Schutz gegen Code 10, **kein dauerhafter Live-Sprachmodus**. Eigenen begrenzten ASR/VAD-Prototyp nach Lizenz-/Größenprüfung als Adapter implementieren; nicht parallel zwei Mikrofon-Pipelines starten. Freisprechen aktiv nur bei explizitem Opt-in, mit sichtbarem Zustand und sofortigem Mikrofon-Stopp.

**Gate:** 10 Minuten gesprochene Testeingaben im sichtbaren Vordergrund auf dem S24, dokumentierte Erkennungsfehler, **kein Code 10**, kein unberechtigter Mikrofonzugriff nach Stop/Hintergrund. Push-to-talk/Text bleibt als sichere Rückfallebene.

### G3 – **echte Dialogschleife und natürlicher Klang**

Lokales ASR → Dialog-Orchestrator → laufende lokale Text-KI → TTS als **einen kontrollierten Gesprächszug** verdrahten. Gesprächskontext, Sprecher-/Mikrofon-Turn-State, Echo/Barge-in, Cancel, Stumm, Fehler, Hintergrund und Antwortalter testen. Stimme anhand Geräteeignung und Nutzungsrechten wählen; nicht auf eine einzelne Engine festlegen.

**Gate:** 5 Kontext-Folgefragen in einem Gespräch, mindestens 3 erfolgreiche Sprach-Unterbrechungen während TTS, kein Selbstgespräch durch Lautsprecher-Echo, keine veraltete Antwort nach Stop. Latenz/RAM/Batterie messen. Frühere experimentelle Voice-PRs #44/#45/#46 **nicht blind mergen**, sondern nur geprüfte Teile mit Regressionstests übernehmen.

### G4 – **zuverlässige lokale Fähigkeiten**

Kleine, erlaubnisbasierte Skills über `jarvis-core`: Status, Stumm, Abbrechen und weitere lokal unterstützte Android-Aktionen, später Timer/Notizen nach gezielter Berechtigung. Aktionen müssen tatsächliche Ergebnisse/Fehler und Cancel liefern; keine „erledigt“-Meldung ohne Tool-Erfolg. Freiwillige persistente Erinnerungen getrennt vom temporären Dialog, überprüf-/löschbar.

**Gate:** Mindestens zwei echte, explizit autorisierte lokale Aktionen auf Gerät nachweisbar; Permissions, Stopp und Fehlerpfade überprüft; keine ungefragten kostenpflichtigen Dienste.

### G5 – **optionales TFT- und Bildschirmmodul**

Zuerst das Offline-HUD-Testframework ([Issue #30/PR #32](https://github.com/sroroxd/TFT-Jarvis-Coach/pull/32)) fachlich abnehmen; auf dessen Basis den lokalen, datenschutzsicheren Screenshot-/OCR-Nachweis ([Issue #31/Draft #56](https://github.com/sroroxd/TFT-Jarvis-Coach/pull/56)) erstellen. **Nur** mit eigenen, freiwillig bereitgestellten, manuell gelabelten S24-Frames; keine Cloud, keine öffentliche Bilddatei. Sichtprotokoll: Ohne Frame oder bei Mehrdeutigkeit keine erfundenen Werte. Riot-Policy und Freigaben beachten; keine verbotenen dynamischen Ingame-Handlungsvorgaben.

**Gate:** Feldgenauigkeit mit Zählern/Nennern aus echten Aufnahmen statt synthetischer Test-Doubles, Freigabeabbruch/Frame-Alter/Geräteperformance überprüft. **Keine Abhängigkeit von G5 für allgemeinen JARVIS.**

### G6 – **Geräteabnahme und stabiler Release**

Zu jeder Stufe ein kleines reviewbares PR mit Ist-/Soll-Verhalten, automatisierten Regressionen, aktueller CI/CodeQL und reproduzierbarem S24-Test. Abschlussprüfung: Flugmodus-Konversation, Freisprechen, Unterbrechen, App-Wechsel, Berechtigungen, TTS, längere Nutzung, RAM/Wärme/Akku und Sicherheitskontrollen. Signatur- und Versionsregeln aus `AGENTS.md` strikt einhalten; kein Auto-Merge und keine automatische APK-Veröffentlichung.

**Gate:** Abgenommene S24-Ergebnisse und explizite Release-Freigabe, nicht bloß „BUILD SUCCESS“.

## Sequenzverwaltung: Keine unkontrollierten Parallelentwicklungen

1. **Aktiv:** G1 Offline-KI mit kleinstem klaren Gerätetest. G0 läuft als technische Kontrolle mit, nicht als eigenes Dauer-Refactoring.
2. **Wartend:** G2/G3 können untersucht und als kleine unabhängige PRs vorbereitet, aber erst nach nachgewiesener G1-Textinferenz gemeinsam integriert werden.
3. **Wartend:** G4 nach funktionierender Dialogschleife; sichere Basisbefehle bleiben natürlich bereits nutzbar.
4. **Getrennt geparkt:** TFT #18/#30/#31 und zugehörige PRs #32/#56; sie dürfen parallel als isolierte Offline-Tests bestehen, aber nicht die Voice-Priorität verdrängen.
5. **Jede Stufe:** ein Issue/ein kleiner PR, Code-/Lizenz-/Kostenprüfung, Rollback und klare technische Nachweise. Keine mehreren voneinander abweichenden „Master-Roadmaps“.

**Nächster konkreter Coding-Auftrag:** Auf dem **aktuellen main** und ohne Veröffentlichung die bestehende `JarvisOfflineChatActivity` + `JarvisOfflineEngine`/JNI-Runtime auf Build-/Modelllade-/Generierungsfehler prüfen, einen reproduzierbaren textbasierten S24-Flugmodus-Test und Diagnostik (ohne Prompt-/Modelldaten in Logs) bereitstellen und erst nach wirklichen Geräteergebnissen G1 als erledigt markieren.
