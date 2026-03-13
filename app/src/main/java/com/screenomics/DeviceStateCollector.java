package com.screenomics;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.Manifest;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.json.JSONObject;

import java.io.File;

/**
 * Collects a snapshot of device and app state for diagnostics.
 * Each section is wrapped in its own try-catch so one failure
 * does not prevent the rest from populating.
 */
public class DeviceStateCollector {

    private static final String TAG = "DeviceStateCollector";

    public static JSONObject collectSnapshot(Context context) {
        JSONObject root = new JSONObject();
        try { root.put("battery", collectBattery(context)); } catch (Exception e) { Log.w(TAG, "battery", e); }
        try { root.put("storage", collectStorage(context)); } catch (Exception e) { Log.w(TAG, "storage", e); }
        try { root.put("memory", collectMemory(context)); } catch (Exception e) { Log.w(TAG, "memory", e); }
        try { root.put("network", collectNetwork(context)); } catch (Exception e) { Log.w(TAG, "network", e); }
        try { root.put("permissions", collectPermissions(context)); } catch (Exception e) { Log.w(TAG, "permissions", e); }
        try { root.put("service_state", collectServiceState(context)); } catch (Exception e) { Log.w(TAG, "service_state", e); }
        try { root.put("device", collectDevice(context)); } catch (Exception e) { Log.w(TAG, "device", e); }
        try { root.put("runtime", collectRuntime(context)); } catch (Exception e) { Log.w(TAG, "runtime", e); }
        return root;
    }

    private static JSONObject collectBattery(Context context) throws Exception {
        JSONObject obj = new JSONObject();
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, filter);
        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                obj.put("battery_level", (int) ((level / (float) scale) * 100));
            }
            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            obj.put("battery_charging",
                    status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL);
            int plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            String plugType;
            switch (plugged) {
                case BatteryManager.BATTERY_PLUGGED_USB: plugType = "usb"; break;
                case BatteryManager.BATTERY_PLUGGED_AC: plugType = "ac"; break;
                case BatteryManager.BATTERY_PLUGGED_WIRELESS: plugType = "wireless"; break;
                default: plugType = "none"; break;
            }
            obj.put("battery_plugged_type", plugType);
        }
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            obj.put("power_save_mode", pm.isPowerSaveMode());
            obj.put("battery_optimization_exempt",
                    pm.isIgnoringBatteryOptimizations(context.getPackageName()));
        }
        return obj;
    }

    private static JSONObject collectStorage(Context context) throws Exception {
        JSONObject obj = new JSONObject();
        File extDir = context.getExternalFilesDir(null);
        if (extDir != null) {
            StatFs statFs = new StatFs(extDir.getAbsolutePath());
            obj.put("storage_total_bytes", statFs.getTotalBytes());
            obj.put("storage_available_bytes", statFs.getAvailableBytes());

            File encryptDir = new File(extDir, "encrypt");
            int pendingCount = 0;
            long pendingBytes = 0;
            if (encryptDir.exists()) {
                File[] files = encryptDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.isFile() && f.getName().endsWith(".enc")) {
                            pendingCount++;
                            pendingBytes += f.length();
                        }
                    }
                }
            }
            obj.put("pending_upload_count", pendingCount);
            obj.put("pending_upload_bytes", pendingBytes);
        }
        return obj;
    }

    private static JSONObject collectMemory(Context context) throws Exception {
        JSONObject obj = new JSONObject();
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            obj.put("mem_total_bytes", mi.totalMem);
            obj.put("mem_available_bytes", mi.availMem);
            obj.put("mem_low_memory", mi.lowMemory);
        }
        Runtime rt = Runtime.getRuntime();
        obj.put("app_heap_used_bytes", rt.totalMemory() - rt.freeMemory());
        obj.put("app_heap_max_bytes", rt.maxMemory());
        return obj;
    }

    private static JSONObject collectNetwork(Context context) throws Exception {
        JSONObject obj = new JSONObject();
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            Network network = cm.getActiveNetwork();
            if (network != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null) {
                    obj.put("network_connected",
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        obj.put("network_type", "wifi");
                    } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        obj.put("network_type", "cellular");
                    } else {
                        obj.put("network_type", "other");
                    }
                } else {
                    obj.put("network_connected", false);
                    obj.put("network_type", "none");
                }
            } else {
                obj.put("network_connected", false);
                obj.put("network_type", "none");
            }
            obj.put("network_metered", cm.isActiveNetworkMetered());
        }
        return obj;
    }

    private static JSONObject collectPermissions(Context context) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("perm_fine_location",
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED);
        obj.put("perm_coarse_location",
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED);
        obj.put("perm_background_location",
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        == PackageManager.PERMISSION_GRANTED);
        obj.put("perm_camera",
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            obj.put("perm_notifications",
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                            == PackageManager.PERMISSION_GRANTED);
        } else {
            obj.put("perm_notifications", true);
        }
        AppOpsManager appOps =
                (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps != null) {
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), context.getPackageName());
            obj.put("perm_usage_stats", mode == AppOpsManager.MODE_ALLOWED);
        }
        try {
            obj.put("perm_accessibility_enabled",
                    AccessibilityCaptureService.isServiceEnabled(context));
        } catch (Exception e) {
            Log.w(TAG, "accessibility check failed", e);
        }
        return obj;
    }

    private static JSONObject collectServiceState(Context context) throws Exception {
        JSONObject obj = new JSONObject();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        obj.put("recording_state", prefs.getBoolean("recordingState", false));
        boolean useA11y = prefs.getBoolean("useAccessibilityCapture", false);
        obj.put("capture_mode", useA11y ? "accessibility" : "media_projection");
        obj.put("a11y_capture_active", AccessibilityCaptureService.isCaptureRunning());
        obj.put("a11y_last_image_ts", prefs.getLong("a11y_last_image_ts", 0));
        obj.put("a11y_last_error", prefs.getString("a11y_last_error", ""));
        obj.put("a11y_consecutive_failures", prefs.getInt("a11y_consecutive_failures", 0));
        obj.put("last_upload_ms", prefs.getLong("last_non_screenshot_upload_ms", 0));
        return obj;
    }

    private static JSONObject collectDevice(Context context) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("manufacturer", Build.MANUFACTURER);
        obj.put("model", Build.MODEL);
        obj.put("brand", Build.BRAND);
        obj.put("board", Build.BOARD);
        obj.put("hardware", Build.HARDWARE);
        obj.put("fingerprint", Build.FINGERPRINT);
        obj.put("os_version", Build.VERSION.RELEASE);
        obj.put("api_level", Build.VERSION.SDK_INT);
        obj.put("security_patch", Build.VERSION.SECURITY_PATCH);
        try {
            PackageInfo pi = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            obj.put("app_version_name", pi.versionName);
            obj.put("app_version_code", pi.getLongVersionCode());
        } catch (Exception e) {
            Log.w(TAG, "version info", e);
        }
        return obj;
    }

    private static JSONObject collectRuntime(Context context) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("device_uptime_ms", SystemClock.elapsedRealtime());
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            obj.put("screen_interactive", pm.isInteractive());
            obj.put("doze_idle", pm.isDeviceIdleMode());
        }
        return obj;
    }
}
