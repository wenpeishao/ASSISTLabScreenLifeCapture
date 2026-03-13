package com.screenomics;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.install.model.UpdateAvailability;
import com.google.android.gms.tasks.Tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class DailyReminderWorker extends Worker {
    private static final String TAG = "SCREENOMICS_DAILY";
    private static final String CHANNEL_ID = "mindpulse_daily_reminder";
    private static final String CHANNEL_NAME = "MindPulse Daily Reminders";
    private static final int NOTIFICATION_ID = 4001;
    private static final String WORK_NAME = "daily_reminder_periodic";

    private static final String PREF_LAST_DAILY_REMINDER_MS = "last_daily_reminder_ms";
    private static final String PREF_LAST_UPDATE_NOTIF_CODE = "last_update_notif_version_code";

    private static final long THROTTLE_MS = 20 * 60 * 60 * 1000L; // 20 hours

    public DailyReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);

        // Guard: skip if user not registered
        String key = prefs.getString("key", "");
        if (key.isEmpty()) {
            Log.i(TAG, "User not registered, skipping daily reminder");
            return Result.success();
        }

        // Throttle: skip if last notification was < 20 hours ago
        long lastReminder = prefs.getLong(PREF_LAST_DAILY_REMINDER_MS, 0);
        long now = System.currentTimeMillis();
        if (now - lastReminder < THROTTLE_MS) {
            Log.i(TAG, "Throttled -- last reminder was " + ((now - lastReminder) / 3600000) + "h ago");
            return Result.success();
        }

        boolean recording = prefs.getBoolean("recordingState", false);

        // Check permissions (only meaningful when recording)
        List<String> missingPermissions = new ArrayList<>();
        if (recording) {
            missingPermissions = auditPermissions(ctx, prefs);
        }

        // Check for app update
        boolean updateAvailable = false;
        int availableVersionCode = -1;
        try {
            AppUpdateInfo info = Tasks.await(
                    AppUpdateManagerFactory.create(ctx).getAppUpdateInfo(),
                    10, TimeUnit.SECONDS);
            if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
                availableVersionCode = info.availableVersionCode();
                int lastNotifiedCode = prefs.getInt(PREF_LAST_UPDATE_NOTIF_CODE, -1);
                if (availableVersionCode != lastNotifiedCode) {
                    updateAvailable = true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Update check failed (Play Store unavailable?)", e);
        }

        // Build notification content (priority: permissions > update > generic)
        String title;
        String body;

        if (!missingPermissions.isEmpty()) {
            title = "MindPulse: Permissions Need Attention";
            body = "Missing: " + String.join(", ", missingPermissions) + ". Tap to fix.";
        } else if (updateAvailable) {
            title = "MindPulse Update Available";
            body = "A new version is available. Tap to update.";
            prefs.edit().putInt(PREF_LAST_UPDATE_NOTIF_CODE, availableVersionCode).apply();
        } else if (recording) {
            title = "MindPulse Daily Check";
            body = "Data collection is active. Tap to verify.";
        } else {
            title = "MindPulse is Paused";
            body = "Data collection is not active. Tap to resume.";
        }

        showNotification(ctx, title, body, updateAvailable);
        prefs.edit().putLong(PREF_LAST_DAILY_REMINDER_MS, now).apply();
        Log.i(TAG, "Daily reminder shown: " + title);

        return Result.success();
    }

    private List<String> auditPermissions(Context ctx, SharedPreferences prefs) {
        List<String> missing = new ArrayList<>();

        // Battery optimization
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(ctx.getPackageName())) {
            missing.add("Battery");
        }

        // Accessibility (only if useAccessibilityCapture is true)
        boolean useA11y = prefs.getBoolean("useAccessibilityCapture", false);
        if (useA11y && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!AccessibilityCaptureService.isServiceEnabled(ctx)) {
                missing.add("Accessibility");
            }
        }

        // Location
        boolean fineGranted = ContextCompat.checkSelfPermission(ctx,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted = ContextCompat.checkSelfPermission(ctx,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fineGranted && !coarseGranted) {
            missing.add("Location");
        }

        // Notifications (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(ctx,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                missing.add("Notifications");
            }
        }

        // Usage stats
        AppOpsManager appOps = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), ctx.getPackageName());
        if (mode != AppOpsManager.MODE_ALLOWED) {
            missing.add("Usage Stats");
        }

        // Camera
        if (ContextCompat.checkSelfPermission(ctx,
                Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            missing.add("Camera");
        }

        return missing;
    }

    private void showNotification(Context ctx, String title, String body, boolean isUpdate) {
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(channel);

        Intent tapIntent;
        if (isUpdate) {
            tapIntent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + ctx.getPackageName()));
        } else {
            tapIntent = new Intent(ctx, MainActivity.class);
            tapIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        }
        PendingIntent pi = PendingIntent.getActivity(ctx, NOTIFICATION_ID, tapIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.dna)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();

        nm.notify(NOTIFICATION_ID, notification);
    }

    public static void ensureScheduled(Context context) {
        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
                DailyReminderWorker.class, 24, TimeUnit.HOURS)
                .setInitialDelay(12, TimeUnit.HOURS)
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                work);
        Log.i(TAG, "Daily reminder work ensured");
    }
}
