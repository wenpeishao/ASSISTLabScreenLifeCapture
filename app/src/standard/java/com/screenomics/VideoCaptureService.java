package com.screenomics;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

/**
 * No-op placeholder service used in the standard flavor where the
 * MindPulse recording pipeline is disabled. The manifest can keep
 * referencing this service without pulling in the heavy camera stack.
 */
public class VideoCaptureService extends Service {

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
