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

    private static final String P_API = "api";
    private static final String P_MODEL = "model";
    private static final String P_GEMINI_API = "gemini_api";
    private static final String P_GEMINI_MODEL = "gemini_model";
    private static final String P_PROVIDER = "provider";
    private static final String P_SMART_AUTO = "smart_auto";

    public static final String PROVIDER_GEMINI = "GEMINI";
    public static final String PROVIDER_OPENAI = "OPENAI";

    private ConfigStore() {}

    // Backward-compatible OpenAI storage.
    public static void save(Context context, String apiKey, String model) {
        SharedPreferences.Editor e = prefs(context).edit();
        e.putString(P_MODEL, model);
        writeEncrypted(e, P_API, apiKey);
        e.apply();
    }

    public static String loadApiKey(Context context) {
        return readEncrypted(context, P_API);
    }

    public static String loadModel(Context context) {
        return prefs(context).getString(P_MODEL, "gpt-6-luna");
    }

    public static void saveGemini(Context context, String apiKey, String model) {
        SharedPreferences.Editor e = prefs(context).edit();
        e.putString(P_GEMINI_MODEL,
                model == null || model.trim().isEmpty()
                        ? GeminiClient.DEFAULT_MODEL
                        : model.trim());
        writeEncrypted(e, P_GEMINI_API, apiKey);
        e.apply();
    }

    public static String loadGeminiApiKey(Context context) {
        return readEncrypted(context, P_GEMINI_API);
    }

    public static String loadGeminiModel(Context context) {
        return prefs(context).getString(P_GEMINI_MODEL, GeminiClient.DEFAULT_MODEL);
    }

    public static void saveProvider(Context context, String provider) {
        prefs(context).edit()
                .putString(P_PROVIDER, normalizeProvider(provider))
                .apply();
    }

    public static String loadProvider(Context context) {
        return normalizeProvider(
                prefs(context).getString(P_PROVIDER, PROVIDER_GEMINI)
        );
    }

    public static boolean loadSmartAuto(Context context) {
        return prefs(context).getBoolean(P_SMART_AUTO, true);
    }

    public static void saveSmartAuto(Context context, boolean enabled) {
        prefs(context).edit()
                .putBoolean(P_SMART_AUTO, enabled)
                .apply();
    }

    private static String normalizeProvider(String provider) {
        return PROVIDER_OPENAI.equalsIgnoreCase(provider)
                ? PROVIDER_OPENAI
                : PROVIDER_GEMINI;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void writeEncrypted(
            SharedPreferences.Editor editor,
            String prefKey,
            String value
    ) {
        if (value != null && !value.trim().isEmpty()) {
            try {
                editor.putString(prefKey, encrypt(value.trim()));
            } catch (Exception ignored) {
                // Never fall back to plaintext storage for API keys.
            }
        } else {
            editor.remove(prefKey);
        }
    }

    private static String readEncrypted(Context context, String prefKey) {
        String enc = prefs(context).getString(prefKey, "");
        if (enc == null || enc.trim().isEmpty()) return "";
        try {
            return decrypt(enc);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static SecretKey getKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            KeyStore.SecretKeyEntry entry =
                    (KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null);
            return entry.getSecretKey();
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
        );
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
        byte[] ciphertext =
                cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(iv, Base64.NO_WRAP) + "." +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP);
    }

    private static String decrypt(String stored) throws Exception {
        String[] parts = stored.split("\\.", 2);
        if (parts.length != 2) return "";

        byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
        byte[] ciphertext = Base64.decode(parts[1], Base64.NO_WRAP);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                getKey(),
                new GCMParameterSpec(128, iv)
        );
        return new String(
                cipher.doFinal(ciphertext),
                StandardCharsets.UTF_8
        );
    }
}
