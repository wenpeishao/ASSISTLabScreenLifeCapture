package com.screenomics;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.app.ActivityCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.preference.PreferenceManager;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.Date;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoCaptureService extends Service implements LifecycleOwner {
    private static final String TAG = "VideoCaptureService";
    private static final String CHANNEL_ID = "video_capture_channel";
    private static final int NOTIFICATION_ID = 2;

    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private ProcessCameraProvider cameraProvider;
    private ExecutorService cameraExecutor;
    private boolean isRecording = false;
    private LifecycleRegistry lifecycleRegistry;

    public class LocalBinder extends Binder {
        VideoCaptureService getService() { return VideoCaptureService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.markState(Lifecycle.State.CREATED);
        
        cameraExecutor = Executors.newSingleThreadExecutor();
        createNotificationChannel();
        initializeCamera();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lifecycleRegistry.markState(Lifecycle.State.STARTED);
        
        if (intent != null) {
            String action = intent.getStringExtra("action");
            if ("start_recording".equals(action)) {
                startVideoRecording();
            } else if ("stop_recording".equals(action)) {
                stopVideoRecording();
            }
        }
        
        return START_STICKY;
    }

    private void initializeCamera() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission not granted");
            return;
        }

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                setupCamera();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error initializing camera", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void setupCamera() {
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build();

        QualitySelector qualitySelector = QualitySelector.from(Quality.HD);

        Recorder recorder = new Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build();

        videoCapture = VideoCapture.withOutput(recorder);

        try {
            cameraProvider.unbindAll();
            // Note: Preview is handled by the fragment, we only bind video capture here
            Camera camera = cameraProvider.bindToLifecycle(this, cameraSelector, videoCapture);
            Log.d(TAG, "Camera setup for recording completed successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error binding camera for recording", e);
        }
    }

    public void startVideoRecording() {
        if (isRecording || videoCapture == null) {
            Log.w(TAG, "Already recording or camera not ready");
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String hash = prefs.getString("hash", "00000000").substring(0, 8);

        // Generate IV for video file
        byte[] videoIV = SecureFileUtils.generateSecureIV();
        String filename = SecureFileUtils.generateSecureFilename(hash, "video", "mp4", videoIV);

        // Store IV in SharedPreferences for later encryption
        prefs.edit().putString("currentVideoIV", SecureFileUtils.bytesToHex(videoIV)).apply();
        
        File videoDir = new File(getExternalFilesDir(null), "videos");
        if (!videoDir.exists()) {
            videoDir.mkdirs();
        }
        
        File videoFile = new File(videoDir, filename);
        
        FileOutputOptions outputOptions = new FileOutputOptions.Builder(videoFile).build();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Audio permission not granted, recording video only");
        }

        recording = videoCapture.getOutput()
                .prepareRecording(this, outputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this), videoRecordEvent -> {
                    if (videoRecordEvent instanceof VideoRecordEvent.Start) {
                        isRecording = true;
                        updateNotification("Recording video...", "MindPulse video capture in progress");
                        Log.d(TAG, "Video recording started");
                    } else if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                        VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) videoRecordEvent;
                        if (!finalizeEvent.hasError()) {
                            Log.d(TAG, "Video recording completed successfully");
                            encryptAndUploadVideo(videoFile.getAbsolutePath());
                        } else {
                            Log.e(TAG, "Video recording failed: " + finalizeEvent.getError());
                        }
                        isRecording = false;
                        updateNotification("Video capture complete", "Processing video file");
                    }
                });

        startForeground(NOTIFICATION_ID, createNotification("Starting video recording...", "Preparing camera"));
    }

    public void stopVideoRecording() {
        if (recording != null) {
            recording.stop();
            recording = null;
            Log.d(TAG, "Video recording stopped");
        }
    }

    private void encryptAndUploadVideo(String videoPath) {
        cameraExecutor.execute(() -> {
            try {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                String keyRaw = prefs.getString("key", "");
                byte[] key = Converter.hexStringToByteArray(keyRaw);
                
                File originalFile = new File(videoPath);
                String encryptDir = getExternalFilesDir(null).getAbsolutePath() + File.separator + "encrypt";
                File encryptDirFile = new File(encryptDir);
                if (!encryptDirFile.exists()) {
                    encryptDirFile.mkdirs();
                }
                
                String encryptedPath = encryptDir + File.separator + originalFile.getName();
                
                // Use the IV that matches the filename
                byte[] returnedIv = Encryptor.encryptFile(key, videoPath, encryptedPath);

                // Real-time upload after video encryption
                UploadScheduler.uploadFileImmediately(this, encryptedPath);

                if (originalFile.delete()) {
                    Log.d(TAG, "Original video file deleted");
                }

                Log.d(TAG, "Video encrypted and uploaded");
                
            } catch (Exception e) {
                Log.e(TAG, "Error encrypting video", e);
            }
        });
    }

    public boolean isRecording() {
        return isRecording;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        lifecycleRegistry.markState(Lifecycle.State.DESTROYED);
        
        if (recording != null) {
            recording.stop();
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        cameraExecutor.shutdown();
    }
    
    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new LocalBinder();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Video Capture Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("MindPulse video capture notifications");
            channel.setSound(null, null);
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification(String title, String content) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        
        int intentFlags;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            intentFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        } else {
            intentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        }
        
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, intentFlags);
        
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.drawable.dna)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String title, String content) {
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.notify(NOTIFICATION_ID, createNotification(title, content));
    }
}