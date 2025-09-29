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
        Log.i(TAG, "Auto upload alarm triggered");

        // Delegate to UploadScheduler to handle the upload
        UploadScheduler.handleAutoUpload(context);
    }
}