package com.pete.tftjarvis;

/** Offline Android recognizer error handling with bounded, explicit retries. */
final class JarvisSpeechRecovery {
    // Android SpeechRecognizer error codes (API 35); keep this class pure Java.
    static final int NETWORK_TIMEOUT = 1;
    static final int NETWORK = 2;
    static final int AUDIO = 3;
    static final int SERVER = 4;
    static final int CLIENT = 5;
    static final int SPEECH_TIMEOUT = 6;
    static final int NO_MATCH = 7;
    static final int BUSY = 8;
    static final int PERMISSION = 9;
    static final int TOO_MANY_REQUESTS = 10;
    static final int SERVER_DISCONNECTED = 11;
    static final int LANGUAGE_NOT_SUPPORTED = 12;
    static final int LANGUAGE_UNAVAILABLE = 13;
    static final int ON_DEVICE_UNAVAILABLE = -100;
    static final int PERMISSION_DENIED = -101;
    static final int START_FAILED = -102;

    private JarvisSpeechRecovery() { }

    static boolean isSilence(int error) {
        return error == SPEECH_TIMEOUT || error == NO_MATCH;
    }

    static boolean mustStop(int error) {
        return error == PERMISSION || error == PERMISSION_DENIED
                || error == ON_DEVICE_UNAVAILABLE
                || error == LANGUAGE_NOT_SUPPORTED || error == LANGUAGE_UNAVAILABLE
                || error == TOO_MANY_REQUESTS;
    }

    static long delayMs(int error, int failures) {
        // Repeated silence is normal; do not count it as a service failure.
        if (isSilence(error)) return 1700L;
        int attempt = Math.max(1, Math.min(5, failures));
        long exponential = Math.min(14000L, 1800L * (1L << (attempt - 1)));
        if (error == BUSY || error == CLIENT) return Math.max(4500L, exponential);
        return Math.max(2700L, exponential);
    }

    static String diagnose(int error) {
        switch (error) {
            case NO_MATCH:
            case SPEECH_TIMEOUT:
                return "Keine Sprache erkannt";
            case NETWORK_TIMEOUT:
            case NETWORK:
                return "Sprachdienst meldet ein Netzwerkproblem";
            case AUDIO:
                return "Mikrofon-/Audiofehler";
            case SERVER:
            case SERVER_DISCONNECTED:
                return "Android-Sprachdienst nicht erreichbar";
            case CLIENT:
                return "Android-Spracherkennung meldet einen Start-/Sitzungsfehler";
            case BUSY:
                return "Android-Spracherkennung ist beschäftigt";
            case PERMISSION:
            case PERMISSION_DENIED:
                return "Mikrofonberechtigung fehlt";
            case TOO_MANY_REQUESTS:
                return "Zu viele Spracherkennungsstarts in kurzer Zeit";
            case LANGUAGE_NOT_SUPPORTED:
                return "Deutsch wird vom lokalen Sprachdienst nicht unterstützt";
            case LANGUAGE_UNAVAILABLE:
                return "Deutsches Offline-Sprachpaket ist nicht verfügbar";
            case ON_DEVICE_UNAVAILABLE:
                return "Keine lokale Android-Spracherkennung verfügbar";
            case START_FAILED:
                return "Lokale Spracherkennung konnte nicht gestartet werden";
            default:
                return "Unbekannter Spracherkennungsfehler";
        }
    }

    static String actionableHint(int error) {
        if (error == LANGUAGE_UNAVAILABLE || error == LANGUAGE_NOT_SUPPORTED
                || error == ON_DEVICE_UNAVAILABLE) {
            return "Prüfe die deutsche Offline-Spracherkennung in den Android-Spracheinstellungen.";
        }
        if (error == PERMISSION || error == PERMISSION_DENIED || error == AUDIO) {
            return "Prüfe Mikrofonzugriff und ob eine andere App das Mikrofon verwendet.";
        }
        if (error == TOO_MANY_REQUESTS) {
            return "Android begrenzt die Spracherkennung. Warte mindestens zwei Minuten; "
                    + "schnelles erneutes Aktivieren verlängert die Störung möglicherweise.";
        }
        if (error == BUSY || error == CLIENT
                || error == SERVER || error == SERVER_DISCONNECTED) {
            return "Warte kurz und aktiviere Freisprechen erneut; prüfe den Android-Sprachdienst.";
        }
        return "Aktiviere Freisprechen erneut oder prüfe den Android-Sprachdienst.";
    }
}
