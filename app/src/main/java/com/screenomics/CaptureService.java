package com.screenomics;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
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
import android.os.PowerManager;
import android.os.Process;
import androidx.preference.PreferenceManager;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.ServiceCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;


public class CaptureService extends Service {
    private static final String TAG = "Screencapture";
    private static final String CHANNEL_ID = "screenomics_id";
    private static final DateFormat sdf = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss");
    private static Intent intent;
    private static int resultCode;
    private static int screenDensity;
    private MediaProjection mMediaProjection;
    private MediaProjectionManager mProjectionManager;
    private MediaProjectionCallback mMediaProjectionCallback;
    private ImageReader mImageReader;
    private KeyguardManager mKeyguardManager;
    private VirtualDisplay mVirtualDisplay;
    private static final int DISPLAY_WIDTH = 720;
    private static final int DISPLAY_HEIGHT = 1280;
    private Runnable captureInterval;
    private Runnable insertStartImage;
    private Runnable insertPauseImage;
    private Runnable projection;
    private Handler mHandler = new Handler();
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    public static byte[] key;
    private static ByteBuffer buffer;
    private static int pixelStride;
    private static int rowPadding;
    private static boolean capture = false;
    private boolean mInitializing = false;

    private ActivityManager mActivityManager;
    private PowerManager.WakeLock mWakeLock;

    private class ImageAvailableListener implements ImageReader.OnImageAvailableListener {
        @Override
        public void onImageAvailable(ImageReader reader) {
            mBackgroundHandler.post(new ImageProcessor(reader.acquireLatestImage()));
            /*Image image = reader.acquireLatestImage();
            if (image != null) {
                Image.Plane[] planes = image.getPlanes();
                buffer = planes[0].getBuffer();
                pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                rowPadding = rowStride - pixelStride * DISPLAY_WIDTH;
                image.close();
            }*/
        }
    }

    private class ImageProcessor implements Runnable {
        private final Image mImage;

        public ImageProcessor(Image image) {
            mImage = image;
        }

        @Override
        public void run() {
            if (mImage != null) {
                Image.Plane[] planes = mImage.getPlanes();
                buffer = planes[0].getBuffer();
                pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                rowPadding = rowStride - pixelStride * DISPLAY_WIDTH;
                mImage.close();
            }
        }
    }

    // Removed encryptTextFile - encryption now handled inline with proper IV in filename

    private void encryptImage(Bitmap bitmap, String descriptor) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String hash = prefs.getString("hash", "00000000").substring(0, 8);
        String keyRaw = prefs.getString("key", "");
        byte[] key;
        try {
            key = Encryptor.deriveAesKeyFromToken(keyRaw);
        } catch (Exception e) {
            Log.e("SCREENOMICS_CAPTURE", "Failed to derive AES key from token", e);
            return;
        }   FileOutputStream fos = null;
        String dir = getApplicationContext().getExternalFilesDir(null).getAbsolutePath();

        // Generate IV first since we need it for the filename
        byte[] iv = SecureFileUtils.generateSecureIV();
        String screenshot = "/" + SecureFileUtils.generateSecureFilename(hash, "screenshot", "png", iv);

        Log.d("SCREENOMICS_CAPTURE", "Creating screenshot: " + screenshot);
        Log.d("SCREENOMICS_CAPTURE", "Hash: " + hash);

        try {
            // Use temp file for unencrypted image
            String tempImagePath = dir + "/temp_image.jpg";
            String encryptPath = dir + "/encrypt" + screenshot;

            Log.d("SCREENOMICS_CAPTURE", "Saving temp image to: " + tempImagePath);
            fos = new FileOutputStream(tempImagePath);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, fos);
            Log.d("SCREENOMICS_CAPTURE", "Image saved, size: " + new File(tempImagePath).length() + " bytes");

            try {
                Log.d("SCREENOMICS_CAPTURE", "Encrypting file to: " + encryptPath);
                // Encrypt with pre-generated IV that matches filename
                Encryptor.encryptFile(key, tempImagePath, encryptPath, iv);
                Log.i("SCREENOMICS_CAPTURE", "Encryption completed successfully");
                Log.d("SCREENOMICS_CAPTURE", "Encrypted file size: " + new File(encryptPath).length() + " bytes");
            } catch (Exception e) {
                Log.e("SCREENOMICS_CAPTURE", "Encryption failed: " + e.getMessage());
                e.printStackTrace();
            }

            File f = new File(tempImagePath);
            if (f.delete()) {
                Log.d("SCREENOMICS_CAPTURE", "Temp image deleted: " + tempImagePath);
            } else {
                Log.w("SCREENOMICS_CAPTURE", "Failed to delete temp image: " + tempImagePath);
            }
        } catch (FileNotFoundException e) {
            Log.e("SCREENOMICS_CAPTURE", "Failed to save image: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                if (fos != null) fos.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            bitmap.recycle();
            Log.d("SCREENOMICS_CAPTURE", "Bitmap recycled");
        }
    }

    // Called when Screen Cast is disabled
    private class MediaProjectionCallback extends MediaProjection.Callback {
        @Override
        public void onStop() {
            Log.e(TAG, "MediaProjection stopped by system");
            try {
                capture = false;
                mHandler.removeCallbacksAndMessages(null);
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

        // Acquire WakeLock to prevent CPU sleep during capture
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MindPulse:CaptureWakeLock");
        mWakeLock.acquire();
        Log.i(TAG, "WakeLock acquired");

        mActivityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        captureInterval = new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);
                if (!capture) {
                    Log.d("SCREENOMICS_CAPTURE", "Capture disabled, skipping");
                    return;
                }
                if (buffer != null && !mKeyguardManager.isKeyguardLocked()) {
                    Log.d("SCREENOMICS_CAPTURE", "Taking screenshot and collecting metadata");
                    Bitmap bitmap = Bitmap.createBitmap(DISPLAY_WIDTH + rowPadding / pixelStride, DISPLAY_HEIGHT, Bitmap.Config.ARGB_8888);
                    bitmap.copyPixelsFromBuffer(buffer);
                    encryptImage(bitmap, "image");
                    buffer.rewind();

                    /*List<ActivityManager.RecentTaskInfo> mRecentTasks = mActivityManager.getRecentTasks(1, ActivityManager.RECENT_WITH_EXCLUDED);
                    for(int i = 0; i < mRecentTasks.size(); i++){
                        Log.d("SCREENOMICS Tasks", mRecentTasks.get(i).topActivity.getPackageName());
                    }*/
                    /*List<ActivityManager.RunningAppProcessInfo> mRunningProcesses = mActivityManager.getRunningAppProcesses();

                    for(int i = 0; i < mRunningProcesses.size(); i++){
                        Log.d("SCREENOMICS Running Processes", mRunningProcesses.get(i).processName);
                    }*/

                    // get and write the foreground app if we found one
                    String topPackageName = "";
                    //long foregroundTime = 0;
                    try{
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP){
                            UsageStatsManager mUsageStatsManager = (UsageStatsManager) getSystemService(Service.USAGE_STATS_SERVICE);
                            List<UsageStats> stats =
                                    mUsageStatsManager.queryUsageStats(
                                            UsageStatsManager.INTERVAL_DAILY,
                                            System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1),
                                            System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1));
                            if (stats != null) {
                                Log.d("SCREENOMICS", "STATS NOT NULL " + stats.size());
                                SortedMap<Long, UsageStats> mySortedMap = new TreeMap<>();
                                for (UsageStats usageStats : stats) {
                                    //Log.d("USAGE STATS", usageStats.toString());
                                    mySortedMap.put(usageStats.getLastTimeUsed(), usageStats);
                                }
                                if (!mySortedMap.isEmpty()) {
                                    topPackageName = mySortedMap.get(mySortedMap.lastKey()).getPackageName();
                                    //foregroundTime = mySortedMap.get(mySortedMap.lastKey()).getTotalTimeVisible();
                                }
                            } else {
                                Log.d("SCREENOMICS", "STATs NULL");
                                topPackageName = mActivityManager.getRunningAppProcesses().get(0).processName;
                            }
                        }else{
                            Log.d("SCREENOMICS", "SDK VERSION TOO LOW");
                        }
                    }catch (Exception e) {
                        e.printStackTrace();
                    }
                    Log.d("SCREENOMICS Proc", "Top Package Name: " + topPackageName);
                    //Log.d("SCREENOMICS Proc", "Foreground Time: " + foregroundTime);

                    if(topPackageName != null && !topPackageName.isEmpty()){
                        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
                        String hash = prefs.getString("hash", "00000000").substring(0, 8);
                        String keyRaw = prefs.getString("key", "");
                        byte[] key;
                        try {
                            key = Encryptor.deriveAesKeyFromToken(keyRaw);
                        } catch (Exception e) {
                            Log.e("SCREENOMICS_CAPTURE", "Failed to derive AES key from token", e);
                            return;
                        }    Date date = new Date();

                        // Generate IV first for filename
                        byte[] iv = SecureFileUtils.generateSecureIV();
                        String filename = "/" + SecureFileUtils.generateSecureFilename(hash, "metadata", "json", iv);
                        String dir = getApplicationContext().getExternalFilesDir(null).getAbsolutePath();

                        Log.d("SCREENOMICS_CAPTURE", "Creating metadata file: " + filename);
                        Log.d("SCREENOMICS_CAPTURE", "Foreground app: " + topPackageName);


                        JSONObject jsonObject = new JSONObject();
                        try {
                            DateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

                            jsonObject.put("foreground", topPackageName);
                            jsonObject.put("timestamp", formatter.format(date));

                            String jsonString = jsonObject.toString();

                            Log.d(TAG, "Writing file contents: " + jsonString);

                            // Use temp file first
                            File tempFile = new File(dir + "/temp_metadata.json");

                            FileWriter writer = new FileWriter(tempFile);
                            BufferedWriter bufferedWriter = new BufferedWriter(writer);
                            bufferedWriter.write(jsonString);
                            bufferedWriter.close();

                            // Encrypt directly to final location with the IV from filename
                            String encryptPath = dir + "/encrypt" + filename;
                            try {
                                Encryptor.encryptFile(key, tempFile.getAbsolutePath(), encryptPath, iv);
                            } catch (Exception encryptException) {
                                Log.e("SCREENOMICS_CAPTURE", "Encryption failed for metadata", encryptException);
                            }

                            // Delete temp file
                            if (tempFile.delete()) {
                                Log.d("SCREENOMICS_CAPTURE", "Temp metadata file deleted");
                            }

                        } catch (JSONException e) {
                            throw new RuntimeException(e);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                } else {
                    Log.d("SCREENOMICS_CAPTURE", "Skipping capture - buffer null or screen locked");
                }
                mHandler.postDelayed(captureInterval, 5000);
                Log.d("SCREENOMICS_CAPTURE", "Scheduled next capture in 5 seconds");
            }
        };

        // To insert a 'start capture' image
        insertStartImage = new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);
//                if (!capture) return;
                InputStream is = getResources().openRawResource(R.raw.resumerecord);
                Bitmap bitmap = BitmapFactory.decodeStream(is);
                encryptImage(bitmap, "resume");

            }
        };

        insertPauseImage = new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);
//                if (!capture) return;
                InputStream is = getResources().openRawResource(R.raw.pauserecord);
                Bitmap bitmap = BitmapFactory.decodeStream(is);
                encryptImage(bitmap, "pause");

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

        HandlerThread thread = mBackgroundThread;
        if (thread != null) {
            mBackgroundHandler = new Handler(thread.getLooper());
            mHandler = new Handler();
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

        Notification notification = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.dna)
                    .setContentTitle("MindPulse is currently enabled")
                    .setContentText("If this notification disappears, please re-enable it from the application.")
                    .setContentIntent(pendingIntent)
                    .build();
        }

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
            buffer = null;
            capture = true;

            mHandler.post(insertStartImage);

            //mHandler.post(captureInterval);
            mHandler.postDelayed(captureInterval, 12345, 2000);
        } catch (Exception e) {
            e.printStackTrace();
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
            mImageReader = ImageReader.newInstance(DISPLAY_WIDTH, DISPLAY_HEIGHT, PixelFormat.RGBA_8888, 5);
            mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                TAG, DISPLAY_WIDTH, DISPLAY_HEIGHT, screenDensity,
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
        //mHandler.removeCallbacksAndMessages(captureInterval);
        mHandler.removeCallbacksAndMessages(null);
        mHandler.post(insertPauseImage);

        if (mBackgroundThread != null) {
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

        // Release WakeLock
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
            Log.i(TAG, "WakeLock released");
        }

        Log.e(TAG, "I'm destroyed!");
    }

    private void destroyImageReader() {
        if (mImageReader != null) {
            mImageReader.setOnImageAvailableListener(null, null);
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
        Log.i(TAG, "MediaProjection stopped");

        int intentflags;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
            intentflags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        }else{
            intentflags = PendingIntent.FLAG_UPDATE_CURRENT;
        }

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, intentflags);
        Notification notification = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("MindPulse is NOT Running!")
                    .setContentText("Please restart the application!")
                    .setContentIntent(pendingIntent)
                    .build();
        }
        //startForeground(1, notification);
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        }else{
            startForeground(1, notification);
        }
        capture = false;
    }

    public class LocalBinder extends Binder {
        CaptureService getService() { return CaptureService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return new LocalBinder(); }

    public boolean isCapturing() { return capture; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "MindPulse Service Channel",
                    NotificationManager.IMPORTANCE_HIGH
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

        Notification notification = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, "screenomics_alert_id")
                    .setSmallIcon(R.drawable.dna)
                    .setContentTitle("MindPulse Capture Stopped")
                    .setContentText("Screen capture was stopped. Tap to restart.")
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build();
        }

        if (notification != null) {
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.notify(100, notification);
            Log.i(TAG, "Stopped notification shown to user");
        }
    }
}
