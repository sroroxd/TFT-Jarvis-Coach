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

/** Validates and atomically imports GGUF into private app storage. NOT an inference engine. */
final class JarvisGgufModelStore {
    static final long MAX_MODEL_BYTES = 3L * 1024 * 1024 * 1024;
    private static final int HEADER_BYTES = 24;
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
    static File importFrom(InputStream source, File privateDirectory) throws IOException {
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
            byte[] chunk = new byte[65536];
            try (FileOutputStream output = new FileOutputStream(partial)) {
                int n;
                while ((n = source.read(chunk)) != -1) {
                    if (n == 0) continue;
                    if (written > MAX_MODEL_BYTES - n) {
                        throw new IOException("GGUF-Modell zu groß (max. 3 GiB).");
                    }
                    written += n;
                    output.write(chunk, 0, n);
                }
                output.getFD().sync();
            }
            try (FileInputStream verify = new FileInputStream(partial)) {
                inspect(verify);
            }
            try {
                Files.move(partial.toPath(), destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                throw new IOException("Keine sichere atomare Speicherung möglich.", e);
            }
            published = true;
            return destination;
        } finally {
            if (!published && partial.exists()) partial.delete();
        }
    }

    static boolean isImported(File directory) {
        if (directory == null) return false;
        File f = new File(directory, NAME);
        if (!f.isFile() || f.length() < HEADER_BYTES
                || f.length() > MAX_MODEL_BYTES) return false;
        try (FileInputStream in = new FileInputStream(f)) {
            inspect(in);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }
}
