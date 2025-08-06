package com.screenomics;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
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
    private Button statsSettingsButton;
    private Timer numImageRefreshTimer;
    private UploadService uploadService;

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
        statsSettingsButton = view.findViewById(R.id.settingsButton);
    }

    private void setupListeners() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        SharedPreferences.Editor editor = prefs.edit();

        switchCapture.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!buttonView.isPressed()) return;
            if (isChecked) {
                editor.putBoolean("recordingState", true);
                editor.apply();
                ((MainActivity) requireActivity()).startLocationService();
                ((MainActivity) requireActivity()).startMediaProjectionRequest();
                captureState.setText(getResources().getString(R.string.capture_state_on));
                captureState.setTextColor(ContextCompat.getColor(requireContext(), R.color.light_sea_green));
            } else {
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

    @Override
    public void onResume() {
        super.onResume();
        
        captureState.setText(getResources().getString(R.string.capture_state_off));
        switchCapture.setEnabled(true);
        switchCapture.setChecked(false);

        Intent intent = new Intent(requireContext(), UploadService.class);
        requireContext().bindService(intent, uploaderServiceConnection, 0);

        startImageRefreshTimer();
    }

    @Override
    public void onPause() {
        super.onPause();
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
}