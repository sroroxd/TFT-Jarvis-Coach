package com.pete.tftjarvis;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;

import javax.net.ssl.HttpsURLConnection;

/** Offline policy tests: constructing these connections never sends a request. */
public class JarvisHttpsTransportTest {
    @Test public void rejectsPlaintextConnection() throws Exception {
        rejects("http://api.openai.com/v1/responses", "api.openai.com");
    }

    @Test public void rejectsHostLookalikeAndUserInfo() throws Exception {
        rejects("https://api.openai.com.attacker.invalid/v1/responses", "api.openai.com");
        rejects("https://api.openai.com@attacker.invalid/v1/responses", "api.openai.com");
    }

    @Test public void rejectsCustomPortsAndFragments() throws Exception {
        rejects("https://api.openai.com:8443/v1/responses", "api.openai.com");
        rejects("https://api.openai.com/v1/responses#fragment", "api.openai.com");
    }

    @Test public void allowsOnlyExpectedProvidersWithoutRedirects() throws Exception {
        HttpsURLConnection openAi = JarvisHttpsTransport.open(
                "https://api.openai.com/v1/responses", "api.openai.com");
        HttpsURLConnection gemini = JarvisHttpsTransport.open(
                "https://generativelanguage.googleapis.com/v1beta/models?pageSize=100",
                "generativelanguage.googleapis.com");
        HttpsURLConnection hf = JarvisHttpsTransport.open(
                "https://router.huggingface.co/v1/chat/completions", "router.huggingface.co");
        try {
            assertFalse(openAi.getInstanceFollowRedirects());
            assertFalse(gemini.getInstanceFollowRedirects());
            assertFalse(hf.getInstanceFollowRedirects());
        } finally {
            openAi.disconnect();
            gemini.disconnect();
            hf.disconnect();
        }
    }

    private static void rejects(String url, String host) throws Exception {
        try {
            JarvisHttpsTransport.open(url, host);
            fail("An untrusted destination must not be opened.");
        } catch (IOException expected) {
            // The connection must fail before any network request.
        }
    }
}
