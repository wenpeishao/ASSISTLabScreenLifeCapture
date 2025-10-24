package com.screenomics;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.preference.PreferenceManager;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import android.content.SharedPreferences;

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


public class LocationService extends Service {

    private static final String TAG = "GPS";
    private static final String LOCATION_CHANNEL_ID = "screenomics_location_id";

    private FusedLocationProviderClient fusedLocationClient;

    LocationRequest locationRequest;

    private static final DateFormat sdf = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss");

    @Override
    public void onCreate(){
        Log.d(TAG, "Location Service onCreate");
        super.onCreate();
        createLocationRequest();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

    }

    protected void createLocationRequest() {
        // Requested interval is 120 seconds
        locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
                .build();
    }

    public class LocalBinder extends Binder {
        LocationService getService() {
            return LocationService.this;
        }
    }


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "Location Service Bound");

        return new LocalBinder();
    }

    private void createNotificationChannel() {
        Log.d(TAG, "Creating Notification Channel");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    LOCATION_CHANNEL_ID,
                    "Screenomics Location Channel",
                    NotificationManager.IMPORTANCE_HIGH
            );
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(serviceChannel);
        }
    }

    private LocationCallback locationCallback = new LocationCallback() {
        @Override
        public void onLocationResult(LocationResult locationResult) {
            super.onLocationResult(locationResult);
            Location currentLocation = locationResult.getLastLocation();

            if (currentLocation == null) {
                Log.w(TAG, "Received null location");
                return;
            }

            Log.d(TAG, "GPS data collected: " + currentLocation.getLatitude() + "," + currentLocation.getLongitude());

            //write the location to a file
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            String hash = prefs.getString("hash", "00000000").substring(0, 8);
            String keyRaw = prefs.getString("key", "");
            byte[] key;
            try {
                key = Encryptor.deriveAesKeyFromToken(keyRaw);
            } catch (Exception e) {
                Log.e(TAG, "Failed to derive AES key from token", e);
                return;
            }
            Date date = new Date();

            // Generate IV first for filename
            byte[] iv = SecureFileUtils.generateSecureIV();
            String filename = "/" + SecureFileUtils.generateSecureFilename(hash, "gps", "json", iv);
            String dir = getApplicationContext().getExternalFilesDir(null).getAbsolutePath();


            JSONObject jsonObject = new JSONObject();
            try {
                DateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
                jsonObject.put("latitude", currentLocation.getLatitude());
                jsonObject.put("longitude", currentLocation.getLongitude());
                jsonObject.put("timestamp", formatter.format(date));

                String jsonString = jsonObject.toString();

                Log.d(TAG, "Writing file contents: " + jsonString);

                // Use temp file first
                File tempFile = new File(dir + "/temp_gps.json");

                FileWriter writer = new FileWriter(tempFile);
                BufferedWriter bufferedWriter = new BufferedWriter(writer);
                bufferedWriter.write(jsonString);
                bufferedWriter.close();

                // Encrypt directly to final location with the IV from filename
                String encryptPath = dir + "/encrypt" + filename;
                try {
                    Encryptor.encryptFile(key, tempFile.getAbsolutePath(), encryptPath, iv);
                } catch (Exception encryptException) {
                    Log.e(TAG, "Encryption failed for GPS data", encryptException);
                }

                // Delete temp file
                if (tempFile.delete()) {
                    Log.d(TAG, "Temp GPS file deleted");
                }

            } catch (JSONException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    };

    private void startLocationUpdates() {
        Log.d(TAG, "Starting Location Updates");

        // Check if we have the necessary location permissions
        if (!PermissionHelper.hasPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) &&
            !PermissionHelper.hasPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)) {
            Log.w(TAG, "Location permissions not granted. Location tracking will not work.");

            // Show notification to user about missing permissions
            showLocationPermissionNotification();
            return;
        }
        fusedLocationClient.requestLocationUpdates(locationRequest,
                locationCallback,
                Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent receivedIntent, int flags, int startId) {
        Log.d(TAG, "Location Service Started");
        createNotificationChannel();

        Intent notificationIntent = new Intent(this, MainActivity.class);
        int intentflags;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
            intentflags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        }else{
            intentflags = PendingIntent.FLAG_UPDATE_CURRENT;
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(this,0, notificationIntent, intentflags);

        Notification notification = new NotificationCompat.Builder(this, LOCATION_CHANNEL_ID)
                .setContentTitle("SCREENOMICS")
                .setContentTitle("Screenomics Location Updates Running")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .build();
        //startForeground(AppConstants.LOCATION_SERVICE_NOTIF_ID, notification);
        ServiceCompat.startForeground(this,22, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);

        startLocationUpdates();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        fusedLocationClient.removeLocationUpdates(locationCallback);
    }

    private void encryptFile(String filename){
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        String keyRaw = prefs.getString("key", "");
        byte[] key = Converter.hexStringToByteArray(keyRaw);
        //FileOutputStream fos = null;

        String dir = getApplicationContext().getExternalFilesDir(null).getAbsolutePath();

        //String filename = "/" + hash + "_" + sdf.format(date) + "_gps.txt";

        //try{
            //fos = new FileOutputStream(dir + "/images" + filename);

            try{
                // Generate IV for legacy encryption (needs cleanup)
                byte[] iv = SecureFileUtils.generateSecureIV();
                Encryptor.encryptFile(key, dir + "/images" + filename, dir + "/encrypt" + filename, iv);
            } catch (Exception e) {
                e.printStackTrace();
            }
            File f = new File(dir + "/images" + filename);
            if (f.delete()) Log.e(TAG, "file deleted: " + dir +"/images" + filename);
        /*} catch (FileNotFoundException e) {
            e.printStackTrace();
        } finally {
            try {
                if (fos != null) fos.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }*/
    }

    private void showLocationPermissionNotification() {
        createNotificationChannel();

        // Create pending intent to open app settings
        Intent settingsIntent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        settingsIntent.setData(android.net.Uri.parse("package:" + getPackageName()));
        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent settingsPendingIntent = PendingIntent.getActivity(
            this,
            0,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, LOCATION_CHANNEL_ID)
            .setContentTitle("Location Permission Required")
            .setContentText("Tap to enable location permissions for data collection")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(settingsPendingIntent)
            .addAction(android.R.drawable.ic_menu_preferences, "Settings", settingsPendingIntent)
            .build();

        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.notify(2001, notification);
    }

}
