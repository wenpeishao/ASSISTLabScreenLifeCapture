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

        // Delegate to UploadScheduler static method
        UploadScheduler.handleAutoUpload(getApplicationContext());

        return Result.success();
    }
}