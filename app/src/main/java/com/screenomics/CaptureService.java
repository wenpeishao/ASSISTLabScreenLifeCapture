package com.screenomics;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import androidx.preference.PreferenceManager;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.ServiceCompat;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.SortedMap;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import android.graphics.Bitmap.Config;


public class CaptureService extends Service {
    private static final String TAG = "Screencapture";
    private static final String CHANNEL_ID = "screenomics_id";
    private static final long MIN_FREE_SPACE_BYTES = 200L * 1024L * 1024L;
    private static final long LOW_STORAGE_LOG_THROTTLE_MS = 60_000L;
    private Intent intent;
    private int resultCode;
    private int screenDensity;
    private MediaProjection mMediaProjection;
    private MediaProjectionManager mProjectionManager;
    private MediaProjectionCallback mMediaProjectionCallback;
    private ImageReader mImageReader;
    private KeyguardManager mKeyguardManager;
    private VirtualDisplay mVirtualDisplay;
    // Target width for captured screenshots. Height is derived from actual screen
    // aspect ratio to avoid distortion on modern 20:9 / 19.5:9 displays.
    private static final int TARGET_WIDTH = 720;
    private int displayWidth = TARGET_WIDTH;
    private int displayHeight = 1280; // recalculated in onStartCommand
    private Runnable captureInterval;
    private Runnable insertStartImage;
    private Runnable insertPauseImage;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    private volatile boolean capture = false;
    private volatile boolean mInitializing = false;
    private volatile boolean mCaptureNextFrame = false;

    private ActivityManager mActivityManager;
    private PowerManager.WakeLock mWakeLock;
    private static final String WAKE_LOCK_TAG = "MindPulse:CaptureWakeLock";
    private long lastLowStorageLogMs = 0L;

    // VLM benchmark (dev only)
    private VlmBenchmark mVlmBenchmark;

    public void setVlmBenchmark(VlmBenchmark benchmark) {
        mVlmBenchmark = benchmark;
    }

    private class ImageAvailableListener implements ImageReader.OnImageAvailableListener {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = reader.acquireLatestImage();
            if (image == null) return;
            try {
                if (mCaptureNextFrame) {
                    mCaptureNextFrame = false;
                    try {
                        processCapturedImage(image);
                    } catch (Exception e) {
                        Log.e(TAG, "processCapturedImage failed", e);
                    }
                }
            } finally {
                image.close();
            }
        }
    }

    /**
     * Encrypt a temp file to .enc + .meta sidecar. Deletes tempFile when done.
     * Skips if no server public key is available (pre-enrollment).
     */
    private void encryptAndWriteMeta(File tempFile, String descriptor, String mime, String type, Date timestamp, String foregroundApp) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String hashFull = prefs.getString("hash", "00000000");
        String hash = hashFull.substring(0, Math.min(8, hashFull.length()));
        String pubKeyPem = prefs.getString("image_public_key", "");

        File extDir = getApplicationContext().getExternalFilesDir(null);
        if (extDir == null) {
            Log.e("SCREENOMICS_CAPTURE", "getExternalFilesDir returned null, skipping " + descriptor);
            return;
        }
        File encryptDir = new File(extDir, "encrypt");
        if (!encryptDir.exists() && !encryptDir.mkdirs()) {
            Log.e("SCREENOMICS_CAPTURE", "Failed to create encrypt directory, skipping " + descriptor);
            return;
        }
        String baseName = hash + "_" + System.currentTimeMillis() + "_" + descriptor;

        try {
            if (pubKeyPem == null || pubKeyPem.trim().isEmpty()) {
                Log.w("SCREENOMICS_CAPTURE", "[WARNING] No image_public_key, skipping " + descriptor);
                return;
            }

            File encFile = new File(encryptDir, baseName + ".enc");
            Encryptor.Result result = Encryptor.encryptFileToEnc(tempFile, encFile, pubKeyPem);
            Log.i("SCREENOMICS_CAPTURE", "Encrypted " + descriptor + " -> " + encFile.getName());

            DateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            isoFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            JSONObject metaObj = new JSONObject();
            metaObj.put("aes_key_encrypted_b64", result.aesKeyEncB64);
            metaObj.put("tag_len_bits", result.tagLenBits);
            metaObj.put("mime", mime);
            metaObj.put("type", type);
            metaObj.put("captured_at", isoFmt.format(timestamp));
            metaObj.put("epoch_ms", timestamp.getTime());
            if (foregroundApp != null && !foregroundApp.isEmpty()) {
                metaObj.put("foreground_app", foregroundApp);
            }

            File metaFile = new File(encryptDir, baseName + ".meta");
            try (FileWriter fw = new FileWriter(metaFile)) {
                fw.write(metaObj.toString());
            }
            Log.d("SCREENOMICS_CAPTURE", "Wrote sidecar " + metaFile.getName());

        } catch (Exception e) {
            Log.e("SCREENOMICS_CAPTURE", "Encryption failed for " + descriptor + ": " + e.getMessage(), e);
        } finally {
            if (tempFile.exists()) tempFile.delete();
        }
    }

    /** Encrypt a captured bitmap to .enc + .meta sidecar. */
    private void encryptImage(Bitmap bitmap, String descriptor, String foregroundApp) {
        if (!hasEnoughStorageForCapture()) {
            Log.w("SCREENOMICS_CAPTURE", "Skipping image write for " + descriptor + " due to low storage");
            bitmap.recycle();
            return;
        }

        File extDir = getApplicationContext().getExternalFilesDir(null);
        if (extDir == null) {
            Log.e("SCREENOMICS_CAPTURE", "getExternalFilesDir returned null, skipping capture");
            bitmap.recycle();
            return;
        }

        File tempFile = new File(extDir.getAbsolutePath(), "tmp_" + UUID.randomUUID() + ".jpg");
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(tempFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, fos);
            fos.close();
            fos = null;
            encryptAndWriteMeta(tempFile, descriptor, "image/jpeg", "image", new Date(), foregroundApp);
        } catch (Exception e) {
            Log.e("SCREENOMICS_CAPTURE", "Failed to write temp image: " + e.getMessage(), e);
            if (tempFile.exists()) tempFile.delete();
        } finally {
            try { if (fos != null) fos.close(); } catch (IOException ignored) {}
            bitmap.recycle();
        }
    }

    /**
     * Called on ImageReaderThread when mCaptureNextFrame was set.
     * Image is still open; caller closes it after this returns.
     */
    private void processCapturedImage(Image image) {
        if (mKeyguardManager.isKeyguardLocked()) {
            Log.d("SCREENOMICS_CAPTURE", "Screen locked, skipping capture");
            return;
        }
        if (!hasEnoughStorageForCapture()) {
            Log.w("SCREENOMICS_CAPTURE", "Low storage, skipping capture cycle");
            return;
        }
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * displayWidth;

        String foregroundApp = getForegroundApp();
        Log.d("SCREENOMICS_CAPTURE", "Screenshot captured | foreground_app=" + foregroundApp);
        Bitmap bitmap = Bitmap.createBitmap(
                displayWidth + rowPadding / pixelStride,
                displayHeight, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);

        // VLM benchmark: submit a copy before bitmap is recycled by encryptImage
        if (mVlmBenchmark != null && mVlmBenchmark.isRunning()) {
            Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (copy != null) {
                mVlmBenchmark.submitFrame(copy);
            }
        }

        encryptImage(bitmap, "image", foregroundApp);
    }

    /** Returns the package name of the current foreground app, or empty string on failure. */
    private String getForegroundApp() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Service.USAGE_STATS_SERVICE);

            // UsageEvents gives us the exact MOVE_TO_FOREGROUND event, not a 24h aggregate.
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - 60_000L, now);
            String foreground = "";
            if (events != null) {
                UsageEvents.Event event = new UsageEvents.Event();
                while (events.hasNextEvent()) {
                    events.getNextEvent(event);
                    if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        foreground = event.getPackageName();
                    }
                }
            }
            if (foreground != null && !foreground.isEmpty()) {
                return foreground;
            }

            // Fallback: INTERVAL_DAILY aggregate (less accurate but better than nothing).
            List<UsageStats> stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    now - TimeUnit.DAYS.toMillis(1),
                    now + TimeUnit.DAYS.toMillis(1));
            if (stats != null && !stats.isEmpty()) {
                SortedMap<Long, UsageStats> sorted = new TreeMap<>();
                for (UsageStats s : stats) sorted.put(s.getLastTimeUsed(), s);
                if (!sorted.isEmpty()) {
                    String pkg = sorted.get(sorted.lastKey()).getPackageName();
                    Log.d("SCREENOMICS_CAPTURE", "getForegroundApp: UsageEvents empty, fell back to UsageStats -> " + pkg);
                    return pkg != null ? pkg : "";
                }
            }

            // Last resort: running processes (only returns own process on modern Android).
            List<ActivityManager.RunningAppProcessInfo> procs = mActivityManager.getRunningAppProcesses();
            if (procs != null && !procs.isEmpty()) {
                Log.d("SCREENOMICS_CAPTURE", "getForegroundApp: fell back to RunningAppProcesses");
                return procs.get(0).processName;
            }
        } catch (Exception e) {
            Log.e("SCREENOMICS_CAPTURE", "Failed to get foreground app", e);
        }
        return "";
    }

    // Called when Screen Cast is disabled
    private class MediaProjectionCallback extends MediaProjection.Callback {
        @Override
        public void onStop() {
            Log.e(TAG, "MediaProjection stopped by system");
            try {
                capture = false;
                mCaptureNextFrame = false;
                mHandler.removeCallbacksAndMessages(null);
                releaseCaptureWakeLock();
                destroyImageReader();
                destroyVirtualDisplay();
                // Clear the reference since projection is no longer valid
                mMediaProjection = null;
                mInitializing = false;
                Log.w(TAG, "MediaProjection invalidated, cleared state");

                // Notify user that capture was stopped
                showStoppedNotification();

                // Update SharedPreferences to reflect stopped state
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(CaptureService.this);
                prefs.edit().putBoolean("recordingState", false).apply();

            } catch (RuntimeException e) {
                Log.e(TAG, "Error handling MediaProjection stop", e);
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mBackgroundThread = new HandlerThread("ImageReaderThread");
        mBackgroundThread.start();

        mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mKeyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

        // Hold/release this lock with capture lifecycle, not service lifecycle.
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
            mWakeLock.setReferenceCounted(false);
        }

        mActivityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        captureInterval = new Runnable() {
            @Override
            public void run() {
                if (!capture) {
                    Log.d("SCREENOMICS_CAPTURE", "Capture disabled, skipping");
                    return;
                }
                // Re-acquire WakeLock each cycle to reset the timeout.
                // This keeps it alive during normal operation but still
                // auto-releases if the service crashes between cycles.
                acquireCaptureWakeLock();
                if (!hasEnoughStorageForCapture()) {
                    mHandler.postDelayed(captureInterval, 10_000);
                    return;
                }
                mCaptureNextFrame = true;
                mHandler.postDelayed(captureInterval, 5000);
                Log.d("SCREENOMICS_CAPTURE", "Scheduled next capture in 5 seconds");
            }
        };

        insertStartImage = new Runnable() {
            @Override
            public void run() {
                try (InputStream is = getResources().openRawResource(R.raw.resumerecord)) {
                    Bitmap bitmap = BitmapFactory.decodeStream(is);
                    if (bitmap != null) encryptImage(bitmap, "resume", null);
                } catch (IOException e) {
                    Log.e(TAG, "Failed to read resume record image", e);
                }
            }
        };

        insertPauseImage = new Runnable() {
            @Override
            public void run() {
                try (InputStream is = getResources().openRawResource(R.raw.pauserecord)) {
                    Bitmap bitmap = BitmapFactory.decodeStream(is);
                    if (bitmap != null) encryptImage(bitmap, "pause", null);
                } catch (IOException e) {
                    Log.e(TAG, "Failed to read pause record image", e);
                }
            }
        };
    }


    @RequiresApi(api = Build.VERSION_CODES.Q)
    @Override
    public int onStartCommand(Intent receivedIntent, int flags, int startId) {
        if (receivedIntent != null) {
            resultCode = receivedIntent.getIntExtra("resultCode", -1);
            intent = receivedIntent.getParcelableExtra("intentData");
            screenDensity = receivedIntent.getIntExtra("screenDensity", 0);
        }

        // Compute capture dimensions preserving actual screen aspect ratio
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int screenW = metrics.widthPixels;
        int screenH = metrics.heightPixels;
        if (screenW > 0 && screenH > 0) {
            displayWidth = TARGET_WIDTH;
            displayHeight = (int) ((long) TARGET_WIDTH * screenH / screenW);
            Log.i(TAG, "Capture dimensions: " + displayWidth + "x" + displayHeight
                    + " (screen " + screenW + "x" + screenH + ")");
        }

        HandlerThread thread = mBackgroundThread;
        if (thread != null) {
            mBackgroundHandler = new Handler(thread.getLooper());
            mHandler = new Handler(Looper.getMainLooper());
        }

        createNotificationChannel();

        // Check if MediaProjection already exists to avoid re-using token
        if (mMediaProjection != null) {
            Log.w(TAG, "MediaProjection already exists, skipping initialization");
            if (capture) {
                Log.d(TAG, "Already capturing, nothing to do");
                return START_REDELIVER_INTENT;
            }
            startCapturing();
            return START_REDELIVER_INTENT;
        }

        // Prevent multiple initialization attempts
        if (mInitializing) {
            Log.w(TAG, "Already initializing MediaProjection, ignoring duplicate request");
            return START_REDELIVER_INTENT;
        }

        // Create notification
        Intent notificationIntent = new Intent(this, MainActivity.class);
        int intentflags;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
            intentflags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        }else{
            intentflags = PendingIntent.FLAG_UPDATE_CURRENT;
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(this,0, notificationIntent, intentflags);

        // minSdk=29 > O=26, so notification channels are always available
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.dna)
                .setContentTitle("MindPulse is currently enabled")
                .setContentText("If this notification disappears, please re-enable it from the application.")
                .setContentIntent(pendingIntent)
                .build();

        // MUST call startForeground first before getMediaProjection
        Log.i(TAG, "Starting foreground service");
        ServiceCompat.startForeground(this,1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        // Now get MediaProjection after service is in foreground
        mInitializing = true;
        mHandler.postDelayed( new Runnable(){
            @Override
            public void run(){
                try {
                    mMediaProjection = mProjectionManager.getMediaProjection(resultCode, intent);
                    if (mMediaProjection == null) {
                        Log.e(TAG, "Failed to get MediaProjection");
                        mInitializing = false;
                        stopSelf();
                        return;
                    }
                    mMediaProjectionCallback = new MediaProjectionCallback();
                    mMediaProjection.registerCallback(mMediaProjectionCallback, null);
                    createVirtualDisplay();
                    startCapturing();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to initialize MediaProjection", e);
                    stopSelf();
                } finally {
                    mInitializing = false;
                }
            }

        }, 750);

        return START_REDELIVER_INTENT;
    }

    private void startCapturing() {
        Log.d(TAG, "Start Capturing");
        try {
            capture = true;
            acquireCaptureWakeLock();

            mBackgroundHandler.post(insertStartImage);

            mHandler.postDelayed(captureInterval, 2000);

            UploadScheduler.ensurePeriodicUpload(getApplicationContext());
        } catch (Exception e) {
            Log.e(TAG, "Failed to start capturing", e);
        }
    }

    private void createVirtualDisplay() {
        if (mMediaProjection == null) {
            Log.e(TAG, "Cannot create VirtualDisplay: MediaProjection is null");
            return;
        }

        if (mVirtualDisplay != null) {
            Log.w(TAG, "VirtualDisplay already exists, skipping creation");
            return;
        }

        try {
            mImageReader = ImageReader.newInstance(displayWidth, displayHeight, PixelFormat.RGBA_8888, 5);
            mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                TAG, displayWidth, displayHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mImageReader.getSurface(), null, null
            );
            mImageReader.setOnImageAvailableListener(new ImageAvailableListener(), mBackgroundHandler);
            Log.i(TAG, "VirtualDisplay created successfully");
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to create VirtualDisplay: MediaProjection token invalid or expired", e);
            // Clean up invalid state
            mMediaProjection = null;
            mInitializing = false;
            if (mImageReader != null) {
                mImageReader.close();
                mImageReader = null;
            }
        }
    }

    private void stopCapturing() {
        capture = false;
        mCaptureNextFrame = false;
        mHandler.removeCallbacksAndMessages(null);
        releaseCaptureWakeLock();

        if (mBackgroundThread != null) {
            // Post pause image before quitSafely - quitSafely processes pending messages
            if (mBackgroundHandler != null) mBackgroundHandler.post(insertPauseImage);
            mBackgroundThread.quitSafely();
            try {
                mBackgroundThread.join();
                mBackgroundThread = null;
                mBackgroundHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        destroyImageReader();
        destroyVirtualDisplay();
        destroyMediaProjection();
    }

    // Called on intentionally stopping the screen capture
    @Override
    public void onDestroy() {
        super.onDestroy();
        stopCapturing();

        Log.e(TAG, "I'm destroyed!");
    }

    private void destroyImageReader() {
        if (mImageReader != null) {
            mImageReader.setOnImageAvailableListener(null, null);
            mImageReader.close();
            mImageReader = null;
        }
        Log.i(TAG, "ImageReader stopped");
    }

    private void destroyVirtualDisplay() {
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        Log.i(TAG, "VirtualDisplay stopped");
    }

    private void destroyMediaProjection() {
        if (mMediaProjection != null) {
            mMediaProjection.unregisterCallback(mMediaProjectionCallback);
            mMediaProjection.stop();
            mMediaProjection = null;
        }
        mInitializing = false;
        capture = false;
        Log.i(TAG, "MediaProjection stopped");
    }

    private static final long WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L; // 10 minutes

    private void acquireCaptureWakeLock() {
        if (mWakeLock == null || mWakeLock.isHeld()) return;
        // Use a timeout to prevent indefinite battery drain if the service
        // crashes without calling releaseCaptureWakeLock(). The capture loop
        // re-acquires it every cycle so this is safe for normal operation.
        mWakeLock.acquire(WAKELOCK_TIMEOUT_MS);
        Log.i(TAG, "WakeLock acquired with " + WAKELOCK_TIMEOUT_MS + "ms timeout");
    }

    private void releaseCaptureWakeLock() {
        if (mWakeLock == null || !mWakeLock.isHeld()) return;
        mWakeLock.release();
        Log.i(TAG, "WakeLock released");
    }

    public class LocalBinder extends Binder {
        CaptureService getService() { return CaptureService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return new LocalBinder(); }

    public boolean isCapturing() { return capture; }

    private void createNotificationChannel() {
        // minSdk=29 > O=26, notification channels always available
        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                "MindPulse Service Channel",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(serviceChannel);

        // Create alert channel for stopped notifications
        NotificationChannel alertChannel = new NotificationChannel(
                "screenomics_alert_id",
                "MindPulse Alerts",
                NotificationManager.IMPORTANCE_HIGH
        );
        alertChannel.setDescription("Alerts when screen capture stops");
        notificationManager.createNotificationChannel(alertChannel);
    }

    private void showStoppedNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int intentFlags;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            intentFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        } else {
            intentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, intentFlags);

        // minSdk=29 > O=26, notification channels always available
        Notification notification = new Notification.Builder(this, "screenomics_alert_id")
                .setSmallIcon(R.drawable.dna)
                .setContentTitle("MindPulse Capture Stopped")
                .setContentText("Screen capture was stopped. Tap to restart.")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();

        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.notify(100, notification);
        Log.i(TAG, "Stopped notification shown to user");
    }

    private boolean hasEnoughStorageForCapture() {
        File extDir = getApplicationContext().getExternalFilesDir(null);
        if (extDir == null) {
            Log.e("SCREENOMICS_CAPTURE", "Storage unavailable: external files dir is null");
            return false;
        }
        long usableBytes = extDir.getUsableSpace();
        if (usableBytes >= MIN_FREE_SPACE_BYTES) {
            return true;
        }

        long now = System.currentTimeMillis();
        if (now - lastLowStorageLogMs >= LOW_STORAGE_LOG_THROTTLE_MS) {
            lastLowStorageLogMs = now;
            String msg = "Low storage, skipping capture. usable_bytes=" + usableBytes
                    + " threshold_bytes=" + MIN_FREE_SPACE_BYTES;
            Log.w("SCREENOMICS_CAPTURE", msg);
            Logger.e(getApplicationContext(), "CAPTURE_LOW_STORAGE " + msg);
        }
        return false;
    }
}
