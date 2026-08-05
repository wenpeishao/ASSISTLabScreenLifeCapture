package com.screenomics;

import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encryptor for MindPulse.
 *
 * API:
 *  - encryptFileToEnc(plaintextFile, encOutFile, serverImagePublicKeyPem)
 *      * AES-GCM, 12-byte nonce prefixed to the .enc
 *      * RSA-OAEP(SHA-256) wraps the AES key -> base64 returned for metadata
 */
public final class Encryptor {

    private Encryptor() {}

    // ---- GCM constants ----
    public static final int GCM_TAG_BITS  = 128;
    public static final int GCM_NONCE_LEN = 12;   // 96-bit nonce
    public static final int AES_BITS      = 256;  // use 128 if device requires

    private static final String CIPH_AEAD = "AES/GCM/NoPadding";
    private static final String CIPH_RSA  = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final SecureRandom RNG = new SecureRandom();

    // Result container for modern API
    public static final class Result {
        public final File encFile;          // file content = [nonce || ciphertext||tag]
        public final String aesKeyEncB64;   // RSA-OAEP(SHA-256)(AES key), base64
        public final String nonceB64;       // base64(12-byte nonce) — for reference
        public final int tagLenBits = GCM_TAG_BITS;

        public Result(File f, String k, String n) { encFile = f; aesKeyEncB64 = k; nonceB64 = n; }
    }

    /**
     * Modern API: AES-GCM encrypt a plaintext file to .enc and wrap AES key with RSA-OAEP(SHA-256).
     * Output framing: [ 12-byte nonce ] [ ciphertext ... tag ]
     */
    public static Result encryptFileToEnc(File in, File outEnc, String serverImagePublicKeyPem) throws Exception {
        // 1) per-file AES key
        SecretKey aesKey = genAesKey(AES_BITS);

        // 2) 12-byte nonce
        byte[] nonce = new byte[GCM_NONCE_LEN];
        RNG.nextBytes(nonce);

        // 3) GCM init
        Cipher gcm = Cipher.getInstance(CIPH_AEAD);
        gcm.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));

        // 4) stream encrypt: write nonce then ciphertext+tag
        try (FileOutputStream fos = new FileOutputStream(outEnc)) {
            fos.write(nonce);
            try (CipherOutputStream cos = new CipherOutputStream(fos, gcm);
                 FileInputStream fis = new FileInputStream(in)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) != -1) {
                    cos.write(buf, 0, n);
                }
                // CipherOutputStream.close() calls doFinal() internally
            }
        }

        // 5) RSA-OAEP(SHA-256) wrap AES key
        PublicKey serverKey = loadRsaPublicKeyFromPem(serverImagePublicKeyPem);
        Cipher rsa = Cipher.getInstance(CIPH_RSA);
        rsa.init(Cipher.ENCRYPT_MODE, serverKey,
                new javax.crypto.spec.OAEPParameterSpec(
                        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
                        javax.crypto.spec.PSource.PSpecified.DEFAULT
                )
        );
        String aesKeyEncB64 = Base64.encodeToString(rsa.doFinal(aesKey.getEncoded()), Base64.NO_WRAP);
        String nonceB64     = Base64.encodeToString(nonce, Base64.NO_WRAP);

        return new Result(outEnc, aesKeyEncB64, nonceB64);
    }

    /**
     * Legacy entry point kept only so the disabled mindpulseDev video-capture code
     * compiles. It must never silently succeed: callers delete the plaintext source
     * after this returns, so a no-op here would destroy the recording while logging
     * success. Throwing keeps the original file intact (callers catch and log).
     *
     * @deprecated Port callers to {@link #encryptFileToEnc} before re-enabling video.
     */
    @Deprecated
    public static void encryptFile(byte[] key, String inputPath, String outputPath, byte[] iv) {
        throw new UnsupportedOperationException(
                "Legacy encryptFile is not implemented; port to encryptFileToEnc before use");
    }

    // ---- helpers ----

    private static SecretKey genAesKey(int bits) throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(bits, RNG);
        return kg.generateKey();
    }

    /** Load RSA public key from PEM ("-----BEGIN PUBLIC KEY-----"). */
    private static PublicKey loadRsaPublicKeyFromPem(String pem) throws Exception {
        String clean = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.decode(clean, Base64.DEFAULT);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }

}
