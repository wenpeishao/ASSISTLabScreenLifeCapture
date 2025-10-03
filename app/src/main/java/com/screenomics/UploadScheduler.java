package com.screenomics;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import androidx.preference.PreferenceManager;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.io.File;
import java.util.concurrent.TimeUnit;


public class UploadScheduler {

    public static void handleAutoUpload(Context context) {
        // Handle 2-minute automatic upload
        android.util.Log.i("SCREENOMICS_AUTO_UPLOAD", "Auto upload triggered");

        // Check if there are files to upload
        File f_encrypt = new File(context.getExternalFilesDir(null).getAbsolutePath() + File.separator + "encrypt");
        File[] files = f_encrypt.listFiles();
        int fileCount = (files != null) ? files.length : 0;
        android.util.Log.i("SCREENOMICS_AUTO_UPLOAD", "Found " + fileCount + " files to upload in " + f_encrypt.getAbsolutePath());

        if (fileCount == 0) {
            android.util.Log.i("SCREENOMICS_AUTO_UPLOAD", "No files to upload, skipping");
            return;
        }

        // Clear old logs first
        clearOldLogs(context);

        // Check WiFi preference and upload
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean continueWithoutWifi = prefs.getBoolean("continueWithoutWifi", false);

        if (InternetConnection.checkWiFiConnection(context)) {
            android.util.Log.i("SCREENOMICS_AUTO_UPLOAD", "Starting auto upload with WiFi");
            startUpload(context, false);
        } else if (continueWithoutWifi) {
            android.util.Log.i("SCREENOMICS_AUTO_UPLOAD", "Starting auto upload with mobile data");
            startUpload(context, true);
        } else {
            android.util.Log.i("SCREENOMICS_AUTO_UPLOAD", "Skipping auto upload - no WiFi and mobile data disabled");
        }
    }
    
    private static void clearOldLogs(Context context) {
        try {
            // Clear Logger logs
            Logger.reset(context);
            android.util.Log.i("SCREENOMICS_AUTO_UPLOAD", "Old logs cleared");
        } catch (Exception e) {
            android.util.Log.e("SCREENOMICS_AUTO_UPLOAD", "Error clearing logs", e);
        }
    }

    public static void scheduleUploadInSeconds(Context context, int seconds) {
        System.out.printf("SCHEDULING UPLOAD TO RUN IN: %d seconds%n", seconds);
        Logger.i(context, "SCH" + seconds);

        // Use WorkManager for one-time upload scheduling
        androidx.work.OneTimeWorkRequest uploadWork =
            new androidx.work.OneTimeWorkRequest.Builder(AutoUploadWorker.class)
                .setInitialDelay(seconds, TimeUnit.SECONDS)
                .build();

        WorkManager.getInstance(context).enqueue(uploadWork);
    }


    public static void startUpload(Context context, boolean continueWithoutWifi) {
        File f_encrypt = new File(context.getExternalFilesDir(null).getAbsolutePath() + File.separator + "encrypt");
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String participant = prefs.getString("participant", "MISSING_PARTICIPANT_NAME");
        String key = prefs.getString("participantKey", "MISSING_KEY");
        Intent intent = new Intent(context, UploadService.class);
        intent.putExtra("dirPath", f_encrypt.getAbsolutePath());
        intent.putExtra("continueWithoutWifi", continueWithoutWifi);
        intent.putExtra("participant", participant);
        intent.putExtra("key", key);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        }
    }

    // Set up automatic upload every 2 minutes using AlarmManager
    public static void setupAutoUpload(Context context) {
        // Cancel any existing work
        cancelAutoUpload(context);

        // Use AlarmManager for exact 2-minute intervals
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, AutoUploadReceiver.class);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags);

        // Schedule repeating alarm every 2 minutes
        long intervalMillis = 2 * 60 * 1000; // 2 minutes
        long triggerAtMillis = SystemClock.elapsedRealtime() + intervalMillis;

        android.util.Log.i("SCREENOMICS_AUTO_UPLOAD", "Scheduling alarm: interval=" + intervalMillis + "ms, triggerAt=" + triggerAtMillis);

        if (alarmManager != null) {
            // Cancel any existing alarm first
            alarmManager.cancel(pendingIntent);

            // Use exact alarms for precise timing - setExactAndAllowWhileIdle is more reliable
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Android 6+ - use exact alarm that works even in doze mode
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                );
                android.util.Log.i("SCREENOMICS_AUTO_UPLOAD", "Using setExactAndAllowWhileIdle for precise timing");
            } else {
                // Older Android - use regular repeating alarm
                alarmManager.setRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    intervalMillis,
                    pendingIntent
                );
                android.util.Log.i("SCREENOMICS_AUTO_UPLOAD", "Using setRepeating for older Android");
            }
        }

        android.util.Log.i("SCREENOMICS_AUTO_UPLOAD", "Auto upload scheduled every 2 minutes using AlarmManager");
    }

    public static void cancelAutoUpload(Context context) {
        // Cancel AlarmManager
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, AutoUploadReceiver.class);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags);

        if (alarmManager != null) {
            alarmManager.cancel(pendingIntent);
        }

        // Also cancel any existing WorkManager work as fallback
        WorkManager.getInstance(context).cancelUniqueWork("auto_upload_periodic");
        android.util.Log.i("SCREENOMICS_AUTO_UPLOAD", "Auto upload cancelled");
    }

    /**
     * Upload a single file immediately (real-time upload)
     */
    public static void uploadFileImmediately(Context context, String filePath) {
        android.util.Log.i("SCREENOMICS_REALTIME_UPLOAD", "Starting real-time upload for: " + filePath);

        // Check network and upload preference
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean continueWithoutWifi = prefs.getBoolean("continueWithoutWifi", false);

        boolean shouldUpload = false;
        boolean useMobileData = false;

        if (InternetConnection.checkWiFiConnection(context)) {
            shouldUpload = true;
            useMobileData = false;
            android.util.Log.i("SCREENOMICS_REALTIME_UPLOAD", "WiFi available, uploading");
        } else if (continueWithoutWifi) {
            shouldUpload = true;
            useMobileData = true;
            android.util.Log.i("SCREENOMICS_REALTIME_UPLOAD", "Using mobile data for upload");
        } else {
            android.util.Log.i("SCREENOMICS_REALTIME_UPLOAD", "No WiFi and mobile data disabled, skipping upload");
        }

        if (shouldUpload) {
            // Use scheduleUploadInSeconds with a very short delay to upload immediately
            scheduleUploadInSeconds(context, 1); // 1 second delay
        }
    }
}
