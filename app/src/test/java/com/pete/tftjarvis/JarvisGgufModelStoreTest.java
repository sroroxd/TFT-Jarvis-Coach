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
                new ByteArrayInputStream(gguf(3, 10, 20)), root);
        assertEquals("local-model.gguf", file.getName());
        assertTrue(JarvisGgufModelStore.isImported(root));
        assertFalse(new File(root, "local-model.gguf.partial").exists());
    }
    @Test public void badImportDoesNotReplaceOldModel() throws Exception {
        File root = tmp.newFolder();
        JarvisGgufModelStore.importFrom(
                new ByteArrayInputStream(gguf(3, 10, 20)), root);
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
