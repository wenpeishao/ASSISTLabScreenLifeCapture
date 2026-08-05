package com.screenomics;

import android.content.Context;

/**
 * Flavor facade for the OMI Glass BLE collection feature.
 *
 * standard (production): no-op stub — the feature is not shipped.
 * See the mindpulseDev source set for the real implementation.
 */
public final class GlassesFeature {

    public static final boolean AVAILABLE = false;

    private GlassesFeature() {}

    public static boolean isEnabled(Context ctx) { return false; }

    public static boolean hasBlePermissions(Context ctx) { return false; }

    public static String[] requiredRuntimePermissions() { return new String[0]; }

    public static void start(Context ctx) { }

    public static void stop(Context ctx) { }

    public static void ensureStartedIfEnabled(Context ctx) { }

    public static boolean isConnected() { return false; }
    public static int photoCount() { return 0; }
    public static int batteryPct() { return -1; }
}
