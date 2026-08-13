package com.screenomics;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.json.JSONObject;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Tells the receiver this device is alive and collecting.
 *
 * The app already builds a full device-state snapshot every 30 minutes, but it
 * goes into the encrypted upload queue alongside the screenshots -- so the
 * answer to "is this participant's phone still working" exists as a file that
 * nobody can query. This sends the operational subset of the same snapshot to
 * an endpoint that staff can read, which is what makes a follow-up list
 * possible.
 *
 * Sent from {@link AutoUploadWorker}, which WorkManager schedules independently
 * of the capture service. That independence is the point: when the accessibility
 * service is disabled or killed, the heartbeat keeps arriving and says so. It is
 * how "capture is broken, contact them" is distinguished from "the phone is off,
 * nothing to do" -- which, with only screenshot arrivals to go on, look
 * identical.
 */
public final class Heartbeat {

    private static final String TAG = "Heartbeat";
    private static final MediaType JSON_TYPE = MediaType.parse("application/json");

    private static final String PREF_LAST_SENT_MS = "hb_last_sent_ms";
    // Watermarks: the counter values already reported. Deltas are computed
    // against these rather than by resetting the counters, so the capture
    // service stays the only writer and nothing is lost to a race.
    private static final String PREF_SENT_ARMED_MS = "hb_sent_armed_total_ms";
    private static final String PREF_SENT_UNLOCKED_MS = "hb_sent_unlocked_total_ms";
    private static final String PREF_SENT_CAPTURES = "hb_sent_captures_total";
    private static final String PREF_SENT_BLANKS = "hb_sent_blank_total";
    private static final String PREF_SENT_STARTS = "hb_sent_service_starts";

    private Heartbeat() {}

    /**
     * Send a heartbeat unless one went out within {@code minIntervalMs}.
     *
     * Blocking; call from a worker thread.
     */
    public static void maybeSend(Context context, long minIntervalMs) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);

        String pptId = prefs.getString("ppt_id", "");
        String studyId = prefs.getString("study_id", "");
        if (pptId.isEmpty() || studyId.isEmpty()) {
            return; // not enrolled yet
        }

        long now = System.currentTimeMillis();
        if (now - prefs.getLong(PREF_LAST_SENT_MS, 0) < minIntervalMs) {
            return;
        }

        try {
            send(app, prefs, pptId, studyId, now);
        } catch (Exception e) {
            // A failed heartbeat is itself a signal: the receiver sees silence
            // and the participant lands on the follow-up list.
            Log.w(TAG, "heartbeat failed", e);
        }
    }

    private static void send(Context app, SharedPreferences prefs,
                             String pptId, String studyId, long now) throws Exception {
        CaptureStats.Totals totals = CaptureStats.read(app);
        JSONObject snapshot = DeviceStateCollector.collectSnapshot(app);
        JSONObject payload = buildPayload(app, prefs, snapshot, totals, now);

        String baseUrl = prefs.getString("base_url", Constants.BASE_URL);
        String url = baseUrl + "/api/v1/device-status/" + pptId;
        String bearerToken = SecureStore.getSecret(app, "enrollment_token", "");

        Request.Builder rb = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("X-Participant-ID", pptId)
                .addHeader("X-Study-ID", studyId)
                .addHeader("X-Request-Nonce", UUID.randomUUID().toString())
                .addHeader("X-Request-Timestamp", iso8601ZuluNow())
                .addHeader("X-Request-Id", "and-hb-" + UUID.randomUUID())
                .post(RequestBody.create(JSON_TYPE, payload.toString()));
        if (!bearerToken.isEmpty()) {
            rb.addHeader("Authorization", "Bearer " + bearerToken);
        }

        OkHttpClient client = HttpClientProvider.get(app);
        try (Response response = client.newCall(rb.build()).execute()) {
            int code = response.code();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "heartbeat rejected: " + code);
                return;
            }
            // Advance the watermarks only once the server has the numbers.
            // Advancing on send would silently drop the interval whenever the
            // POST fails, which is exactly when the device is in trouble and
            // the interval matters most.
            commitWatermarks(prefs, totals, now);
        }
    }

    static JSONObject buildPayload(Context app, SharedPreferences prefs, JSONObject snapshot,
                                   CaptureStats.Totals totals, long now) throws Exception {
        JSONObject battery = snapshot.optJSONObject("battery");
        JSONObject storage = snapshot.optJSONObject("storage");
        JSONObject network = snapshot.optJSONObject("network");
        JSONObject permissions = snapshot.optJSONObject("permissions");
        JSONObject serviceState = snapshot.optJSONObject("service_state");
        JSONObject device = snapshot.optJSONObject("device");
        JSONObject runtime = snapshot.optJSONObject("runtime");

        boolean a11yEnabled = permissions != null
                && permissions.optBoolean("perm_accessibility_enabled", false);

        JSONObject out = new JSONObject();
        out.put("reported_at", iso8601Zulu(new Date(now)));
        out.put("capture_mode", serviceState != null
                ? serviceState.optString("capture_mode", "accessibility") : "accessibility");
        out.put("capture_active", A11yState.isCaptureRunning());
        // The system setting, not the in-process flag: the setting is the thing
        // OEM battery managers and app hibernation actually switch off.
        out.put("a11y_service_enabled", a11yEnabled);
        out.put("permissions_ok", a11yEnabled);

        if (serviceState != null) {
            out.put("consecutive_failures", serviceState.optInt("a11y_consecutive_failures", 0));
            out.put("a11y_last_error", serviceState.optString("a11y_last_error", ""));
            long lastImageTs = serviceState.optLong("a11y_last_image_ts", 0);
            if (lastImageTs > 0) {
                out.put("last_capture_at", iso8601Zulu(new Date(lastImageTs)));
            }
        }

        // Deltas since the last heartbeat the server acknowledged. A counter
        // that went backwards means a reinstall or cleared data, so the current
        // value is the whole of it.
        out.put("active_seconds_since_last_report",
                deltaSeconds(totals.armedMs, prefs.getLong(PREF_SENT_ARMED_MS, 0)));
        out.put("unlocked_seconds_since_last_report",
                deltaSeconds(totals.unlockedMs, prefs.getLong(PREF_SENT_UNLOCKED_MS, 0)));
        out.put("captures_written_since_last_report",
                delta(totals.captures, prefs.getLong(PREF_SENT_CAPTURES, 0)));
        out.put("blank_captures_since_last_report",
                delta(totals.blanks, prefs.getLong(PREF_SENT_BLANKS, 0)));
        out.put("service_restarts_since_last_report",
                delta(totals.serviceStarts, prefs.getInt(PREF_SENT_STARTS, 0)));

        if (battery != null) {
            out.put("battery_level", battery.optInt("battery_level", -1));
            out.put("power_save", battery.optBoolean("power_save_mode", false));
            out.put("battery_optimization_exempt",
                    battery.optBoolean("battery_optimization_exempt", false));
        }
        if (storage != null) {
            out.put("pending_uploads", storage.optInt("pending_upload_count", 0));
            out.put("storage_available_bytes", storage.optLong("storage_available_bytes", -1));
        }
        if (network != null) {
            out.put("network_type", network.optString("network_type", ""));
            out.put("network_metered", network.optBoolean("network_metered", false));
        }
        if (runtime != null) {
            out.put("screen_interactive", runtime.optBoolean("screen_interactive", false));
            out.put("doze_idle", runtime.optBoolean("doze_idle", false));
        }
        if (device != null) {
            out.put("app_version", device.optString("app_version_name", ""));
        }
        return out;
    }

    static void commitWatermarks(SharedPreferences prefs, CaptureStats.Totals totals, long now) {
        prefs.edit()
                .putLong(PREF_LAST_SENT_MS, now)
                .putLong(PREF_SENT_ARMED_MS, totals.armedMs)
                .putLong(PREF_SENT_UNLOCKED_MS, totals.unlockedMs)
                .putLong(PREF_SENT_CAPTURES, totals.captures)
                .putLong(PREF_SENT_BLANKS, totals.blanks)
                .putInt(PREF_SENT_STARTS, totals.serviceStarts)
                .apply();
    }

    private static long delta(long current, long reported) {
        return current >= reported ? current - reported : current;
    }

    private static long deltaSeconds(long currentMs, long reportedMs) {
        return delta(currentMs, reportedMs) / 1000L;
    }

    private static String iso8601ZuluNow() {
        return iso8601Zulu(new Date());
    }

    private static String iso8601Zulu(Date date) {
        DateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(date);
    }
}
