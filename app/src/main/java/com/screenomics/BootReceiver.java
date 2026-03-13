package com.screenomics;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.preference.PreferenceManager;

/**
 * Restarts capture and location services after device reboot.
 *
 * This is critical for 24/7 operation: without it, a reboot silently kills all
 * foreground services and the user must manually re-enable capture.
 *
 * For accessibility capture (Android 11+), the AccessibilityService is restarted
 * by the system automatically. We only need to ensure the recordingState pref is
 * still set so the service resumes capture on its next onServiceConnected().
 *
 * For MediaProjection capture (Android 10), we cannot restart automatically because
 * the user must re-grant screen recording permission interactively. We show a
 * notification prompting them to open the app.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "SCREENOMICS_BOOT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        Log.i(TAG, "Boot/package-replace received: " + action);

        // Daily reminder must survive reboots regardless of capture state
        DailyReminderWorker.ensureScheduled(context);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean wasRecording = prefs.getBoolean("recordingState", false);

        if (!wasRecording) {
            Log.i(TAG, "Recording was not active before reboot, nothing to restart");
            return;
        }

        Log.i(TAG, "Recording was active before reboot, attempting to restart services");
        Logger.i(context, "Device rebooted, restarting services");

        // Restart location service (works for both capture modes)
        try {
            Intent locationIntent = new Intent(context, LocationService.class);
            context.startForegroundService(locationIntent);
            Log.i(TAG, "LocationService restart requested");
        } catch (Exception e) {
            Log.e(TAG, "Failed to restart LocationService", e);
        }

        // For accessibility capture mode (Android 11+), the AccessibilityService
        // restarts automatically by the system. The recordingState pref is already
        // true, so AccessibilityCaptureService.shouldCapture() will return true
        // when onServiceConnected() fires.
        boolean useAccessibility = prefs.getBoolean("useAccessibilityCapture", false);
        if (useAccessibility && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Log.i(TAG, "Accessibility capture mode -- system will restart the service");
            UploadScheduler.ensurePeriodicUpload(context);

            // On package update, always warn -- isServiceEnabled() is unreliable
            // right after MY_PACKAGE_REPLACED (system hasn't updated the list yet).
            // The periodic health check in LocationService will dismiss this
            // notification if the service recovers on its own.
            if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
                Log.w(TAG, "Package updated in a11y mode -- firing permission-lost notification");
                showAccessibilityLostNotification(context);
            }
            return;
        }

        // For MediaProjection mode, we cannot restart without user interaction.
        // Show a notification so the user knows to re-enable capture.
        Log.w(TAG, "MediaProjection mode -- user must re-grant screen recording permission");
        showRestartNotification(context);
    }

    private void showAccessibilityLostNotification(Context context) {
        try {
            String channelId = "screenomics_alert_id";
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    channelId,
                    "MindPulse Alerts",
                    android.app.NotificationManager.IMPORTANCE_HIGH
            );
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);

            Intent settingsIntent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = android.app.PendingIntent.FLAG_IMMUTABLE
                    | android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            android.app.PendingIntent pi =
                    android.app.PendingIntent.getActivity(context, 3001, settingsIntent, flags);

            android.app.Notification notification = new android.app.Notification.Builder(context, channelId)
                    .setSmallIcon(R.drawable.dna)
                    .setContentTitle("Accessibility Permission Lost")
                    .setContentText("Tap to re-enable MindPulse in Accessibility Settings")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();

            nm.notify(3001, notification);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show accessibility lost notification", e);
        }
    }

    private void showRestartNotification(Context context) {
        try {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    "boot_restart_channel",
                    "MindPulse Restart Alerts",
                    android.app.NotificationManager.IMPORTANCE_HIGH
            );
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);

            Intent launchIntent = new Intent(context, MainActivity.class);
            launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            launchIntent.putExtra("start_video_recording", true);

            int flags = android.app.PendingIntent.FLAG_IMMUTABLE
                    | android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            android.app.PendingIntent pendingIntent =
                    android.app.PendingIntent.getActivity(context, 0, launchIntent, flags);

            android.app.Notification notification = new android.app.Notification.Builder(context, "boot_restart_channel")
                    .setSmallIcon(R.drawable.dna)
                    .setContentTitle("MindPulse needs restart")
                    .setContentText("Your device rebooted. Tap to restart screen capture.")
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build();

            nm.notify(200, notification);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show restart notification", e);
        }
    }
}
