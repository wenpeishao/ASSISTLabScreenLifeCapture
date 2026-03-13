package com.screenomics;

import android.content.Context;
import android.util.Log;

/**
 * Previously queued app logs as separate encrypted logdata files.
 * Logs are now included in the combined sensordata stream produced by
 * LocationService. These methods are kept as no-ops for call-site safety.
 */
public class LogUploader {

    private static final String TAG = "SCREENOMICS_LOG_UPLOAD";

    /** @deprecated No-op. Logs are now part of the sensordata stream. */
    public static void upload(Context context) {
        Log.d(TAG, "upload() is a no-op; logs are now part of sensordata stream");
    }

    /** @deprecated No-op. Logs are now part of the sensordata stream. */
    public static void uploadSync(Context appContext) {
        Log.d(TAG, "uploadSync() is a no-op; logs are now part of sensordata stream");
    }
}
