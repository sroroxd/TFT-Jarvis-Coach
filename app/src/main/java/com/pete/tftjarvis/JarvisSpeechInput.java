package com.pete.tftjarvis;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Locale;

/**
 * One-shot, explicitly initiated speech input.
 *
 * Only Android's on-device SpeechRecognizer is accepted. When it is unavailable,
 * no cloud speech recognizer is silently selected and text commands still work.
 */
final class JarvisSpeechInput {
    static final int MICROPHONE_REQUEST = 0x4A52;

    interface Callbacks {
        void onSpeechState(String state);
        void onRecognizedText(String text);
        void onSpeechError(String description);
    }

    private final Activity activity;
    private final Callbacks callbacks;
    private SpeechRecognizer recognizer;
    private boolean listening;

    JarvisSpeechInput(Activity activity, Callbacks callbacks) {
        this.activity = activity;
        this.callbacks = callbacks;
    }

    boolean isListening() {
        return listening;
    }

    void startOrCancel() {
        if (recognizer != null) {
            cancel();
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || !SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)) {
            callbacks.onSpeechError("Lokale Spracherkennung ist auf diesem Gerät nicht verfügbar. Texteingabe funktioniert weiterhin.");
            return;
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            callbacks.onSpeechState("MIKROFONFREIGABE ERFORDERLICH");
            activity.requestPermissions(
                    new String[] { Manifest.permission.RECORD_AUDIO }, MICROPHONE_REQUEST);
            return;
        }
        try {
            final SpeechRecognizer next =
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(activity);
            recognizer = next;
            listening = true;
            next.setRecognitionListener(new RecognitionListener() {
                private boolean isCurrent() { return recognizer == next; }

                @Override public void onReadyForSpeech(Bundle params) {
                    if (isCurrent()) callbacks.onSpeechState("JETZT SPRECHEN");
                }

                @Override public void onBeginningOfSpeech() {
                    if (isCurrent()) callbacks.onSpeechState("ZUHÖREN");
                }

                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }

                @Override public void onEndOfSpeech() {
                    if (isCurrent()) callbacks.onSpeechState("VERARBEITEN");
                }

                @Override public void onError(int error) {
                    if (!isCurrent()) return;
                    release();
                    callbacks.onSpeechError(describeError(error));
                }

                @Override public void onResults(Bundle results) {
                    if (!isCurrent()) return;
                    ArrayList<String> matches =
                            results == null ? null : results.getStringArrayList(
                                    SpeechRecognizer.RESULTS_RECOGNITION);
                    String recognized = matches == null || matches.isEmpty()
                            ? "" : matches.get(0).trim();
                    release();
                    if (recognized.isEmpty()) {
                        callbacks.onSpeechError("Keine Sprache erkannt. Bitte erneut versuchen.");
                    } else {
                        callbacks.onRecognizedText(recognized);
                    }
                }

                @Override public void onPartialResults(Bundle partialResults) { }
                @Override public void onEvent(int eventType, Bundle params) { }
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.GERMAN.toLanguageTag());
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            next.startListening(intent);
            callbacks.onSpeechState("MIKROFON AKTIV");
        } catch (RuntimeException e) {
            release();
            callbacks.onSpeechError("Lokale Spracheingabe konnte nicht gestartet werden. Texteingabe bleibt verfügbar.");
        }
    }

    void onPermissionResult(int requestCode, int[] grantResults) {
        if (requestCode != MICROPHONE_REQUEST) return;
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startOrCancel();
        } else {
            callbacks.onSpeechError("Mikrofonzugriff nicht erlaubt. Texteingabe bleibt verfügbar.");
        }
    }

    void cancel() {
        if (recognizer != null) {
            SpeechRecognizer previous = recognizer;
            recognizer = null;
            listening = false;
            previous.cancel();
            previous.destroy();
        }
        callbacks.onSpeechState("BEREIT");
    }

    private void release() {
        SpeechRecognizer previous = recognizer;
        recognizer = null;
        listening = false;
        if (previous != null) previous.destroy();
        callbacks.onSpeechState("BEREIT");
    }

    private static String describeError(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "Keine Sprache erkannt. Bitte erneut sprechen.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Mikrofonberechtigung fehlt.";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Spracherkennung ist beschäftigt. Bitte erneut versuchen.";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Lokale Spracherkennung hat einen Dienstfehler gemeldet.";
            default:
                return "Spracherkennung fehlgeschlagen (" + error + "). Texteingabe bleibt verfügbar.";
        }
    }
}
