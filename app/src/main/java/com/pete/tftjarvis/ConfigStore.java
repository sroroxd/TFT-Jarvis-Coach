package com.pete.tftjarvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class ConfigStore {
    private static final String PREFS = "tft_jarvis_config";
    private static final String KEY_ALIAS = "tft_jarvis_api_key";
    private static final String P_API = "gemini_api";
    private static final String P_MODEL = "model";
    private static final String P_SMART_AUTO = "smart_auto";

    private ConfigStore() {}

    public static void save(Context context, String apiKey, String model) {
        SharedPreferences.Editor e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        String safeModel = model != null && model.trim().startsWith("gemini-")
                ? model.trim()
                : GeminiClient.DEFAULT_MODEL;
        e.putString(P_MODEL, safeModel);
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            try {
                e.putString(P_API, encrypt(apiKey));
            } catch (Exception ignored) {
                // Never fall back to plaintext storage for an API key.
            }
        } else {
            e.remove(P_API);
        }
        e.apply();
    }

    public static String loadApiKey(Context context) {
        String enc = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(P_API, "");
        if (enc == null || enc.trim().isEmpty()) return "";
        try {
            return decrypt(enc);
        } catch (Exception ignored) {
            return "";
        }
    }

    public static String loadModel(Context context) {
        String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(P_MODEL, GeminiClient.DEFAULT_MODEL);
        if (saved == null || !saved.trim().startsWith("gemini-")) {
            return GeminiClient.DEFAULT_MODEL;
        }
        return saved.trim();
    }

    public static boolean loadSmartAuto(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(P_SMART_AUTO, true);
    }

    public static void saveSmartAuto(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(P_SMART_AUTO, enabled)
                .apply();
    }


    private static SecretKey getKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null);
            return entry.getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static String encrypt(String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey());
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(iv, Base64.NO_WRAP) + "." +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP);
    }

    private static String decrypt(String stored) throws Exception {
        String[] parts = stored.split("\\.", 2);
        if (parts.length != 2) return "";
        byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
        byte[] ciphertext = Base64.decode(parts[1], Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }
}
