package com.screenomics;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.util.Collections;
import java.util.UUID;

/**
 * Foreground BLE collector for the OMI Glass wearable (mindpulseDev flavor only).
 *
 * Scans for "OMI Glass" (matched by the 19B10000 service UUID, which is in the
 * primary advertisement), connects with autoConnect for resilient reconnection,
 * enables notifications on the Photo Data characteristic (19B10005), writes the
 * Photo Control characteristic (19B10006) to start interval capture, reassembles
 * each JPEG via {@link GlassPhotoAssembler}, and hands it to
 * {@link Logger#queueImageForUpload} — the same encrypted /encrypt sink + upload
 * pipeline used by screenshots.
 *
 * Gated by SharedPreferences flag "glassesEnabled". Start/stop via
 * {@link GlassesFeature}.
 *
 * Threading model: all connection state (gatt, scanning, backoff) is owned by the
 * main thread; BLE binder-thread callbacks post state transitions to {@link #handler}.
 * The photo data path (handleNotify -> assembler) stays on the binder thread and
 * hands completed JPEGs to {@link #ioHandler} for encryption + queueing.
 */
public class GlassBleService extends Service {

    private static final String TAG = "GlassBle";
    private static final String CHANNEL_ID = "screenomics_glass_id";
    private static final int FGS_ID = 23;
    private static final long HEALTH_CHECK_INTERVAL_MS = 60_000L;
    private static final long SCAN_TIMEOUT_MS = 30_000L;
    /** If a connect attempt hasn't produced a configured link in this long,
     *  tear it down and fall back to scanning (handles MAC changes, stuck stack). */
    private static final long CONNECT_FALLBACK_MS = 120_000L;
    /** Delay between the "stop capture" write and closing the GATT, so the write
     *  actually reaches the device before the link is torn down. */
    private static final long STOP_WRITE_GRACE_MS = 600L;
    private static final long SCAN_BACKOFF_MIN_MS = 60_000L;
    private static final long SCAN_BACKOFF_MAX_MS = 30 * 60_000L;

    // OMI BLE protocol UUIDs
    private static final UUID SVC = UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214");
    private static final UUID PHOTO_DATA = UUID.fromString("19B10005-E8F2-537E-4F6C-D104768A1214");
    private static final UUID PHOTO_CTRL = UUID.fromString("19B10006-E8F2-537E-4F6C-D104768A1214");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_SVC = UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LVL = UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb");

    // Lightweight status for the UI
    public static volatile boolean sRunning = false;
    public static volatile boolean sConnected = false;
    public static volatile int sPhotoCount = 0;
    public static volatile int sBatteryPct = -1;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private boolean scanning = false;
    /** True once the link is fully set up (capture-start write acked or photos flowing). */
    private volatile boolean configured = false;
    private long scanBackoffMs = 0;
    private long nextScanAllowedAt = 0;
    private final GlassPhotoAssembler assembler = new GlassPhotoAssembler();
    private Handler handler;
    private HandlerThread ioThread;
    private Handler ioHandler;

    private final Runnable scanTimeout = () -> {
        if (scanning) {
            Log.w(TAG, "scan timeout, no OMI Glass found");
            stopScan();
            bumpScanBackoff();
        }
    };

    /** Fired when a connect attempt never reached a configured link. */
    private final Runnable connectFallback = () -> {
        if (configured && sConnected) return; // link is healthy; nothing to do
        Log.w(TAG, "connect attempt stalled; closing GATT and falling back to scan");
        closeGatt();
        startScan();
    };

    private final Runnable healthRunnable = new Runnable() {
        @Override public void run() {
            HealthChecker.check(GlassBleService.this);
            // reconnect if we lost the link and aren't already scanning/connecting
            if (!sConnected && gatt == null && !scanning
                    && System.currentTimeMillis() >= nextScanAllowedAt) {
                startDiscovery();
            }
            handler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS);
        }
    };

    /** React to Bluetooth being toggled: a GATT client from before the toggle is dead. */
    private final BroadcastReceiver btStateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_OFF) {
                Log.w(TAG, "BT adapter off; dropping connection state");
                handler.post(() -> {
                    stopScan();
                    closeGatt();
                });
            } else if (state == BluetoothAdapter.STATE_ON) {
                Log.i(TAG, "BT adapter on; restarting discovery");
                handler.post(() -> {
                    resetScanBackoff();
                    if (gatt == null && !scanning) startDiscovery();
                });
            }
        }
    };

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "GlassBleService onCreate");
        Logger.sweepStaleTempFiles(this); // clean any crash-orphaned plaintext temp files
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = (bm != null) ? bm.getAdapter() : null;
        handler = new Handler(Looper.getMainLooper());
        ioThread = new HandlerThread("GlassIoThread");
        ioThread.start();
        ioHandler = new Handler(ioThread.getLooper());
        registerReceiver(btStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "GlassBleService onStartCommand");
        createNotificationChannel();

        Intent ni = new Intent(this, MainActivity.class);
        int piFlags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, ni, piFlags);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MindPulse Glasses")
                .setContentText("Collecting photos from OMI Glass.")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();

        // On API 34+ the connectedDevice FGS type requires a granted BLE permission;
        // GlassesFeature checks before starting us, but guard anyway.
        try {
            ServiceCompat.startForeground(this, FGS_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed (missing BLE permission?); stopping", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        sRunning = true;

        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "BT adapter null/off; will retry via health loop");
        } else if (!hasBlePermissions()) {
            Log.w(TAG, "BLE runtime permissions missing; cannot scan/connect");
        } else if (sConnected || gatt != null || scanning) {
            Log.d(TAG, "already connected/connecting; leaving link untouched");
        } else {
            startDiscovery();
        }

        handler.removeCallbacks(healthRunnable);
        handler.postDelayed(healthRunnable, HEALTH_CHECK_INTERVAL_MS);
        return START_STICKY;
    }

    // ---- discovery: direct-connect by remembered MAC, else scan ----
    private void startDiscovery() {
        if (adapter == null || !adapter.isEnabled() || !hasBlePermissions()) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String mac = prefs.getString("glassDeviceMac", "");
        if (mac != null && !mac.isEmpty()) {
            try {
                BluetoothDevice dev = adapter.getRemoteDevice(mac);
                Log.i(TAG, "connecting to remembered device");
                connect(dev, true);
                return;
            } catch (Exception e) {
                Log.w(TAG, "bad remembered MAC, falling back to scan: " + e);
            }
        }
        startScan();
    }

    private void startScan() {
        if (scanning || adapter == null || !adapter.isEnabled() || !hasBlePermissions()) return;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { Log.w(TAG, "no LE scanner"); return; }
        Log.i(TAG, "SCAN_START");
        scanning = true;
        // Filter on the OMI service UUID: required for results while the screen is
        // off (Android 8.1+ suppresses unfiltered scan results), and lets us use
        // the battery-friendly BALANCED mode instead of LOW_LATENCY.
        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(SVC)).build();
        ScanSettings ss = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED).build();
        try {
            scanner.startScan(Collections.singletonList(filter), ss, scanCb);
        } catch (SecurityException e) {
            Log.e(TAG, "scan SecurityException", e); scanning = false; return;
        }
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
    }

    private void stopScan() {
        handler.removeCallbacks(scanTimeout);
        try { if (scanner != null && hasBlePermissions()) scanner.stopScan(scanCb); } catch (Exception ignored) {}
        scanning = false;
    }

    private void bumpScanBackoff() {
        scanBackoffMs = (scanBackoffMs == 0) ? SCAN_BACKOFF_MIN_MS
                : Math.min(scanBackoffMs * 2, SCAN_BACKOFF_MAX_MS);
        nextScanAllowedAt = System.currentTimeMillis() + scanBackoffMs;
        Log.i(TAG, "next scan in " + (scanBackoffMs / 1000) + "s");
    }

    private void resetScanBackoff() {
        scanBackoffMs = 0;
        nextScanAllowedAt = 0;
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            if (r.getDevice() == null) return;
            final BluetoothDevice dev = r.getDevice();
            final int rssi = r.getRssi();
            handler.post(() -> {
                if (!scanning) return;
                // MAC is deliberately not logged: logcat is uploaded as diagnostics.
                Log.i(TAG, "FOUND OMI Glass rssi=" + rssi);
                PreferenceManager.getDefaultSharedPreferences(GlassBleService.this)
                        .edit().putString("glassDeviceMac", dev.getAddress()).apply();
                stopScan();
                resetScanBackoff();
                connect(dev, true);
            });
        }
        @Override public void onScanFailed(int code) {
            handler.post(() -> {
                Log.e(TAG, "SCAN_FAILED " + code);
                // Clean up fully: cancel the timeout and make sure no orphaned scan
                // keeps running (e.g. SCAN_FAILED_ALREADY_STARTED).
                stopScan();
                bumpScanBackoff();
            });
        }
    };

    /** Must be called on the main thread. */
    private void connect(BluetoothDevice dev, boolean autoConnect) {
        try {
            closeGatt();
            assembler.reset();
            gatt = dev.connectGatt(this, autoConnect, gattCb, BluetoothDevice.TRANSPORT_LE);
            // If this attempt never produces a working link (device changed MAC,
            // stale GATT cache, stack wedged), fall back to scanning.
            handler.removeCallbacks(connectFallback);
            handler.postDelayed(connectFallback, CONNECT_FALLBACK_MS);
        } catch (SecurityException e) {
            Log.e(TAG, "connect SecurityException", e);
        }
    }

    /** Must be called on the main thread. */
    private void closeGatt() {
        handler.removeCallbacks(connectFallback);
        if (gatt != null) {
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        sConnected = false;
        configured = false;
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            Log.i(TAG, "conn status=" + status + " newState=" + newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                sConnected = true;
                assembler.reset(); // never merge chunks across connections
                if (!safeRequestMtu(g, 517)) {
                    // requestMtu refused (GATT busy) — don't stall; discover directly.
                    safeDiscover(g);
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                sConnected = false;
                configured = false;
                assembler.reset();
                // autoConnect=true lets the stack reconnect automatically; keep gatt,
                // but re-arm the fallback so a link that never comes back gets torn
                // down and rediscovered instead of waiting forever.
                Log.w(TAG, "disconnected (status=" + status + "); relying on autoConnect with fallback");
                handler.post(() -> {
                    if (gatt == g && sRunning) {
                        handler.removeCallbacks(connectFallback);
                        handler.postDelayed(connectFallback, CONNECT_FALLBACK_MS);
                    }
                });
            }
        }
        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            Log.i(TAG, "MTU=" + mtu + " status=" + status);
            // Proceed regardless of status: a failed MTU negotiation still leaves a
            // usable (23-byte) link.
            safeDiscover(g);
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "service discovery failed status=" + status);
                handler.post(() -> { closeGatt(); bumpScanBackoff(); });
                return;
            }
            BluetoothGattService svc = g.getService(SVC);
            if (svc == null) { Log.e(TAG, "OMI service not found"); return; }
            BluetoothGattCharacteristic pd = svc.getCharacteristic(PHOTO_DATA);
            if (pd == null) { Log.e(TAG, "photo data char not found"); return; }
            try { g.setCharacteristicNotification(pd, true); } catch (SecurityException ignored) {}
            BluetoothGattDescriptor cccd = pd.getDescriptor(CCCD);
            if (cccd != null) writeCccdEnableNotify(g, cccd);
            else Log.e(TAG, "photo CCCD not found");
        }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            // photo notifications enabled -> start interval capture
            if (CCCD.equals(d.getUuid()) && PHOTO_DATA.equals(d.getCharacteristic().getUuid())) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    // Without notifications the glasses would capture (and burn
                    // battery) while we receive nothing — do NOT start capture.
                    Log.e(TAG, "CCCD write failed status=" + status + "; not starting capture");
                    handler.post(() -> { closeGatt(); bumpScanBackoff(); });
                    return;
                }
                int interval = PreferenceManager.getDefaultSharedPreferences(GlassBleService.this)
                        .getInt("glassCaptureIntervalSec", 30);
                if (interval < 5) interval = 5;
                if (interval > 300) interval = 300;
                BluetoothGattService svc = g.getService(SVC);
                BluetoothGattCharacteristic ctrl = (svc != null) ? svc.getCharacteristic(PHOTO_CTRL) : null;
                // (byte) cast keeps the uint8 bit pattern for values > 127
                if (ctrl != null) writeChar(g, ctrl, new byte[]{(byte) interval});
                Log.i(TAG, "requested capture interval=" + interval + "s");
            }
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (PHOTO_CTRL.equals(c.getUuid())) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "capture started");
                    configured = true;
                    handler.post(() -> {
                        handler.removeCallbacks(connectFallback); // link is fully configured
                        resetScanBackoff();
                    });
                } else {
                    Log.e(TAG, "photo ctrl write failed status=" + status);
                }
                // read battery once either way
                readBattery(g);
            }
        }
        // Android 13+ value-param callback
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            handleNotify(c.getUuid(), value);
        }
        // Pre-13 callback
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) handleNotify(c.getUuid(), c.getValue());
        }
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int status) {
            if (BATTERY_LVL.equals(c.getUuid()) && value != null && value.length > 0) {
                sBatteryPct = value[0] & 0xFF; Log.i(TAG, "battery=" + sBatteryPct + "%");
            }
        }
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                    && BATTERY_LVL.equals(c.getUuid()) && c.getValue() != null && c.getValue().length > 0) {
                sBatteryPct = c.getValue()[0] & 0xFF; Log.i(TAG, "battery=" + sBatteryPct + "%");
            }
        }
    };

    /** Runs on the BLE binder thread; keep it light. Encryption + file I/O happen
     *  on the dedicated ioThread so notification delivery is never blocked. */
    private void handleNotify(UUID uuid, byte[] value) {
        if (!PHOTO_DATA.equals(uuid) || value == null) return;
        final byte[] jpeg = assembler.feed(value);
        if (jpeg != null) {
            configured = true; // photos flowing == link is definitely healthy
            final int orientation = assembler.getOrientation();
            final int photoNum = ++sPhotoCount; // single producer: binder thread
            final Context ctx = getApplicationContext();
            ioHandler.post(() -> {
                boolean ok = Logger.queueImageForUpload(ctx, jpeg, "glass", "omi_glass", orientation);
                if (ok) UploadScheduler.ensurePeriodicUpload(ctx);
                Log.i(TAG, "photo #" + photoNum + " bytes=" + jpeg.length + " queued=" + ok);
            });
        }
    }

    private void readBattery(BluetoothGatt g) {
        try {
            BluetoothGattService bs = g.getService(BATTERY_SVC);
            if (bs == null) return;
            BluetoothGattCharacteristic bc = bs.getCharacteristic(BATTERY_LVL);
            if (bc != null) g.readCharacteristic(bc);
        } catch (SecurityException ignored) {}
    }

    // ---- SDK-branched BLE write helpers (minSdk 29; new APIs are API 33+) ----
    @SuppressWarnings("deprecation")
    private void writeCccdEnableNotify(BluetoothGatt g, BluetoothGattDescriptor d) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            } else {
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(d);
            }
        } catch (SecurityException e) { Log.e(TAG, "writeDescriptor perm", e); }
    }

    @SuppressWarnings("deprecation")
    private void writeChar(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, v, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } else {
                c.setValue(v);
                c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                g.writeCharacteristic(c);
            }
        } catch (SecurityException e) { Log.e(TAG, "writeCharacteristic perm", e); }
    }

    private boolean safeRequestMtu(BluetoothGatt g, int mtu) {
        try { return g.requestMtu(mtu); }
        catch (SecurityException e) { Log.e(TAG, "requestMtu perm", e); return false; }
    }

    private void safeDiscover(BluetoothGatt g) {
        try { g.discoverServices(); } catch (SecurityException e) { Log.e(TAG, "discover perm", e); }
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        // API 29-30: BLE scanning silently returns nothing without fine location.
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "MindPulse Glasses Channel", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sRunning = false;
        sConnected = false;
        try { unregisterReceiver(btStateReceiver); } catch (Exception ignored) {}
        if (handler != null) {
            handler.removeCallbacks(healthRunnable);
            handler.removeCallbacks(scanTimeout);
            handler.removeCallbacks(connectFallback);
        }
        stopScan();
        try {
            if (gatt != null) {
                final BluetoothGatt g = gatt;
                gatt = null;
                // Ask the device to stop capturing, then give the async write a
                // moment to go out before tearing the link down; closing
                // immediately cancels the write and leaves the glasses capturing.
                BluetoothGattService svc = g.getService(SVC);
                BluetoothGattCharacteristic ctrl = (svc != null) ? svc.getCharacteristic(PHOTO_CTRL) : null;
                if (ctrl != null) {
                    writeChar(g, ctrl, new byte[]{(byte) 0x00}); // 0 = stop
                    handler.postDelayed(() -> {
                        try { g.disconnect(); } catch (Exception ignored) {}
                        try { g.close(); } catch (Exception ignored) {}
                    }, STOP_WRITE_GRACE_MS);
                } else {
                    try { g.disconnect(); } catch (Exception ignored) {}
                    try { g.close(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        assembler.reset();
        if (ioThread != null) {
            ioThread.quitSafely();
            ioThread = null;
            ioHandler = null;
        }
    }
}
