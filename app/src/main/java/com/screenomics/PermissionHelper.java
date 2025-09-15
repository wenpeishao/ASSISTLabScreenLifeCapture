package com.screenomics;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PermissionHelper {

    // Permission request codes
    public static final int PERMISSION_REQUEST_CAMERA = 1001;
    public static final int PERMISSION_REQUEST_LOCATION = 1002;
    public static final int PERMISSION_REQUEST_NOTIFICATION = 1003;
    public static final int PERMISSION_REQUEST_STORAGE = 1004;
    public static final int PERMISSION_REQUEST_PHONE_STATE = 1005;
    public static final int PERMISSION_REQUEST_ALL = 1006;

    // Permission groups for the app
    public static final String[] LOCATION_PERMISSIONS = {
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION
    };

    public static final String[] STORAGE_PERMISSIONS = {
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    public static final String[] CAMERA_PERMISSIONS = {
        Manifest.permission.CAMERA
    };

    // Permission explanations
    private static final Map<String, PermissionInfo> PERMISSION_INFO = new HashMap<>();

    static {
        PERMISSION_INFO.put(Manifest.permission.CAMERA, new PermissionInfo(
            "Camera Permission",
            "Camera access is needed to scan QR codes for registration and optional video recording for research purposes.",
            "The camera will only be used when explicitly enabled by you."
        ));

        PERMISSION_INFO.put(Manifest.permission.ACCESS_FINE_LOCATION, new PermissionInfo(
            "Location Permission",
            "Location access helps researchers understand context and movement patterns as part of the study.",
            "Location data is encrypted and only collected when the app is actively recording."
        ));

        PERMISSION_INFO.put(Manifest.permission.ACCESS_BACKGROUND_LOCATION, new PermissionInfo(
            "Background Location Permission",
            "Background location allows the app to track location even when not in the foreground, providing complete research data.",
            "You can disable this anytime from app settings."
        ));

        PERMISSION_INFO.put(Manifest.permission.POST_NOTIFICATIONS, new PermissionInfo(
            "Notification Permission",
            "Notifications keep you informed about app status, upload progress, and important study updates.",
            "You can customize notification preferences in settings."
        ));

        PERMISSION_INFO.put(Manifest.permission.READ_EXTERNAL_STORAGE, new PermissionInfo(
            "Storage Permission",
            "Storage access is required to save encrypted screenshots and data for the research study.",
            "All data is encrypted before storage for your privacy."
        ));

        PERMISSION_INFO.put(Manifest.permission.READ_PHONE_STATE, new PermissionInfo(
            "Phone State Permission",
            "Phone state access helps track app usage context for research purposes.",
            "No personal calls or messages are accessed."
        ));
    }

    public static class PermissionInfo {
        public final String title;
        public final String description;
        public final String additionalInfo;

        PermissionInfo(String title, String description, String additionalInfo) {
            this.title = title;
            this.description = description;
            this.additionalInfo = additionalInfo;
        }
    }

    public interface PermissionCallback {
        void onPermissionGranted();
        void onPermissionDenied();
        void onPermissionPermanentlyDenied();
    }

    /**
     * Check if a permission is granted
     */
    public static boolean hasPermission(Context context, String permission) {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Check if all permissions in array are granted
     */
    public static boolean hasAllPermissions(Context context, String[] permissions) {
        for (String permission : permissions) {
            if (!hasPermission(context, permission)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get list of missing permissions from array
     */
    public static String[] getMissingPermissions(Context context, String[] permissions) {
        List<String> missingPermissions = new ArrayList<>();
        for (String permission : permissions) {
            if (!hasPermission(context, permission)) {
                missingPermissions.add(permission);
            }
        }
        return missingPermissions.toArray(new String[0]);
    }

    /**
     * Request single permission with explanation dialog
     */
    public static void requestPermissionWithExplanation(
            Activity activity,
            String permission,
            int requestCode,
            PermissionCallback callback) {

        if (hasPermission(activity, permission)) {
            if (callback != null) callback.onPermissionGranted();
            return;
        }

        PermissionInfo info = PERMISSION_INFO.get(permission);
        if (info == null) {
            // No explanation available, request directly
            ActivityCompat.requestPermissions(activity, new String[]{permission}, requestCode);
            return;
        }

        if (ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
            // User has denied permission before, show rationale
            showRationaleDialog(activity, info, permission, requestCode, callback);
        } else {
            // First time asking or "Don't ask again" was selected
            showExplanationDialog(activity, info, permission, requestCode, callback);
        }
    }

    /**
     * Request multiple permissions with explanation
     */
    public static void requestPermissionsWithExplanation(
            Activity activity,
            String[] permissions,
            int requestCode) {

        String[] missingPermissions = getMissingPermissions(activity, permissions);

        if (missingPermissions.length == 0) {
            return; // All permissions granted
        }

        // Build explanation for all missing permissions
        StringBuilder explanationBuilder = new StringBuilder();
        explanationBuilder.append("This app requires the following permissions to function properly:\n\n");

        for (String permission : missingPermissions) {
            PermissionInfo info = PERMISSION_INFO.get(permission);
            if (info != null) {
                explanationBuilder.append("• ").append(info.title).append("\n");
                explanationBuilder.append("  ").append(info.description).append("\n\n");
            }
        }

        new AlertDialog.Builder(activity)
            .setTitle("Permissions Required")
            .setMessage(explanationBuilder.toString())
            .setIcon(android.R.drawable.ic_dialog_info)
            .setPositiveButton("Grant Permissions", (dialog, which) -> {
                ActivityCompat.requestPermissions(activity, missingPermissions, requestCode);
            })
            .setNegativeButton("Cancel", (dialog, which) -> {
                dialog.dismiss();
            })
            .setCancelable(false)
            .show();
    }

    private static void showExplanationDialog(
            Activity activity,
            PermissionInfo info,
            String permission,
            int requestCode,
            PermissionCallback callback) {

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(info.title + " Required");
        builder.setMessage(info.description + "\n\n" + info.additionalInfo);
        builder.setIcon(android.R.drawable.ic_dialog_info);

        builder.setPositiveButton("Grant Permission", (dialog, which) -> {
            ActivityCompat.requestPermissions(activity, new String[]{permission}, requestCode);
        });

        builder.setNegativeButton("Not Now", (dialog, which) -> {
            if (callback != null) callback.onPermissionDenied();
        });

        builder.setCancelable(false);
        builder.show();
    }

    private static void showRationaleDialog(
            Activity activity,
            PermissionInfo info,
            String permission,
            int requestCode,
            PermissionCallback callback) {

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(info.title + " Needed");
        builder.setMessage("This permission was previously denied.\n\n" +
                          info.description + "\n\n" +
                          "Would you like to grant this permission now?");
        builder.setIcon(android.R.drawable.ic_dialog_alert);

        builder.setPositiveButton("Try Again", (dialog, which) -> {
            ActivityCompat.requestPermissions(activity, new String[]{permission}, requestCode);
        });

        builder.setNegativeButton("No Thanks", (dialog, which) -> {
            if (callback != null) callback.onPermissionDenied();
        });

        builder.setNeutralButton("Open Settings", (dialog, which) -> {
            openAppPermissionsWithInstructions(activity, info.title);
            if (callback != null) callback.onPermissionPermanentlyDenied();
        });

        builder.setCancelable(false);
        builder.show();
    }

    /**
     * Show dialog when permission is permanently denied
     */
    public static void showPermissionDeniedDialog(
            Activity activity,
            String permissionName,
            PermissionCallback callback) {

        new AlertDialog.Builder(activity)
            .setTitle(permissionName + " Denied")
            .setMessage("This permission has been denied. Some features may not work properly.\n\n" +
                       "You can enable this permission in Settings if you change your mind.")
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton("Open Settings", (dialog, which) -> {
                openAppPermissionsWithInstructions(activity, permissionName);
                if (callback != null) callback.onPermissionPermanentlyDenied();
            })
            .setNegativeButton("Continue Without", (dialog, which) -> {
                if (callback != null) callback.onPermissionDenied();
            })
            .setCancelable(false)
            .show();
    }

    /**
     * Open app settings page - tries app permissions first, falls back to app details
     */
    public static void openAppSettings(Context context) {
        try {
            // Try to open the app-specific permissions page (Android 6.0+)
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            Uri uri = Uri.fromParts("package", context.getPackageName(), null);
            intent.setData(uri);

            // Add flags to ensure we can navigate back properly
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
            intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

            context.startActivity(intent);
        } catch (Exception e) {
            // Fallback to general settings
            try {
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception fallbackE) {
                // Last resort - do nothing and show toast
                if (context instanceof Activity) {
                    Toast.makeText(context,
                        "Please manually enable permissions in Settings > Apps > " +
                        context.getString(R.string.app_name),
                        Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    /**
     * Open specific app permissions page with more detailed instructions
     */
    public static void openAppPermissionsWithInstructions(Activity activity, String permissionType) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Enable " + permissionType + " Permission");

        String instructions = "To enable " + permissionType.toLowerCase() + " permission:\n\n" +
                             "1. Tap 'Open Settings' below\n" +
                             "2. Look for 'Permissions' or 'App permissions'\n" +
                             "3. Find '" + permissionType + "' in the list\n" +
                             "4. Toggle it ON\n" +
                             "5. Return to the app\n\n" +
                             "If you don't see 'Permissions', try:\n" +
                             "• Scroll down in the app settings page\n" +
                             "• Look for 'App info' then 'Permissions'\n" +
                             "• Or check device Settings > Privacy > Permission manager";

        builder.setMessage(instructions);
        builder.setIcon(android.R.drawable.ic_dialog_info);

        builder.setPositiveButton("Open Settings", (dialog, which) -> {
            openAppSettings(activity);
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> {
            dialog.dismiss();
        });

        // Add alternative button to try permission manager (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setNeutralButton("Permission Manager", (dialog, which) -> {
                tryOpenPermissionManager(activity);
            });
        }

        builder.show();
    }

    /**
     * Try to open the system Permission Manager (Android 10+)
     */
    private static void tryOpenPermissionManager(Activity activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Try to open Permission Manager
                Intent intent = new Intent(Settings.ACTION_PRIVACY_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);

                Toast.makeText(activity,
                    "Look for 'Permission manager' then find this app",
                    Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            // Fallback to regular app settings
            openAppSettings(activity);
        }
    }

    /**
     * Handle permission result and show appropriate dialog if denied
     */
    public static void handlePermissionResult(
            Activity activity,
            String permission,
            boolean granted,
            PermissionCallback callback) {

        if (granted) {
            if (callback != null) callback.onPermissionGranted();
        } else {
            PermissionInfo info = PERMISSION_INFO.get(permission);
            String permissionName = info != null ? info.title : "Permission";

            if (!ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
                // Permission permanently denied
                showPermissionDeniedDialog(activity, permissionName, callback);
            } else {
                // Permission temporarily denied
                if (callback != null) callback.onPermissionDenied();
            }
        }
    }

    /**
     * Check and request location permissions with proper flow
     */
    public static void requestLocationPermissions(Activity activity, PermissionCallback callback) {
        // Check fine location first
        if (!hasPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestPermissionWithExplanation(
                activity,
                Manifest.permission.ACCESS_FINE_LOCATION,
                PERMISSION_REQUEST_LOCATION,
                callback
            );
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                   !hasPermission(activity, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            // If fine location is granted, request background location (API 29+)
            requestPermissionWithExplanation(
                activity,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                PERMISSION_REQUEST_LOCATION,
                callback
            );
        } else {
            if (callback != null) callback.onPermissionGranted();
        }
    }

    /**
     * Request notification permission (Android 13+)
     */
    public static void requestNotificationPermission(Activity activity, PermissionCallback callback) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(activity, Manifest.permission.POST_NOTIFICATIONS)) {
                requestPermissionWithExplanation(
                    activity,
                    Manifest.permission.POST_NOTIFICATIONS,
                    PERMISSION_REQUEST_NOTIFICATION,
                    callback
                );
            } else {
                if (callback != null) callback.onPermissionGranted();
            }
        } else {
            // Notifications don't require permission before Android 13
            if (callback != null) callback.onPermissionGranted();
        }
    }
}