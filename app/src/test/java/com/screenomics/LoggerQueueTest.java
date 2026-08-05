package com.screenomics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;
import java.security.KeyPairGenerator;

/**
 * Tests the encrypted upload queue: every queued item must land in /encrypt as
 * an .enc + .meta pair with the fields the Receiver expects, and no plaintext
 * temp file may remain.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LoggerQueueTest {

    private Context context;
    private File encryptDir;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + android.util.Base64.encodeToString(
                        kpg.generateKeyPair().getPublic().getEncoded(), android.util.Base64.NO_WRAP)
                + "\n-----END PUBLIC KEY-----";

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit()
                .putString("image_public_key", pem)
                .putString("hash", "0123456789abcdef")
                .commit();

        encryptDir = new File(context.getExternalFilesDir(null), "encrypt");
    }

    private File[] filesEndingWith(String suffix) {
        File[] all = encryptDir.listFiles((d, n) -> n.endsWith(suffix));
        return all == null ? new File[0] : all;
    }

    @Test
    public void queueTextForUpload_createsEncMetaPair() throws Exception {
        boolean queued = Logger.queueTextForUpload(
                context, "{\"hello\":1}", "gps", "application/json");
        assertTrue(queued);

        File[] enc = filesEndingWith(".enc");
        File[] meta = filesEndingWith(".meta");
        assertEquals(1, enc.length);
        assertEquals(1, meta.length);
        assertTrue("filename starts with 8-char hash prefix",
                enc[0].getName().startsWith("01234567_"));
        assertTrue("descriptor is part of the filename", enc[0].getName().endsWith("_gps.enc"));

        JSONObject metaObj = new JSONObject(new String(Files.readAllBytes(meta[0].toPath())));
        assertNotNull(metaObj.getString("aes_key_encrypted_b64"));
        assertEquals(128, metaObj.getInt("tag_len_bits"));
        assertEquals("application/json", metaObj.getString("mime"));
        assertEquals("gps", metaObj.getString("type"));
        assertTrue(metaObj.has("captured_at"));
        assertTrue(metaObj.getLong("epoch_ms") > 0);

        // ciphertext, not plaintext, on disk
        String encContent = new String(Files.readAllBytes(enc[0].toPath()));
        assertFalse(encContent.contains("hello"));

        // no plaintext temp left behind
        File[] tmp = context.getExternalFilesDir(null)
                .listFiles((d, n) -> n.startsWith("tmp_"));
        assertEquals(0, tmp == null ? 0 : tmp.length);
    }

    @Test
    public void queueImageForUpload_writesProvenanceMeta() throws Exception {
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2, 3, (byte) 0xFF, (byte) 0xD9};
        boolean queued = Logger.queueImageForUpload(context, jpeg, "glass", "omi_glass", 3);
        assertTrue(queued);

        File[] meta = filesEndingWith(".meta");
        assertEquals(1, meta.length);
        JSONObject metaObj = new JSONObject(new String(Files.readAllBytes(meta[0].toPath())));
        assertEquals("image", metaObj.getString("type"));
        assertEquals("image/jpeg", metaObj.getString("mime"));
        assertEquals("omi_glass", metaObj.getString("source"));
        assertEquals(3, metaObj.getInt("orientation"));
    }

    @Test
    public void queueImageForUpload_rejectsEmptyPayload() {
        assertFalse(Logger.queueImageForUpload(context, new byte[0], "glass", "omi_glass", -1));
        assertFalse(Logger.queueImageForUpload(context, null, "glass", "omi_glass", -1));
        assertEquals(0, filesEndingWith(".enc").length);
    }

    @Test
    public void queueTextForUpload_refusesWithoutServerKey() {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit().remove("image_public_key").commit();
        assertFalse(Logger.queueTextForUpload(context, "x", "gps", "application/json"));
        assertEquals(0, filesEndingWith(".enc").length);
    }
}
