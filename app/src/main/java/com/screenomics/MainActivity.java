package com.screenomics;

import android.app.AppOpsManager;
import android.Manifest;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.preference.PreferenceManager;

import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;

import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.activity.EdgeToEdge;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.install.InstallStateUpdatedListener;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.model.UpdateAvailability;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import java.io.File;

// Firebase temporarily disabled
// import com.google.firebase.FirebaseApp;
// import com.google.firebase.analytics.FirebaseAnalytics;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_CODE_APP_UPDATE = 2201;
    private static final String PREF_A15_ACCESSIBILITY_PROMPT_SHOWN = "a15_accessibility_prompt_shown";
    private static final String PREF_FGS_CONSENT_ACCEPTED = "fgs_consent_accepted";
    public MediaProjectionManager mProjectionManager;
    public static int mScreenDensity;
    private static final int PERMISSION_REQUEST_NOTIFICATIONS = 1;
    
    private Button infoButton;
    private Button logButton;
    private Button devButton;
    private Boolean recordingState;
    private int infoOpenCount = 0;
    
    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private MainPagerAdapter pagerAdapter;

    private LocationService locationService;

    private CaptureService captureService;
    private boolean captureServiceBound = false;
    private boolean uploadServiceBound = false;
    private boolean locationServiceBound = false;

    public boolean continueWithoutWifi = false;
    private AlertDialog activeUpdateDialog;
    private AppUpdateManager appUpdateManager;
    private ActivityResultLauncher<String[]> locationPermissionRequest;
    private ActivityResultLauncher<String[]> blePermissionRequest;

    private final InstallStateUpdatedListener installStateUpdatedListener = state -> {
        if (state.installStatus() == InstallStatus.DOWNLOADED && appUpdateManager != null) {
            Log.i(TAG, "In-app update downloaded. Completing update.");
            appUpdateManager.completeUpdate();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Enable edge-to-edge display for Android 15+ compatibility
        EdgeToEdge.enable(this);

        setContentView(R.layout.activity_main);

        // Firebase temporarily disabled - uncomment after setup
        // FirebaseApp.initializeApp(this);
        // FirebaseAnalytics mFirebaseAnalytics = FirebaseAnalytics.getInstance(this);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = prefs.edit();

        String key = SecureStore.getSecret(this, "key", "");
        recordingState = prefs.getBoolean("recordingState", false);
        continueWithoutWifi = prefs.getBoolean("continueWithoutWifi", false);
        if (key.equals("")) {
            Intent intent = new Intent(this, RegisterActivity.class);
            startActivity(intent);
            finish();
            return;
        }

        // Register location permission launcher (must happen before onStart)
        locationPermissionRequest = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                    Boolean fineGranted = result.getOrDefault(
                            Manifest.permission.ACCESS_FINE_LOCATION, false);
                    Boolean coarseGranted = result.getOrDefault(
                            Manifest.permission.ACCESS_COARSE_LOCATION, false);
                    if (fineGranted != null && fineGranted) {
                        Log.i(TAG, "Fine location permission granted");
                    } else if (coarseGranted != null && coarseGranted) {
                        Log.i(TAG, "Coarse location permission granted");
                    } else {
                        Log.w(TAG, "Location permission denied");
                    }
                });

        // Register BLE permission launcher for OMI Glass collection (mindpulseDev only)
        blePermissionRequest = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                    boolean allGranted = !result.isEmpty();
                    for (Boolean granted : result.values()) {
                        if (!Boolean.TRUE.equals(granted)) allGranted = false;
                    }
                    if (allGranted) {
                        GlassesFeature.start(this);
                    } else {
                        Log.w(TAG, "BLE permission denied; glasses collection unavailable");
                        Toast.makeText(this, "Bluetooth permission is required to connect glasses",
                                Toast.LENGTH_LONG).show();
                    }
                });

        maybeEnableAccessibilityModeForAndroid15(prefs);
        initInAppUpdate();

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        mScreenDensity = metrics.densityDpi;

        // Initialize UI components
        infoButton = findViewById(R.id.infoButton);
        logButton = findViewById(R.id.logButton);
        devButton = findViewById(R.id.devButton);
        tabLayout = findViewById(R.id.tabLayout);
        viewPager = findViewById(R.id.viewPager);

        // Setup tabs
        setupTabs();
        checkForAppUpdate(false);
        DailyReminderWorker.ensureScheduled(this);

        devButton.setOnClickListener(view -> {
            Intent intent = new Intent(MainActivity.this, DevToolsActivity.class);
            MainActivity.this.startActivity(intent);
        });

        infoButton.setOnClickListener(v -> {
            infoOpenCount++;
            // Five-tap dev entry works only in internal builds (any debug build or
            // the mindpulseDev flavor) — never in the production Play release.
            if (infoOpenCount == 5 && isDevToolsAllowed()) {
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




        File externalDir = getApplicationContext().getExternalFilesDir(null);
        if (externalDir == null) {
            Log.e(TAG, "getExternalFilesDir returned null, cannot create image/encrypt dirs");
            Toast.makeText(this, "Storage unavailable", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        File f_image = new File(externalDir.getAbsolutePath() + File.separator + "images");
        File f_encrypt = new File(externalDir.getAbsolutePath() + File.separator + "encrypt");
        if (!f_image.exists()) f_image.mkdir();
        if (!f_encrypt.exists()) f_encrypt.mkdir();

        // Real-time upload enabled - files uploaded immediately after creation
        Log.i("MainActivity", "Real-time upload enabled");

        // Gate all permission requests behind FGS consent dialog.
        // Google Play requires foreground service usage to be user-initiated and
        // perceptible. This consent dialog satisfies that requirement.
        if (!prefs.getBoolean(PREF_FGS_CONSENT_ACCEPTED, false)) {
            showFgsConsentDialog();
        } else {
            requestAllPermissions();
        }
    }


    ActivityResultLauncher<Intent> mMediaProjectionLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    if(result.getResultCode() != RESULT_OK){
                        Toast.makeText(getApplicationContext(), "Screen recording permission denied", Toast.LENGTH_SHORT).show();
                        resetCaptureState();
                        return;
                    }
                    try {
                        Intent screenCaptureIntent = new Intent(MainActivity.this, CaptureService.class);
                        screenCaptureIntent.putExtra("resultCode", result.getResultCode());
                        screenCaptureIntent.putExtra("intentData", result.getData());
                        screenCaptureIntent.putExtra("screenDensity", mScreenDensity);
                        startForegroundService(screenCaptureIntent);
                        Toast.makeText(MainActivity.this, "MindPulse is running!", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Log.e("MainActivity", "Failed to start CaptureService: " + e.getMessage(), e);
                        Toast.makeText(MainActivity.this, "Failed to start screen capture: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        resetCaptureState();
                    }
                }
            });

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            CaptureService.LocalBinder binder = (CaptureService.LocalBinder) iBinder;
            captureService = binder.getService();
            captureServiceBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            captureServiceBound = false;
            captureService = null;
        }
    };

    private final ServiceConnection uploaderServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            uploadServiceBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            uploadServiceBound = false;
        }
    };

    private final ServiceConnection locationServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            LocationService.LocalBinder localBinder = (LocationService.LocalBinder) iBinder;
            locationService = localBinder.getService();
            locationServiceBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            locationServiceBound = false;
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        infoOpenCount = 0;

        if (!captureServiceBound) {
            try {
                Intent screenCaptureIntent = new Intent(this, CaptureService.class);
                captureServiceBound = bindService(screenCaptureIntent, serviceConnection, 0);
            } catch (Exception e) {
                Log.w(TAG, "Failed to bind CaptureService", e);
            }
        }

        if (!uploadServiceBound) {
            try {
                Intent intent = new Intent(this, UploadService.class);
                uploadServiceBound = bindService(intent, uploaderServiceConnection, 0);
            } catch (Exception e) {
                Log.w(TAG, "Failed to bind UploadService", e);
            }
        }

        if (!locationServiceBound) {
            try {
                Intent locationIntent = new Intent(this, LocationService.class);
                locationServiceBound = bindService(locationIntent, locationServiceConnection, 0);
            } catch (Exception e) {
                Log.w(TAG, "Failed to bind LocationService", e);
            }
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean isDev = prefs.getBoolean("isDev", false) && isDevToolsAllowed();
        devButton.setVisibility(isDev ? View.VISIBLE : View.GONE);

        Intent launchIntent = getIntent();
        if (launchIntent != null && launchIntent.getBooleanExtra("start_video_recording", false)) {
            // Switch to MindPulse tab and start recording
            if (viewPager != null) {
                viewPager.setCurrentItem(1); // Switch to MindPulse tab
            }
        }

        // OMI Glass (mindpulseDev only): restart the service if it should be running
        // but isn't. No-op when already running, so a live connection is untouched.
        GlassesFeature.ensureStartedIfEnabled(this);

        checkForAppUpdate(true);
        maybeResumeInProgressUpdate();
    }

    // This needs to be here so that onResume is called at the correct time.
    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (activeUpdateDialog != null && activeUpdateDialog.isShowing()) {
            activeUpdateDialog.dismiss();
            activeUpdateDialog = null;
        }
        if (captureServiceBound) {
            unbindService(serviceConnection);
            captureServiceBound = false;
        }
        if (uploadServiceBound) {
            unbindService(uploaderServiceConnection);
            uploadServiceBound = false;
        }
        if (locationServiceBound) {
            unbindService(locationServiceConnection);
            locationServiceBound = false;
        }
    }

    @Override
    protected void onDestroy() {
        if (appUpdateManager != null) {
            appUpdateManager.unregisterListener(installStateUpdatedListener);
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_APP_UPDATE && resultCode != RESULT_OK) {
            Log.w(TAG, "In-app update flow canceled/failed, resultCode=" + resultCode);
        }
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
        // Only start LocationService when location permission is granted.
        // Health checks for the no-location case are handled by the
        // periodic WorkManager job (AutoUploadWorker) -- no FGS needed.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            showLocationForegroundDisclosureAndStart();
        } else {
            PermissionHelper.requestLocationPermissions(this, new PermissionHelper.PermissionCallback() {
                @Override
                public void onPermissionGranted() {
                    showLocationForegroundDisclosureAndStart();
                }

                @Override
                public void onPermissionDenied() {
                    Log.i(TAG, "Location denied; health checks via WorkManager safety net");
                }

                @Override
                public void onPermissionPermanentlyDenied() {
                    Log.i(TAG, "Location permanently denied; health checks via WorkManager safety net");
                }
            });
        }
    }

    public void stopLocationService(){
        Intent intent = new Intent(this, LocationService.class);
        stopService(intent);
    }

    /** Dev tools are for internal builds only: any debug build, or the mindpulseDev flavor. */
    static boolean isDevToolsAllowed() {
        return BuildConfig.DEBUG || BuildConfig.MINDPULSE_ENABLED;
    }

    // ---- OMI Glass BLE collection (mindpulseDev only; no-op in standard) ----

    /** Start OMI Glass BLE collection, requesting BLE permissions first if needed. */
    public void startGlassesService() {
        if (!GlassesFeature.AVAILABLE) return;
        if (!GlassesFeature.hasBlePermissions(this)) {
            blePermissionRequest.launch(GlassesFeature.requiredRuntimePermissions());
            return;
        }
        GlassesFeature.start(this);
    }

    public void stopGlassesService() {
        GlassesFeature.stop(this);
    }

    public void startMediaProjectionRequest() {
        // Android 11+ (R): always prefer accessibility capture -- it survives reboots
        // and does not require a one-time MediaProjection token.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            if (!prefs.getBoolean("useAccessibilityCapture", false)) {
                prefs.edit().putBoolean("useAccessibilityCapture", true).apply();
            }
            startAccessibilityCaptureRequest();
            return;
        }

        if (isUsingAccessibilityCapture()) {
            startAccessibilityCaptureRequest();
            return;
        }
        // First check notification permission for Android 13+
        PermissionHelper.requestNotificationPermission(this, new PermissionHelper.PermissionCallback() {
            @Override
            public void onPermissionGranted() {
                // Show explanation dialog before media projection
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Screen Recording Permission")
                    .setMessage("The app needs to record your screen for the research study.\n\n" +
                               "Your data will be encrypted and used only for research purposes.\n\n" +
                               "You'll see a system prompt to allow screen recording.")
                    .setIcon(android.R.drawable.ic_menu_camera)
                    .setPositiveButton("Continue", (dialog, which) -> {
                        mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                        mMediaProjectionLauncher.launch(mProjectionManager.createScreenCaptureIntent());
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> {
                        resetCaptureState();
                    })
                    .setCancelable(false)
                    .show();
            }

            @Override
            public void onPermissionDenied() {
                Toast.makeText(MainActivity.this,
                    "Notification permission recommended for status updates",
                    Toast.LENGTH_SHORT).show();
                // Still continue with media projection even if notifications denied
                onPermissionGranted();
            }

            @Override
            public void onPermissionPermanentlyDenied() {
                // Still continue with media projection even if notifications denied
                onPermissionGranted();
            }
        });
    }

    public void stopCaptureService() {
        if (isUsingAccessibilityCapture()) {
            return;
        }
        Intent serviceIntent = new Intent(this, CaptureService.class);
        stopService(serviceIntent);
    }

    public boolean isCaptureServiceRunning() {
        if (isUsingAccessibilityCapture()) {
            return A11yState.isCaptureRunning();
        }
        return captureServiceBound && captureService != null && captureService.isCapturing();
    }

    private boolean isUsingAccessibilityCapture() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        return prefs.getBoolean("useAccessibilityCapture", false);
    }

    private void startAccessibilityCaptureRequest() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            Toast.makeText(this, "Accessibility capture requires Android 11 or later", Toast.LENGTH_LONG).show();
            resetCaptureState();
            return;
        }

        if (A11yState.isServiceEnabled(this)) {
            Toast.makeText(this, "MindPulse accessibility capture is running!", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Accessibility API Data Disclosure")
                .setMessage("MindPulse uses Android AccessibilityService API to capture encrypted "
                        + "screenshots for this IRB-approved UW-Madison research study.\n\n"
                        + "DATA COLLECTED VIA ACCESSIBILITYSERVICE:\n\n"
                        + "Because screenshots capture everything visible on your screen, "
                        + "the following data types may be collected:\n\n"
                        + "- Web browsing history\n"
                        + "- Emails\n"
                        + "- SMS or MMS messages\n"
                        + "- Other in-app messages\n"
                        + "- Precise location\n"
                        + "- Personal identifiers (name, email, address, phone number)\n"
                        + "- Race and ethnicity; political or religious beliefs\n"
                        + "- Sexual orientation or gender identity\n"
                        + "- Financial information (credit/debit/bank accounts, purchases)\n"
                        + "- Health and fitness information\n"
                        + "- Photos, videos, voice/sound recordings, music, files, documents\n"
                        + "- Calendar events and contacts\n"
                        + "- Page views, taps, in-app search history\n"
                        + "- Installed apps and other user-generated content\n"
                        + "- Device or other identifiers\n\n"
                        + "ADDITIONAL DATA COLLECTED:\n\n"
                        + "- App usage metadata (active app name, timestamps)\n"
                        + "- Device diagnostic logs (for research quality assurance)\n"
                        + "- Location data (GPS coordinates collected alongside screenshots)\n\n"
                        + "PURPOSE:\n"
                        + "This data is used exclusively for approved academic research on "
                        + "smartphone use and digital behavior at UW-Madison.\n\n"
                        + "SECURITY:\n"
                        + "All data is encrypted on your device and transmitted securely to "
                        + "authorized UW-Madison research systems.\n\n"
                        + "Participation is voluntary. You can decline now or disable "
                        + "Accessibility Capture at any time from app controls and Android settings.\n\n"
                        + "By tapping \"Agree and Open Settings\", you consent to this data "
                        + "collection. Enable \"MindPulse Accessibility Capture\" on the next "
                        + "screen, then return to the app.")

                .setPositiveButton("Agree and Open Settings", (dialog, which) -> {
                    try {
                        startActivity(A11yState.buildAccessibilitySettingsIntent());
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to open accessibility settings", e);
                        Toast.makeText(this, "Open Settings > Accessibility and enable MindPulse Accessibility Capture", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", (dialog, which) -> resetCaptureState())
                .setCancelable(false)
                .show();
    }

    private void resetCaptureState() {
        Log.d("MainActivity", "resetCaptureState() called - broadcasting reset");
        // Reset the recording state in SharedPreferences
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("recordingState", false);
        editor.apply();

        // Since we can't easily access the fragment from ViewPager2,
        // the fragment will pick up the updated state when it checks onResume()
        // or we can send a broadcast to notify fragments
        Intent broadcastIntent = new Intent("com.screenomics.RESET_CAPTURE_STATE");
        broadcastIntent.setPackage(getPackageName());
        sendBroadcast(broadcastIntent);
    }

    private void maybeEnableAccessibilityModeForAndroid15(SharedPreferences prefs) {
        // Accessibility capture requires Android 11+ (API 30, Build.VERSION_CODES.R).
        // It is more reliable than MediaProjection for 24/7 operation because it
        // survives app restarts and does not require a one-time user permission token.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            return;
        }

        boolean currentlyUsingAccessibility = prefs.getBoolean("useAccessibilityCapture", false);
        if (!currentlyUsingAccessibility) {
            prefs.edit().putBoolean("useAccessibilityCapture", true).apply();
            Log.i(TAG, "Android 11+ detected. Switched default capture mode to Accessibility.");
        }

        boolean prompted = prefs.getBoolean(PREF_A15_ACCESSIBILITY_PROMPT_SHOWN, false);
        if (!prompted) {
            prefs.edit().putBoolean(PREF_A15_ACCESSIBILITY_PROMPT_SHOWN, true).apply();
            new AlertDialog.Builder(this)
                    .setTitle("Recommended: Accessibility Capture")
                    .setMessage("Your device supports Accessibility Capture mode, which is more "
                            + "reliable for continuous data collection.\n\n"
                            + "Accessibility Capture:\n"
                            + "- Survives app restarts and reboots\n"
                            + "- Does not require re-granting screen recording permission\n"
                            + "- More stable for 24/7 operation\n\n"
                            + "Please enable \"MindPulse Accessibility Capture\" on the next screen.")
                    .setPositiveButton("Enable Now", (dialog, which) -> startAccessibilityCaptureRequest())
                    .setNegativeButton("Later", null)
                    .show();
        }
    }

    private void initInAppUpdate() {
        try {
            appUpdateManager = AppUpdateManagerFactory.create(this);
            appUpdateManager.registerListener(installStateUpdatedListener);
        } catch (Exception e) {
            Log.w(TAG, "Failed to initialize in-app update manager", e);
            appUpdateManager = null;
        }
    }

    private void checkForAppUpdate(boolean silentIfUnavailable) {
        if (appUpdateManager == null) {
            return;
        }
        final long installedVersionCode = getCurrentVersionCode();
        final String installedVersionName = getCurrentVersionName();
        appUpdateManager.getAppUpdateInfo()
                .addOnSuccessListener(info -> {
                    int availability = info.updateAvailability();
                    int availableVersionCode = info.availableVersionCode();
                    Log.i(TAG, "Update check: installedCode=" + installedVersionCode
                            + ", installedName=" + installedVersionName
                            + ", availableCode=" + availableVersionCode
                            + ", availability=" + availability);

                    if (availability == UpdateAvailability.UPDATE_AVAILABLE) {
                        showUpdatePrompt(info, installedVersionName, installedVersionCode, availableVersionCode);
                    } else if (availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                        startInAppUpdateFlow(info);
                    } else if (!silentIfUnavailable) {
                        Log.i(TAG, "No app update available.");
                    }
                })
                .addOnFailureListener(e -> Log.w(TAG, "App update check failed", e));
    }

    private void maybeResumeInProgressUpdate() {
        if (appUpdateManager == null) {
            return;
        }
        appUpdateManager.getAppUpdateInfo()
                .addOnSuccessListener(info -> {
                    if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                        startInAppUpdateFlow(info);
                        return;
                    }
                    if (info.installStatus() == InstallStatus.DOWNLOADED) {
                        Log.i(TAG, "In-app update already downloaded. Completing update.");
                        appUpdateManager.completeUpdate();
                    }
                })
                .addOnFailureListener(e -> Log.w(TAG, "Failed to resume update flow", e));
    }

    private void showUpdatePrompt(AppUpdateInfo info, String installedVersionName, long installedVersionCode, int availableVersionCode) {
        if (activeUpdateDialog != null && activeUpdateDialog.isShowing()) {
            activeUpdateDialog.dismiss();
        }
        activeUpdateDialog = new AlertDialog.Builder(this)
                .setTitle("Update Available")
                .setMessage("Installed version: " + installedVersionName + " (" + installedVersionCode + ")\n"
                        + "Available version code: " + availableVersionCode + "\n\n"
                        + "Update now to keep MindPulse stable.")
                .setPositiveButton("Update Now", (dialog, which) -> startInAppUpdateFlow(info))
                .setNegativeButton("Later", null)
                .create();
        activeUpdateDialog.show();
    }

    private void startInAppUpdateFlow(AppUpdateInfo info) {
        if (appUpdateManager == null) {
            openPlayStorePage();
            return;
        }
        try {
            if (info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                appUpdateManager.startUpdateFlowForResult(info, AppUpdateType.FLEXIBLE, this, REQUEST_CODE_APP_UPDATE);
                return;
            }
            if (info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                appUpdateManager.startUpdateFlowForResult(info, AppUpdateType.IMMEDIATE, this, REQUEST_CODE_APP_UPDATE);
                return;
            }
            openPlayStorePage();
        } catch (Exception e) {
            Log.w(TAG, "Failed to launch in-app update flow; opening Play Store instead", e);
            openPlayStorePage();
        }
    }

    private void openPlayStorePage() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + getPackageName())));
        } catch (Exception ignored) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + getPackageName())));
        }
    }

    private long getCurrentVersionCode() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return packageInfo.getLongVersionCode();
        } catch (Exception e) {
            Log.w(TAG, "Failed to read version code", e);
            return -1L;
        }
    }

    private String getCurrentVersionName() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return packageInfo.versionName != null ? packageInfo.versionName : "unknown";
        } catch (Exception e) {
            Log.w(TAG, "Failed to read version name", e);
            return "unknown";
        }
    }

    private void showLocationForegroundDisclosureAndStart() {
        new AlertDialog.Builder(this)
                .setTitle("Start Background Location Updates?")
                .setMessage("MindPulse can collect location while capture is ON. If you continue, a persistent notification will be shown while location updates are active. You can stop this at any time by turning capture OFF.")
                .setPositiveButton("Start Location Updates", (dialog, which) -> startLocationServiceInternal())
                .setNegativeButton("Skip Location", (dialog, which) -> {
                    Toast.makeText(MainActivity.this,
                            "Continuing without location updates.",
                            Toast.LENGTH_SHORT).show();
                })
                .setCancelable(false)
                .show();
    }

    private void startLocationServiceInternal() {
        try {
            Intent intent = new Intent(MainActivity.this, LocationService.class);
            startService(intent);
        } catch (Exception e) {
            Log.e("MainActivity", "Failed to start LocationService: " + e.getMessage());
            Toast.makeText(this, "Location service unavailable - continuing without location data", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestBatteryOptimizationExemption() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        String packageName = getPackageName();

        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            Log.i(TAG, "Requesting battery optimization exemption");
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + packageName));
            try {
                startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to request battery optimization exemption", e);
                // Fallback: open battery optimization settings
                try {
                    Intent fallbackIntent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    startActivity(fallbackIntent);
                } catch (Exception e2) {
                    Log.e(TAG, "Failed to open battery settings", e2);
                }
            }
        } else {
            Log.i(TAG, "Battery optimization already disabled for this app");
        }
    }

    private void checkUsageStatsPermission() {
        if (!hasUsageStatsPermission()) {
            Log.i(TAG, "Usage stats permission not granted, showing dialog");
            new AlertDialog.Builder(this)
                    .setTitle("Permission Required")
                    .setMessage("MindPulse needs Usage Access permission to track which apps you use. Please enable it in the next screen.")
                    .setPositiveButton("Open Settings", (dialog, which) -> {
                        try {
                            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                            startActivity(intent);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to open usage access settings", e);
                            Toast.makeText(this, "Please enable Usage Access in Settings > Apps > Special Access", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("Later", (dialog, which) -> {
                        Log.w(TAG, "User declined usage stats permission");
                        dialog.dismiss();
                    })
                    .setCancelable(false)
                    .show();
        } else {
            Log.i(TAG, "Usage stats permission already granted");
        }
    }

    private boolean hasUsageStatsPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Show a one-time consent dialog explaining all foreground services and
     * permissions the app requires. Google Play policy requires that FGS usage
     * is user-initiated and perceptible. The user must tap "I Agree" before
     * any foreground service or special permission is requested.
     */
    private void showFgsConsentDialog() {
        new AlertDialog.Builder(this)
                .setTitle("MindPulse Data Collection Consent")
                .setMessage("MindPulse is an IRB-approved research app from UW-Madison. "
                        + "To function properly, MindPulse requires the following permissions "
                        + "and background services. Please review and agree before continuing.\n\n"
                        + "1. SCREEN CAPTURE (Foreground Service)\n"
                        + "MindPulse captures encrypted screenshots while data collection is enabled. "
                        + "A persistent notification will be shown while this service is active.\n\n"
                        + "2. DATA SYNC / UPLOAD (Foreground Service)\n"
                        + "Captured data is uploaded to secure UW-Madison research servers. "
                        + "A notification will be shown during active uploads.\n\n"
                        + "3. BACKGROUND LOCATION (Foreground Service)\n"
                        + "Location data is collected alongside screenshots for research context. "
                        + "A notification will be shown while location tracking is active.\n\n"
                        + "4. APP USAGE DATA\n"
                        + "MindPulse tracks which apps you use for research purposes.\n\n"
                        + "5. BATTERY OPTIMIZATION EXEMPTION\n"
                        + "Required so data collection is not interrupted by the system.\n\n"
                        + "All data is encrypted on your device and transmitted securely. "
                        + "You can disable data collection at any time from the app. "
                        + "Participation is voluntary.\n\n"
                        + "By tapping \"I Agree\", you consent to these services running "
                        + "in the background with visible notifications.")
                .setPositiveButton("I Agree", (dialog, which) -> {
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                    prefs.edit().putBoolean(PREF_FGS_CONSENT_ACCEPTED, true).apply();
                    Log.i(TAG, "User accepted FGS consent dialog");
                    requestAllPermissions();
                })
                .setNegativeButton("Decline", (dialog, which) -> {
                    Log.w(TAG, "User declined FGS consent dialog");
                    Toast.makeText(this,
                            "You must accept to use MindPulse. You can restart the app to try again.",
                            Toast.LENGTH_LONG).show();
                })
                .setCancelable(false)
                .show();
    }

    /**
     * Request all permissions sequentially after the user has given FGS consent.
     */
    private void requestAllPermissions() {
        // Battery optimization exemption
        requestBatteryOptimizationExemption();

        // Usage stats permission
        checkUsageStatsPermission();

        // Location permission
        int finePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION);
        int coarsePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION);
        if (finePermission == PackageManager.PERMISSION_DENIED
                && coarsePermission == PackageManager.PERMISSION_DENIED) {
            locationPermissionRequest.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

}

