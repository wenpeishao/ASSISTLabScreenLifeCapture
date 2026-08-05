package com.screenomics;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/**
 * Round-trip test of the production file-encryption path: AES-256-GCM with a
 * 12-byte nonce prefix, AES key wrapped with RSA-OAEP(SHA-256). The test plays
 * the server's role: unwrap the key with the RSA private key and decrypt.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class EncryptorTest {

    private KeyPair rsa;
    private String publicKeyPem;
    private File tempDir;

    @Before
    public void setUp() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        rsa = kpg.generateKeyPair();
        String b64 = android.util.Base64.encodeToString(
                rsa.getPublic().getEncoded(), android.util.Base64.NO_WRAP);
        publicKeyPem = "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----";
        tempDir = Files.createTempDirectory("encryptor-test").toFile();
    }

    @Test
    public void encryptFileToEnc_roundTripsThroughServerSideDecryption() throws Exception {
        byte[] plaintext = new byte[64 * 1024 + 17]; // multiple 8KB buffers + remainder
        new SecureRandom().nextBytes(plaintext);
        File in = new File(tempDir, "plain.bin");
        try (FileOutputStream fos = new FileOutputStream(in)) { fos.write(plaintext); }
        File out = new File(tempDir, "cipher.enc");

        Encryptor.Result result = Encryptor.encryptFileToEnc(in, out, publicKeyPem);

        byte[] enc = Files.readAllBytes(out.toPath());
        assertTrue("output must be nonce + ciphertext + tag", enc.length > 12 + plaintext.length);

        // The stored nonce reference must match the file's 12-byte prefix
        byte[] nonce = Arrays.copyOfRange(enc, 0, Encryptor.GCM_NONCE_LEN);
        assertArrayEquals(nonce, android.util.Base64.decode(result.nonceB64, android.util.Base64.NO_WRAP));

        // Unwrap the AES key exactly like the Receiver does
        Cipher unwrap = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        unwrap.init(Cipher.DECRYPT_MODE, rsa.getPrivate(), new OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        byte[] aesKey = unwrap.doFinal(
                android.util.Base64.decode(result.aesKeyEncB64, android.util.Base64.NO_WRAP));
        assertEquals("AES-256 key expected", 32, aesKey.length);

        // Decrypt the payload
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                new GCMParameterSpec(Encryptor.GCM_TAG_BITS, nonce));
        byte[] decrypted = gcm.doFinal(Arrays.copyOfRange(enc, Encryptor.GCM_NONCE_LEN, enc.length));
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void encryptFileToEnc_usesFreshKeyAndNoncePerFile() throws Exception {
        File in = new File(tempDir, "p.txt");
        try (FileOutputStream fos = new FileOutputStream(in)) {
            fos.write("same plaintext".getBytes(StandardCharsets.UTF_8));
        }
        Encryptor.Result a = Encryptor.encryptFileToEnc(in, new File(tempDir, "a.enc"), publicKeyPem);
        Encryptor.Result b = Encryptor.encryptFileToEnc(in, new File(tempDir, "b.enc"), publicKeyPem);
        assertTrue("per-file AES key must differ", !a.aesKeyEncB64.equals(b.aesKeyEncB64));
        assertTrue("per-file nonce must differ", !a.nonceB64.equals(b.nonceB64));
    }

    @Test
    public void legacyEncryptFile_throwsInsteadOfSilentlySucceeding() {
        // Callers delete the plaintext source after this returns; a silent no-op
        // would destroy the recording. It must throw.
        assertThrows(UnsupportedOperationException.class,
                () -> Encryptor.encryptFile(new byte[32], "in.mp4", "out.enc", new byte[16]));
    }
}
