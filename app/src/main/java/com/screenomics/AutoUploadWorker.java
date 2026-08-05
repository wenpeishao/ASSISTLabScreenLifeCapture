package com.screenomics;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class AutoUploadWorker extends Worker {

    public AutoUploadWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        android.util.Log.i("SCREENOMICS_AUTO_UPLOAD", "Auto upload worker triggered");

        // Health check -- runs every time the worker fires (60s chain + 15min safety net).
        // No FGS required; fully Google Play compliant.
        HealthChecker.check(getApplicationContext());

        // Devicestate heartbeat, throttled to 30 min. LocationService normally
        // covers this every 10 min, but never runs when location permission is
        // denied -- this keeps the server-side "alive vs broken" signal flowing
        // for every enrolled participant. Runs on the worker thread (file I/O).
        DeviceStateCollector.maybeQueueSnapshot(getApplicationContext(), 30 * 60_000L);

        // Sensor buffer flushing is handled by LocationService on its own 10-min
        // cycle (flushCombinedBuffer).  Crash recovery of stale JSONL files happens
        // in LocationService.onStartCommand() via flushStaleCombinedBuffer().
        // We do NOT flush here to avoid creating duplicate GPS/appusage files
        // on the 60-second AutoUploadWorker cadence.

        // Delegate to UploadScheduler static method
        UploadScheduler.handleAutoUpload(getApplicationContext());

        return Result.success();
    }
}