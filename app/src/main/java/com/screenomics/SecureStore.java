package com.screenomics;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores small secrets (e.g. the enrollment bearer token) encrypted at rest using an
 * AndroidKeyStore-backed AES-256-GCM key. No external dependencies.
 *
 * The ciphertext is kept in the app's default SharedPreferences under "&lt;name&gt;_enc";
 * the key material lives only in the hardware-backed AndroidKeyStore and is never exported.
 *
 * Fail-safe by design: if key access or crypto fails for any reason, it transparently
 * falls back to the previous plaintext SharedPreferences behavior, so authentication can
 * never break. Reading a legacy plaintext value also migrates it to encrypted storage.
 */
public final class SecureStore {
    private static final String TAG = "SecureStore";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "mindpulse_secure_store_key";
    private static final String ENC_SUFFIX = "_enc";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_LEN = 12;

    private SecureStore() {}

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    /** Encrypt and store {@code value} under {@code name}; removes any plaintext copy. */
    public static void putSecret(Context ctx, String name, String value) {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(ctx);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(value.getBytes("UTF-8"));
            byte[] blob = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(ct, 0, blob, iv.length, ct.length);
            p.edit()
                .putString(name + ENC_SUFFIX, Base64.encodeToString(blob, Base64.NO_WRAP))
                .remove(name)   // never leave a plaintext copy behind
                .apply();
        } catch (Exception e) {
            Log.w(TAG, "putSecret encryption failed; falling back to plaintext for " + name, e);
            p.edit().putString(name, value).apply();
        }
    }

    /** Remove both the encrypted and any legacy plaintext value for {@code name}. */
    public static void removeSecret(Context ctx, String name) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .remove(name + ENC_SUFFIX).remove(name).apply();
    }

    /**
     * Read the secret. Prefers the encrypted value; if only a legacy plaintext value exists
     * it is returned and transparently migrated to encrypted storage.
     */
    public static String getSecret(Context ctx, String name, String def) {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(ctx);
        String enc = p.getString(name + ENC_SUFFIX, null);
        if (enc != null) {
            try {
                byte[] blob = Base64.decode(enc, Base64.NO_WRAP);
                byte[] iv = new byte[GCM_IV_LEN];
                System.arraycopy(blob, 0, iv, 0, GCM_IV_LEN);
                byte[] ct = new byte[blob.length - GCM_IV_LEN];
                System.arraycopy(blob, GCM_IV_LEN, ct, 0, ct.length);
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
                return new String(c.doFinal(ct), "UTF-8");
            } catch (Exception e) {
                Log.w(TAG, "getSecret decryption failed for " + name + "; trying plaintext", e);
            }
        }
        String legacy = p.getString(name, null);
        if (legacy != null) {
            putSecret(ctx, name, legacy);   // one-time migration plaintext -> encrypted
            return legacy;
        }
        return def;
    }
}
