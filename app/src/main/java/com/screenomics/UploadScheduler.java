package com.screenomics;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.preference.PreferenceManager;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.io.File;
import java.util.concurrent.TimeUnit;


public class UploadScheduler {
    private static final String TAG = "SCREENOMICS_AUTO_UPLOAD";
    private static final String ONE_TIME_WORK_NAME = "auto_upload_once";
    private static final String PERIODIC_WORK_NAME = "auto_upload_periodic";
    private static final String DAILY_REMINDER_WORK_NAME = "daily_reminder_periodic";
    private static final int UPLOAD_CHAIN_DELAY_SECONDS = 60;

    public static void handleAutoUpload(Context context) {
        // Handle 2-minute automatic upload
        android.util.Log.i(TAG, "Auto upload triggered");

        // Check if there are files to upload
        File extDir = context.getExternalFilesDir(null);
        if (extDir == null) {
            android.util.Log.w(TAG, "External storage unavailable, skipping");
            return;
        }
        File f_encrypt = new File(extDir.getAbsolutePath() + File.separator + "encrypt");
        File[] files = f_encrypt.listFiles();
        int fileCount = (files != null) ? files.length : 0;
        long usableBytes = extDir.getUsableSpace();
        android.util.Log.i(TAG, "Found " + fileCount + " files to upload in " + f_encrypt.getAbsolutePath());
        android.util.Log.i("SCREENOMICS_HEALTH",
                "AUTO_UPLOAD_HEALTH pending_files=" + fileCount
                        + " usable_bytes=" + usableBytes
                        + " net=" + InternetConnection.getState(context));

        if (fileCount == 0) {
            android.util.Log.i(TAG, "No files to upload, skipping upload");
            // Still re-chain so health checks keep firing every 60s
            scheduleNextUploadCycle(context, true);
            return;
        }

        // Check WiFi preference and upload
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean continueWithoutWifi = prefs.getBoolean("continueWithoutWifi", false);

        if (InternetConnection.checkWiFiConnection(context)) {
            android.util.Log.i(TAG, "Starting auto upload with WiFi");
            startUpload(context, false);
        } else if (continueWithoutWifi) {
            android.util.Log.i(TAG, "Starting auto upload with mobile data");
            startUpload(context, true);
        } else {
            android.util.Log.i(TAG, "Skipping auto upload - no WiFi and mobile data disabled");
        }
    }
    
    public static void scheduleUploadInSeconds(Context context, int seconds) {
        System.out.printf("SCHEDULING UPLOAD TO RUN IN: %d seconds%n", seconds);
        Logger.i(context, "SCH" + seconds);

        // Use WorkManager for one-time upload scheduling
        OneTimeWorkRequest uploadWork =
            new OneTimeWorkRequest.Builder(AutoUploadWorker.class)
                .setInitialDelay(seconds, TimeUnit.SECONDS)
                .build();

        WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                uploadWork
        );
    }


    public static void startUpload(Context context, boolean continueWithoutWifi) {
        startUpload(context, continueWithoutWifi, false);
    }

    public static void startUpload(Context context, boolean continueWithoutWifi, boolean forceAll) {
        File extDir = context.getExternalFilesDir(null);
        if (extDir == null) {
            android.util.Log.w(TAG, "External storage unavailable, cannot upload");
            return;
        }
        File f_encrypt = new File(extDir.getAbsolutePath() + File.separator + "encrypt");
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String participant = prefs.getString("participant", "MISSING_PARTICIPANT_NAME");
        String key = prefs.getString("participantKey", "MISSING_KEY");
        Intent intent = new Intent(context, UploadService.class);
        intent.putExtra("dirPath", f_encrypt.getAbsolutePath());
        intent.putExtra("continueWithoutWifi", continueWithoutWifi);
        intent.putExtra("forceAll", forceAll);
        intent.putExtra("participant", participant);
        intent.putExtra("key", key);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        }
    }

    /**
     * Register a 15-minute periodic WorkManager job as a safety net, and kick the
     * first 60-second one-shot upload chain. Call this when capture starts or on boot.
     */
    public static void ensurePeriodicUpload(Context context) {
        android.util.Log.i(TAG, "Ensuring periodic upload safety net (15 min) + first 60s chain");

        PeriodicWorkRequest periodicWork =
                new PeriodicWorkRequest.Builder(AutoUploadWorker.class, 15, TimeUnit.MINUTES)
                        .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWork
        );

        // Kick the first fast upload cycle
        scheduleUploadInSeconds(context, UPLOAD_CHAIN_DELAY_SECONDS);
    }

    /**
     * Called by UploadService when an upload run finishes (success or failure).
     * If capture is still active, chains the next upload in 60 seconds.
     * On failure, backs off to 120 seconds.
     */
    public static void scheduleNextUploadCycle(Context context, boolean lastRunSucceeded) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean recording = prefs.getBoolean("recordingState", false);
        if (!recording) {
            android.util.Log.i(TAG, "Recording inactive, not chaining next upload");
            return;
        }
        int delay = lastRunSucceeded ? UPLOAD_CHAIN_DELAY_SECONDS : UPLOAD_CHAIN_DELAY_SECONDS * 2;
        android.util.Log.i(TAG, "Chaining next upload in " + delay + "s (success=" + lastRunSucceeded + ")");
        scheduleUploadInSeconds(context, delay);
    }

    /**
     * Ensure the daily reminder worker is scheduled.
     * Does NOT get cancelled by cancelAll() -- reminders persist even when capture is off.
     */
    public static void ensureDailyReminder(Context context) {
        DailyReminderWorker.ensureScheduled(context);
    }

    /**
     * Cancel all scheduled uploads. Call when capture stops.
     */
    public static void cancelAll(Context context) {
        android.util.Log.i(TAG, "Cancelling all scheduled uploads");
        WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME_WORK_NAME);
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME);
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
