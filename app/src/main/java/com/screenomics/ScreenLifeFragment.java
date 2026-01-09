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
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Stream;

public class ScreenLifeFragment extends Fragment {
    private static final String TAG = "ScreenLifeFragment";
    
    private Switch switchCapture;
    private Switch mobileDataUse;
    // autoUploadSwitch removed - feature no longer needed
    private TextView captureState;
    private TextView numImagesText;
    private TextView numUploadText;
    private Button uploadButton;
    private Button updateQRButton;
    private Button statsSettingsButton;
    private Timer numImageRefreshTimer;
    private UploadService uploadService;

    // Permission status dots
    private View cameraPermissionDot;
    private View locationPermissionDot;
    private View usageAccessDot;
    private View notificationPermissionDot;

    private boolean justStartedCapture = false;
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
        statsSettingsButton = view.findViewById(R.id.settingsButton);

        // Permission status dots
        cameraPermissionDot = view.findViewById(R.id.cameraPermissionDot);
        locationPermissionDot = view.findViewById(R.id.locationPermissionDot);
        usageAccessDot = view.findViewById(R.id.usageAccessDot);
        notificationPermissionDot = view.findViewById(R.id.notificationPermissionDot);
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
                editor.putBoolean("recordingState", true);
                editor.apply();
                ((MainActivity) requireActivity()).startLocationService();
                ((MainActivity) requireActivity()).startMediaProjectionRequest();
                captureState.setText(getResources().getString(R.string.capture_state_on));
                captureState.setTextColor(ContextCompat.getColor(requireContext(), R.color.light_sea_green));
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

        statsSettingsButton.setOnClickListener(view -> {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        });

        uploadButton.setOnClickListener(v -> {
            if (!InternetConnection.checkWiFiConnection(requireContext())) {
                AlertDialog alertDialog = new AlertDialog.Builder(requireContext()).create();
                alertDialog.setTitle("Alert");
                alertDialog.setMessage("Upload image data while not on WiFi?");
                alertDialog.setButton(AlertDialog.BUTTON_POSITIVE, "Upload",
                        (dialog, which) -> {
                            dialog.dismiss();
                            UploadScheduler.startUpload(requireContext(), true);
                            Toast.makeText(requireContext(), "Uploading...", Toast.LENGTH_SHORT).show();
                        });
                alertDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel",
                        (dialog, which) -> dialog.dismiss());
                alertDialog.show();
            } else {
                UploadScheduler.startUpload(requireContext(), false);
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
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    File outputDir = new File(requireContext().getExternalFilesDir(null).getAbsolutePath() + File.separator + "encrypt");
                    File[] allFiles = outputDir.listFiles();
                    if (allFiles == null) return;
                    
                    int numImages = 0;
                    int numVideos = 0;
                    float bytesTotal = 0;
                    
                    for (File file : allFiles) {
                        bytesTotal += file.length();
                        String fileName = file.getName();
                        // Video files have .mp4.enc extension
                        if (fileName.endsWith(".mp4.enc") || fileName.contains("_video.mp4")) {
                            numVideos++;
                        } else {
                            numImages++;
                        }
                    }
                    
                    numImagesText.setText(String.format("Images: %d, Videos: %d (%.2fMB)", numImages, numVideos, bytesTotal / 1024 / 1024));
                    Log.i(TAG, "Files - Images:" + numImages + ", Videos:" + numVideos);
                    
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

                    // Update permission status regularly
                    updatePermissionStatus();
                });
            }
        }, 500, 5000);
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
        String currentKey = prefs.getString("key", "");
        String currentHash = prefs.getString("hash", "");

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Update QR Code");

        String message = "Current Registration:\n\n";
        if (isTester) {
            message += "Status: TESTER ACCOUNT\n";
            message += "Test ID: " + currentHash.substring(0, 12) + "...\n\n";
            message += "This will replace your test ID with a real QR code registration.";
        } else {
            message += "Status: Regular Account\n";
            message += "Hash: " + currentHash.substring(0, 12) + "...\n\n";
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
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(new java.util.Date());
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

            // Generate hash (simplified version)
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(Converter.hexStringToByteArray(key));
            byte[] hashBytes = md.digest();

            java.math.BigInteger num = new java.math.BigInteger(1, hashBytes);
            StringBuilder hexString = new StringBuilder(num.toString(16));
            while (hexString.length() < 64) {
                hexString.insert(0, '0');
            }
            String hash = hexString.toString();

            // Save to preferences
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("key", key);
            editor.putString("hash", hash);
            editor.putBoolean("isTester", true);
            editor.putString("testerTimestamp", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date()));
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AppOpsManager appOps = (AppOpsManager) requireContext().getSystemService(Context.APP_OPS_SERVICE);
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), requireContext().getPackageName());
            usageAccessGranted = mode == AppOpsManager.MODE_ALLOWED;
        }
        updatePermissionDot(usageAccessDot, usageAccessGranted);

        // Check Notification Permission (Android 13+)
        boolean notificationGranted = true; // Default true for older versions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationGranted = ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        updatePermissionDot(notificationPermissionDot, notificationGranted);
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