package com.screenomics;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

/**
 * Stateless health-check logic shared by LocationService (1-min handler)
 * and AutoUploadWorker (WorkManager safety net). No FGS required.
 */
public final class HealthChecker {

    private static final String TAG = "HealthChecker";
    private static final String ALERT_CHANNEL_ID = "screenomics_alert_id";

    static final int NOTIF_ID_A11Y_LOST = 3001;
    static final int NOTIF_ID_CAPTURE_STALL = 3002;
    static final int NOTIF_ID_UPLOAD_BACKLOG = 3003;

    /** Consecutive screenshot failures before alerting. Failures only accumulate
     *  during unlocked capture attempts (the loop skips while the keyguard is up),
     *  so overnight non-use can never trip this. ~30 = about a minute of solid
     *  failures at the 1s retry cadence. */
    private static final int STALL_CONSEC_FAILURES = 30;
    /** Backlog thresholds for the participant-facing upload alert. */
    private static final int BACKLOG_ALERT_COUNT = 2000;
    private static final long LOW_SPACE_ALERT_BYTES = 500L * 1024 * 1024;

    private HealthChecker() {}

    /**
     * Run the health check. Safe to call from any thread / any component.
     */
    public static void check(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean recording = prefs.getBoolean("recordingState", false);
        boolean useA11y = prefs.getBoolean("useAccessibilityCapture", false);

        if (!recording || !useA11y) {
            dismissAll(context);
            return;
        }

        ensureAlertChannel(context);
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        boolean a11yEnabled = AccessibilityCaptureService.isServiceEnabled(context);

        // Accessibility permission lost
        if (!a11yEnabled) {
            showA11yLostNotification(context, nm);
        } else {
            nm.cancel(NOTIF_ID_A11Y_LOST);
        }

        // Capture stalled: permission is fine but screenshots keep failing
        // (persistent takeScreenshot errors, storage problems, ...)
        int consecFailures = prefs.getInt("a11y_consecutive_failures", 0);
        if (a11yEnabled && consecFailures >= STALL_CONSEC_FAILURES) {
            showCaptureStallNotification(context, nm);
        } else {
            nm.cancel(NOTIF_ID_CAPTURE_STALL);
        }
    }

    /**
     * Raise or clear the participant-facing upload-backlog / low-storage alert.
     * Called from the AutoUploadWorker heartbeat (background thread) with values
     * from the devicestate snapshot; not from the 1-minute main-thread check.
     */
    public static void notifyUploadBacklog(Context context, int pendingCount, long availableBytes) {
        ensureAlertChannel(context);
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        boolean backlog = pendingCount >= BACKLOG_ALERT_COUNT;
        boolean lowSpace = availableBytes >= 0 && availableBytes < LOW_SPACE_ALERT_BYTES;
        if (!backlog && !lowSpace) {
            nm.cancel(NOTIF_ID_UPLOAD_BACKLOG);
            return;
        }
        String text = lowSpace
                ? "Phone storage is almost full — connect to Wi-Fi so MindPulse can upload and free space."
                : pendingCount + " files are waiting to upload — please connect to Wi-Fi.";
        nm.notify(NOTIF_ID_UPLOAD_BACKLOG, buildOpenAppNotification(context,
                NOTIF_ID_UPLOAD_BACKLOG, "MindPulse needs to upload", text));
        Log.w(TAG, "Upload backlog alert: pending=" + pendingCount + " availableBytes=" + availableBytes);
    }

    public static void dismissAll(Context context) {
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(NOTIF_ID_A11Y_LOST);
        nm.cancel(NOTIF_ID_CAPTURE_STALL);
        nm.cancel(NOTIF_ID_UPLOAD_BACKLOG);
    }

    private static void ensureAlertChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "MindPulse Alerts",
                    NotificationManager.IMPORTANCE_HIGH
            );
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
    }

    private static void showA11yLostNotification(Context context, NotificationManager nm) {
        Intent settingsIntent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(
                context, NOTIF_ID_A11Y_LOST, settingsIntent, flags);

        Notification n = new NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.dna)
                .setContentTitle("Accessibility Permission Lost")
                .setContentText("Tap to re-enable MindPulse in Accessibility Settings")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        nm.notify(NOTIF_ID_A11Y_LOST, n);
        Log.w(TAG, "Health check: accessibility service not enabled");
    }

    private static void showCaptureStallNotification(Context context, NotificationManager nm) {
        nm.notify(NOTIF_ID_CAPTURE_STALL, buildOpenAppNotification(context,
                NOTIF_ID_CAPTURE_STALL,
                "MindPulse capture needs attention",
                "Screen capture keeps failing. Tap to open MindPulse and toggle capture off and on."));
        Log.w(TAG, "Health check: capture stalled (consecutive screenshot failures)");
    }

    private static Notification buildOpenAppNotification(
            Context context, int requestCode, String title, String text) {
        Intent appIntent = new Intent(context, MainActivity.class);
        appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(context, requestCode, appIntent, flags);
        return new NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.dna)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
    }

}
