package com.screenomics;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
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
    private Switch autoUploadSwitch;
    private TextView captureState;
    private TextView numImagesText;
    private TextView numUploadText;
    private Button uploadButton;
    private Button updateQRButton;
    private Button statsSettingsButton;
    private Timer numImageRefreshTimer;
    private UploadService uploadService;

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
        autoUploadSwitch = view.findViewById(R.id.autoUploadSwitch);
        captureState = view.findViewById(R.id.captureState);
        numImagesText = view.findViewById(R.id.imageNumber);
        numUploadText = view.findViewById(R.id.uploadNumber);
        uploadButton = view.findViewById(R.id.uploadButton);
        updateQRButton = view.findViewById(R.id.updateQRButton);
        statsSettingsButton = view.findViewById(R.id.settingsButton);
    }

    private void setupListeners() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        SharedPreferences.Editor editor = prefs.edit();

        switchCapture.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.d("ScreenLifeFragment", "Switch changed - isChecked: " + isChecked + ", isPressed: " + buttonView.isPressed());
            if (!buttonView.isPressed()) return;
            if (isChecked) {
                Log.d("ScreenLifeFragment", "User turned switch ON - starting capture");
                editor.putBoolean("recordingState", true);
                editor.apply();
                ((MainActivity) requireActivity()).startLocationService();
                ((MainActivity) requireActivity()).startMediaProjectionRequest();
                captureState.setText(getResources().getString(R.string.capture_state_on));
                captureState.setTextColor(ContextCompat.getColor(requireContext(), R.color.light_sea_green));
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

        autoUploadSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!buttonView.isPressed()) return;
            editor.putBoolean("autoUploadEnabled", isChecked);
            editor.apply();
            
            if (isChecked) {
                UploadScheduler.setupAutoUpload(requireContext());
                Toast.makeText(requireContext(), "Auto upload enabled - every 12 hours", Toast.LENGTH_SHORT).show();
            } else {
                UploadScheduler.cancelAutoUpload(requireContext());
                Toast.makeText(requireContext(), "Auto upload disabled", Toast.LENGTH_SHORT).show();
            }
        });

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
        boolean autoUploadEnabled = prefs.getBoolean("autoUploadEnabled", true); // Default to enabled

        switchCapture.setChecked(recordingState);
        mobileDataUse.setChecked(continueWithoutWifi);
        autoUploadSwitch.setChecked(autoUploadEnabled);
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
        } else {
            Log.d("ScreenLifeFragment", "Setting switch to OFF - capture is inactive");
            captureState.setText(getResources().getString(R.string.capture_state_off));
            captureState.setTextColor(ContextCompat.getColor(requireContext(), R.color.white_isabelline));
        }

        Intent intent = new Intent(requireContext(), UploadService.class);
        requireContext().bindService(intent, uploaderServiceConnection, 0);

        startImageRefreshTimer();
    }

    @Override
    public void onPause() {
        super.onPause();

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
                        if (file.getName().contains("_video.mp4")) {
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

            Toast.makeText(requireContext(), "New test ID generated: " + hash.substring(0, 8) + "...", Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Log.e("ScreenLifeFragment", "Error generating test ID", e);
            Toast.makeText(requireContext(), "Error generating test ID", Toast.LENGTH_LONG).show();
        }
    }
}