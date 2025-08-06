package com.screenomics;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

public class MindPulseFragment extends Fragment {
    private static final String TAG = "MindPulseFragment";
    private static final int REQUEST_CODE_PERMISSIONS = 1001;
    
    private PreviewView cameraPreview;
    private Button videoRecordButton;
    private TextView recordingStatus;
    private TextView cameraStatus;
    private View recordingIndicator;
    private Spinner reminderSpinner;
    
    private ProcessCameraProvider cameraProvider;
    private Preview preview;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private boolean isRecording = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mindpulse, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        initializeViews(view);
        setupReminderSpinner();
        // Video recording temporarily disabled - to be re-enabled in future version
        // setupVideoRecording();
        
        // Camera permissions temporarily disabled
        /*
        if (checkPermissions()) {
            initializeCamera();
        } else {
            requestPermissions();
        }
        */
        
        // Display message about video recording being disabled
        if (cameraStatus != null) {
            cameraStatus.setText("Video recording will be available in a future version");
        }
    }

    private void initializeViews(View view) {
        cameraPreview = view.findViewById(R.id.cameraPreview);
        videoRecordButton = view.findViewById(R.id.videoRecordButton);
        recordingStatus = view.findViewById(R.id.recordingStatus);
        cameraStatus = view.findViewById(R.id.cameraStatus);
        recordingIndicator = view.findViewById(R.id.recordingIndicator);
        reminderSpinner = view.findViewById(R.id.reminderSpinner);
        
        // Hide video recording UI elements
        if (cameraPreview != null) cameraPreview.setVisibility(View.GONE);
        if (videoRecordButton != null) {
            videoRecordButton.setVisibility(View.GONE);
            videoRecordButton.setEnabled(false);
        }
        if (recordingStatus != null) recordingStatus.setVisibility(View.GONE);
        if (recordingIndicator != null) recordingIndicator.setVisibility(View.GONE);
    }

    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(requireActivity(),
                new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                REQUEST_CODE_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            boolean cameraGranted = false;
            boolean audioGranted = false;
            
            for (int i = 0; i < permissions.length; i++) {
                if (Manifest.permission.CAMERA.equals(permissions[i]) && 
                    grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    cameraGranted = true;
                }
                if (Manifest.permission.RECORD_AUDIO.equals(permissions[i]) && 
                    grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    audioGranted = true;
                }
            }
            
            if (cameraGranted && audioGranted) {
                initializeCamera();
            } else {
                cameraStatus.setText("Camera permissions required");
                Toast.makeText(requireContext(), "Camera and audio permissions are required for video recording", 
                    Toast.LENGTH_LONG).show();
            }
        }
    }

    private void initializeCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = 
            ProcessCameraProvider.getInstance(requireContext());
        
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                startCameraPreview();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error initializing camera", e);
                cameraStatus.setText("Camera initialization failed");
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    private void startCameraPreview() {
        if (cameraProvider == null) return;

        // Unbind all use cases before rebinding
        cameraProvider.unbindAll();

        // Set up camera selector for front camera
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build();

        // Set up preview use case
        preview = new Preview.Builder().build();
        preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());

        // Set up video capture use case - optimized for ML training
        // Use FHD (1080p) for better quality for ML training
        QualitySelector qualitySelector = QualitySelector.from(Quality.FHD);
        Recorder recorder = new Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build();
        videoCapture = VideoCapture.withOutput(recorder);

        try {
            // Bind both preview and video capture to camera
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture);
            cameraStatus.setVisibility(View.GONE);
            Log.d(TAG, "Camera preview and video capture started successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error starting camera", e);
            cameraStatus.setText("Camera initialization failed");
            cameraStatus.setVisibility(View.VISIBLE);
        }
    }

    private void setupReminderSpinner() {
        String[] reminderOptions = {
            "Disabled", "Every 2 hours", "Every 4 hours", 
            "Every 6 hours", "Every 8 hours", "Every 12 hours", "Every 24 hours"
        };
        
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), 
            android.R.layout.simple_spinner_item, reminderOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        reminderSpinner.setAdapter(adapter);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        int currentInterval = prefs.getInt("video_reminder_interval", 0);
        int selectedPosition = 0;
        switch (currentInterval) {
            case 2: selectedPosition = 1; break;
            case 4: selectedPosition = 2; break;
            case 6: selectedPosition = 3; break;
            case 8: selectedPosition = 4; break;
            case 12: selectedPosition = 5; break;
            case 24: selectedPosition = 6; break;
        }
        reminderSpinner.setSelection(selectedPosition);

        reminderSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int intervalHours = 0;
                switch (position) {
                    case 1: intervalHours = 2; break;
                    case 2: intervalHours = 4; break;
                    case 3: intervalHours = 6; break;
                    case 4: intervalHours = 8; break;
                    case 5: intervalHours = 12; break;
                    case 6: intervalHours = 24; break;
                }
                VideoReminderScheduler.scheduleReminders(requireContext(), intervalHours);
                
                String message = intervalHours > 0 ? 
                    "Video reminders set for every " + intervalHours + " hours" :
                    "Video reminders disabled";
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupVideoRecording() {
        videoRecordButton.setOnClickListener(v -> {
            if (isRecording) {
                stopVideoRecording();
            } else {
                startVideoRecording();
            }
        });
    }

    private void startVideoRecording() {
        if (!checkPermissions()) {
            requestPermissions();
            return;
        }

        if (videoCapture == null) {
            Toast.makeText(requireContext(), "Camera not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String hash = prefs.getString("hash", "00000000").substring(0, 8);
        
        long timestamp = System.currentTimeMillis();
        String filename = hash + "_" + timestamp + "_video.mp4";
        
        File videoDir = new File(requireContext().getExternalFilesDir(null), "videos");
        if (!videoDir.exists()) {
            videoDir.mkdirs();
        }
        
        File videoFile = new File(videoDir, filename);
        FileOutputOptions outputOptions = new FileOutputOptions.Builder(videoFile).build();

        recording = videoCapture.getOutput()
                .prepareRecording(requireContext(), outputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(requireContext()), videoRecordEvent -> {
                    if (videoRecordEvent instanceof VideoRecordEvent.Start) {
                        updateRecordingUI(true);
                        Log.d(TAG, "Video recording started");
                    } else if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                        VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) videoRecordEvent;
                        if (!finalizeEvent.hasError()) {
                            Log.d(TAG, "Video recording completed successfully");
                            encryptAndUploadVideo(videoFile.getAbsolutePath());
                        } else {
                            Log.e(TAG, "Video recording failed: " + finalizeEvent.getError());
                        }
                        updateRecordingUI(false);
                    }
                });

        Toast.makeText(requireContext(), "Starting video recording...", Toast.LENGTH_SHORT).show();
    }

    private void stopVideoRecording() {
        if (recording != null) {
            recording.stop();
            recording = null;
            Log.d(TAG, "Video recording stopped");
            Toast.makeText(requireContext(), "Stopping video recording...", Toast.LENGTH_SHORT).show();
        }
    }

    private void encryptAndUploadVideo(String videoPath) {
        // Run encryption in background thread
        new Thread(() -> {
            try {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
                String keyRaw = prefs.getString("key", "");
                byte[] key = Converter.hexStringToByteArray(keyRaw);
                
                File originalFile = new File(videoPath);
                long originalSize = originalFile.length();
                Log.d(TAG, "Original video file size: " + originalSize + " bytes");
                
                String encryptDir = requireContext().getExternalFilesDir(null).getAbsolutePath() + File.separator + "encrypt";
                File encryptDirFile = new File(encryptDir);
                if (!encryptDirFile.exists()) {
                    encryptDirFile.mkdirs();
                }
                
                String encryptedPath = encryptDir + File.separator + originalFile.getName();
                
                Encryptor.encryptFile(key, "/" + originalFile.getName(), videoPath, encryptedPath);
                
                File encryptedFile = new File(encryptedPath);
                long encryptedSize = encryptedFile.length();
                Log.d(TAG, "Encrypted video file size: " + encryptedSize + " bytes");
                
                if (originalFile.delete()) {
                    Log.d(TAG, "Original video file deleted");
                }
                
                Log.d(TAG, "Video encrypted and ready for upload");
                
            } catch (Exception e) {
                Log.e(TAG, "Error encrypting video", e);
            }
        }).start();
    }

    private void updateRecordingUI(boolean recording) {
        isRecording = recording;
        
        if (recording) {
            videoRecordButton.setText("Stop Recording");
            videoRecordButton.setBackgroundTintList(
                ContextCompat.getColorStateList(requireContext(), android.R.color.holo_red_dark)
            );
            recordingStatus.setText("Recording in progress...");
            recordingIndicator.setVisibility(View.VISIBLE);
        } else {
            videoRecordButton.setText("Start Recording");
            videoRecordButton.setBackgroundTintList(
                ContextCompat.getColorStateList(requireContext(), R.color.sea_green)
            );
            recordingStatus.setText("Ready to record");
            recordingIndicator.setVisibility(View.GONE);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (checkPermissions() && cameraProvider == null) {
            initializeCamera();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (recording != null) {
            recording.stop();
            recording = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

}