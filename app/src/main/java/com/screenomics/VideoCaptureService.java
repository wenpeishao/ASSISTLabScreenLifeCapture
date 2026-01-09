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

import androidx.annotation.NonNull;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoCaptureService extends Service implements LifecycleOwner {
    private static final String TAG = "VideoCaptureService";
    private static final String CHANNEL_ID = "video_capture_channel";
    private static final int NOTIFICATION_ID = 2;

    public static final String ACTION_START_RECORDING = "start_recording";
    public static final String ACTION_STOP_RECORDING = "stop_recording";

    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private ProcessCameraProvider cameraProvider;
    private ExecutorService cameraExecutor;
    private boolean isRecording = false;
    private LifecycleRegistry lifecycleRegistry;
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public VideoCaptureService getService() {
            return VideoCaptureService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);

        cameraExecutor = Executors.newSingleThreadExecutor();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Start foreground immediately
        Notification notification = createNotification("Video service ready", "MindPulse video capture");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);

        if (intent != null) {
            String action = intent.getStringExtra("action");
            if (ACTION_START_RECORDING.equals(action)) {
                initializeCameraAndRecord();
            } else if (ACTION_STOP_RECORDING.equals(action)) {
                stopVideoRecording();
            }
        }

        return START_STICKY;
    }

    private void initializeCameraAndRecord() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission not granted");
            stopSelf();
            return;
        }

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                setupCameraAndStartRecording();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error initializing camera", e);
                stopSelf();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void setupCameraAndStartRecording() {
        if (cameraProvider == null) {
            Log.e(TAG, "CameraProvider is null");
            stopSelf();
            return;
        }

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
            cameraProvider.bindToLifecycle(this, cameraSelector, videoCapture);
            Log.d(TAG, "Camera bound successfully");
            startVideoRecording();
        } catch (Exception e) {
            Log.e(TAG, "Error binding camera", e);
            stopSelf();
        }
    }

    public void startVideoRecording() {
        if (isRecording || videoCapture == null) {
            Log.w(TAG, "Already recording or camera not ready");
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String hash = prefs.getString("hash", "00000000");
        if (hash.length() >= 8) {
            hash = hash.substring(0, 8);
        }

        // Generate filename for video - use simple format that Batch can process
        // Batch will encrypt the file and handle metadata
        long timestamp = System.currentTimeMillis();
        String filename = hash + "_" + timestamp + "_video.mp4";

        // Save directly to encrypt directory so Batch can pick it up for upload
        File encryptDir = new File(getExternalFilesDir(null), "encrypt");
        if (!encryptDir.exists()) {
            encryptDir.mkdirs();
        }

        File videoFile = new File(encryptDir, filename);
        FileOutputOptions outputOptions = new FileOutputOptions.Builder(videoFile).build();

        // Check audio permission
        boolean hasAudioPermission = ActivityCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

        androidx.camera.video.PendingRecording pendingRecording =
                videoCapture.getOutput().prepareRecording(this, outputOptions);

        if (hasAudioPermission) {
            pendingRecording = pendingRecording.withAudioEnabled();
        } else {
            Log.w(TAG, "Audio permission not granted, recording video only");
        }

        recording = pendingRecording.start(ContextCompat.getMainExecutor(this), videoRecordEvent -> {
            if (videoRecordEvent instanceof VideoRecordEvent.Start) {
                isRecording = true;
                updateNotification("Recording video...", "MindPulse video capture in progress");
                Log.d(TAG, "Video recording started: " + videoFile.getName());
            } else if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) videoRecordEvent;
                isRecording = false;

                if (!finalizeEvent.hasError()) {
                    Log.d(TAG, "Video recording completed: " + videoFile.getAbsolutePath());
                    Log.d(TAG, "Video file size: " + videoFile.length() + " bytes");
                    // Video is saved to encrypt dir - Batch will encrypt and upload it
                } else {
                    Log.e(TAG, "Video recording failed with error: " + finalizeEvent.getError());
                    videoFile.delete();
                }

                updateNotification("Video capture complete", "Ready for upload");
            }
        });
    }

    public void stopVideoRecording() {
        if (recording != null) {
            recording.stop();
            recording = null;
            Log.d(TAG, "Video recording stopped");
        }
    }

    private void encryptVideo(File videoFile, byte[] iv, String hash) {
        cameraExecutor.execute(() -> {
            try {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                String keyRaw = prefs.getString("key", "");

                if (keyRaw.isEmpty()) {
                    Log.e(TAG, "No encryption key found");
                    videoFile.delete();
                    return;
                }

                byte[] key = Encryptor.deriveAesKeyFromToken(keyRaw);

                String encryptDir = getExternalFilesDir(null).getAbsolutePath() + File.separator + "encrypt";
                File encryptDirFile = new File(encryptDir);
                if (!encryptDirFile.exists()) {
                    encryptDirFile.mkdirs();
                }

                // Generate new filename with IV for encrypted file
                String encryptedFilename = SecureFileUtils.generateSecureFilename(hash, "video", "mp4.enc", iv);
                String encryptedPath = encryptDir + File.separator + encryptedFilename;

                Log.d(TAG, "Encrypting video to: " + encryptedPath);
                Encryptor.encryptFile(key, videoFile.getAbsolutePath(), encryptedPath, iv);

                // Delete original unencrypted video
                if (videoFile.delete()) {
                    Log.d(TAG, "Original video file deleted");
                } else {
                    Log.w(TAG, "Failed to delete original video file");
                }

                Log.d(TAG, "Video encrypted successfully");

            } catch (Exception e) {
                Log.e(TAG, "Error encrypting video", e);
                videoFile.delete();
            }
        });
    }

    public boolean isRecording() {
        return isRecording;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);

        if (recording != null) {
            recording.stop();
            recording = null;
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        Log.d(TAG, "VideoCaptureService destroyed");
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
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
