package com.pete.tftjarvis;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.BooleanSupplier;

/** Validates and atomically imports GGUF into private app storage. NOT an inference engine. */
final class JarvisGgufModelStore {
    static final long MAX_MODEL_BYTES = 3L * 1024 * 1024 * 1024;
    private static final int HEADER_BYTES = 24;
    // A 24-byte magic/header is not a usable model. Require substantial payload,
    // while the native runtime must still perform full GGUF structural validation.
    static final long MIN_MODEL_BYTES = 1024L * 1024L;
    private static final String NAME = "local-model.gguf";

    static final class Header {
        final int version;
        final long tensors;
        final long metadata;
        Header(int version, long tensors, long metadata) {
            this.version = version; this.tensors = tensors; this.metadata = metadata;
        }
    }

    static Header inspect(InputStream input) throws IOException {
        if (input == null) throw new IOException("Keine Modell-Datei.");
        byte[] bytes = new byte[HEADER_BYTES];
        int read = 0;
        while (read < bytes.length) {
            int n = input.read(bytes, read, bytes.length - read);
            if (n <= 0) throw new IOException("Unvollständiger GGUF-Header.");
            read += n;
        }
        if (bytes[0] != 'G' || bytes[1] != 'G'
                || bytes[2] != 'U' || bytes[3] != 'F') {
            throw new IOException("Kein GGUF-Modell.");
        }
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int version = b.getInt(4);
        long tensors = b.getLong(8);
        long metadata = b.getLong(16);
        if ((version != 2 && version != 3) || tensors < 1
                || tensors > 1000000L || metadata < 1 || metadata > 1000000L) {
            throw new IOException("GGUF-Header nicht unterstützt oder ungültig.");
        }
        return new Header(version, tensors, metadata);
    }

    /** Source is opened via ACTION_OPEN_DOCUMENT in a later UI milestone. */
    static final class ImportedModel {
        final File file;
        final String sha256;
        final long bytes;
        ImportedModel(File file, String sha256, long bytes) {
            this.file = file; this.sha256 = sha256; this.bytes = bytes;
        }
    }

    static ImportedModel importVerified(InputStream source, File privateDirectory) throws IOException {
        return importInternal(source, privateDirectory, null, () -> false);
    }

    static ImportedModel importVerified(InputStream source, File privateDirectory,
                                        String expectedSha256) throws IOException {
        if (expectedSha256 == null || !expectedSha256.matches("[a-fA-F0-9]{64}"))
            throw new IOException("Ungültiger SHA-256-Referenzwert.");
        return importInternal(source, privateDirectory, expectedSha256.toLowerCase(java.util.Locale.ROOT), () -> false);
    }

    /** Supports cooperative cancel while importing a large model from the Android document picker. */
    static ImportedModel importVerified(InputStream source, File directory,
                                        String expectedSha256, BooleanSupplier canceled)
            throws IOException {
        if (expectedSha256 == null || !expectedSha256.matches("[a-fA-F0-9]{64}")) {
            throw new IOException("Ungültiger SHA-256-Referenzwert.");
        }
        if (canceled == null) throw new IOException("Abbruchprüfung fehlt.");
        return importInternal(source, directory, expectedSha256.toLowerCase(java.util.Locale.ROOT), canceled);
    }

    static File importFrom(InputStream source, File privateDirectory) throws IOException {
        return importInternal(source, privateDirectory, null, () -> false).file;
    }

    private static ImportedModel importInternal(InputStream source, File privateDirectory,
                                                String expectedSha256, BooleanSupplier canceled)
            throws IOException {
        requireNotCanceled(canceled);
        if (source == null) throw new IOException("Modell konnte nicht geöffnet werden.");
        if (privateDirectory == null || (!privateDirectory.isDirectory()
                && !privateDirectory.mkdirs())) {
            throw new IOException("Privater Modellordner nicht verfügbar.");
        }
        File partial = new File(privateDirectory, NAME + ".partial");
        File destination = new File(privateDirectory, NAME);
        if (partial.exists() && !partial.delete()) {
            throw new IOException("Alte Teildatei kann nicht gelöscht werden.");
        }
        boolean published = false;
        try {
            long written = 0L;
            final MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException e) { throw new IOException("SHA-256 nicht verfügbar.", e); }
            byte[] chunk = new byte[65536];
            try (FileOutputStream output = new FileOutputStream(partial)) {
                int n;
                while (true) {
                    requireNotCanceled(canceled);
                    n = source.read(chunk);
                    requireNotCanceled(canceled);
                    if (n == -1) break;
                    if (n == 0) continue;
                    if (written > MAX_MODEL_BYTES - n) {
                        throw new IOException("GGUF-Modell zu groß (max. 3 GiB).");
                    }
                    written += n;
                    digest.update(chunk, 0, n);
                    output.write(chunk, 0, n);
                }
                output.getFD().sync();
            }
            requireNotCanceled(canceled);
            if (written < MIN_MODEL_BYTES) {
                throw new IOException("GGUF-Datei ist zu klein für ein lokales Sprachmodell.");
            }
            try (FileInputStream verify = new FileInputStream(partial)) {
                inspect(verify);
            }
            String sha256 = toHex(digest.digest());
            if (expectedSha256 != null && !expectedSha256.equals(sha256)) {
                throw new IOException("SHA-256-Prüfung fehlgeschlagen. Bestehendes Modell bleibt erhalten.");
            }
            requireNotCanceled(canceled);
            try {
                Files.move(partial.toPath(), destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                throw new IOException("Keine sichere atomare Speicherung möglich.", e);
            }
            published = true;
            return new ImportedModel(destination, sha256, written);
        } finally {
            if (!published && partial.exists()) partial.delete();
        }
    }

    private static void requireNotCanceled(BooleanSupplier canceled) throws IOException {
        if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new IOException("Modellimport abgebrochen.");
        }
    }

    private static String toHex(byte[] bytes) {
        final char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = digits[(bytes[i] >>> 4) & 15];
            out[i * 2 + 1] = digits[bytes[i] & 15];
        }
        return new String(out);
    }

    static boolean isImported(File directory) {
        if (directory == null) return false;
        File f = new File(directory, NAME);
        if (!f.isFile() || f.length() < MIN_MODEL_BYTES
                || f.length() > MAX_MODEL_BYTES) return false;
        try (FileInputStream in = new FileInputStream(f)) {
            inspect(in);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }
}
