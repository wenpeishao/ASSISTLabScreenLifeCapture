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

        // Only check: accessibility permission lost
        if (!a11yEnabled) {
            showA11yLostNotification(context, nm);
        } else {
            nm.cancel(NOTIF_ID_A11Y_LOST);
        }
    }

    public static void dismissAll(Context context) {
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(NOTIF_ID_A11Y_LOST);
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

}
