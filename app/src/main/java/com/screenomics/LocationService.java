package com.screenomics;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.Date;

public class LocationService extends Service {

    private static final String TAG = "GPS";
    private static final String LOCATION_CHANNEL_ID = "screenomics_location_id";
    private static final long FLUSH_INTERVAL_MS = 10 * 60 * 1000L;
    private static final long HEALTH_CHECK_INTERVAL_MS = 60_000L;
    private static final String GPS_BUFFER_FILENAME = "gps_buffer.jsonl";
    private static final String APPUSAGE_BUFFER_FILENAME = "appusage_buffer.jsonl";

    private FusedLocationProviderClient fusedLocationClient;
    private LocationRequest locationRequest;
    private final List<JSONObject> gpsBuffer = new ArrayList<>();
    private final List<JSONObject> appUsageBuffer = new ArrayList<>();
    private final Object bufferLock = new Object();
    private long lastFlushTimeMs = System.currentTimeMillis();
    private String lastKnownForeground = "";
    private long lastForegroundEventTimeMs = 0;

    private Handler healthHandler;
    private final Runnable healthRunnable = new Runnable() {
        @Override
        public void run() {
            HealthChecker.check(LocationService.this);
            healthHandler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS);
        }
    };

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
                    "MindPulse Location Channel",
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

            // Poll app usage events BEFORE building GPS JSON so lastKnownForeground is fresh
            pollAppUsageEvents();

            Log.d(TAG, "GPS data: " + currentLocation.getLatitude() + "," + currentLocation.getLongitude());

            try {
                long nowMs = System.currentTimeMillis();
                DateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
                isoFmt.setTimeZone(TimeZone.getTimeZone("UTC"));

                JSONObject json = new JSONObject();
                json.put("latitude",  currentLocation.getLatitude());
                json.put("longitude", currentLocation.getLongitude());
                json.put("timestamp", isoFmt.format(new Date(nowMs)));
                json.put("epoch_ms", nowMs);

                // Full Location sensor data
                json.put("accuracy_m", currentLocation.hasAccuracy() ? currentLocation.getAccuracy() : JSONObject.NULL);
                json.put("altitude_m", currentLocation.hasAltitude() ? currentLocation.getAltitude() : JSONObject.NULL);
                json.put("speed_mps", currentLocation.hasSpeed() ? currentLocation.getSpeed() : JSONObject.NULL);
                json.put("bearing_deg", currentLocation.hasBearing() ? currentLocation.getBearing() : JSONObject.NULL);
                json.put("provider", currentLocation.getProvider());
                json.put("location_epoch_ms", currentLocation.getTime());

                // Device context
                json.put("timezone", TimeZone.getDefault().getID());
                json.put("battery_pct", getBatteryPct());
                json.put("screen_on", isScreenOn());

                String foregroundApp = getForegroundApp();
                if (foregroundApp != null && !foregroundApp.isEmpty()) {
                    json.put("foreground_app", foregroundApp);
                }

                synchronized (bufferLock) {
                    gpsBuffer.add(json);
                    appendToBufferFile(json);
                    if (nowMs - lastFlushTimeMs >= FLUSH_INTERVAL_MS) {
                        flushCombinedBuffer();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to buffer GPS data", e);
            }
        }
    };

    /** Flush buffered sensor data as 4 separate encrypted files: gps, appusage, devicestate, logdata. */
    private void flushCombinedBuffer() {
        List<JSONObject> gpsSnapshot;
        List<JSONObject> appUsageSnapshot;
        synchronized (bufferLock) {
            gpsSnapshot = new ArrayList<>(gpsBuffer);
            appUsageSnapshot = new ArrayList<>(appUsageBuffer);
            gpsBuffer.clear();
            appUsageBuffer.clear();
            lastFlushTimeMs = System.currentTimeMillis();
        }

        Context ctx = getApplicationContext();
        int queued = 0;

        // 1. GPS
        if (!gpsSnapshot.isEmpty()) {
            try {
                JSONArray gpsArray = new JSONArray();
                for (JSONObject obj : gpsSnapshot) gpsArray.put(obj);
                if (Logger.queueTextForUpload(ctx, gpsArray.toString(), "gps", "application/json")) {
                    queued++;
                    clearBufferFile();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to flush GPS data", e);
            }
        }

        // 2. App usage
        if (!appUsageSnapshot.isEmpty()) {
            try {
                JSONArray appArray = new JSONArray();
                for (JSONObject obj : appUsageSnapshot) appArray.put(obj);
                if (Logger.queueTextForUpload(ctx, appArray.toString(), "appusage", "application/json")) {
                    queued++;
                    clearAppUsageBufferFile();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to flush appusage data", e);
            }
        }

        // 3. Device state
        try {
            JSONObject deviceState = DeviceStateCollector.collectSnapshot(ctx);
            if (Logger.queueTextForUpload(ctx, deviceState.toString(), "devicestate", "application/json")) {
                queued++;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to flush device state", e);
        }

        // 4. Logdata (app logs + logcat)
        try {
            String logs = Logger.getAll(ctx);
            String logcat = Logger.captureOwnProcessLogcat();
            if ((logs != null && !logs.isEmpty()) || (logcat != null && !logcat.isEmpty())) {
                JSONObject logPayload = new JSONObject();
                logPayload.put("app_logs", logs != null ? logs : "");
                logPayload.put("logcat", logcat != null ? logcat : "");
                if (Logger.queueTextForUpload(ctx, logPayload.toString(), "logdata", "application/json")) {
                    Logger.reset(ctx);
                    queued++;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to flush logdata", e);
        }

        Log.d(TAG, "Flushed sensor data: " + gpsSnapshot.size() + " GPS, "
                + appUsageSnapshot.size() + " appusage, " + queued + " streams queued");
    }

    private File getGpsBufferFile() {
        return new File(getExternalFilesDir(null), GPS_BUFFER_FILENAME);
    }

    private void appendToBufferFile(JSONObject json) {
        try (FileWriter fw = new FileWriter(getGpsBufferFile(), true)) {
            fw.write(json.toString() + "\n");
        } catch (Exception e) {
            Log.e(TAG, "Failed to append to GPS buffer file", e);
        }
    }

    private void clearBufferFile() {
        try (FileWriter fw = new FileWriter(getGpsBufferFile(), false)) {
            // truncate
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear GPS buffer file", e);
        }
    }

    /** Recover GPS + appusage data stranded on disk from a previously killed instance. */
    private void flushStaleCombinedBuffer() {
        int flushed = Logger.flushPersistedSensorBuffers(getApplicationContext());
        if (flushed > 0) {
            Log.i(TAG, "Flushed " + flushed + " stale sensor entries from buffer files");
        }
    }

    private String getForegroundApp() {
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) getSystemService(Service.USAGE_STATS_SERVICE);
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
            // Fallback: daily aggregate
            java.util.List<android.app.usage.UsageStats> stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    now - java.util.concurrent.TimeUnit.DAYS.toMillis(1),
                    now + java.util.concurrent.TimeUnit.DAYS.toMillis(1));
            if (stats != null && !stats.isEmpty()) {
                java.util.SortedMap<Long, android.app.usage.UsageStats> sorted = new java.util.TreeMap<>();
                for (android.app.usage.UsageStats s : stats) sorted.put(s.getLastTimeUsed(), s);
                if (!sorted.isEmpty()) {
                    String pkg = sorted.get(sorted.lastKey()).getPackageName();
                    return pkg != null ? pkg : "";
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get foreground app", e);
        }
        return "";
    }

    // ---- Device context helpers ----

    private int getBatteryPct() {
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, filter);
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) return (int) ((level / (float) scale) * 100);
            }
        } catch (Exception e) { Log.w(TAG, "Failed to get battery pct", e); }
        return -1;
    }

    private boolean isScreenOn() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Exception e) { return true; }
    }

    // ---- App usage event stream ----

    private void pollAppUsageEvents() {
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) getSystemService(Service.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            long from = lastForegroundEventTimeMs > 0 ? lastForegroundEventTimeMs : now - 60_000L;
            UsageEvents events = usm.queryEvents(from, now);
            if (events == null) return;

            DateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            isoFmt.setTimeZone(TimeZone.getTimeZone("UTC"));

            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                int type = event.getEventType();
                if (type != UsageEvents.Event.MOVE_TO_FOREGROUND
                        && type != UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    continue;
                }
                long eventTime = event.getTimeStamp();
                // Skip already-processed events
                if (eventTime <= lastForegroundEventTimeMs) continue;

                String eventName = (type == UsageEvents.Event.MOVE_TO_FOREGROUND)
                        ? "FOREGROUND" : "BACKGROUND";
                String pkg = event.getPackageName();

                JSONObject obj = new JSONObject();
                obj.put("event", eventName);
                obj.put("package", pkg);
                obj.put("timestamp", isoFmt.format(new Date(eventTime)));
                obj.put("epoch_ms", eventTime);

                synchronized (bufferLock) {
                    appUsageBuffer.add(obj);
                    appendToAppUsageBufferFile(obj);
                }

                if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    lastKnownForeground = pkg;
                }
                lastForegroundEventTimeMs = eventTime;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to poll app usage events", e);
        }
    }

    private File getAppUsageBufferFile() {
        return new File(getExternalFilesDir(null), APPUSAGE_BUFFER_FILENAME);
    }

    private void appendToAppUsageBufferFile(JSONObject json) {
        try (FileWriter fw = new FileWriter(getAppUsageBufferFile(), true)) {
            fw.write(json.toString() + "\n");
        } catch (Exception e) {
            Log.e(TAG, "Failed to append to app usage buffer file", e);
        }
    }

    private void clearAppUsageBufferFile() {
        try (FileWriter fw = new FileWriter(getAppUsageBufferFile(), false)) {
            // truncate
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear app usage buffer file", e);
        }
    }

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
                .setContentTitle("MindPulse Location Updates Running")
                .setContentText("Background location updates are active for study data collection.")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();

        ServiceCompat.startForeground(this, 22, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);

        startLocationUpdates();
        flushStaleCombinedBuffer();

        healthHandler = new Handler(Looper.getMainLooper());
        healthHandler.postDelayed(healthRunnable, HEALTH_CHECK_INTERVAL_MS);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (healthHandler != null) {
            healthHandler.removeCallbacks(healthRunnable);
        }
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        } catch (SecurityException se) {
            Log.w(TAG, "SecurityException removing location updates (permissions may have been revoked)", se);
        }
        flushCombinedBuffer();
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
