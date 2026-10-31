package com.pete.tftjarvis;

import java.io.File;

/**
 * Local CPU inference through a pinned arm64-v8a llama.cpp JNI build.
 * No cloud, key, background model download or automatic microphone activation.
 * The caller must invoke load/generate off the Android UI thread.
 */
final class JarvisOfflineEngine {
    static {
        System.loadLibrary("jarvis_offline");
    }

    private JarvisOfflineEngine() { }

    static synchronized boolean load(File verifiedGguf) {
        if (verifiedGguf == null || !verifiedGguf.isFile()
                || verifiedGguf.length() < JarvisGgufModelStore.MIN_MODEL_BYTES
                || verifiedGguf.length() > JarvisGgufModelStore.MAX_MODEL_BYTES) return false;
        return nativeLoad(verifiedGguf.getAbsolutePath());
    }

    static String generate(String fullPrompt, int maxNewTokens) {
        if (fullPrompt == null || fullPrompt.isEmpty() || fullPrompt.length() > 12000)
            throw new IllegalArgumentException("Ungültiger Prompt.");
        return nativeGenerate(fullPrompt, Math.min(256, Math.max(1, maxNewTokens)));
    }

    static void interrupt() { nativeStop(); }
    static synchronized void unload() { nativeUnload(); }

    private static native boolean nativeLoad(String absolutePath);
    private static native String nativeGenerate(String fullPrompt, int maxNewTokens);
    private static native void nativeStop();
    private static native void nativeUnload();
}
