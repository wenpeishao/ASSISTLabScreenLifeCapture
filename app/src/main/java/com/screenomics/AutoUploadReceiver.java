package com.screenomics;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * BroadcastReceiver that handles periodic auto-upload alarms
 */
public class AutoUploadReceiver extends BroadcastReceiver {
    private static final String TAG = "AutoUploadReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        long currentTime = System.currentTimeMillis();
        Log.i(TAG, "Auto upload alarm triggered at: " + currentTime);

        // Delegate to UploadScheduler to handle the upload
        UploadScheduler.handleAutoUpload(context);

        // Reschedule the next alarm for continuous 2-minute intervals
        // This is needed because setExactAndAllowWhileIdle only triggers once
        UploadScheduler.setupAutoUpload(context);
        Log.i(TAG, "Next upload scheduled for 2 minutes from now");
    }
}