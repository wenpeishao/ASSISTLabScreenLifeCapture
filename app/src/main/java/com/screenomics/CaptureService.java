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

    private ActivityManager mActivityManager;

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

    private void encryptTextFile(String filename){
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        String keyRaw = prefs.getString("key", "");
        byte[] key = Converter.hexStringToByteArray(keyRaw);

        String dir = getApplicationContext().getExternalFilesDir(null).getAbsolutePath();

        try{
            Encryptor.encryptFile(key, filename, dir + "/images" + filename, dir + "/encrypt" + filename);
        } catch (Exception e) {
            e.printStackTrace();
        }
        File f = new File(dir + "/images" + filename);
        if (f.delete()) Log.e(TAG, "file deleted: " + dir +"/images" + filename);

    }

    private void encryptImage(Bitmap bitmap, String descriptor) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String hash = prefs.getString("hash", "00000000").substring(0, 8);
        String keyRaw = prefs.getString("key", "");
        byte[] key = Converter.hexStringToByteArray(keyRaw);
        FileOutputStream fos = null;
        Date date = new Date();
        String dir = getApplicationContext().getExternalFilesDir(null).getAbsolutePath();
        long timestamp = date.getTime();
        String screenshot = "/" + hash + "_" + timestamp + "_screenshot.png";
        
        Log.d("SCREENOMICS_CAPTURE", "Creating screenshot: " + screenshot);
        Log.d("SCREENOMICS_CAPTURE", "Hash: " + hash + ", Timestamp: " + timestamp);

        try {
            String imagePath = dir + "/images" + screenshot;
            String encryptPath = dir + "/encrypt" + screenshot;
            
            Log.d("SCREENOMICS_CAPTURE", "Saving image to: " + imagePath);
            fos = new FileOutputStream(imagePath);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, fos);
            Log.d("SCREENOMICS_CAPTURE", "Image saved, size: " + new File(imagePath).length() + " bytes");
            
            try {
                Log.d("SCREENOMICS_CAPTURE", "Encrypting file to: " + encryptPath);
                Encryptor.encryptFile(key, screenshot, imagePath, encryptPath);
                Log.i("SCREENOMICS_CAPTURE", "Encryption completed successfully");
                Log.d("SCREENOMICS_CAPTURE", "Encrypted file size: " + new File(encryptPath).length() + " bytes");
            } catch (Exception e) {
                Log.e("SCREENOMICS_CAPTURE", "Encryption failed: " + e.getMessage());
                e.printStackTrace();
            }
            File f = new File(imagePath);
            if (f.delete()) {
                Log.d("SCREENOMICS_CAPTURE", "Original image deleted: " + imagePath);
            } else {
                Log.w("SCREENOMICS_CAPTURE", "Failed to delete original image: " + imagePath);
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
            Log.e(TAG, "I'm stopped");
            try {
                //stopCapturing();
                destroyImageReader();
            } catch (RuntimeException e) {
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

        mActivityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        captureInterval = new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);
                if (!capture) return;
                if (buffer != null && !mKeyguardManager.isKeyguardLocked()) {
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

                    if(topPackageName != ""){
                        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
                        String hash = prefs.getString("hash", "00000000").substring(0, 8);
                        Date date = new Date();
                        long timestamp = date.getTime();
                        String filename = "/" + hash + "_" + timestamp + "_metadata.json";
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

                            File locationFile = new File(dir + "/images", filename);

                            FileWriter writer = new FileWriter(locationFile);
                            BufferedWriter bufferedWriter = new BufferedWriter(writer);
                            bufferedWriter.write(jsonString);
                            bufferedWriter.close();

                            encryptTextFile(filename);

                        } catch (JSONException e) {
                            throw new RuntimeException(e);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
                mHandler.postDelayed(captureInterval, 5000);
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
        Intent notificationIntent = new Intent(this, MainActivity.class);
        int intentflags;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
            intentflags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        }else{
            intentflags = PendingIntent.FLAG_UPDATE_CURRENT;
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(this,0, notificationIntent, intentflags);
        /*Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.dna)
                .setContentTitle("ScreenLife Capture is running!")
                .setContentText("If this notification disappears, please re-enable it from the application!")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();*/
        Notification notification = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.dna)
                    .setContentTitle("ScreenLife Capture is currently enabled")
                    .setContentText("If this notification disappears, please re-enable it from the application.")
                    .setContentIntent(pendingIntent)
                    .build();
        }

        Log.i(TAG, "Starting foreground service");
        /*if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        }else{
            startForeground(1, notification);
        }*/

        ServiceCompat.startForeground(this,1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        // getting crash reports about:
        //Media projections require a foreground service of type ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        // the supposed solution is to delay the call to getMediaProjection after startForeground
  /*      projection = new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "Projection");
                mMediaProjection = mProjectionManager.getMediaProjection(resultCode, intent);
                mMediaProjectionCallback = new MediaProjectionCallback();
                mMediaProjection.registerCallback(mMediaProjectionCallback, null);

            }
        };
        mHandler.postDelayed(projection, 1000);
*/
        mHandler.postDelayed( new Runnable(){
            @Override
            public void run(){
                mMediaProjection = mProjectionManager.getMediaProjection(resultCode, intent);
                mMediaProjectionCallback = new MediaProjectionCallback();
                mMediaProjection.registerCallback(mMediaProjectionCallback, null);
                createVirtualDisplay();
                startCapturing();
            }

        }, 750);

        /*mMediaProjection = mProjectionManager.getMediaProjection(resultCode, intent);
        mMediaProjectionCallback = new MediaProjectionCallback();
        mMediaProjection.registerCallback(mMediaProjectionCallback, null);

        createVirtualDisplay();
        startCapturing();*/
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
        if (mMediaProjection != null) {
            //mImageReader = ImageReader.newInstance(DISPLAY_WIDTH, DISPLAY_HEIGHT, ImageFormat.FLEX_RGB_888, 2);
            mImageReader = ImageReader.newInstance(DISPLAY_WIDTH, DISPLAY_HEIGHT, PixelFormat.RGBA_8888, 5);
            mVirtualDisplay = mMediaProjection.createVirtualDisplay(TAG, DISPLAY_WIDTH, DISPLAY_HEIGHT, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mImageReader.getSurface(), null, null);
            mImageReader.setOnImageAvailableListener(new ImageAvailableListener(), mBackgroundHandler);
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
                    .setContentTitle("ScreenLife Capture is NOT Running!")
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
                    "Screenomics Service Channel",
                    NotificationManager.IMPORTANCE_HIGH
            );
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(serviceChannel);
        }
    }
}
