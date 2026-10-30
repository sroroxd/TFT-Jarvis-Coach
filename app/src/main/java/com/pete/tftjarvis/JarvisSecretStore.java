package com.pete.tftjarvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * User-provided API credential, encrypted at rest using Android Keystore AES-GCM.
 * Never stores, prints, or checks a credential into source control.
 * A personal key stored on a mobile device is not equivalent to server-side
 * credential isolation and must not be used for a shared production account.
 */
final class JarvisSecretStore {
    private static final String ALIAS = "jarvis_personal_ai_key_v1";
    private static final String PREFS = "jarvis_private_ai_config";
    private static final String ENCRYPTED = "api_key_ciphertext";
    private static final String IV = "api_key_iv";
    private static final String ENABLED = "explicit_cost_consent";

    boolean isEnabled(Context context) {
        return prefs(context).getBoolean(ENABLED, false);
    }

    boolean isConfigured(Context context) {
        return prefs(context).contains(ENCRYPTED) && isEnabled(context);
    }

    synchronized void saveAndEnable(Context context, String key) throws GeneralSecurityException, IOException {
        if (key == null || key.trim().length() < 12 || key.length() > 1024) {
            throw new IllegalArgumentException("Kein gültiger API-Schlüssel eingegeben.");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey());
        byte[] ciphertext = cipher.doFinal(key.trim().getBytes(StandardCharsets.UTF_8));
        prefs(context).edit()
                .putString(ENCRYPTED, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putBoolean(ENABLED, true)
                .apply();
    }

    synchronized String load(Context context) throws GeneralSecurityException, IOException {
        if (!isConfigured(context)) return null;
        SharedPreferences preferences = prefs(context);
        String ciphertext = preferences.getString(ENCRYPTED, null);
        String iv = preferences.getString(IV, null);
        if (ciphertext == null || iv == null) return null;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey(),
                new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)),
                StandardCharsets.UTF_8);
    }

    synchronized void disableAndClear(Context context) {
        prefs(context).edit().remove(ENCRYPTED).remove(IV).putBoolean(ENABLED, false).commit();
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
        } catch (GeneralSecurityException | IOException ignored) {
            // Clearing preferences makes the previous ciphertext inaccessible.
        }
    }

    private SecretKey secretKey() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        KeyStore.Entry entry = store.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
