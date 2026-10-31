# JARVIS 0.9.16 – Befund aus der S24-Bildschirmaufnahme (31.10.2026)

## Reproduktion und Befund
Die vom Nutzer bereitgestellte ca. 3:15-minütige Aufnahme der
Hugging-Face-Test-APK 0.9.15 zeigt wiederholt:
- „Freisprechen gestoppt: Zu viele Spracherkennungsstarts in kurzer Zeit
  (Android-Code 10)“. Das ist `SpeechRecognizer.ERROR_TOO_MANY_REQUESTS`,
  **kein nachgewiesener Mikrofon-Berechtigungsfehler**.
- In der HF-Konfiguration wurde zeitweise ein **leeres** Tokenfeld
  bestätigt. Die bisherige pauschale Meldung „Schlüssel konnte nicht
  sicher gespeichert werden“ unterschied diesen Eingabefehler nicht
  von einem wirklichen Android-Keystore-Fehler.
- „HUGGING FACE EINGERICHTET“ heißt nur: lokale Konfiguration
  gespeichert. Die Aufnahme belegt **keinen erfolgreichen API-Aufruf**
  und **keine funktionierende gesprochene KI-Konversation**.

Keine Videobilder, persönlichen Tokens oder Transkripte wurden ins
Repository geschrieben.

## Fehlerhypothese
Die Android-`SpeechRecognizer`-Implementierung erzeugt für jede
Erkennung eine eigene kurze Session und startet nach Stille/Antwort/TTS
erneut; schnelle Start-/Abbruchfolgen können das Dienstlimit auslösen.
Wiederholtes manuelles Reaktivieren ohne Sperrzeit verstärkt das Problem.

## Änderung (nur isolierter Testzweig)
- `JarvisSpeechStartGuard` begrenzt Starts **mindestens alle 8 Sekunden**,
  einschließlich Neustarts aus automatischer Retry-Logik und Voice-TTS.
- Bei Code 10 wird sofort gestoppt und eine **120-Sekunden-Sperrzeit**
  gesetzt; eine gespeicherte Restzeit gilt auch bei Neuerstellung der
  Android-Activity. Währenddessen kann der Nutzer Freisprechen nicht
  sofort neu starten und bekommt eine verständliche Fehlermeldung.
- Lokale Texteingabe, lokaler Befehlsrouter und **KI FRAGEN** bleiben
  verfügbar; es wird keine Cloud-API automatisch kontaktiert.
- Fehlendes HF-Token, fehlende Zustimmung oder ungültiges Modell
  werden direkt am jeweiligen Eingabefeld angezeigt. Fehlende Eingabe
  wird nicht länger als Keystore-Defekt ausgegeben.
- Debug-App-Paket `com.pete.tftjarvis.jarvis0916`, versionCode 46,
  Name `0.9.16-speech-throttle-preview`. PR #48 und Release-App bleiben
  unabhängig und unverändert.
- Eigenständige JUnit-Tests für Mindestabstand, Code-10-Sperre und
  Wiederherstellen der Sperre.

## Grenzen / keine Erfolgsgarantie
**Wichtig:** Auch bei bestandenem Build bleibt der
Android-`SpeechRecognizer` ein kurzer One-Shot-Dienst. Die zusätzliche
Startbegrenzung kann Antworten verzögern und garantiert **weder**
stabilen endlosen Live-Sprachchat noch Barge-in/akustische
Echounterdrückung. Ein echtes dauerndes Live-Audio-Streaming benötigt
eine gesonderte ASR-Architektur (z. B. getestetes on-device Streaming
mit sherpa-onnx) und echte Gerätemessungen, Modellgrößen-/Lizenzprüfung
sowie Strom-/RAM-Budget. Das ist ausdrücklich **nicht** Teil dieser
Hotfix-APK.

Die HF-Anbindung bleibt optional, benötigt persönlich autorisierten
Token und ein tatsächlich verfügbares Modell/Inference-Kontingent;
kostenlose Nutzung ist nicht garantiert.

## Akzeptanztest auf dem S24
1. CI-GitHub-Prüfungen erfolgreich auf Head: JARVIS-Core-Unit-Tests,
   Android-Unit-Tests, Lint und Debug-APK, inklusive Paket- und
   Signaturprüfung. Grün allein ist **kein Praxistest**.
2. Isolierte 0.9.16 Debug-APK installieren (nicht Release ersetzen).
3. KI ausgeschaltet lassen, Freisprechen 30–60 Sekunden testen.
   Prüfen: startet es, erkennt es Sprache, meldet es Code 10?
4. Falls Code 10: **mindestens 120 s** kein erneuter Start;
   UI muss die Restsperre nennen. App neu öffnen: Sperre bleibt erhalten.
5. Wenn Spracherkennung ohne Code 10 funktioniert, fünf freie Fragen
   mit bestätigter Provider-Freigabe testen, drei TTS-Unterbrechungen.
   Keine unbelegte Voll-Duplex-Zusage.
6. In `KI EINRICHTEN` ohne Token speichern: Feldfehler statt
   „Keystore defekt“. Eigenes HF-Token nur in der App eingeben, nie im
   Screenshot oder GitHub-Issue zeigen. Mit echter Modell-ID manuelle
   KI-Antwort + Folgefrage prüfen.
7. Bei ausbleibendem Erfolg Gerätedienst und lokale Streaming-ASR
   als nächsten Architektur-Schritt untersuchen. Preview deinstallieren
   als einfacher Rollback.

Referenzen: Android SpeechRecognizer
https://developer.android.com/reference/android/speech/SpeechRecognizer
und https://developer.android.com/reference/android/speech/SpeechRecognizer#ERROR_TOO_MANY_REQUESTS
