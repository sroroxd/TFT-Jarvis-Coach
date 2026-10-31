package com.pete.tftjarvis;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;

import javax.net.ssl.HttpsURLConnection;

/**
 * Opens only explicitly allowed HTTPS API destinations.
 *
 * Standard Android TLS certificate chain and hostname validation remain in place.
 * Redirects are blocked so credentials cannot be forwarded to another host.
 * This is NOT certificate pinning: third-party provider certificate rotations
 * require independent pin verification and a documented recovery process.
 */
final class JarvisHttpsTransport {
    private JarvisHttpsTransport() { }

    static HttpsURLConnection open(String endpoint, String expectedHost) throws IOException {
        URL url = new URL(endpoint);
        if (!"https".equals(url.getProtocol())
                || !expectedHost.equals(url.getHost())
                || url.getPort() != -1
                || url.getUserInfo() != null
                || url.getRef() != null) {
            throw new IOException("Unsicheres KI-Verbindungsziel abgelehnt.");
        }

        URLConnection raw = url.openConnection();
        if (!(raw instanceof HttpsURLConnection)) {
            throw new IOException("KI-Verbindung ist nicht HTTPS.");
        }
        HttpsURLConnection connection = (HttpsURLConnection) raw;
        connection.setInstanceFollowRedirects(false);
        return connection;
    }
}
