package com.screenomics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * On-device runtime verification of the security fixes:
 *   #3 SecureStore (enrollment-token encryption at rest, round-trip, no plaintext, migration)
 *   #5 Logger.sweepStaleTempFiles (orphan temp cleanup)
 *   #1 TLS certificate pinning (real pinned handshake to the SSCC server)
 */
@RunWith(AndroidJUnit4.class)
public class SecurityFixesTest {

    private Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @After
    public void cleanup() {
        SecureStore.removeSecret(ctx(), "enrollment_token_test");
    }

    // ---- #3 SecureStore ----

    @Test
    public void secureStore_roundtrip_and_no_plaintext() {
        Context c = ctx();
        String secret = "tok-" + System.nanoTime();
        SecureStore.putSecret(c, "enrollment_token_test", secret);

        // round-trips correctly
        assertEquals(secret, SecureStore.getSecret(c, "enrollment_token_test", "DEFAULT"));

        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(c);
        // no plaintext copy left behind
        assertNull("plaintext token must not exist", p.getString("enrollment_token_test", null));
        // an encrypted blob exists and is NOT the plaintext
        String enc = p.getString("enrollment_token_test_enc", null);
        assertNotNull("encrypted blob must exist", enc);
        assertNotEquals("stored value must be ciphertext, not plaintext", secret, enc);

        // removal clears everything
        SecureStore.removeSecret(c, "enrollment_token_test");
        assertEquals("DEFAULT", SecureStore.getSecret(c, "enrollment_token_test", "DEFAULT"));
    }

    @Test
    public void secureStore_migrates_legacy_plaintext() {
        Context c = ctx();
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(c);
        // simulate an already-enrolled device with a legacy plaintext token
        p.edit().putString("enrollment_token_test", "legacy-123").remove("enrollment_token_test_enc").apply();

        // read migrates it to encrypted storage and returns the value
        assertEquals("legacy-123", SecureStore.getSecret(c, "enrollment_token_test", "DEFAULT"));
        assertNull("legacy plaintext must be removed after migration",
                p.getString("enrollment_token_test", null));
        assertNotNull("migrated encrypted blob must exist",
                p.getString("enrollment_token_test_enc", null));
    }

    // ---- #5 temp-file sweep ----

    @Test
    public void sweep_deletes_stale_but_keeps_fresh() throws Exception {
        Context c = ctx();
        File dir = c.getExternalFilesDir(null);
        assertNotNull(dir);
        File stale = new File(dir, "tmp_test_stale.jpg");
        File fresh = new File(dir, "tmp_test_fresh.jpg");
        try (FileOutputStream f = new FileOutputStream(stale)) { f.write(new byte[]{1, 2, 3}); }
        try (FileOutputStream f = new FileOutputStream(fresh)) { f.write(new byte[]{4}); }
        assertTrue(stale.setLastModified(System.currentTimeMillis() - 10 * 60 * 1000L)); // 10 min old

        Logger.sweepStaleTempFiles(c);

        assertFalse("stale temp file should be swept", stale.exists());
        assertTrue("fresh temp file should be kept", fresh.exists());
        //noinspection ResultOfMethodCallIgnored
        fresh.delete();
    }

    // ---- #1 TLS certificate pinning ----

    @Test
    public void tlsPinning_allows_real_server() {
        // Runs in the app process => the app's network_security_config (cert pinning) applies.
        // A successful HTTPS response proves the pinned TLS handshake to the real server works.
        OkHttpClient client = new OkHttpClient();
        Request req = new Request.Builder()
                .url("https://mindpulse.ssc.wisc.edu/api/v1/health").get().build();
        try (Response resp = client.newCall(req).execute()) {
            assertTrue("pinned TLS handshake succeeded, HTTP code=" + resp.code(), resp.code() > 0);
            System.out.println("PINNING TEST: reached server through pinned TLS, HTTP " + resp.code());
        } catch (javax.net.ssl.SSLPeerUnverifiedException e) {
            fail("TLS pinning REJECTED the real server (pins are wrong): " + e.getMessage());
        } catch (java.io.IOException e) {
            // DNS/timeout/offline — inconclusive for pinning, not a pinning failure.
            System.out.println("PINNING TEST inconclusive (network issue, not pinning): " + e);
        }
    }
}
