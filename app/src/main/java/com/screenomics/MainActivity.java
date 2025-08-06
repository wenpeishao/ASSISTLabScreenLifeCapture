package com.screenomics;

import android.app.AlarmManager;
import android.Manifest;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.IBinder;
import androidx.preference.PreferenceManager;

import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;

import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import com.google.common.util.concurrent.ListenableFuture;
import org.joda.time.DateTime;
import org.joda.time.Duration;

import java.io.File;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.google.firebase.FirebaseApp;
import com.google.firebase.analytics.FirebaseAnalytics;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    public MediaProjectionManager mProjectionManager;
    public static int mScreenDensity;
    private static final int PERMISSION_REQUEST_NOTIFICATIONS = 1;
    private static final int REQUEST_CODE_MEDIA = 1000;
    private static final int REQUEST_CODE_PHONE = 1001;
    
    private Button infoButton;
    private Button logButton;
    private Button devButton;
    private Boolean recordingState;
    private int infoOpenCount = 0;
    
    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private MainPagerAdapter pagerAdapter;

    private LocationService locationService;

    private WorkManager mWorkManager;

    public boolean continueWithoutWifi = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        FirebaseApp.initializeApp(this);
        FirebaseAnalytics mFirebaseAnalytics = FirebaseAnalytics.getInstance(this);

        mWorkManager = WorkManager.getInstance(this);

        //WorkManager.getInstance(this).cancelAllWork();
        mWorkManager.cancelAllWork();

        //ListenableFuture<List<WorkInfo>> send_periodic1 = WorkManager.getInstance(this).getWorkInfosByTag("send_periodic");
        ListenableFuture<List<WorkInfo>> send_periodic1 = mWorkManager.getWorkInfosByTag("send_periodic");
        try {
            System.out.println(new StringBuilder().append("SENDPERIODIC: ").append(send_periodic1.get()).toString());
        } catch (ExecutionException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build();
        PeriodicWorkRequest workRequest = new PeriodicWorkRequest.Builder(
                SenderWorker.class,
                30,
                TimeUnit.MINUTES )
                .addTag("send_periodic")
                .setConstraints(constraints)
                .setInitialDelay(1, TimeUnit.HOURS)
                .build();
        //WorkManager.getInstance(this)
          //      .enqueueUniquePeriodicWork("send_periodic", ExistingPeriodicWorkPolicy.REPLACE, workRequest);
        mWorkManager.enqueueUniquePeriodicWork("send_periodic", ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, workRequest);

        //ListenableFuture<List<WorkInfo>> send_periodic = WorkManager.getInstance(this).getWorkInfosByTag("send_periodic");
        ListenableFuture<List<WorkInfo>> send_periodic = mWorkManager.getWorkInfosByTag("send_periodic");
        try {
            System.out.println(new StringBuilder().append("SENDPERIODIC: ").append(send_periodic.get()).toString());
        } catch (ExecutionException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = prefs.edit();

        String key = prefs.getString("key", "");
        recordingState = prefs.getBoolean("recordingState", false);
        continueWithoutWifi = prefs.getBoolean("continueWithoutWifi", false);
        if (key.equals("")) {
            Intent intent = new Intent(this, RegisterActivity.class);
            startActivity(intent);
            finish();
        }

        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        mScreenDensity = metrics.densityDpi;

        // Initialize UI components
        infoButton = findViewById(R.id.infoButton);
        logButton = findViewById(R.id.logButton);
        devButton = findViewById(R.id.devButton);
        tabLayout = findViewById(R.id.tabLayout);
        viewPager = findViewById(R.id.viewPager);

        // Setup tabs
        setupTabs();

        devButton.setOnClickListener(view -> {
            Intent intent = new Intent(MainActivity.this, DevToolsActivity.class);
            MainActivity.this.startActivity(intent);
        });

        infoButton.setOnClickListener(v -> {
            infoOpenCount++;
            if (infoOpenCount == 5) {
                editor.putBoolean("isDev", true);
                editor.apply();
                devButton.setVisibility(View.VISIBLE);
            }
            DialogFragment informationDialog = new InfoDialog();
            informationDialog.show(getSupportFragmentManager(), "Information Dialog");
        });

        logButton.setOnClickListener(v -> {

            AlertDialog alertDialog = new AlertDialog.Builder(MainActivity.this).create();
            alertDialog.setTitle("Logs");
            alertDialog.setMessage(Logger.getAll(this));
            alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Ok",
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    });
            alertDialog.show();
        });




        File f_image = new File(getApplicationContext().getExternalFilesDir(null).getAbsolutePath() + File.separator + "images");
        File f_encrypt = new File(getApplicationContext().getExternalFilesDir(null).getAbsolutePath() + File.separator + "encrypt");
        if (!f_image.exists()) f_image.mkdir();
        if (!f_encrypt.exists()) f_encrypt.mkdir();

        // Setup automatic upload every 12 hours if enabled (default: enabled)
        boolean autoUploadEnabled = prefs.getBoolean("autoUploadEnabled", true);
        if (autoUploadEnabled) {
            UploadScheduler.setupAutoUpload(this);
        }

        ActivityResultLauncher<String[]> locationPermissionRequest =
                registerForActivityResult(new ActivityResultContracts
                                .RequestMultiplePermissions(), result -> {
                            Boolean fineLocationGranted = result.getOrDefault(
                                    Manifest.permission.ACCESS_FINE_LOCATION, false);
                            Boolean coarseLocationGranted = result.getOrDefault(
                                    Manifest.permission.ACCESS_COARSE_LOCATION,false);
                            if (fineLocationGranted != null && fineLocationGranted) {
                                // Precise location access granted.
                            } else if (coarseLocationGranted != null && coarseLocationGranted) {
                                // Only approximate location access granted.
                            } else {
                                // No location access granted.
                            }
                        }
                );


        // Before you perform the actual permission request, check whether your app
        // already has the permissions, and whether your app needs to show a permission
        // rationale dialog.
        int finePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION);
        int coarsePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION);
        if(finePermission == PackageManager.PERMISSION_DENIED && coarsePermission == PackageManager.PERMISSION_DENIED){
            locationPermissionRequest.launch(new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
        /*int statsPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.PACKAGE_USAGE_STATS);
        if(statsPermission == PackageManager.PERMISSION_DENIED){
            Log.d("SCREENOMICS", "Requesting stats permission");
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        }*/
    }


    /*private void startLocationTracking(){
        Constraints constraints = new Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build();
        PeriodicWorkRequest workRequest = new PeriodicWorkRequest.Builder(
                LocationWorker.class,
                2,
                TimeUnit.MINUTES )
                .addTag(LocationWorker.Companion.getWorkName())
                .setConstraints(constraints)
                .setInitialDelay(1, TimeUnit.MINUTES)
                .build();

        mWorkManager.enqueueUniquePeriodicWork(LocationWorker.Companion.getWorkName(),
                ExistingPeriodicWorkPolicy.KEEP, workRequest);
    }

    private void stopLocationTracking(){
        mWorkManager.cancelAllWorkByTag(LocationWorker.Companion.getWorkName());
    }*/

    ActivityResultLauncher<Intent> mMediaProjectionLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    if(result.getResultCode() != RESULT_OK){
                        Toast.makeText(getApplicationContext(), "Permission denied", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        Intent screenCaptureIntent = new Intent(MainActivity.this, CaptureService.class);
                        screenCaptureIntent.putExtra("resultCode", result.getResultCode());
                        screenCaptureIntent.putExtra("intentData", result.getData());
                        screenCaptureIntent.putExtra("screenDensity", mScreenDensity);
                        startForegroundService(screenCaptureIntent);
                        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        Toast.makeText(MainActivity.this, "ScreenLife Capture is running!", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });

    /*@Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CODE_MEDIA) {
            Log.e(TAG, "Unknown request code: " + requestCode);
            return;
        }
        if (resultCode != RESULT_OK) {
            Toast.makeText(getApplicationContext(), "Permission denied", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent screenCaptureIntent = new Intent(this, CaptureService.class);
            screenCaptureIntent.putExtra("resultCode", resultCode);
            screenCaptureIntent.putExtra("intentData", data);
            screenCaptureIntent.putExtra("screenDensity", mScreenDensity);
            startForegroundService(screenCaptureIntent);
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Toast.makeText(this, "ScreenLife Capture is running!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }*/

    private void createAlarm(){
        final UploadScheduler alarm = new UploadScheduler(this);
    }


    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            // Service connection maintained for fragments to use if needed
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) { }
    };

    private final ServiceConnection uploaderServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            // No UI updates needed in main activity anymore
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) { }
    };

    private final ServiceConnection locationServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            LocationService.LocalBinder localBinder = (LocationService.LocalBinder) iBinder;
            locationService = localBinder.getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) { }
    };

    @Override
    protected void onResume() {
        super.onResume();
        infoOpenCount = 0;

        Intent screenCaptureIntent = new Intent(this, CaptureService.class);
        bindService(screenCaptureIntent, serviceConnection, 0);

        Intent intent = new Intent(this, UploadService.class);
        bindService(intent, uploaderServiceConnection, 0);

        Intent locationIntent = new Intent(this, LocationService.class);
        bindService(locationIntent, locationServiceConnection, 0);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean isDev = prefs.getBoolean("isDev", false);
        if (!isDev) devButton.setVisibility(View.GONE);

        // MindPulse tab switching disabled - feature temporarily unavailable
        /*
        Intent launchIntent = getIntent();
        if (launchIntent != null && launchIntent.getBooleanExtra("start_video_recording", false)) {
            // Switch to MindPulse tab and start recording
            if (viewPager != null) {
                viewPager.setCurrentItem(1); // Switch to MindPulse tab
            }
        }
        */
    }

    // This needs to be here so that onResume is called at the correct time.
    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unbindService(serviceConnection);
        unbindService(uploaderServiceConnection);
        unbindService(locationServiceConnection);
    }

    private void setupTabs() {
        pagerAdapter = new MainPagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);
        
        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            tab.setText(pagerAdapter.getTabTitle(position));
        }).attach();
    }

    // Public methods for fragments to call
    public void startLocationService(){
        Intent intent = new Intent(this, LocationService.class);
        startService(intent);
    }

    public void stopLocationService(){
        Intent intent = new Intent(this, LocationService.class);
        stopService(intent);
    }

    public void startMediaProjectionRequest() {
        mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mMediaProjectionLauncher.launch(mProjectionManager.createScreenCaptureIntent());
    }

    public void stopCaptureService() {
        Intent serviceIntent = new Intent(this, CaptureService.class);
        stopService(serviceIntent);
    }

}

