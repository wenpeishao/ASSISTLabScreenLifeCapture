package com.screenomics;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

/**
 * Flavor facade for the OMI Glass BLE collection feature.
 *
 * mindpulseDev: real implementation backed by {@link GlassBleService}.
 * standard: no-op stub — the feature is not shipped in production.
 */
public final class GlassesFeature {
    private static final String TAG = "GlassesFeature";

    public static final boolean AVAILABLE = true;

    private GlassesFeature() {}

    public static boolean isEnabled(Context ctx) {
        return PreferenceManager.getDefaultSharedPreferences(ctx)
                .getBoolean("glassesEnabled", false);
    }

    public static boolean hasBlePermissions(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        // On API 29-30 BLE scanning requires fine location at runtime.
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Permissions to request before starting the service (SDK-dependent). */
    public static String[] requiredRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    /** Enable the feature and start the foreground service. Caller must hold BLE permissions. */
    public static void start(Context ctx) {
        if (!hasBlePermissions(ctx)) {
            Log.w(TAG, "start requested without BLE permissions; ignoring");
            return;
        }
        PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit().putBoolean("glassesEnabled", true).apply();
        try {
            ctx.startForegroundService(new Intent(ctx, GlassBleService.class));
        } catch (Exception e) {
            Log.e(TAG, "Failed to start GlassBleService", e);
        }
    }

    /** Disable the feature and stop the service. */
    public static void stop(Context ctx) {
        PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit().putBoolean("glassesEnabled", false).apply();
        ctx.stopService(new Intent(ctx, GlassBleService.class));
    }

    /**
     * Start the service only if the feature is enabled, permissions are granted,
     * and it is not already running. Safe to call from onResume/boot without
     * tearing down a live connection.
     */
    public static void ensureStartedIfEnabled(Context ctx) {
        if (!isEnabled(ctx)) return;
        if (!hasBlePermissions(ctx)) {
            Log.w(TAG, "glassesEnabled but BLE permissions missing; not starting");
            return;
        }
        if (GlassBleService.sRunning) return;
        try {
            ctx.startForegroundService(new Intent(ctx, GlassBleService.class));
        } catch (Exception e) {
            Log.e(TAG, "Failed to start GlassBleService", e);
        }
    }

    public static boolean isConnected() { return GlassBleService.sConnected; }
    public static int photoCount() { return GlassBleService.sPhotoCount; }
    public static int batteryPct() { return GlassBleService.sBatteryPct; }
}
