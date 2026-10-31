package com.pete.tftjarvis;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.Assert.*;

public class JarvisGgufModelStoreTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static byte[] gguf(int version, long tensors, long metadata) {
        return ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .put(new byte[]{'G','G','U','F'})
                .putInt(version).putLong(tensors).putLong(metadata).array();
    }
    private static byte[] model() {
        byte[] payload = new byte[(int) JarvisGgufModelStore.MIN_MODEL_BYTES + 64];
        byte[] header = gguf(3, 10, 20);
        System.arraycopy(header, 0, payload, 0, header.length);
        return payload;
    }
    @Test public void parsesGGUFHeader() throws Exception {
        JarvisGgufModelStore.Header h = JarvisGgufModelStore.inspect(
                new ByteArrayInputStream(gguf(3, 432, 28)));
        assertEquals(3, h.version);
        assertEquals(432, h.tensors);
        assertEquals(28, h.metadata);
    }
    @Test public void rejectsInvalidModels() throws Exception {
        for (byte[] wrong : new byte[][]{
                new byte[]{'Z','I','P'}, gguf(4, 1, 1),
                gguf(3, 0, 1), gguf(3, 1, 0)
        }) {
            try {
                JarvisGgufModelStore.inspect(new ByteArrayInputStream(wrong));
                fail("Invalid GGUF accepted.");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().length() > 0);
            }
        }
    }
    @Test public void importsIntoPrivateFolder() throws Exception {
        File root = tmp.newFolder();
        File file = JarvisGgufModelStore.importFrom(
                new ByteArrayInputStream(model()), root);
        assertEquals("local-model.gguf", file.getName());
        assertTrue(JarvisGgufModelStore.isImported(root));
        assertFalse(new File(root, "local-model.gguf.partial").exists());
    }
    @Test public void rejectsHeaderOnlyFakeModel() throws Exception {
        File root = tmp.newFolder();
        try {
            JarvisGgufModelStore.importFrom(new ByteArrayInputStream(gguf(3, 10, 20)), root);
            fail("Header-only fake GGUF must not be imported.");
        } catch (IOException expected) {
            assertFalse(JarvisGgufModelStore.isImported(root));
        }
    }
    @Test public void returnsStableChecksumAndSize() throws Exception {
        byte[] payload = model();
        JarvisGgufModelStore.ImportedModel imported = JarvisGgufModelStore.importVerified(
                new ByteArrayInputStream(payload), tmp.newFolder());
        assertEquals(payload.length, imported.bytes);
        assertEquals(64, imported.sha256.length());
        assertTrue(imported.sha256.matches("[0-9a-f]{64}"));
        assertEquals(payload.length, imported.file.length());
    }
    @Test public void badImportDoesNotReplaceOldModel() throws Exception {
        File root = tmp.newFolder();
        JarvisGgufModelStore.importFrom(
                new ByteArrayInputStream(model()), root);
        try {
            JarvisGgufModelStore.importFrom(
                    new ByteArrayInputStream(new byte[]{0, 1, 2}), root);
            fail("Bad import accepted.");
        } catch (IOException expected) {
            assertTrue(JarvisGgufModelStore.isImported(root));
            assertFalse(new File(root, "local-model.gguf.partial").exists());
        }
    }
}
