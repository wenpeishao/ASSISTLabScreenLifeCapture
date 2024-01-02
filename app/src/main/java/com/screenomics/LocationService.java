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

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;


public class LocationService extends Service {

    private static final String LOCATION_CHANNEL_ID = "screenomics_location_id";

    private FusedLocationProviderClient fusedLocationClient;

    LocationRequest locationRequest;

    @Override
    public void onCreate(){
        Log.d("SCREENOMICS", "Location Service onCreate");
        super.onCreate();
        createLocationRequest();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

    }

    //...
    protected void createLocationRequest() {

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
        Log.d("SCREENOMICS", "Location Service Bound");

        return new LocalBinder();
    }

    private void createNotificationChannel() {
        Log.d("SCREENOMICS", "Creating Notification Channel");
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

            Log.d("SCREENOMICS Locations", currentLocation.getLatitude() + "," + currentLocation.getLongitude());
        }
    };

    private void startLocationUpdates() {
        Log.d("SCREENOMICS", "Starting Location Updates");
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            Log.d("SCREENOMICS", "No Location Permissions");
            return;
        }
        fusedLocationClient.requestLocationUpdates(locationRequest,
                locationCallback,
                Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent receivedIntent, int flags, int startId) {
        Log.d("SCREENOMICS", "Location Service Started");
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
}
