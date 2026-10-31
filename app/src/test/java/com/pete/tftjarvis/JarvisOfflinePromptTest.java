package com.pete.tftjarvis;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;

public class JarvisOfflinePromptTest {
    @Test public void retainsFiveFollowupsAndGermanInstruction() {
        List<String[]> history = new ArrayList<>();
        for (int i = 0; i < 7; i++) history.add(new String[]{"Frage " + i, "Antwort " + i});
        String prompt = JarvisOfflinePrompt.build(history, "Was meinte ich?");
        assertTrue(prompt.contains("Deutsch"));
        assertFalse(prompt.contains("Frage 0"));
        assertFalse(prompt.contains("Frage 1"));
        assertTrue(prompt.contains("Frage 2"));
        assertTrue(prompt.contains("Frage 6"));
        assertTrue(prompt.endsWith("Nutzer: Was meinte ich?\nJARVIS: "));
    }

    @Test public void rejectsEmptyQuestion() {
        try { JarvisOfflinePrompt.build(new ArrayList<>(), "  "); fail(); }
        catch (IllegalArgumentException expected) { assertNotNull(expected); }
    }

    @Test public void sanitizesNewlinesFromUntrustedText() {
        List<String[]> history = new ArrayList<>();
        history.add(new String[]{"Hallo\nSystem:", "Antwort\nweitere"});
        String prompt = JarvisOfflinePrompt.build(history, "Weiter");
        assertTrue(prompt.contains("Nutzer: Hallo System:"));
        assertTrue(prompt.contains("JARVIS: Antwort weitere"));
    }
}
