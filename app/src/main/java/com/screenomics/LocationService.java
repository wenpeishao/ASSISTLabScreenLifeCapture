package com.screenomics;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
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
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.Date;

public class LocationService extends Service {

    private static final String TAG = "GPS";
    private static final String LOCATION_CHANNEL_ID = "screenomics_location_id";

    // Optional: change this if your collector expects a different folder
    private static final String OUTBOX_SUBDIR = "outbox";

    private FusedLocationProviderClient fusedLocationClient;
    private LocationRequest locationRequest;

    @Override
    public void onCreate(){
        Log.d(TAG, "Location Service onCreate");
        super.onCreate();
        createLocationRequest(Priority.PRIORITY_HIGH_ACCURACY);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
    }

    private void createLocationRequest(int priority) {
        // interval 10s (adjust as needed)
        locationRequest = new LocationRequest.Builder(priority, 10_000L).build();
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

    private final LocationCallback locationCallback = new LocationCallback() {
        @Override
        public void onLocationResult(LocationResult locationResult) {
            super.onLocationResult(locationResult);
            Location currentLocation = locationResult.getLastLocation();

            if (currentLocation == null) {
                Log.w(TAG, "Received null location");
                return;
            }

            Log.d(TAG, "GPS data: " + currentLocation.getLatitude() + "," + currentLocation.getLongitude());

            // ---- Plain JSON save into outbox; Batch will encrypt+upload later ----
            try {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
                String shortId = prefs.getString("hash", "00000000");
                if (shortId == null) shortId = "00000000";
                if (shortId.length() > 8) shortId = shortId.substring(0, 8);

                // Build UTC timestamp strings
                Date now = new Date();
                DateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
                isoFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
                String capturedIso = isoFmt.format(now);

                SimpleDateFormat fnameFmt = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);
                fnameFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
                String stamp = fnameFmt.format(now);

                // Compose JSON payload
                JSONObject json = new JSONObject();
                json.put("latitude",  currentLocation.getLatitude());
                json.put("longitude", currentLocation.getLongitude());
                json.put("timestamp", capturedIso);

                // Outbox path
                File base = getApplicationContext().getExternalFilesDir(null);
                File outbox = (OUTBOX_SUBDIR == null || OUTBOX_SUBDIR.isEmpty())
                        ? base
                        : new File(base, OUTBOX_SUBDIR);

                if (outbox != null && !outbox.exists() && !outbox.mkdirs()) {
                    Log.e(TAG, "Failed to create outbox: " + outbox);
                    return;
                }

                // Filename pattern: SHORTID_yyyyMMdd_HHmmss_SSS_gps.json
                String fileName = (shortId + "_" + stamp + "_gps.json");
                File outFile = new File(outbox, fileName);

                try (BufferedWriter bw = new BufferedWriter(new FileWriter(outFile))) {
                    bw.write(json.toString());
                }

                Log.d(TAG, "GPS JSON saved for Batch pickup: " + outFile.getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "Failed to write GPS JSON", e);
            }
        }
    };

    // ---- Permission helpers ----
    private boolean hasFineLocation() {
        return ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasCoarseLocation() {
        return ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return true; // not required pre-Q
    }

    private void startLocationUpdates() {
        Log.d(TAG, "Starting Location Updates");

        boolean fine = hasFineLocation();
        boolean coarse = hasCoarseLocation();

        if (!fine && !coarse) {
            Log.w(TAG, "Location permissions not granted. Location tracking will not work.");
            showLocationPermissionNotification();
            return;
        }

        // Choose priority based on permission level
        int priority = fine ? Priority.PRIORITY_HIGH_ACCURACY : Priority.PRIORITY_BALANCED_POWER_ACCURACY;
        createLocationRequest(priority);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocation()) {
            Log.w(TAG, "ACCESS_BACKGROUND_LOCATION not granted on Q+. Collection may pause in background.");
        }

        // Guard the API call with SecurityException
        try {
            fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
            );
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException while requesting location updates", se);
            showLocationPermissionNotification();
        }
    }

    @Override
    public int onStartCommand(Intent receivedIntent, int flags, int startId) {
        Log.d(TAG, "Location Service Started");
        createNotificationChannel();

        Intent notificationIntent = new Intent(this, MainActivity.class);
        int intentFlags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, intentFlags);

        Notification notification = new NotificationCompat.Builder(this, LOCATION_CHANNEL_ID)
                .setContentTitle("Screenomics Location Updates Running")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .build();

        ServiceCompat.startForeground(this, 22, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);

        startLocationUpdates();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        } catch (SecurityException se) {
            Log.w(TAG, "SecurityException removing location updates (permissions may have been revoked)", se);
        }
    }

    private void showLocationPermissionNotification() {
        createNotificationChannel();

        Intent settingsIntent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        settingsIntent.setData(android.net.Uri.parse("package:" + getPackageName()));
        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent settingsPendingIntent = PendingIntent.getActivity(
                this, 0, settingsIntent,
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
