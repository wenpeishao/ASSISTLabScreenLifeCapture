package com.screenomics;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.VideoCapture;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.google.common.util.concurrent.ListenableFuture;

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

    private ProcessCameraProvider cameraProvider;
    private Preview preview;
    private boolean isRecording = false;

    // Timer for recording duration
    private Handler timerHandler;
    private Runnable timerRunnable;
    private long recordingStartTime;

    private VideoCaptureService videoCaptureService;
    private boolean serviceBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            VideoCaptureService.LocalBinder binder = (VideoCaptureService.LocalBinder) service;
            videoCaptureService = binder.getService();
            serviceBound = true;
            updateRecordingUI(videoCaptureService.isRecording());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            videoCaptureService = null;
            serviceBound = false;
        }
    };

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
    }

    @Override
    public void onStart() {
        super.onStart();
        // Bind to VideoCaptureService if it's running
        Intent intent = new Intent(requireContext(), VideoCaptureService.class);
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (serviceBound) {
            requireContext().unbindService(serviceConnection);
            serviceBound = false;
        }
    }

    private void initializeViews(View view) {
        cameraPreview = view.findViewById(R.id.cameraPreview);
        videoRecordButton = view.findViewById(R.id.videoRecordButton);
        recordingStatus = view.findViewById(R.id.recordingStatus);
        cameraStatus = view.findViewById(R.id.cameraStatus);
        recordingIndicator = view.findViewById(R.id.recordingIndicator);
        reminderSpinner = view.findViewById(R.id.reminderSpinner);
        recordingTimer = view.findViewById(R.id.recordingTimer);

        // Initialize timer handler
        timerHandler = new Handler(Looper.getMainLooper());
    }

    private boolean checkPermissions() {
        boolean cameraGranted = ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean audioGranted = ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        return cameraGranted && audioGranted;
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

            if (cameraGranted) {
                initializeCamera();
                if (!audioGranted) {
                    Toast.makeText(requireContext(),
                            "Audio permission denied. Videos will be recorded without sound.",
                            Toast.LENGTH_LONG).show();
                }
            } else {
                cameraStatus.setText("Camera permission required");
                cameraStatus.setVisibility(View.VISIBLE);
                videoRecordButton.setEnabled(false);
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
                cameraStatus.setVisibility(View.VISIBLE);
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    private void startCameraPreview() {
        if (cameraProvider == null || !isAdded()) return;

        cameraProvider.unbindAll();

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build();

        preview = new Preview.Builder().build();
        preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());

        try {
            cameraProvider.bindToLifecycle(this, cameraSelector, preview);
            cameraStatus.setVisibility(View.GONE);
            videoRecordButton.setEnabled(true);
            Log.d(TAG, "Camera preview started successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error starting camera preview", e);
            cameraStatus.setText("Camera initialization failed");
            cameraStatus.setVisibility(View.VISIBLE);
            videoRecordButton.setEnabled(false);
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
        int selectedPosition = intervalToPosition(currentInterval);
        reminderSpinner.setSelection(selectedPosition);

        reminderSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int intervalHours = positionToInterval(position);
                VideoReminderScheduler.scheduleReminders(requireContext(), intervalHours);

                String message = intervalHours > 0 ?
                        "Video reminders set for every " + intervalHours + " hours" :
                        "Video reminders disabled";
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private int intervalToPosition(int interval) {
        switch (interval) {
            case 2: return 1;
            case 4: return 2;
            case 6: return 3;
            case 8: return 4;
            case 12: return 5;
            case 24: return 6;
            default: return 0;
        }
    }

    private int positionToInterval(int position) {
        switch (position) {
            case 1: return 2;
            case 2: return 4;
            case 3: return 6;
            case 4: return 8;
            case 5: return 12;
            case 6: return 24;
            default: return 0;
        }
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
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions();
            return;
        }

        // Check if registered
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String hash = prefs.getString("hash", "");
        if (hash.isEmpty()) {
            Toast.makeText(requireContext(), "Please register first", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent serviceIntent = new Intent(requireContext(), VideoCaptureService.class);
        serviceIntent.putExtra("action", VideoCaptureService.ACTION_START_RECORDING);
        ContextCompat.startForegroundService(requireContext(), serviceIntent);

        updateRecordingUI(true);
        Log.d(TAG, "Video recording started via service");
    }

    private void stopVideoRecording() {
        if (serviceBound && videoCaptureService != null) {
            videoCaptureService.stopVideoRecording();
        } else {
            Intent serviceIntent = new Intent(requireContext(), VideoCaptureService.class);
            serviceIntent.putExtra("action", VideoCaptureService.ACTION_STOP_RECORDING);
            requireContext().startService(serviceIntent);
        }

        updateRecordingUI(false);
        Log.d(TAG, "Video recording stopped");
    }

    private void updateRecordingUI(boolean recording) {
        isRecording = recording;

        if (!isAdded()) return;

        requireActivity().runOnUiThread(() -> {
            if (recording) {
                videoRecordButton.setText("Stop Recording");
                videoRecordButton.setBackgroundTintList(
                        ContextCompat.getColorStateList(requireContext(), android.R.color.holo_red_dark));
                recordingStatus.setText("Recording...");
                recordingIndicator.setVisibility(View.VISIBLE);

                // Start timer
                if (recordingTimer != null) {
                    recordingTimer.setVisibility(View.VISIBLE);
                    recordingStartTime = System.currentTimeMillis();
                    startRecordingTimer();
                }
            } else {
                videoRecordButton.setText("Start Recording");
                videoRecordButton.setBackgroundTintList(
                        ContextCompat.getColorStateList(requireContext(), R.color.sea_green));
                recordingStatus.setText("Ready to record");
                recordingIndicator.setVisibility(View.GONE);

                // Stop timer
                stopRecordingTimer();
                if (recordingTimer != null) {
                    recordingTimer.setVisibility(View.GONE);
                    recordingTimer.setText("00:00");
                }
            }
        });
    }

    private void startRecordingTimer() {
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isAdded() || recordingTimer == null) return;

                long millis = System.currentTimeMillis() - recordingStartTime;
                int seconds = (int) (millis / 1000);
                int minutes = seconds / 60;
                seconds = seconds % 60;

                recordingTimer.setText(String.format("%02d:%02d", minutes, seconds));

                if (isRecording) {
                    timerHandler.postDelayed(this, 1000);
                }
            }
        };
        timerHandler.postDelayed(timerRunnable, 0);
    }

    private void stopRecordingTimer() {
        if (timerRunnable != null && timerHandler != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopRecordingTimer();
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    // Called from MainActivity when video recording is triggered from notification
    public void triggerRecording() {
        if (!isRecording) {
            startVideoRecording();
        }
    }
}
