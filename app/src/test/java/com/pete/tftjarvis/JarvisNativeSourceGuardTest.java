package com.pete.tftjarvis;

import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Build-time source regression guard only. It does NOT prove that GGUF
 * generation succeeds on the S24 Ultra; that needs an offline device test.
 */
public class JarvisNativeSourceGuardTest {
    private static String source(String relative) throws Exception {
        return new String(Files.readAllBytes(Paths.get(relative)), StandardCharsets.UTF_8);
    }

    @Test public void qwenChatmlSpecialTokensAreParsedInBothPasses() throws Exception {
        String code = source("src/main/cpp/jarvis_offline.cpp");
        assertTrue(code.contains("constexpr bool kParseSpecial = true;"));
        assertTrue(code.contains("static_assert(kParseSpecial"));
        assertTrue(code.contains("0, true, kParseSpecial)"));
        assertTrue(code.contains("tokens.data(), (int) tokens.size(), true, kParseSpecial)"));
    }

    @Test public void nativeDecodeMustNotReturnTruncatedSuccessfulAnswer() throws Exception {
        String code = source("src/main/cpp/jarvis_offline.cpp");
        assertTrue(code.contains("actualTokens != (int) tokens.size()"));
        assertTrue(code.contains("if (llama_decode(ctx, batch) != 0) return env->NewStringUTF(\"\");"));
    }

    @Test public void llamaUpstreamCommitIsPinnedAndVerified() throws Exception {
        String code = source("src/main/cpp/CMakeLists.txt");
        assertTrue(code.contains("4762ad7316dcdec20016ab5985fb46a27902204d"));
        assertTrue(code.contains("JARVIS_LLAMA_ACTUAL_SHA STREQUAL JARVIS_LLAMA_EXPECTED_SHA"));
        assertTrue(code.contains("message(FATAL_ERROR"));
    }
}
