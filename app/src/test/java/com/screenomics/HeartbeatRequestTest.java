package com.screenomics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.security.KeyPairGenerator;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * What the app actually puts on the wire.
 *
 * The receiver side of this is proven end-to-end by ops/e2e_heartbeat_test.py,
 * which signs the way this client signs. That leaves one question those tests
 * cannot answer -- whether this client really sends what that test assumed --
 * which is what this covers: the URL, the body, and the rule that a rejected
 * heartbeat must be retried rather than silently dropped.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class HeartbeatRequestTest {

    private static final String PPT_ID = "ppt-e2e-0001";

    private Context context;
    private SharedPreferences prefs;
    private MockWebServer server;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();

        server = new MockWebServer();
        server.start();

        // The signing key the interceptor looks for. Without it every request
        // fails to sign and nothing reaches the server.
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        FakeAndroidKeyStore.install("mindpulse_client_key", generator.generateKeyPair().getPrivate());

        prefs.edit()
                .putBoolean("useAccessibilityCapture", true)
                .putBoolean("recordingState", true)
                .putString("ppt_id", PPT_ID)
                .putString("study_id", "7")
                .putString("base_url", server.url("").toString().replaceAll("/$", ""))
                .commit();
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    private RecordedRequest sendAndTake(int responseCode) throws Exception {
        server.enqueue(new MockResponse().setResponseCode(responseCode)
                .setBody("{\"status\":\"recorded\"}"));
        Heartbeat.maybeSend(context, 0);
        return server.takeRequest();
    }

    @Test
    public void postsToTheParticipantsDeviceStatusEndpoint() throws Exception {
        RecordedRequest request = sendAndTake(200);

        assertEquals("POST", request.getMethod());
        assertEquals("/api/v1/device-status/" + PPT_ID, request.getPath());
        assertEquals(PPT_ID, request.getHeader("X-Participant-ID"));
        assertEquals("7", request.getHeader("X-Study-ID"));
    }

    @Test
    public void theRequestIsSigned() throws Exception {
        RecordedRequest request = sendAndTake(200);

        // Same headers the receiver's require_valid_signature() demands.
        assertNotNull("Signature", request.getHeader("Signature"));
        assertNotNull("Digest", request.getHeader("Digest"));
        assertNotNull("Date", request.getHeader("Date"));
        assertNotNull("X-Request-Nonce", request.getHeader("X-Request-Nonce"));
        assertNotNull("X-Request-Timestamp", request.getHeader("X-Request-Timestamp"));
        assertTrue(request.getHeader("Signature").contains("keyId=\"" + PPT_ID + "\""));
        assertTrue(request.getHeader("Signature").contains(
                "headers=\"(request-target) date digest x-request-nonce x-request-timestamp\""));
    }

    @Test
    public void theBodyCarriesTheFieldsTheReceiverReads() throws Exception {
        RecordedRequest request = sendAndTake(200);
        JSONObject body = new JSONObject(request.getBody().readUtf8());

        for (String field : new String[]{
                "capture_active", "permissions_ok", "a11y_service_enabled", "capture_mode",
                "reported_at", "active_seconds_since_last_report",
                "unlocked_seconds_since_last_report", "captures_written_since_last_report",
                "blank_captures_since_last_report", "service_restarts_since_last_report"}) {
            assertTrue("missing " + field, body.has(field));
        }
        assertEquals("accessibility", body.getString("capture_mode"));
    }

    @Test
    public void aRejectedHeartbeatIsRetriedWithTheSameInterval() throws Exception {
        // The interval belongs to the app until the server confirms it. Advancing
        // the watermark on send would throw the interval away exactly when the
        // device is having trouble.
        CaptureStats.resetPendingForTest();
        CaptureStats.onServiceStarted(context);
        CaptureStats.addUnlockedMs(60_000L);
        CaptureStats.addCapture();
        CaptureStats.flush(context);

        RecordedRequest rejected = sendAndTake(500);
        JSONObject first = new JSONObject(rejected.getBody().readUtf8());
        assertEquals(60L, first.getLong("unlocked_seconds_since_last_report"));

        prefs.edit().putLong("hb_last_sent_ms", 0).commit();   // clear the throttle
        RecordedRequest retry = sendAndTake(200);
        JSONObject second = new JSONObject(retry.getBody().readUtf8());
        assertEquals("the rejected interval was dropped",
                60L, second.getLong("unlocked_seconds_since_last_report"));
    }

    @Test
    public void anAcknowledgedIntervalIsNotSentAgain() throws Exception {
        CaptureStats.resetPendingForTest();
        CaptureStats.onServiceStarted(context);
        CaptureStats.addUnlockedMs(60_000L);
        CaptureStats.flush(context);

        sendAndTake(200);

        prefs.edit().putLong("hb_last_sent_ms", 0).commit();
        RecordedRequest second = sendAndTake(200);
        JSONObject body = new JSONObject(second.getBody().readUtf8());

        assertEquals(0L, body.getLong("unlocked_seconds_since_last_report"));
    }

    @Test
    public void anUnenrolledDeviceSendsNothing() throws Exception {
        prefs.edit().remove("ppt_id").commit();

        Heartbeat.maybeSend(context, 0);

        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void theThrottleHoldsBetweenBeats() throws Exception {
        sendAndTake(200);

        Heartbeat.maybeSend(context, 15 * 60_000L);   // too soon

        assertEquals(1, server.getRequestCount());
    }
}
