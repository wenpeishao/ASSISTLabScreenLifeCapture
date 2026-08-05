package com.screenomics;

import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.Manifest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenLifeFragment extends Fragment {
    private static final String TAG = "ScreenLifeFragment";
    private static final String PREF_USE_ACCESSIBILITY_CAPTURE = "useAccessibilityCapture";
    
    private Switch switchCapture;
    private Switch mobileDataUse;
    // autoUploadSwitch removed - feature no longer needed
    private TextView captureState;
    private TextView numImagesText;
    private TextView numUploadText;
    private Button uploadButton;
    private Button updateQRButton;
    // statsSettingsButton removed - each permission row opens its own settings
    private Button accessibilityCaptureButton;
    private Timer numImageRefreshTimer;
    private UploadService uploadService;
    private TextView accessibilityModeStatus;
    private View glassCard;
    private View glassStatusDot;
    private TextView glassStatus;
    private Button glassConnectButton;
    private final ExecutorService statsExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean statsRefreshInFlight = new AtomicBoolean(false);

    // Permission status dots
    private View cameraPermissionDot;
    private View locationPermissionDot;
    private View usageAccessDot;
    private View notificationPermissionDot;
    private View batteryPermissionDot;
    private View accessibilityPermissionDot;

    // Permission rows (clickable)
    private View cameraPermissionRow;
    private View locationPermissionRow;
    private View usageAccessRow;
    private View notificationPermissionRow;
    private View batteryPermissionRow;
    private View accessibilityPermissionRow;

    private boolean justStartedCapture = false;
    private boolean pendingCaptureStart = false;
    private Handler captureCheckHandler = new Handler();
    private Runnable captureCheckRunnable;

    private BroadcastReceiver resetCaptureReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.screenomics.RESET_CAPTURE_STATE".equals(intent.getAction())) {
                resetCaptureSwitch();
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_screenlife, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        initializeViews(view);
        setupListeners();
        loadPreferences();
    }

    private void initializeViews(View view) {
        switchCapture = view.findViewById(R.id.switchCapture);
        mobileDataUse = view.findViewById(R.id.mobileDataSwitch);
        // autoUploadSwitch removed - feature no longer needed
        captureState = view.findViewById(R.id.captureState);
        numImagesText = view.findViewById(R.id.imageNumber);
        numUploadText = view.findViewById(R.id.uploadNumber);
        uploadButton = view.findViewById(R.id.uploadButton);
        updateQRButton = view.findViewById(R.id.updateQRButton);
        // statsSettingsButton removed - each permission row opens its own settings
        accessibilityCaptureButton = view.findViewById(R.id.accessibilityCaptureButton);
        accessibilityModeStatus = view.findViewById(R.id.accessibilityModeStatus);
        glassCard = view.findViewById(R.id.glassCard);
        glassStatusDot = view.findViewById(R.id.glassStatusDot);
        glassStatus = view.findViewById(R.id.glassStatus);
        glassConnectButton = view.findViewById(R.id.glassConnectButton);
        if (!GlassesFeature.AVAILABLE && glassCard != null) {
            glassCard.setVisibility(View.GONE);
        }

        // Permission status dots
        cameraPermissionDot = view.findViewById(R.id.cameraPermissionDot);
        locationPermissionDot = view.findViewById(R.id.locationPermissionDot);
        usageAccessDot = view.findViewById(R.id.usageAccessDot);
        notificationPermissionDot = view.findViewById(R.id.notificationPermissionDot);
        batteryPermissionDot = view.findViewById(R.id.batteryPermissionDot);
        accessibilityPermissionDot = view.findViewById(R.id.accessibilityPermissionDot);

        // Permission rows (clickable)
        cameraPermissionRow = view.findViewById(R.id.cameraPermissionRow);
        locationPermissionRow = view.findViewById(R.id.locationPermissionRow);
        usageAccessRow = view.findViewById(R.id.usageAccessRow);
        notificationPermissionRow = view.findViewById(R.id.notificationPermissionRow);
        batteryPermissionRow = view.findViewById(R.id.batteryPermissionRow);
        accessibilityPermissionRow = view.findViewById(R.id.accessibilityPermissionRow);
    }

    private void setupListeners() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        SharedPreferences.Editor editor = prefs.edit();

        switchCapture.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.d("ScreenLifeFragment", "Switch changed - isChecked: " + isChecked + ", isPressed: " + buttonView.isPressed());
            if (!buttonView.isPressed()) return;
            if (isChecked) {
                Log.d("ScreenLifeFragment", "User turned switch ON - starting capture");
                justStartedCapture = true;  // Mark that user just started it

                boolean useA11y = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
                boolean a11yReady = useA11y && AccessibilityCaptureService.isServiceEnabled(requireContext());

                if (!useA11y || a11yReady) {
                    // Standard capture or accessibility already enabled
                    editor.putBoolean("recordingState", true);
                    editor.apply();
                    captureState.setText(getResources().getString(R.string.capture_state_on));
                    captureState.setTextColor(ContextCompat.getColor(requireContext(), R.color.light_sea_green));
                } else {
                    // Accessibility needed but not yet enabled -- defer recordingState
                    pendingCaptureStart = true;
                    Log.d("ScreenLifeFragment", "Accessibility not enabled yet, deferring recordingState");
                }

                ((MainActivity) requireActivity()).startLocationService();
                ((MainActivity) requireActivity()).startMediaProjectionRequest();
                // Reset flag after some time
                captureCheckHandler.postDelayed(() -> justStartedCapture = false, 5000);
            } else {
                Log.d("ScreenLifeFragment", "User turned switch OFF - stopping capture");
                editor.putBoolean("recordingState", false);
                editor.commit();
                ((MainActivity) requireActivity()).stopLocationService();
                ((MainActivity) requireActivity()).stopCaptureService();
                captureState.setText(getResources().getString(R.string.capture_state_off));
                captureState.setTextColor(ContextCompat.getColor(requireContext(), R.color.white_isabelline));
            }
        });

        mobileDataUse.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!buttonView.isPressed()) return;
            editor.putBoolean("continueWithoutWifi", isChecked);
            editor.apply();
        });

        // Auto upload listener removed - feature no longer needed

        updateQRButton.setOnClickListener(view -> {
            showUpdateQRCodeDialog();
        });

        // Permission row click listeners -- each opens its relevant settings
        cameraPermissionRow.setOnClickListener(v -> openAppSettings());
        locationPermissionRow.setOnClickListener(v -> openAppSettings());
        usageAccessRow.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            } catch (Exception e) {
                openAppSettings();
            }
        });
        notificationPermissionRow.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    intent.putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().getPackageName());
                    startActivity(intent);
                } catch (Exception e) {
                    openAppSettings();
                }
            } else {
                openAppSettings();
            }
        });
        batteryPermissionRow.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(android.net.Uri.parse("package:" + requireContext().getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } catch (Exception e2) {
                    openAppSettings();
                }
            }
        });
        accessibilityPermissionRow.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                openAccessibilitySettings();
            } else {
                Toast.makeText(requireContext(), "Accessibility Capture requires Android 11+", Toast.LENGTH_SHORT).show();
            }
        });

        accessibilityCaptureButton.setOnClickListener(view -> handleAccessibilityCaptureSelection());

        glassConnectButton.setOnClickListener(v -> {
            MainActivity act = (MainActivity) requireActivity();
            if (GlassesFeature.isEnabled(requireContext())) act.stopGlassesService();
            else act.startGlassesService();
            v.postDelayed(this::updateGlassUi, 800);
        });

        uploadButton.setOnClickListener(v -> {
            if (!InternetConnection.checkWiFiConnection(requireContext())) {
                AlertDialog alertDialog = new AlertDialog.Builder(requireContext()).create();
                alertDialog.setTitle("Alert");
                alertDialog.setMessage("Upload image data while not on WiFi?");
                alertDialog.setButton(AlertDialog.BUTTON_POSITIVE, "Upload",
                        (dialog, which) -> {
                            dialog.dismiss();
                            UploadScheduler.startUpload(requireContext(), true, true);
                            Toast.makeText(requireContext(), "Uploading...", Toast.LENGTH_SHORT).show();
                        });
                alertDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel",
                        (dialog, which) -> dialog.dismiss());
                alertDialog.show();
            } else {
                UploadScheduler.startUpload(requireContext(), false, true);
                Toast.makeText(requireContext(), "Uploading...", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadPreferences() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        boolean recordingState = prefs.getBoolean("recordingState", false);
        boolean continueWithoutWifi = prefs.getBoolean("continueWithoutWifi", false);
        // autoUploadEnabled removed - feature no longer needed

        switchCapture.setChecked(recordingState);
        mobileDataUse.setChecked(continueWithoutWifi);
        // autoUploadSwitch.setChecked removed - feature no longer needed
    }

    public void resetCaptureSwitch() {
        Log.d("ScreenLifeFragment", "resetCaptureSwitch() called - resetting switch to OFF");
        if (switchCapture != null) {
            switchCapture.setChecked(false);
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("recordingState", false);
        editor.apply();
    }

    @Override
    public void onResume() {
        super.onResume();

        // Register broadcast receiver
        IntentFilter filter = new IntentFilter("com.screenomics.RESET_CAPTURE_STATE");
        requireContext().registerReceiver(resetCaptureReceiver, filter, Context.RECEIVER_NOT_EXPORTED);

        // Check actual recording state from preferences
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        boolean recordingState = prefs.getBoolean("recordingState", false);

        Log.d("ScreenLifeFragment", "onResume() - recordingState from prefs: " + recordingState);

        switchCapture.setEnabled(true);
        switchCapture.setChecked(recordingState);

        if (recordingState) {
            Log.d("ScreenLifeFragment", "Setting switch to ON - capture is active");
            captureState.setText(getResources().getString(R.string.capture_state_on));
            captureState.setTextColor(ContextCompat.getColor(requireContext(), R.color.light_sea_green));

            // Check if CaptureService needs restarting, but delay to allow service binding
            // Skip if user just manually started it (to avoid double permission request)
            if (!justStartedCapture) {
                captureCheckRunnable = () -> {
                    if (getActivity() == null) return;  // Fragment detached
                    MainActivity mainActivity = (MainActivity) requireActivity();
                    if (!mainActivity.isCaptureServiceRunning()) {
                        Log.w("ScreenLifeFragment", "CaptureService not running after delay - may have been killed");
                        // Only restart if user hasn't changed the switch in the meantime
                        SharedPreferences currentPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
                        boolean stillRecording = currentPrefs.getBoolean("recordingState", false);
                        if (stillRecording) {
                            Log.i("ScreenLifeFragment", "Restarting CaptureService");
                            mainActivity.startMediaProjectionRequest();
                        }
                    }
                };
                captureCheckHandler.postDelayed(captureCheckRunnable, 2000);
            } else {
                Log.d("ScreenLifeFragment", "Skipping service check - user just started capture");
            }
        } else {
            Log.d("ScreenLifeFragment", "Setting switch to OFF - capture is inactive");
            captureState.setText(getResources().getString(R.string.capture_state_off));
            captureState.setTextColor(ContextCompat.getColor(requireContext(), R.color.white_isabelline));
        }

        Intent intent = new Intent(requireContext(), UploadService.class);
        requireContext().bindService(intent, uploaderServiceConnection, 0);

        startImageRefreshTimer();
        updatePermissionStatus();
        updateAccessibilityCaptureUi();
        updateGlassUi();

        // If user toggled capture ON but accessibility wasn't enabled yet, check now
        if (pendingCaptureStart) {
            pendingCaptureStart = false;
            if (AccessibilityCaptureService.isServiceEnabled(requireContext())) {
                Log.i("ScreenLifeFragment", "Accessibility now enabled -- confirming recordingState");
                prefs.edit().putBoolean("recordingState", true).apply();
                switchCapture.setChecked(true);
                captureState.setText(getResources().getString(R.string.capture_state_on));
                captureState.setTextColor(ContextCompat.getColor(requireContext(), R.color.light_sea_green));
            } else {
                Log.w("ScreenLifeFragment", "Accessibility still not enabled -- resetting capture switch");
                switchCapture.setChecked(false);
                captureState.setText(getResources().getString(R.string.capture_state_off));
                captureState.setTextColor(ContextCompat.getColor(requireContext(), R.color.white_isabelline));
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        // Cancel any pending service checks
        if (captureCheckRunnable != null) {
            captureCheckHandler.removeCallbacks(captureCheckRunnable);
        }

        // Unregister broadcast receiver
        try {
            requireContext().unregisterReceiver(resetCaptureReceiver);
        } catch (IllegalArgumentException e) {
            // Receiver not registered
        }

        if (numImageRefreshTimer != null) {
            numImageRefreshTimer.cancel();
        }
        try {
            requireContext().unbindService(uploaderServiceConnection);
        } catch (IllegalArgumentException e) {
            // Service not bound
        }
    }

    private void startImageRefreshTimer() {
        numImageRefreshTimer = new Timer();
        numImageRefreshTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (!isAdded() || statsExecutor.isShutdown()) return;
                if (!statsRefreshInFlight.compareAndSet(false, true)) return;

                statsExecutor.execute(() -> {
                    try {
                        File extDir = requireContext().getExternalFilesDir(null);
                        if (extDir == null) return;
                        File outputDir = new File(extDir.getAbsolutePath() + File.separator + "encrypt");
                        FileStats stats = countFiles(outputDir);

                        if (getActivity() == null) return;
                        requireActivity().runOnUiThread(() -> {
                            if (!isAdded()) return;
                            numImagesText.setText(String.format(
                                    Locale.US,
                                    "Images: %d, Videos: %d (%.2fMB)",
                                    stats.numImages,
                                    stats.numVideos,
                                    stats.bytesTotal / 1024f / 1024f
                            ));
                            Log.i(TAG, "Files - Images:" + stats.numImages + ", Videos:" + stats.numVideos);

                            if (uploadService != null) {
                                if (uploadService.status == UploadService.Status.SENDING) {
                                    String progressText = "Uploading: " + uploadService.numUploaded + "/" + uploadService.numTotal;
                                    if (uploadService.numFailed > 0) {
                                        progressText += " (" + uploadService.numFailed + " failed)";
                                    }
                                    numUploadText.setText(progressText);
                                } else if (uploadService.status == UploadService.Status.SUCCESS) {
                                    numUploadText.setText("Successfully uploaded " + uploadService.numUploaded + " files at " + uploadService.lastActivityTime);
                                } else if (uploadService.status == UploadService.Status.FAILED) {
                                    if ("PARTIAL_FAILURE".equals(uploadService.errorCode)) {
                                        numUploadText.setText("Partially uploaded: " + uploadService.numUploaded + " success, " +
                                                uploadService.numFailed + " failed at " + uploadService.lastActivityTime);
                                    } else {
                                        numUploadText.setText("Failed uploading " + uploadService.numToUpload + " files at " +
                                                uploadService.lastActivityTime + " with code " + uploadService.errorCode);
                                    }
                                } else {
                                    numUploadText.setText(uploadService.status.toString());
                                }
                            }

                            updatePermissionStatus();
                            updateAccessibilityCaptureUi();
                            updateGlassUi();
                        });
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to refresh file stats", e);
                    } finally {
                        statsRefreshInFlight.set(false);
                    }
                });
            }
        }, 500, 5000);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        statsExecutor.shutdownNow();
    }

    private FileStats countFiles(File outputDir) {
        FileStats stats = new FileStats();
        if (outputDir == null || !outputDir.exists()) {
            return stats;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(outputDir.toPath())) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path)) continue;
                stats.bytesTotal += Files.size(path);
                String name = path.getFileName().toString().toLowerCase(Locale.US);
                if (name.contains("_video.mp4") || name.endsWith(".mp4")) {
                    stats.numVideos++;
                } else if (name.endsWith(".enc")
                        && !name.endsWith("_metadata.enc")
                        && !name.endsWith("_applog.enc")
                        && !name.endsWith("_logcat.enc")
                        && !name.endsWith("_diagnostics.enc")
                        && !name.endsWith("_gps.enc")
                        && !name.endsWith("_appusage.enc")
                        && !name.endsWith("_logdata.enc")
                        && !name.endsWith("_devicestate.enc")) {
                    stats.numImages++;
                }
                // .meta files and non-image .enc files are excluded from counts
            }
            return stats;
        } catch (Exception e) {
            // Fallback for filesystem implementations that don't support DirectoryStream reliably.
            File[] allFiles = outputDir.listFiles();
            if (allFiles == null) return stats;
            for (File file : allFiles) {
                if (!file.isFile()) continue;
                stats.bytesTotal += file.length();
                String name = file.getName().toLowerCase(Locale.US);
                if (name.contains("_video.mp4") || name.endsWith(".mp4")) {
                    stats.numVideos++;
                } else if (name.endsWith(".enc")
                        && !name.endsWith("_metadata.enc")
                        && !name.endsWith("_applog.enc")
                        && !name.endsWith("_logcat.enc")
                        && !name.endsWith("_diagnostics.enc")
                        && !name.endsWith("_gps.enc")
                        && !name.endsWith("_appusage.enc")
                        && !name.endsWith("_logdata.enc")
                        && !name.endsWith("_devicestate.enc")) {
                    stats.numImages++;
                }
            }
            return stats;
        }
    }

    private static class FileStats {
        int numImages;
        int numVideos;
        long bytesTotal;
    }

    private final ServiceConnection uploaderServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            UploadService.LocalBinder localBinder = (UploadService.LocalBinder) iBinder;
            uploadService = localBinder.getService();
            if (uploadService.status == UploadService.Status.SENDING) {
                String progressText = "Uploading: " + uploadService.numUploaded + "/" + uploadService.numTotal;
                if (uploadService.numFailed > 0) {
                    progressText += " (" + uploadService.numFailed + " failed)";
                }
                numUploadText.setText(progressText);
            } else {
                numUploadText.setText(uploadService.status.toString());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) { }
    };

    private void showUpdateQRCodeDialog() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        boolean isTester = prefs.getBoolean("isTester", false);
        String currentKey = SecureStore.getSecret(requireContext(), "key", "");
        String currentHash = prefs.getString("hash", "");

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Update QR Code");

        String message = "Current Registration:\n\n";
        String shortHash = currentHash.length() > 12 ? currentHash.substring(0, 12) : currentHash;
        if (isTester) {
            message += "Status: TESTER ACCOUNT\n";
            message += "Test ID: " + shortHash + "...\n\n";
            message += "This will replace your test ID with a real QR code registration.";
        } else {
            message += "Status: Regular Account\n";
            message += "Hash: " + shortHash + "...\n\n";
            message += "This will replace your current QR code registration.";
        }

        message += "\n\nChoose how to update:";

        builder.setMessage(message);
        builder.setIcon(android.R.drawable.ic_dialog_info);

        builder.setPositiveButton("Scan QR Code", (dialog, which) -> {
            // Start RegisterActivity to scan new QR code
            Intent intent = new Intent(requireContext(), RegisterActivity.class);
            intent.putExtra("isUpdate", true);
            startActivity(intent);
        });

        builder.setNeutralButton("Generate New Test ID", (dialog, which) -> {
            if (isTester) {
                generateNewTestId();
            } else {
                showTestIdWarningDialog();
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());

        builder.show();
    }

    private void showTestIdWarningDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Convert to Test Account?");
        builder.setMessage("This will convert your regular account to a test account.\n\n" +
                          "All future data will be marked as test data on the backend.\n\n" +
                          "Continue?");
        builder.setIcon(android.R.drawable.ic_dialog_alert);

        builder.setPositiveButton("Generate Test ID", (dialog, which) -> generateNewTestId());
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    private void generateNewTestId() {
        // Similar logic to RegisterActivity but simpler
        try {
            java.text.SimpleDateFormat keyTsFmt = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US);
            keyTsFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            String timestamp = keyTsFmt.format(new java.util.Date());
            java.security.SecureRandom random = new java.security.SecureRandom();

            StringBuilder testKey = new StringBuilder("TEST_");
            testKey.append(timestamp).append("_");

            String hexChars = "0123456789abcdef";
            int remainingLength = 64 - testKey.length();
            for (int i = 0; i < remainingLength; i++) {
                testKey.append(hexChars.charAt(random.nextInt(hexChars.length())));
            }

            String key = testKey.toString();

            // Log the key length for debugging
            Log.d("ScreenLifeFragment", "Generated test ID length: " + key.length());
            Log.d("ScreenLifeFragment", "Generated test ID: " + key);

            // Generate hash
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] hashBytes = md.digest();

            java.math.BigInteger num = new java.math.BigInteger(1, hashBytes);
            StringBuilder hexString = new StringBuilder(num.toString(16));
            while (hexString.length() < 64) {
                hexString.insert(0, '0');
            }
            String hash = hexString.toString();

            // Save to preferences (the key itself goes to encrypted storage)
            SecureStore.putSecret(requireContext(), "key", key);
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("hash", hash);
            editor.putBoolean("isTester", true);
            java.text.SimpleDateFormat testerTsFmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", java.util.Locale.US);
            testerTsFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            editor.putString("testerTimestamp", testerTsFmt.format(new java.util.Date()));
            editor.apply();

            // Show dialog with copy option
            showTestIdGeneratedDialog(key, hash);

        } catch (Exception e) {
            Log.e("ScreenLifeFragment", "Error generating test ID", e);
            Toast.makeText(requireContext(), "Error generating test ID", Toast.LENGTH_LONG).show();
        }
    }

    private void updatePermissionStatus() {
        if (getActivity() == null) return;

        // Check Camera Permission
        boolean cameraGranted = ContextCompat.checkSelfPermission(requireContext(),
            Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        updatePermissionDot(cameraPermissionDot, cameraGranted);

        // Check Location Permission
        boolean locationGranted = ContextCompat.checkSelfPermission(requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(requireContext(),
            Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        updatePermissionDot(locationPermissionDot, locationGranted);

        // Check Usage Access Permission
        boolean usageAccessGranted = false;
        AppOpsManager appOps = (AppOpsManager) requireContext().getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), requireContext().getPackageName());
        usageAccessGranted = mode == AppOpsManager.MODE_ALLOWED;
        updatePermissionDot(usageAccessDot, usageAccessGranted);

        // Check Notification Permission (Android 13+)
        boolean notificationGranted = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationGranted = ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        updatePermissionDot(notificationPermissionDot, notificationGranted);

        // Check Battery Optimization exemption
        android.os.PowerManager pm = (android.os.PowerManager)
                requireContext().getSystemService(Context.POWER_SERVICE);
        boolean batteryExempt = pm.isIgnoringBatteryOptimizations(requireContext().getPackageName());
        updatePermissionDot(batteryPermissionDot, batteryExempt);

        // Check Accessibility Capture permission (Android 11+)
        boolean accessibilityGranted;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            accessibilityGranted = AccessibilityCaptureService.isServiceEnabled(requireContext());
        } else {
            // Not applicable on Android 10, show as granted (N/A)
            accessibilityGranted = true;
        }
        updatePermissionDot(accessibilityPermissionDot, accessibilityGranted);

        // Hide accessibility row on devices that don't support it
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && accessibilityPermissionRow != null) {
            accessibilityPermissionRow.setVisibility(View.GONE);
        }
    }

    private void handleAccessibilityCaptureSelection() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        boolean accessibilityModeEnabled = prefs.getBoolean(PREF_USE_ACCESSIBILITY_CAPTURE, false);

        if (accessibilityModeEnabled) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Accessibility Capture Enabled")
                    .setMessage("Accessibility Capture is already selected for screen capture.\n\nOpen Accessibility settings if you still need to grant permission, or switch back to standard screen recording.")
                    .setPositiveButton("Open Settings", (dialog, which) -> openAccessibilitySettings())
                    .setNeutralButton("Use Standard Capture", (dialog, which) -> disableAccessibilityCapture())
                    .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                    .show();
            return;
        }

        new AlertDialog.Builder(requireContext())
                .setTitle("Accessibility API Data Disclosure")
                .setMessage("MindPulse uses Android AccessibilityService API to capture encrypted "
                        + "screenshots for this IRB-approved UW-Madison research study.\n\n"
                        + "DATA COLLECTED VIA ACCESSIBILITYSERVICE:\n\n"
                        + "Because screenshots capture everything visible on your screen, "
                        + "the following data types may be collected:\n\n"
                        + "- Web browsing history\n"
                        + "- Emails\n"
                        + "- SMS or MMS messages\n"
                        + "- Other in-app messages\n"
                        + "- Precise location\n"
                        + "- Personal identifiers (name, email, address, phone number)\n"
                        + "- Race and ethnicity; political or religious beliefs\n"
                        + "- Sexual orientation or gender identity\n"
                        + "- Financial information (credit/debit/bank accounts, purchases)\n"
                        + "- Health and fitness information\n"
                        + "- Photos, videos, voice/sound recordings, music, files, documents\n"
                        + "- Calendar events and contacts\n"
                        + "- Page views, taps, in-app search history\n"
                        + "- Installed apps and other user-generated content\n"
                        + "- Device or other identifiers\n\n"
                        + "ADDITIONAL DATA COLLECTED:\n\n"
                        + "- App usage metadata (active app name, timestamps)\n"
                        + "- Device diagnostic logs (for research quality assurance)\n"
                        + "- Location data (GPS coordinates collected alongside screenshots)\n\n"
                        + "PURPOSE:\n"
                        + "This data is used exclusively for approved academic research on "
                        + "smartphone use and digital behavior at UW-Madison.\n\n"
                        + "SECURITY:\n"
                        + "All data is encrypted on your device and transmitted securely to "
                        + "authorized UW-Madison research systems.\n\n"
                        + "Participation is voluntary. You can decline now or disable "
                        + "Accessibility Capture at any time from app controls and Android settings.\n\n"
                        + "By tapping \"Agree and Continue\", you consent to this data collection.\n\n"
                        + "On the next screen:\n"
                        + "1. Open Accessibility\n"
                        + "2. Select MindPulse Accessibility Capture\n"
                        + "3. Turn it on\n"
                        + "4. Return to MindPulse")
                .setPositiveButton("Agree and Continue", (dialog, which) -> enableAccessibilityCapture())
                .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void enableAccessibilityCapture() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        prefs.edit().putBoolean(PREF_USE_ACCESSIBILITY_CAPTURE, true).apply();

        // Stop the MediaProjection service so the app does not run both capture modes.
        requireContext().stopService(new Intent(requireContext(), CaptureService.class));

        updateAccessibilityCaptureUi();
        openAccessibilitySettings();
    }

    private void disableAccessibilityCapture() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        prefs.edit().putBoolean(PREF_USE_ACCESSIBILITY_CAPTURE, false).apply();

        updateAccessibilityCaptureUi();
        Toast.makeText(requireContext(),
                "Accessibility Capture disabled. Turn Screen Capture off and back on to use standard capture.",
                Toast.LENGTH_LONG).show();
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(AccessibilityCaptureService.buildAccessibilitySettingsIntent());
            Toast.makeText(requireContext(),
                    "Enable MindPulse Accessibility Capture, then return to the app.",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to open accessibility settings", e);
            Toast.makeText(requireContext(),
                    "Open Settings > Accessibility and enable MindPulse Accessibility Capture.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void updateAccessibilityCaptureUi() {
        if (getActivity() == null || accessibilityCaptureButton == null || accessibilityModeStatus == null) {
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        boolean accessibilityModeEnabled = prefs.getBoolean(PREF_USE_ACCESSIBILITY_CAPTURE, false);
        boolean accessibilityGranted = AccessibilityCaptureService.isServiceEnabled(requireContext());

        if (!accessibilityModeEnabled) {
            accessibilityModeStatus.setText("Standard screen recording is selected.");
            accessibilityCaptureButton.setText("Enable Accessibility Capture");
            return;
        }

        if (accessibilityGranted) {
            accessibilityModeStatus.setText("Accessibility Capture is selected and permission is granted.");
            accessibilityCaptureButton.setText("Accessibility Capture Enabled");
        } else {
            accessibilityModeStatus.setText("Accessibility Capture is selected. Finish setup in Accessibility settings.");
            accessibilityCaptureButton.setText("Finish Accessibility Setup");
        }
    }

    private void updateGlassUi() {
        if (!GlassesFeature.AVAILABLE) return;
        if (getActivity() == null || glassStatus == null) return;
        boolean enabled = GlassesFeature.isEnabled(requireContext());
        boolean connected = GlassesFeature.isConnected();
        updatePermissionDot(glassStatusDot, connected);
        if (!enabled) {
            glassStatus.setText("Off");
            glassConnectButton.setText("Connect OMI Glass");
        } else {
            int batteryPct = GlassesFeature.batteryPct();
            String batt = batteryPct >= 0 ? (batteryPct + "%") : "—";
            glassStatus.setText((connected ? "Connected" : "Scanning…")
                    + "  ·  photos " + GlassesFeature.photoCount() + "  ·  battery " + batt);
            glassConnectButton.setText("Disconnect OMI Glass");
        }
    }

    private void openAppSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(android.net.Uri.parse("package:" + requireContext().getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open app settings", e);
        }
    }

    private void updatePermissionDot(View dot, boolean granted) {
        if (granted) {
            dot.setBackgroundResource(R.drawable.permission_dot_green);
        } else {
            dot.setBackgroundResource(R.drawable.permission_dot_red);
        }
    }

    private void showTestIdGeneratedDialog(String key, String hash) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Test ID Generated Successfully");

        String message = "Your new test ID has been generated:\n\n" +
                        "Test ID: " + hash.substring(0, 12) + "...\n\n" +
                        "Full Key: " + key.substring(0, 20) + "...\n\n" +
                        "This account is now marked as a TESTER account. " +
                        "All data will be identified as test data on the backend.";

        builder.setMessage(message);
        builder.setIcon(android.R.drawable.ic_dialog_info);

        // Copy button
        builder.setNeutralButton("Copy Test ID", (dialog, which) -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("Test ID", key);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(requireContext(), "Test ID copied! (Length: " + key.length() + " chars)", Toast.LENGTH_LONG).show();
        });

        builder.setPositiveButton("OK", (dialog, which) -> dialog.dismiss());

        builder.show();
    }
}
