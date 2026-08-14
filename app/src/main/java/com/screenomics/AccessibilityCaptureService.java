package com.screenomics;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.preference.PreferenceManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.SortedMap;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@RequiresApi(api = Build.VERSION_CODES.R)
public class AccessibilityCaptureService extends AccessibilityService {
    private static final String TAG = "A11yCaptureService";
    private static final long CAPTURE_INTERVAL_MS = 5000L;
    private static final String PREF_USE_ACCESSIBILITY_CAPTURE = "useAccessibilityCapture";
    private static final String PREF_RECORDING_STATE = "recordingState";
    private static final String PREF_A11Y_LAST_IMAGE_TS = "a11y_last_image_ts";
    private static final String PREF_A11Y_LAST_ERROR = "a11y_last_error";
    private static final String PREF_A11Y_CONSEC_FAIL = "a11y_consecutive_failures";
    private static final long MIN_FREE_SPACE_BYTES = 200L * 1024L * 1024L;
    private static final long LOW_STORAGE_LOG_THROTTLE_MS = 60_000L;

    /** Most unlocked time a single tick may credit. Guards against a stalled or
     *  descheduled loop booking a long gap as screen-on time. */
    private static final long MAX_TICK_CREDIT_MS = 15_000L;
    /** Flush the counters to disk about once a minute at the normal cadence. */
    private static final int STATS_FLUSH_EVERY_TICKS = 12;

    // Status flags and the VLM hook live in A11yState so API-29-reachable code
    // can read them without tripping NewApi lint on this @RequiresApi(R) class.

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private volatile boolean captureInFlight = false;
    private boolean captureScheduled = false;
    private long lastLowStorageLogMs = 0L;

    // Unlocked-time accounting. Each tick credits the interval that just ended,
    // scored against the screen state observed at its start.
    private long lastTickElapsed = -1L;
    private boolean lastTickScreenUsable = false;
    private int ticksSinceStatsFlush = 0;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefChangeListener =
            (sharedPreferences, key) -> {
                if (PREF_USE_ACCESSIBILITY_CAPTURE.equals(key) || PREF_RECORDING_STATE.equals(key)) {
                    updateCaptureState();
                }
            };

    private final Runnable captureRunnable = new Runnable() {
        @Override
        public void run() {
            captureScheduled = false;
            if (!shouldCapture()) {
                updateCaptureState();
                return;
            }
            boolean screenUsable = accountTick();
            if (!screenUsable) {
                // Nothing to capture. Poll at the normal cadence rather than
                // every second: five times the wakeups to do nothing, in
                // exchange for noticing an unlock up to 5s later.
                scheduleNextCapture(CAPTURE_INTERVAL_MS);
                return;
            }
            if (!hasEnoughStorageForCapture()) {
                recordCaptureFailure("LOW_STORAGE");
                scheduleNextCapture(10_000L);
                return;
            }
            if (captureInFlight) {
                scheduleNextCapture(1000L);
                return;
            }
            captureInFlight = true;
            takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(@NonNull ScreenshotResult screenshotResult) {
                            Bitmap bitmap = null;
                            HardwareBuffer hardwareBuffer = screenshotResult.getHardwareBuffer();
                            try {
                                ColorSpace colorSpace = screenshotResult.getColorSpace();
                                Bitmap hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace);
                                if (hardwareBitmap == null) {
                                    Log.e(TAG, "takeScreenshot returned a null bitmap");
                                    // Counted: an uncounted failure is one the
                                    // stall detector and the receiver are blind to.
                                    recordCaptureFailure("NULL_BITMAP");
                                    finishCaptureCycle(failureRetryDelayMs());
                                    return;
                                }
                                bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
                                hardwareBitmap.recycle();
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to unwrap screenshot buffer", e);
                                recordCaptureFailure("BUFFER_UNWRAP:" + e.getClass().getSimpleName());
                                finishCaptureCycle(failureRetryDelayMs());
                                return;
                            } finally {
                                hardwareBuffer.close();
                            }

                            Bitmap finalBitmap = bitmap;
                            String foregroundApp = getForegroundApp();
                            Log.d(TAG, "Screenshot captured | foreground_app=" + foregroundApp);

                            // VLM benchmark: submit a copy with context
                            VlmBenchmark vlm = A11yState.vlmBenchmark;
                            if (vlm != null && vlm.isRunning()) {
                                Bitmap copy = finalBitmap.copy(Bitmap.Config.ARGB_8888, false);
                                if (copy != null) vlm.submitFrame(copy, foregroundApp, 0, 0);
                            }

                            ioExecutor.execute(() -> {
                                try {
                                    if (isUniformFrame(finalBitmap)) {
                                        CaptureStats.addBlankCapture();
                                    }
                                    boolean imageSaved = encryptImage(finalBitmap, "image", foregroundApp);
                                    if (imageSaved) {
                                        recordCaptureSuccess();
                                    } else {
                                        recordCaptureFailure("IMAGE_WRITE_OR_ENCRYPT_FAILED");
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "Failed to persist accessibility screenshot", e);
                                    recordCaptureFailure("PERSISTENCE_EXCEPTION:" + e.getClass().getSimpleName());
                                } finally {
                                    finalBitmap.recycle();
                                    finishCaptureCycle(CAPTURE_INTERVAL_MS);
                                }
                            });
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            Log.w(TAG, "takeScreenshot failed with error code: " + errorCode);
                            recordCaptureFailure("TAKE_SCREENSHOT_ERROR_" + errorCode);
                            finishCaptureCycle(failureRetryDelayMs());
                        }
                    }
            );
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener);
        A11yState.serviceConnected = true;
        // Before anything reopens an interval: reconcile one left open by a
        // previous instance that was killed without onDestroy.
        CaptureStats.onServiceStarted(getApplicationContext());
        updateCaptureState();
        Logger.i(getApplicationContext(), "AccessibilityCaptureService connected");
        Log.i(TAG, "Accessibility capture service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopCaptureLoop();
        A11yState.serviceConnected = false;
        A11yState.captureActive = false;
        CaptureStats.onDisarmed(getApplicationContext());
        if (prefs != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener);
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopCaptureLoop();
        A11yState.serviceConnected = false;
        A11yState.captureActive = false;
        CaptureStats.onDisarmed(getApplicationContext());
        if (prefs != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener);
        }
        ioExecutor.shutdownNow();
    }

    // isServiceEnabled / isCaptureRunning / buildAccessibilitySettingsIntent
    // moved to A11yState (API-safe; callable from any API level without lint noise).

    private void updateCaptureState() {
        boolean shouldCapture = shouldCapture();
        A11yState.captureActive = shouldCapture;
        if (shouldCapture) {
            CaptureStats.onArmed(getApplicationContext());
            scheduleNextCapture(500L);
            UploadScheduler.ensurePeriodicUpload(getApplicationContext());
        } else {
            stopCaptureLoop();
            CaptureStats.onDisarmed(getApplicationContext());
        }
    }

    private boolean shouldCapture() {
        if (prefs == null) return false;
        return prefs.getBoolean(PREF_USE_ACCESSIBILITY_CAPTURE, false)
                && prefs.getBoolean(PREF_RECORDING_STATE, false);
    }

    private void stopCaptureLoop() {
        handler.removeCallbacks(captureRunnable);
        captureScheduled = false;
        captureInFlight = false;
        // Drop the tick anchor so the gap while stopped is never credited.
        lastTickElapsed = -1L;
        lastTickScreenUsable = false;
    }

    private void scheduleNextCapture(long delayMs) {
        if (!shouldCapture() || captureScheduled) return;
        captureScheduled = true;
        handler.postDelayed(captureRunnable, delayMs);
    }

    private void finishCaptureCycle(long nextDelayMs) {
        captureInFlight = false;
        handler.post(() -> {
            if (!shouldCapture()) {
                updateCaptureState();
                return;
            }
            scheduleNextCapture(nextDelayMs);
        });
    }

    private boolean isDeviceLocked() {
        KeyguardManager keyguardManager =
                (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        return keyguardManager != null && keyguardManager.isKeyguardLocked();
    }

    /**
     * Whether capture is expected right now: screen on AND unlocked.
     *
     * The gate used to be the keyguard alone, which is not the same thing. On a
     * phone with no lock screen configured isKeyguardLocked() stays false with
     * the display off, so the loop kept calling takeScreenshot() at a dark
     * screen all night. Those failures walked a11y_consecutive_failures up to
     * the stall threshold and woke the participant with an alert about an app
     * that was working perfectly -- and a participant who is told capture is
     * broken tends to switch it off, so the false alarm caused the real outage.
     *
     * Checking interactivity as well also gives us an honest denominator: time
     * the screen was usable is the time capture should have produced something.
     */
    private boolean isScreenUsable() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean interactive = powerManager == null || powerManager.isInteractive();
        return screenIsUsable(interactive, isDeviceLocked());
    }

    /** The gate itself, separated from how the two states are read. */
    static boolean screenIsUsable(boolean interactive, boolean keyguardLocked) {
        return interactive && !keyguardLocked;
    }

    /**
     * Credit the interval that just elapsed and return the current screen state.
     *
     * The interval is scored against the state seen at its start, which is the
     * state that was actually true during it.
     */
    private boolean accountTick() {
        long now = SystemClock.elapsedRealtime();
        boolean usable = isScreenUsable();
        if (lastTickElapsed >= 0 && now > lastTickElapsed && lastTickScreenUsable) {
            CaptureStats.addUnlockedMs(Math.min(now - lastTickElapsed, MAX_TICK_CREDIT_MS));
        }
        lastTickElapsed = now;
        lastTickScreenUsable = usable;
        if (++ticksSinceStatsFlush >= STATS_FLUSH_EVERY_TICKS) {
            ticksSinceStatsFlush = 0;
            CaptureStats.flush(getApplicationContext());
        }
        return usable;
    }

    /**
     * How long to wait after a failed capture.
     *
     * Never below 1.5s: takeScreenshot() is rate-limited by the platform to
     * roughly one call per second, so retrying after exactly 1s can fail with
     * ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -- which is itself a failure,
     * which schedules another 1s retry. That turns one transient error into a
     * self-sustaining streak that walks to the stall threshold on its own.
     * Once failures are clearly not transient, drop to the normal cadence
     * rather than burning battery retrying.
     */
    private long failureRetryDelayMs() {
        int fails = prefs != null ? prefs.getInt(PREF_A11Y_CONSEC_FAIL, 0) : 0;
        return fails >= 5 ? CAPTURE_INTERVAL_MS : 2000L;
    }

    /**
     * True when every sampled pixel is identical, i.e. the frame is one flat
     * colour and carries nothing.
     *
     * Sampled on a grid rather than scanned: this runs on every capture. A
     * single FLAG_SECURE app legitimately produces blank frames, so this is
     * counted and reported, never treated as an error -- but a day that is
     * entirely blank means capture is broken in a way no error code reveals.
     */
    private static boolean isUniformFrame(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width < 8 || height < 8) return false;
        int first = bitmap.getPixel(width / 8, height / 8);
        for (int i = 1; i < 8; i++) {
            for (int j = 1; j < 8; j++) {
                if (bitmap.getPixel(width * i / 8, height * j / 8) != first) return false;
            }
        }
        return true;
    }

    private boolean encryptImage(Bitmap bitmap, String descriptor, String foregroundApp) {
        if (!hasEnoughStorageForCapture()) {
            Log.w(TAG, "Skipping capture due to low storage for descriptor: " + descriptor);
            return false;
        }

        File extDir = getApplicationContext().getExternalFilesDir(null);
        if (extDir == null) {
            Log.e(TAG, "getExternalFilesDir returned null, skipping capture");
            return false;
        }

        File tempFile = new File(extDir.getAbsolutePath(), "tmp_" + UUID.randomUUID() + ".jpg");
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(tempFile);
            boolean compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, 70, fos);
            if (!compressed) {
                Log.e(TAG, "Bitmap compress failed for descriptor: " + descriptor);
                return false;
            }
            fos.close();
            fos = null;
            return encryptAndWriteMeta(tempFile, descriptor, "image/jpeg", "image", new Date(), foregroundApp);
        } catch (Exception e) {
            Log.e(TAG, "Failed to write temp image", e);
            if (tempFile.exists()) tempFile.delete();
            return false;
        } finally {
            try {
                if (fos != null) fos.close();
            } catch (IOException ignored) {
            }
        }
    }

    private boolean encryptAndWriteMeta(
            File tempFile,
            String descriptor,
            String mime,
            String type,
            Date timestamp,
            String foregroundApp
    ) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        String hashFull = sharedPreferences.getString("hash", "00000000");
        String hash = hashFull.substring(0, Math.min(8, hashFull.length()));
        String pubKeyPem = sharedPreferences.getString("image_public_key", "");

        File extDir = getApplicationContext().getExternalFilesDir(null);
        if (extDir == null) {
            Log.e(TAG, "getExternalFilesDir returned null, skipping " + descriptor);
            return false;
        }
        File encryptDir = new File(extDir, "encrypt");
        if (!encryptDir.exists() && !encryptDir.mkdirs()) {
            Log.e(TAG, "Failed to create encrypt directory, skipping " + descriptor);
            return false;
        }
        // Use the same timestamp for filename and metadata so they match
        String baseName = hash + "_" + timestamp.getTime() + "_" + descriptor;

        try {
            if (pubKeyPem == null || pubKeyPem.trim().isEmpty()) {
                Log.w(TAG, "No image_public_key, skipping " + descriptor);
                return false;
            }

            File encFile = new File(encryptDir, baseName + ".enc");
            Encryptor.Result result = Encryptor.encryptFileToEnc(tempFile, encFile, pubKeyPem);

            DateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            isoFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            JSONObject metaObj = new JSONObject();
            metaObj.put("aes_key_encrypted_b64", result.aesKeyEncB64);
            metaObj.put("tag_len_bits", result.tagLenBits);
            metaObj.put("mime", mime);
            metaObj.put("type", type);
            metaObj.put("captured_at", isoFmt.format(timestamp));
            metaObj.put("epoch_ms", timestamp.getTime());
            if (foregroundApp != null && !foregroundApp.isEmpty()) {
                metaObj.put("foreground_app", foregroundApp);
            }

            File metaFile = new File(encryptDir, baseName + ".meta");
            try (FileWriter fw = new FileWriter(metaFile)) {
                fw.write(metaObj.toString());
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Encryption failed for " + descriptor, e);
            return false;
        } finally {
            if (tempFile.exists()) tempFile.delete();
        }
    }

    /** Returns the package name of the current foreground app, or empty string on failure. */
    private String getForegroundApp() {
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) getSystemService(Service.USAGE_STATS_SERVICE);

            // UsageEvents gives us the exact MOVE_TO_FOREGROUND event, not a 24h aggregate.
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - 60_000L, now);
            String foreground = "";
            if (events != null) {
                UsageEvents.Event event = new UsageEvents.Event();
                while (events.hasNextEvent()) {
                    events.getNextEvent(event);
                    if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        foreground = event.getPackageName();
                    }
                }
            }
            if (foreground != null && !foreground.isEmpty()) {
                return foreground;
            }

            // Fallback: INTERVAL_DAILY aggregate (less accurate but better than nothing).
            List<UsageStats> stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    now - TimeUnit.DAYS.toMillis(1),
                    now + TimeUnit.DAYS.toMillis(1));
            if (stats != null && !stats.isEmpty()) {
                SortedMap<Long, UsageStats> sorted = new TreeMap<>();
                for (UsageStats s : stats) sorted.put(s.getLastTimeUsed(), s);
                if (!sorted.isEmpty()) {
                    String pkg = sorted.get(sorted.lastKey()).getPackageName();
                    Log.d(TAG, "getForegroundApp: UsageEvents empty, fell back to UsageStats -> " + pkg);
                    return pkg != null ? pkg : "";
                }
            }

            // Last resort: running processes (only returns own process on modern Android).
            ActivityManager activityManager =
                    (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> procs =
                    activityManager != null ? activityManager.getRunningAppProcesses() : null;
            if (procs != null && !procs.isEmpty()) {
                Log.d(TAG, "getForegroundApp: fell back to RunningAppProcesses");
                return procs.get(0).processName;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get foreground app", e);
        }
        return "";
    }

    private void recordCaptureSuccess() {
        CaptureStats.addCapture();
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        sp.edit()
                .putLong(PREF_A11Y_LAST_IMAGE_TS, System.currentTimeMillis())
                .putString(PREF_A11Y_LAST_ERROR, "")
                .putInt(PREF_A11Y_CONSEC_FAIL, 0)
                .apply();
    }

    private void recordCaptureFailure(String reason) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        int fails = sp.getInt(PREF_A11Y_CONSEC_FAIL, 0) + 1;
        sp.edit()
                .putString(PREF_A11Y_LAST_ERROR, reason)
                .putInt(PREF_A11Y_CONSEC_FAIL, fails)
                .apply();

        // Avoid noisy logs while still preserving periodic diagnostics for upload.
        if (fails == 1 || fails % 20 == 0) {
            Logger.e(getApplicationContext(),
                    "A11Y_CAPTURE_FAIL x" + fails + " reason=" + reason);
        }
    }

    private boolean hasEnoughStorageForCapture() {
        File extDir = getApplicationContext().getExternalFilesDir(null);
        if (extDir == null) {
            Log.e(TAG, "Storage unavailable: external files dir is null");
            return false;
        }
        long usableBytes = extDir.getUsableSpace();
        if (usableBytes >= MIN_FREE_SPACE_BYTES) {
            return true;
        }

        long now = System.currentTimeMillis();
        if (now - lastLowStorageLogMs >= LOW_STORAGE_LOG_THROTTLE_MS) {
            lastLowStorageLogMs = now;
            String msg = "Low storage, skipping capture. usable_bytes=" + usableBytes
                    + " threshold_bytes=" + MIN_FREE_SPACE_BYTES;
            Log.w(TAG, msg);
            Logger.e(getApplicationContext(), "A11Y_LOW_STORAGE " + msg);
        }
        return false;
    }
}
