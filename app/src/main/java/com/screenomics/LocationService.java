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

            Log.d(TAG, currentLocation.getLatitude() + "," + currentLocation.getLongitude());

            //write the location to a file
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            String hash = prefs.getString("hash", "00000000").substring(0, 8);
            Date date = new Date();
            String filename = "/" + hash + "_" + sdf.format(date) + "_gps.json";
            String dir = getApplicationContext().getExternalFilesDir(null).getAbsolutePath();


            JSONObject jsonObject = new JSONObject();
            try {
                DateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
                jsonObject.put("latitude", currentLocation.getLatitude());
                jsonObject.put("longitude", currentLocation.getLongitude());
                jsonObject.put("timestamp", formatter.format(date));

                String jsonString = jsonObject.toString();

                Log.d(TAG, "Writing file contents: " + jsonString);

                File locationFile = new File(dir + "/images", filename);

                FileWriter writer = new FileWriter(locationFile);
                BufferedWriter bufferedWriter = new BufferedWriter(writer);
                bufferedWriter.write(jsonString);
                bufferedWriter.close();

                encryptFile(filename);

            } catch (JSONException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    };

    private void startLocationUpdates() {
        Log.d(TAG, "Starting Location Updates");
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            Log.d(TAG, "No Location Permissions");
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
                Encryptor.encryptFile(key, filename, dir + "/images" + filename, dir + "/encrypt" + filename);
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


    /*
    private void encryptImage(Bitmap bitmap, String descriptor) {


        try {
            fos = new FileOutputStream(dir + "/images" + screenshot);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, fos);
            try {
                Encryptor.encryptFile(key, screenshot, dir + "/images" + screenshot, dir + "/encrypt" + screenshot);
                Log.i(TAG, "Encryption done");
            } catch (Exception e) {
                e.printStackTrace();
            }
            File f = new File(dir+"/images"+screenshot);
            if (f.delete()) Log.e(TAG, "file deleted: " + dir +"/images" + screenshot);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } finally {
            try {
                if (fos != null) fos.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            bitmap.recycle();
        }
    }
     */
}
