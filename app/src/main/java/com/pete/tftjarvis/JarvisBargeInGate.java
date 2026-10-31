package com.pete.tftjarvis;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative text-level echo guard for experimental foreground barge-in.
 *
 * This is NOT acoustic echo cancellation. It only reduces the risk that
 * Android's recognizer mistakes JARVIS's audible TTS output for a new request.
 */
final class JarvisBargeInGate {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    private JarvisBargeInGate() { }

    private static String words(String text) {
        if (text == null) return "";
        Matcher matcher = WORD.matcher(text.toLowerCase(Locale.GERMAN));
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            if (result.length() > 0) result.append(' ');
            result.append(matcher.group());
        }
        return result.toString();
    }

    static boolean isStopCommand(String heard) {
        String candidate = words(heard);
        return candidate.equals("stopp") || candidate.equals("stop")
                || candidate.equals("halt") || candidate.equals("unterbrechen")
                || candidate.equals("jarvis stopp") || candidate.equals("jarvis stop");
    }

    static boolean isLikelyEcho(String heard, String spoken) {
        String candidate = words(heard);
        String response = words(spoken);
        if (candidate.isEmpty() || response.isEmpty()) return false;
        if ((" " + response + " ").contains(" " + candidate + " ")) return true;

        String[] heardWords = candidate.split(" ");
        String[] spokenWords = response.split(" ");
        Set<String> spokenVocabulary = new HashSet<>();
        for (String word : spokenWords) spokenVocabulary.add(word);
        int overlapping = 0;
        for (String word : heardWords) {
            if (spokenVocabulary.contains(word)) overlapping++;
        }
        // Short hypotheses with vocabulary from TTS are too ambiguous for action.
        if (heardWords.length <= 2 && overlapping == heardWords.length) return true;
        return heardWords.length >= 3 && overlapping * 4 >= heardWords.length * 3;
    }

    /**
     * A fallback for Android recognizers that do not send onPartialResults.
     * A final result alone is accepted only if clearly unlike JARVIS's TTS.
     * This is a conservative text heuristic, NOT acoustic echo cancellation.
     */
    static boolean isStrongFinalInterruption(String heard, String spoken) {
        if (isLikelyEcho(heard, spoken)) return false;
        if (isStopCommand(heard)) return true;

        String candidate = words(heard);
        String response = words(spoken);
        if (candidate.length() < 20 || response.isEmpty()) return false;
        String[] candidateWords = candidate.split(" ");
        if (candidateWords.length < 4) return false;

        Set<String> ttsVocabulary = new HashSet<>();
        for (String word : response.split(" ")) ttsVocabulary.add(word);
        int novel = 0;
        for (String word : candidateWords) {
            if (!ttsVocabulary.contains(word)) novel++;
        }
        return novel >= 3 && novel * 4 >= candidateWords.length * 3;
    }

    static boolean mightBeInterruption(String heard, String spoken) {
        if (isLikelyEcho(heard, spoken)) return false;
        if (isStopCommand(heard)) return true;
        String candidate = words(heard);
        return candidate.length() >= 9 && candidate.indexOf(' ') > 0;
    }
}
