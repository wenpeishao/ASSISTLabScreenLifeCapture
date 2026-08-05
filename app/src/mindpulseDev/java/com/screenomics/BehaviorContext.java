package com.screenomics;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Accumulates behavioral context over a time window (e.g., 60s).
 * Each capture cycle adds a snapshot. When the window is flushed,
 * it produces a structured temporal prompt for the LLM.
 */
public class BehaviorContext {
    private static final String TAG = "BehaviorContext";

    private final Context appContext;
    private final OcrProcessor ocrProcessor;
    private final List<Snapshot> snapshots = new ArrayList<>();
    private long windowStartMs = 0;
    private long lastScreenOnMs = 0;
    private String lastApp = "";
    private int appSwitchCount = 0;

    public static class Snapshot {
        long timestampMs;
        String timeStr;        // "4:23:15 PM"
        String foregroundApp;  // package name
        String appLabel;       // human-readable app name
        String appCategory;    // "Social", "Game", "Productivity", etc.
        String ocrText;        // extracted screen text (truncated)
        boolean screenOn;
        float batteryPct;
        boolean charging;
        double lat, lon;
        boolean isIdle;        // true if screen was off or locked

        @Override
        public String toString() {
            if (isIdle) {
                return String.format(Locale.US, "[%s] IDLE (screen off)", timeStr);
            }
            String ocrSnippet = ocrText != null && !ocrText.isEmpty()
                ? ocrText.substring(0, Math.min(100, ocrText.length()))
                : "(no text)";
            return String.format(Locale.US, "[%s] %s (%s) | %s",
                timeStr, appLabel, appCategory, ocrSnippet);
        }
    }

    public BehaviorContext(Context context, OcrProcessor ocr) {
        this.appContext = context.getApplicationContext();
        this.ocrProcessor = ocr;
        this.windowStartMs = System.currentTimeMillis();
    }

    /**
     * Add a snapshot from a captured screenshot.
     * Called every 5s from the capture service. Fast (~200ms for OCR).
     */
    public synchronized void addFrame(Bitmap bitmap, String foregroundApp, double lat, double lon) {
        Snapshot snap = new Snapshot();
        snap.timestampMs = System.currentTimeMillis();
        snap.timeStr = new SimpleDateFormat("h:mm:ss a", Locale.US).format(new Date(snap.timestampMs));
        snap.foregroundApp = foregroundApp != null ? foregroundApp : "";
        snap.screenOn = isScreenOn();
        snap.batteryPct = getBatteryPct();
        snap.charging = isCharging();
        snap.lat = lat;
        snap.lon = lon;
        snap.isIdle = !snap.screenOn || snap.foregroundApp.isEmpty();

        // Track app switches
        if (!snap.foregroundApp.equals(lastApp) && !snap.foregroundApp.isEmpty()) {
            appSwitchCount++;
            lastApp = snap.foregroundApp;
        }

        // Resolve app label and category
        snap.appLabel = getAppLabel(snap.foregroundApp);
        snap.appCategory = getAppCategory(snap.foregroundApp);

        // OCR (only if screen is on and we have a bitmap)
        if (!snap.isIdle && bitmap != null) {
            try {
                long t0 = System.currentTimeMillis();
                snap.ocrText = ocrProcessor.extractText(bitmap);
                long ocrMs = System.currentTimeMillis() - t0;
                // Truncate to save memory
                if (snap.ocrText != null && snap.ocrText.length() > 200) {
                    snap.ocrText = snap.ocrText.substring(0, 200);
                }
                Log.d(TAG, String.format(Locale.US, "Snapshot: %s (%s) OCR=%dms %d chars",
                    snap.appLabel, snap.appCategory, ocrMs,
                    snap.ocrText != null ? snap.ocrText.length() : 0));
            } catch (Exception e) {
                snap.ocrText = "";
                Log.w(TAG, "OCR failed for snapshot", e);
            }
        } else {
            snap.ocrText = "";
        }

        // Recycle bitmap after OCR
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }

        snapshots.add(snap);
    }

    /**
     * Add an idle snapshot (screen off, no bitmap).
     */
    public synchronized void addIdleFrame() {
        Snapshot snap = new Snapshot();
        snap.timestampMs = System.currentTimeMillis();
        snap.timeStr = new SimpleDateFormat("h:mm:ss a", Locale.US).format(new Date(snap.timestampMs));
        snap.isIdle = true;
        snap.screenOn = false;
        snap.foregroundApp = "";
        snap.appLabel = "";
        snap.appCategory = "";
        snap.ocrText = "";
        snap.batteryPct = getBatteryPct();
        snap.charging = isCharging();
        snapshots.add(snap);
    }

    /**
     * Check if the window is ready to flush (>= windowDurationMs elapsed).
     */
    public synchronized boolean isWindowReady(long windowDurationMs) {
        return (System.currentTimeMillis() - windowStartMs) >= windowDurationMs;
    }

    /**
     * Flush the window: build the temporal prompt and reset.
     * Returns the prompt string for the LLM, or null if nothing to report.
     */
    public synchronized String flushAndBuildPrompt() {
        if (snapshots.isEmpty()) return null;

        long windowEndMs = System.currentTimeMillis();
        long windowDurationSec = (windowEndMs - windowStartMs) / 1000;

        // Compute summary stats
        int totalFrames = snapshots.size();
        int idleFrames = 0;
        int activeFrames = 0;
        Map<String, Integer> appDuration = new LinkedHashMap<>(); // app → count (each = 5s)
        Map<String, String> appCategories = new LinkedHashMap<>();
        StringBuilder ocrTimeline = new StringBuilder();

        String prevApp = "";
        for (int i = 0; i < snapshots.size(); i++) {
            Snapshot s = snapshots.get(i);
            if (s.isIdle) {
                idleFrames++;
                if (!prevApp.equals("IDLE")) {
                    ocrTimeline.append(String.format("[%s] -- screen off --\n", s.timeStr));
                    prevApp = "IDLE";
                }
            } else {
                activeFrames++;
                appDuration.merge(s.appLabel, 1, Integer::sum);
                appCategories.putIfAbsent(s.appLabel, s.appCategory);

                // Only add OCR line if app changed or text is substantially different
                if (!s.foregroundApp.equals(prevApp) || i == 0) {
                    String snippet = s.ocrText != null && !s.ocrText.isEmpty()
                        ? s.ocrText.replace("\n", " ").trim() : "(no text)";
                    if (snippet.length() > 80) snippet = snippet.substring(0, 80) + "...";
                    ocrTimeline.append(String.format("[%s] %s (%s): %s\n",
                        s.timeStr, s.appLabel, s.appCategory, snippet));
                }
                prevApp = s.foregroundApp;
            }
        }

        // Build app usage summary
        StringBuilder appSummary = new StringBuilder();
        for (Map.Entry<String, Integer> entry : appDuration.entrySet()) {
            int secs = entry.getValue() * 5;
            String cat = appCategories.getOrDefault(entry.getKey(), "Unknown");
            appSummary.append(String.format("  %s (%s): %ds\n", entry.getKey(), cat, secs));
        }

        // Time context
        String timeOfDay = getTimeOfDay();
        String dayOfWeek = new SimpleDateFormat("EEEE", Locale.US).format(new Date());
        Snapshot first = snapshots.get(0);
        Snapshot last = snapshots.get(snapshots.size() - 1);

        // Location summary
        String locationStr = "unknown";
        for (Snapshot s : snapshots) {
            if (s.lat != 0 || s.lon != 0) {
                locationStr = String.format(Locale.US, "%.4f, %.4f", s.lat, s.lon);
                break;
            }
        }

        // Build prompt
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are analyzing a person's phone usage over the last ")
              .append(windowDurationSec).append(" seconds.\n\n");

        prompt.append("== Context ==\n");
        prompt.append("Time: ").append(dayOfWeek).append(" ").append(timeOfDay)
              .append(" (").append(first.timeStr).append(" - ").append(last.timeStr).append(")\n");
        prompt.append("Location: ").append(locationStr).append("\n");
        prompt.append("Battery: ").append(String.format("%.0f%%", last.batteryPct));
        if (last.charging) prompt.append(" (charging)");
        prompt.append("\n");
        prompt.append("Screen: active ").append(activeFrames * 5).append("s, idle ")
              .append(idleFrames * 5).append("s of ").append(windowDurationSec).append("s window\n");
        prompt.append("App switches: ").append(appSwitchCount).append("\n\n");

        prompt.append("== App Usage ==\n");
        prompt.append(appSummary);
        prompt.append("\n");

        prompt.append("== Screen Timeline ==\n");
        prompt.append(ocrTimeline);
        prompt.append("\n");

        prompt.append("== Task ==\n");
        prompt.append("Write a 2-3 sentence behavioral observation describing what this person was doing, ");
        prompt.append("their engagement level, and any notable patterns. Be specific and concise.\n\n");
        prompt.append("Observation:");

        // Reset window
        int promptTokenEstimate = prompt.length() / 4; // rough estimate
        Log.i(TAG, String.format(Locale.US,
            "Window flushed: %ds, %d frames (%d active, %d idle), %d switches, ~%d prompt tokens",
            windowDurationSec, totalFrames, activeFrames, idleFrames, appSwitchCount, promptTokenEstimate));

        snapshots.clear();
        windowStartMs = System.currentTimeMillis();
        appSwitchCount = 0;

        return prompt.toString();
    }

    public synchronized int getSnapshotCount() { return snapshots.size(); }

    // --- Helpers ---

    private String getAppLabel(String packageName) {
        if (packageName == null || packageName.isEmpty()) return "";
        try {
            PackageManager pm = appContext.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            // Return last component of package name
            int dot = packageName.lastIndexOf('.');
            return dot >= 0 ? packageName.substring(dot + 1) : packageName;
        }
    }

    private String getAppCategory(String packageName) {
        if (packageName == null || packageName.isEmpty()) return "Unknown";
        try {
            PackageManager pm = appContext.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            int cat = ai.category;
            switch (cat) {
                case ApplicationInfo.CATEGORY_GAME: return "Game";
                case ApplicationInfo.CATEGORY_AUDIO: return "Audio/Music";
                case ApplicationInfo.CATEGORY_VIDEO: return "Video";
                case ApplicationInfo.CATEGORY_IMAGE: return "Image/Photo";
                case ApplicationInfo.CATEGORY_SOCIAL: return "Social";
                case ApplicationInfo.CATEGORY_NEWS: return "News";
                case ApplicationInfo.CATEGORY_MAPS: return "Maps/Navigation";
                case ApplicationInfo.CATEGORY_PRODUCTIVITY: return "Productivity";
                case ApplicationInfo.CATEGORY_ACCESSIBILITY: return "Accessibility";
                default: return "Other";
            }
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private String getTimeOfDay() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if (hour < 6) return "late night";
        if (hour < 12) return "morning";
        if (hour < 17) return "afternoon";
        if (hour < 21) return "evening";
        return "night";
    }

    private boolean isScreenOn() {
        try {
            PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Exception e) { return true; }
    }

    private float getBatteryPct() {
        try {
            BatteryManager bm = (BatteryManager) appContext.getSystemService(Context.BATTERY_SERVICE);
            return bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
        } catch (Exception e) { return -1; }
    }

    private boolean isCharging() {
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent b = appContext.registerReceiver(null, f);
            if (b == null) return false;
            int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        } catch (Exception e) { return false; }
    }
}
