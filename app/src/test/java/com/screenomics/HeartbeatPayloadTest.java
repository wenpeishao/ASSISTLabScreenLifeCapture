package com.screenomics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

/**
 * Tests for the heartbeat payload.
 *
 * The heartbeat reports differences against a watermark rather than resetting
 * the counters, so the failure modes are all about that watermark: losing an
 * interval, counting one twice, or reporting a nonsense value after the app is
 * reinstalled.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class HeartbeatPayloadTest {

    private Context context;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
    }

    /** A device-state snapshot shaped like DeviceStateCollector's. */
    private static JSONObject snapshot(boolean a11yEnabled, int consecutiveFailures) throws Exception {
        JSONObject permissions = new JSONObject();
        permissions.put("perm_accessibility_enabled", a11yEnabled);

        JSONObject serviceState = new JSONObject();
        serviceState.put("capture_mode", "accessibility");
        serviceState.put("a11y_consecutive_failures", consecutiveFailures);
        serviceState.put("a11y_last_error", consecutiveFailures > 0 ? "TAKE_SCREENSHOT_ERROR_3" : "");

        JSONObject battery = new JSONObject();
        battery.put("battery_level", 64);
        battery.put("power_save_mode", false);
        battery.put("battery_optimization_exempt", true);

        JSONObject storage = new JSONObject();
        storage.put("pending_upload_count", 12);
        storage.put("storage_available_bytes", 900_000_000L);

        JSONObject runtime = new JSONObject();
        runtime.put("screen_interactive", false);
        runtime.put("doze_idle", true);

        JSONObject root = new JSONObject();
        root.put("permissions", permissions);
        root.put("service_state", serviceState);
        root.put("battery", battery);
        root.put("storage", storage);
        root.put("runtime", runtime);
        return root;
    }

    private static CaptureStats.Totals totals(long armedMs, long unlockedMs, long captures,
                                              long blanks, int starts) {
        return new CaptureStats.Totals(armedMs, unlockedMs, captures, blanks, starts);
    }

    private JSONObject build(CaptureStats.Totals t, JSONObject snap) throws Exception {
        return Heartbeat.buildPayload(context, prefs, snap, t, System.currentTimeMillis());
    }

    @Test
    public void firstHeartbeatReportsEverythingSoFar() throws Exception {
        JSONObject payload = build(totals(3600_000L, 600_000L, 120, 3, 1), snapshot(true, 0));

        assertEquals(3600L, payload.getLong("active_seconds_since_last_report"));
        assertEquals(600L, payload.getLong("unlocked_seconds_since_last_report"));
        assertEquals(120L, payload.getLong("captures_written_since_last_report"));
        assertEquals(3L, payload.getLong("blank_captures_since_last_report"));
    }

    @Test
    public void laterHeartbeatsReportOnlyTheNewInterval() throws Exception {
        CaptureStats.Totals first = totals(3600_000L, 600_000L, 120, 0, 1);
        Heartbeat.commitWatermarks(prefs, first, System.currentTimeMillis());

        JSONObject payload = build(totals(5400_000L, 900_000L, 180, 0, 1), snapshot(true, 0));

        assertEquals(1800L, payload.getLong("active_seconds_since_last_report"));
        assertEquals(300L, payload.getLong("unlocked_seconds_since_last_report"));
        assertEquals(60L, payload.getLong("captures_written_since_last_report"));
    }

    @Test
    public void aFailedHeartbeatDoesNotLoseTheInterval() throws Exception {
        // The watermark advances only once the server has the numbers. Advancing
        // on send would drop the interval whenever the POST fails -- which is
        // exactly when the device is in trouble and the interval matters.
        CaptureStats.Totals atSend = totals(3600_000L, 600_000L, 120, 0, 1);

        JSONObject attempt = build(atSend, snapshot(true, 0));
        assertEquals(3600L, attempt.getLong("active_seconds_since_last_report"));

        // POST failed: no commit. The next attempt must still carry the hour.
        JSONObject retry = build(totals(4200_000L, 700_000L, 140, 0, 1), snapshot(true, 0));
        assertEquals(4200L, retry.getLong("active_seconds_since_last_report"));
        assertEquals(140L, retry.getLong("captures_written_since_last_report"));
    }

    @Test
    public void anAcknowledgedIntervalIsNotSentTwice() throws Exception {
        CaptureStats.Totals t = totals(3600_000L, 600_000L, 120, 0, 1);
        Heartbeat.commitWatermarks(prefs, t, System.currentTimeMillis());

        JSONObject payload = build(t, snapshot(true, 0));

        assertEquals(0L, payload.getLong("active_seconds_since_last_report"));
        assertEquals(0L, payload.getLong("captures_written_since_last_report"));
    }

    @Test
    public void countersThatWentBackwardsAreTakenAtFaceValue() throws Exception {
        // Reinstall or cleared app data: the counters restart from zero while the
        // watermark remembers the old run. Subtracting would give a negative
        // interval, so the current value is the whole of it.
        Heartbeat.commitWatermarks(prefs, totals(9_000_000L, 800_000L, 500, 0, 4),
                System.currentTimeMillis());

        JSONObject payload = build(totals(60_000L, 30_000L, 5, 0, 1), snapshot(true, 0));

        assertEquals(60L, payload.getLong("active_seconds_since_last_report"));
        assertEquals(5L, payload.getLong("captures_written_since_last_report"));
        assertEquals(1L, payload.getLong("service_restarts_since_last_report"));
    }

    @Test
    public void aDisabledAccessibilityServiceIsReportedAsBroken() throws Exception {
        // The commonest real failure: an OEM battery manager or app hibernation
        // switches the service off and only the participant can switch it back.
        JSONObject payload = build(totals(0, 0, 0, 0, 1), snapshot(false, 0));

        assertFalse(payload.getBoolean("a11y_service_enabled"));
        assertFalse(payload.getBoolean("permissions_ok"));
    }

    @Test
    public void theDiagnosticFieldsStaffNeedAreCarried() throws Exception {
        JSONObject payload = build(totals(0, 0, 0, 0, 1), snapshot(true, 30));

        assertEquals(30, payload.getInt("consecutive_failures"));
        // The takeScreenshot error code, which is what distinguishes a rate-limit
        // retry loop from a genuinely broken capture path.
        assertEquals("TAKE_SCREENSHOT_ERROR_3", payload.getString("a11y_last_error"));
        assertTrue(payload.getBoolean("battery_optimization_exempt"));
        assertTrue(payload.getBoolean("doze_idle"));
        assertEquals(12, payload.getInt("pending_uploads"));
        assertEquals(64, payload.getInt("battery_level"));
    }

    @Test
    public void aMissingSnapshotSectionDoesNotSinkTheHeartbeat() throws Exception {
        // Each DeviceStateCollector section is collected in its own try/catch, so
        // any of them can be absent. A heartbeat that throws is a device that
        // looks dead.
        JSONObject payload = Heartbeat.buildPayload(context, prefs, new JSONObject(),
                totals(120_000L, 60_000L, 12, 0, 1), System.currentTimeMillis());

        assertEquals(120L, payload.getLong("active_seconds_since_last_report"));
        assertFalse(payload.getBoolean("permissions_ok"));
    }
}
