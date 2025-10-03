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
import android.widget.ProgressBar;
import android.os.Handler;
import android.os.Looper;
import android.media.MediaRecorder;
import android.media.AudioManager;

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
    private TextView recordingTimer;
    private ProgressBar volumeLevelBar;
    private View faceGuideOverlay;
    private TextView faceGuideText;
    
    // Timer and audio monitoring
    private Handler timerHandler;
    private Runnable timerRunnable;
    private long recordingStartTime;
    private MediaRecorder audioRecorder;
    private Handler volumeHandler;
    private Runnable volumeRunnable;
    
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
        setupVideoRecording();
        
        if (checkPermissions()) {
            initializeCamera();
        } else {
            requestPermissions();
        }
        
        // Initialize handlers
        timerHandler = new Handler(Looper.getMainLooper());
        volumeHandler = new Handler(Looper.getMainLooper());
    }

    private void initializeViews(View view) {
        cameraPreview = view.findViewById(R.id.cameraPreview);
        videoRecordButton = view.findViewById(R.id.videoRecordButton);
        recordingStatus = view.findViewById(R.id.recordingStatus);
        cameraStatus = view.findViewById(R.id.cameraStatus);
        recordingIndicator = view.findViewById(R.id.recordingIndicator);
        reminderSpinner = view.findViewById(R.id.reminderSpinner);
        recordingTimer = view.findViewById(R.id.recordingTimer);
        volumeLevelBar = view.findViewById(R.id.volumeLevelBar);
        faceGuideOverlay = view.findViewById(R.id.faceGuideOverlay);
        faceGuideText = view.findViewById(R.id.faceGuideText);
    }

    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }
    
    private boolean checkAllPermissions() {
        boolean cameraPermission = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean audioPermission = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        
        Log.d(TAG, "Camera permission: " + cameraPermission);
        Log.d(TAG, "Audio permission: " + audioPermission);
        
        return cameraPermission && audioPermission;
    }
    
    private void showPermissionDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Permissions Required")
            .setMessage("Camera and microphone permissions are required for video recording. Please grant all permissions to continue.")
            .setPositiveButton("Grant Permissions", (dialog, which) -> requestPermissions())
            .setNegativeButton("Cancel", null)
            .setCancelable(false)
            .show();
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
                    Log.d(TAG, "Camera permission granted");
                }
                if (Manifest.permission.RECORD_AUDIO.equals(permissions[i]) && 
                    grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    audioGranted = true;
                    Log.d(TAG, "Audio permission granted");
                }
            }
            
            if (cameraGranted && audioGranted) {
                initializeCamera();
                Toast.makeText(requireContext(), "Permissions granted! You can now record videos.", Toast.LENGTH_SHORT).show();
            } else {
                String missingPerms = "";
                if (!cameraGranted) missingPerms += "Camera ";
                if (!audioGranted) missingPerms += "Microphone ";
                
                cameraStatus.setText("Missing permissions: " + missingPerms);
                
                // Show persistent dialog for missing permissions
                showPersistentPermissionDialog(missingPerms.trim());
            }
        }
    }
    
    private void showPersistentPermissionDialog(String missingPermissions) {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Permissions Still Required")
            .setMessage("Video recording needs " + missingPermissions + " permission(s). \n\nWithout these permissions, you cannot record videos. Please grant them to continue.")
            .setPositiveButton("Try Again", (dialog, which) -> {
                requestPermissions();
            })
            .setNegativeButton("Settings", (dialog, which) -> {
                // Open app settings
                Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(android.net.Uri.parse("package:" + requireContext().getPackageName()));
                startActivity(intent);
            })
            .setCancelable(false)
            .show();
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
            
            // Show face guide when camera is ready
            showFaceGuide(true);
            Log.d(TAG, "Camera preview and video capture started successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error starting camera", e);
            cameraStatus.setText("Camera initialization failed");
            cameraStatus.setVisibility(View.VISIBLE);
            showFaceGuide(false);
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
        // Enhanced permission checking
        if (!checkAllPermissions()) {
            showPermissionDialog();
            return;
        }

        if (videoCapture == null) {
            Toast.makeText(requireContext(), "Camera not ready. Please wait for camera initialization.", Toast.LENGTH_LONG).show();
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String hash = prefs.getString("hash", "00000000").substring(0, 8);

        // Generate IV for video file
        byte[] videoIV = SecureFileUtils.generateSecureIV();
        String filename = SecureFileUtils.generateSecureFilename(hash, "video", "mp4", videoIV);

        // Store IV in SharedPreferences for later encryption
        prefs.edit().putString("currentVideoIV", SecureFileUtils.bytesToHex(videoIV)).apply();
        
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

                // Get the IV from SharedPreferences that was stored when filename was created
                String ivHex = prefs.getString("currentVideoIV", "");
                byte[] iv;
                if (!ivHex.isEmpty()) {
                    iv = Converter.hexStringToByteArray(ivHex);
                } else {
                    // Fallback if IV wasn't stored
                    iv = SecureFileUtils.generateSecureIV();
                }

                // Use the IV that matches the filename
                Encryptor.encryptFile(key, videoPath, encryptedPath, iv);

                File encryptedFile = new File(encryptedPath);
                long encryptedSize = encryptedFile.length();
                Log.d(TAG, "Encrypted video file size: " + encryptedSize + " bytes");

                if (originalFile.delete()) {
                    Log.d(TAG, "Original video file deleted");
                }

                Log.d(TAG, "Video encrypted and ready for 2-minute upload");
                
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
            
            // Start timer
            recordingTimer.setVisibility(View.VISIBLE);
            recordingStartTime = System.currentTimeMillis();
            startRecordingTimer();
            
            // Start volume monitoring
            volumeLevelBar.setVisibility(View.VISIBLE);
            startVolumeMonitoring();
            
            // Keep face guide visible during recording to help user maintain position
            // showFaceGuide(false); // Commented out - keep guide visible
        } else {
            videoRecordButton.setText("Start Recording");
            videoRecordButton.setBackgroundTintList(
                ContextCompat.getColorStateList(requireContext(), R.color.sea_green)
            );
            recordingStatus.setText("Ready to record");
            recordingIndicator.setVisibility(View.GONE);
            
            // Stop timer
            recordingTimer.setVisibility(View.GONE);
            stopRecordingTimer();
            
            // Stop volume monitoring
            volumeLevelBar.setVisibility(View.GONE);
            stopVolumeMonitoring();
            
            // Show face guide when not recording
            showFaceGuide(true);
        }
    }
    
    private void startRecordingTimer() {
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                long millis = System.currentTimeMillis() - recordingStartTime;
                int seconds = (int) (millis / 1000);
                int minutes = seconds / 60;
                seconds = seconds % 60;
                
                recordingTimer.setText(String.format("%02d:%02d", minutes, seconds));
                timerHandler.postDelayed(this, 1000);
            }
        };
        timerHandler.postDelayed(timerRunnable, 0);
    }
    
    private void stopRecordingTimer() {
        if (timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
            recordingTimer.setText("00:00");
        }
    }
    
    private void startVolumeMonitoring() {
        // Check audio permission before starting
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Audio permission not granted for volume monitoring");
            return;
        }
        
        try {
            // Stop any existing recorder
            stopVolumeMonitoring();
            
            audioRecorder = new MediaRecorder();
            audioRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            audioRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            audioRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            
            // Use a temporary file instead of /dev/null for better compatibility
            File tempFile = new File(requireContext().getCacheDir(), "temp_audio.3gp");
            audioRecorder.setOutputFile(tempFile.getAbsolutePath());
            
            audioRecorder.prepare();
            audioRecorder.start();
            
            Log.d(TAG, "Volume monitoring started successfully");
            
            volumeRunnable = new Runnable() {
                @Override
                public void run() {
                    if (audioRecorder != null) {
                        try {
                            int amplitude = audioRecorder.getMaxAmplitude();
                            Log.d(TAG, "Raw amplitude: " + amplitude);
                            
                            // Convert amplitude to percentage (0-100)
                            // Max amplitude for MediaRecorder is 32767
                            int volumeLevel = 0;
                            if (amplitude > 0) {
                                volumeLevel = Math.min(100, (int) ((amplitude / 32767.0) * 100));
                            }
                            
                            Log.d(TAG, "Volume level: " + volumeLevel + "%");
                            volumeLevelBar.setProgress(volumeLevel);
                            
                            if (isRecording) {
                                volumeHandler.postDelayed(this, 100);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error getting amplitude: " + e.getMessage());
                        }
                    }
                }
            };
            volumeHandler.postDelayed(volumeRunnable, 100);
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting volume monitoring: " + e.getMessage(), e);
            Toast.makeText(requireContext(), "Could not start audio monitoring. Check microphone permissions.", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void stopVolumeMonitoring() {
        if (volumeRunnable != null) {
            volumeHandler.removeCallbacks(volumeRunnable);
            volumeRunnable = null;
        }
        if (audioRecorder != null) {
            try {
                audioRecorder.stop();
                audioRecorder.release();
                audioRecorder = null;
                Log.d(TAG, "Volume monitoring stopped");
                
                // Clean up temp file
                File tempFile = new File(requireContext().getCacheDir(), "temp_audio.3gp");
                if (tempFile.exists()) {
                    tempFile.delete();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping volume monitoring: " + e.getMessage());
            }
        }
        volumeLevelBar.setProgress(0);
    }
    
    private void showFaceGuide(boolean show) {
        if (faceGuideOverlay != null) {
            faceGuideOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (faceGuideText != null) {
            faceGuideText.setVisibility(show ? View.VISIBLE : View.GONE);
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
        stopRecordingTimer();
        stopVolumeMonitoring();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        stopRecordingTimer();
        stopVolumeMonitoring();
        if (timerHandler != null) {
            timerHandler.removeCallbacksAndMessages(null);
        }
        if (volumeHandler != null) {
            volumeHandler.removeCallbacksAndMessages(null);
        }
    }

}