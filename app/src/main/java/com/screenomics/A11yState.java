package com.screenomics;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/**
 * API-safe status and bridge for {@link AccessibilityCaptureService}.
 *
 * The service class carries a class-level {@code @RequiresApi(R)} (its capture
 * path uses API 30 APIs), which made every call to its harmless static helpers
 * a NewApi lint error at API-29-reachable call sites. Everything here uses
 * API-1-era APIs only and is safe to call on any supported device; the service
 * writes the status flags, everyone else reads them through this class.
 */
public final class A11yState {

    private A11yState() {}

    /** Written by AccessibilityCaptureService on connect/destroy. */
    static volatile boolean serviceConnected = false;
    /** Written by AccessibilityCaptureService as capture starts/stops. */
    static volatile boolean captureActive = false;

    /** VLM benchmark hook (mindpulseDev only) — static because an
     *  AccessibilityService cannot be bound. */
    static volatile VlmBenchmark vlmBenchmark;

    public static void setVlmBenchmark(VlmBenchmark benchmark) {
        vlmBenchmark = benchmark;
    }

    public static boolean isCaptureRunning() {
        return serviceConnected && captureActive;
    }

    /** True when the MindPulse accessibility service is enabled in system settings. */
    public static boolean isServiceEnabled(Context context) {
        AccessibilityManager accessibilityManager =
                (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (accessibilityManager == null) return false;
        List<AccessibilityServiceInfo> enabledServices =
                accessibilityManager.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                );
        ComponentName componentName = new ComponentName(context, AccessibilityCaptureService.class);
        String expectedId = componentName.flattenToShortString();
        for (AccessibilityServiceInfo info : enabledServices) {
            if (expectedId.equals(info.getId())) {
                return true;
            }
        }
        return false;
    }

    public static Intent buildAccessibilitySettingsIntent() {
        return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
    }
}
