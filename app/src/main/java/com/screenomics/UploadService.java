package com.screenomics;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileFilter;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;

public class UploadService extends Service {

    enum Status { IDLE, SENDING, FAILED, SUCCESS }
    private static final int MAX_CONCURRENT_BATCHES_SOFT_CAP = 3;
    private static final int BACKLOG_DRAIN_THRESHOLD = 400;
    private static final int BACKLOG_DRAIN_BATCH_SIZE = 5;
    private static final int FAILED_RETRY_DELAY_SECONDS = 60;

    public volatile int numToUpload = 0;
    public volatile int numUploaded = 0;
    public volatile int numFailed = 0;
    public volatile int numTotal = 0;
    public volatile boolean uploading = false;
    public volatile Status status = Status.IDLE;
    public volatile String errorCode = "";
    public volatile String lastActivityTime = "";
    public volatile boolean continueWithoutWifi = false;

    private int numBatchesSending = 0;
    private int numBatchesToSend = 1;
    private final List<Batch> batches = new ArrayList<>();

    private OkHttpClient client;
    private ExecutorService executor;
    private LocalDateTime startDateTime;
    private volatile boolean backlogDrainMode = false;

    private FileFilter onlyFilesBeforeStart;

    @Override public void onCreate() {
        super.onCreate();
        client = HttpClientProvider.get(this);
        executor = Executors.newFixedThreadPool(maxConcurrentBatches());
    }

    private synchronized void sendNextBatch() {
        Log.d("SCREENOMICS_UPLOAD", "sendNextBatch called - Batches remaining: " + batches.size());
        if (batches.isEmpty()) {
            Log.d("SCREENOMICS_UPLOAD", "No more batches to send");
            return;
        }
        if (numBatchesSending >= numBatchesToSend) {
            Log.d("SCREENOMICS_UPLOAD", "Max concurrent batches reached: " + numBatchesSending + "/" + numBatchesToSend);
            return;
        }
        if (!continueWithoutWifi && !InternetConnection.checkWiFiConnection(this)) {
            Log.e("SCREENOMICS_UPLOAD", "WiFi check failed, aborting upload");
            sendFailure("NOWIFI", "No WiFi connection");
            return;
        }
        Batch batch = batches.remove(0);
        numBatchesSending++;
        Log.i("SCREENOMICS_UPLOAD", "Sending batch " + numBatchesSending + "/" + numBatchesToSend + " with " + batch.size() + " files");
        Log.d("SCREENOMICS_UPLOAD", "Files to upload: " + numToUpload + ", Uploaded: " + numUploaded);
        executor.submit(() -> {
            Log.d("SCREENOMICS_UPLOAD", "Executor running batch with " + batch.size() + " files");
            String[] response = batch.sendFiles();
            String code = response[0];
            String body = response[1];

            boolean ok = "200".equals(code) || "201".equals(code) || "202".equals(code);
            if (ok) {
                // sendFiles() already deletes individual files on success -- no extra deleteFiles() needed
                sendSuccessful(batch, body);
            } else if ("207".equals(code)) {
                int okCount = extractCount(body, "OK");
                int failCount = extractCount(body, "FAIL");
                if (okCount >= 0 && failCount >= 0 && okCount + failCount <= batch.size()) {
                    sendBatchPartial(batch, okCount, failCount, body);
                } else {
                    sendBatchFailure(batch, code, body);
                }
            } else {
                sendBatchFailure(batch, code, body);
            }
        });
    }

    private synchronized void sendSuccessful(Batch batch, String responseBody) {
        numBatchesSending--;
        numUploaded += batch.size();
        numToUpload -= batch.size();

        Log.i("SCREENOMICS_UPLOAD", "Batch upload successful!");
        Log.i("SCREENOMICS_UPLOAD", "Progress: " + numUploaded + "/" + numTotal + " uploaded");
        Log.d("SCREENOMICS_UPLOAD", "Remaining to upload: " + numToUpload);
        Log.d("SCREENOMICS_UPLOAD", "Active batches: " + numBatchesSending);

        Logger.i(getApplicationContext(), "Upload success for " + batch.size() + " files. Server msg: " + responseBody);

        setNotification("Uploading..", "Progress: " + numUploaded + "/" + numTotal);

        if (!backlogDrainMode && numBatchesToSend < maxConcurrentBatches()) numBatchesToSend++;
        for (int i = 0; i < numBatchesToSend; i++) sendNextBatch();

        if (numToUpload <= 0) {
            Log.i("SCREENOMICS_UPLOAD", "All files uploaded successfully!");
            status = Status.SUCCESS;
            reset();
            stopForeground(true);
            stopSelf();
        }
    }

    private synchronized void sendBatchFailure(Batch failedBatch, String code, String responseBody) {
        numBatchesSending--;
        if (numBatchesToSend > 1) numBatchesToSend--;
        numFailed += failedBatch.size();
        numToUpload -= failedBatch.size();

        Log.w("SCREENOMICS_UPLOAD", "Batch upload failed - Code: " + code + ", Files: " + failedBatch.size());
        Log.w("SCREENOMICS_UPLOAD", "Progress: Uploaded=" + numUploaded + ", Failed=" + numFailed + ", Remaining=" + numToUpload);

        Logger.e(getApplicationContext(), "Batch upload failed (" + failedBatch.size() + " files) with code " + code + ". Server msg: " + responseBody);

        setNotification("Uploading..", "Progress: " + numUploaded + "/" + numTotal + " (" + numFailed + " failed)");

        for (int i = 0; i < numBatchesToSend; i++) sendNextBatch();

        if (numToUpload <= 0) {
            if (numFailed > 0) {
                Log.w("SCREENOMICS_UPLOAD", "Upload completed with failures: " + numUploaded + " successful, " + numFailed + " failed");
                status = Status.FAILED;
                errorCode = "PARTIAL_FAILURE";
                setNotification("Upload Completed with Errors", numUploaded + " uploaded, " + numFailed + " failed");
            } else {
                Log.i("SCREENOMICS_UPLOAD", "All files uploaded successfully!");
                status = Status.SUCCESS;
                setNotification("Upload Complete", "All " + numUploaded + " files uploaded!");
            }
            reset();
            stopForeground(true);
            stopSelf();
        }
    }

    private synchronized void sendBatchPartial(Batch batch, int successCount, int failCount, String responseBody) {
        numBatchesSending--;
        if (numBatchesToSend > 1) numBatchesToSend--;
        numUploaded += successCount;
        numFailed += failCount;
        numToUpload -= batch.size();

        Log.w("SCREENOMICS_UPLOAD", "Batch upload partial success - OK=" + successCount + ", FAIL=" + failCount);
        Log.w("SCREENOMICS_UPLOAD", "Progress: Uploaded=" + numUploaded + ", Failed=" + numFailed + ", Remaining=" + numToUpload);

        Logger.e(getApplicationContext(),
                "Batch upload partial success (ok=" + successCount + ", fail=" + failCount + "). Server msg: " + responseBody);

        setNotification("Uploading..", "Progress: " + numUploaded + "/" + numTotal + " (" + numFailed + " failed)");

        for (int i = 0; i < numBatchesToSend; i++) sendNextBatch();

        if (numToUpload <= 0) {
            if (numFailed > 0) {
                Log.w("SCREENOMICS_UPLOAD", "Upload completed with failures: " + numUploaded + " successful, " + numFailed + " failed");
                status = Status.FAILED;
                errorCode = "PARTIAL_FAILURE";
                setNotification("Upload Completed with Errors", numUploaded + " uploaded, " + numFailed + " failed");
            } else {
                Log.i("SCREENOMICS_UPLOAD", "All files uploaded successfully!");
                status = Status.SUCCESS;
                setNotification("Upload Complete", "All " + numUploaded + " files uploaded!");
            }
            reset();
            stopForeground(true);
            stopSelf();
        }
    }

    private void sendFailure(String code, String responseBody) {
        Log.e("SCREENOMICS_UPLOAD", "sendFailure called - Code: " + code + ", Body: " + responseBody);
        Log.e("SCREENOMICS_UPLOAD", "Upload progress at failure: " + numUploaded + "/" + numTotal);
        status = Status.FAILED;
        errorCode = code;
        Logger.e(getApplicationContext(), "Upload failed with code " + code + ". Server msg: " + responseBody);
        setNotification("Failure in Uploading", "Error code: " + errorCode + " (" + numUploaded + "/" + numTotal + ")");
        reset();
        stopForeground(true);
        stopSelf();
    }

    private void reset() {
        ZonedDateTime dateTime = ZonedDateTime.now(ZoneOffset.UTC);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss 'UTC'");
        lastActivityTime = dateTime.format(formatter);

        boolean succeeded = (status == Status.SUCCESS);
        UploadScheduler.scheduleNextUploadCycle(getApplicationContext(), succeeded);

        uploading = false;
        backlogDrainMode = false;
        numBatchesToSend = 0;
        numBatchesSending = 0;
        batches.clear();
        Log.d("SCREENOMICS_UPLOAD", "Service reset - Final stats: Uploaded " + numUploaded + "/" + numTotal);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (status == Status.SENDING) {
            Log.i("SCREENOMICS_UPLOAD", "Upload already in progress; ignoring duplicate trigger");
            return START_NOT_STICKY;
        }

        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        // Only cancel our own upload notification (ID 5), NOT cancelAll() which would
        // destroy CaptureService's foreground notification (ID 1) and risk the system
        // killing the capture service.
        notificationManager.cancel(5);

        createNotificationChannel();
        Notification notification = setNotification("Uploading..", "Preparing..");
        startForeground(5, notification); // Notification ID cannot be 0.

        String dirPath = intent.getStringExtra("dirPath"); // typically ".../files/encrypt"
        continueWithoutWifi = intent.getBooleanExtra("continueWithoutWifi", false);
        if (dirPath == null || dirPath.trim().isEmpty()) {
            Log.e("SCREENOMICS_UPLOAD", "Missing upload directory path in intent");
            sendFailure("NODIR", "Missing upload directory path");
            return START_NOT_STICKY;
        }

        // Ensure no stale state leaks across upload runs.
        batches.clear();
        numBatchesSending = 0;
        numBatchesToSend = 1;

        Log.i("SCREENOMICS_UPLOAD", "UploadService started - Directory: " + dirPath);
        Log.d("SCREENOMICS_UPLOAD", "Continue without WiFi: " + continueWithoutWifi);

        File mainDir = new File(dirPath);

        // Purge .enc/.meta files left by a previous participant registration
        int purged = purgeStaleParticipantFiles(mainDir);
        if (purged > 0) {
            Log.i("SCREENOMICS_UPLOAD", "Purged " + purged + " stale participant files");
            Logger.i(getApplicationContext(), "Purged " + purged + " stale participant files from previous enrollment");
        }

        int orphansPurged = purgeOrphanedEncFiles(mainDir);
        if (orphansPurged > 0) {
            Log.i("SCREENOMICS_UPLOAD", "Purged " + orphansPurged + " orphaned .enc files (>7d, no .meta)");
        }

        int pendingBeforeRun = countFiles(mainDir);
        backlogDrainMode = pendingBeforeRun >= BACKLOG_DRAIN_THRESHOLD;

        if (backlogDrainMode) {
            Log.w("SCREENOMICS_UPLOAD", "Backlog drain mode enabled (pending files="
                    + pendingBeforeRun + "). Using conservative upload settings.");
        }

        startDateTime = LocalDateTime.now();
        final long startMillis = System.currentTimeMillis();
        onlyFilesBeforeStart = file -> file.isFile() && file.lastModified() < startMillis;

        // --- Collect files from the encrypt directory ---
        Set<File> allFiles = new LinkedHashSet<>();

        File[] files = mainDir.listFiles(onlyFilesBeforeStart);
        if (files != null) allFiles.addAll(Arrays.asList(files));
        Log.d("SCREENOMICS_UPLOAD", "Found " + (files != null ? files.length : 0) + " files in encrypt dir");

        if (allFiles.isEmpty()) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // Prepare working list
        LinkedList<File> fileList = new LinkedList<>(allFiles);
        // Oldest-first ordering helps drain long-lived backlog deterministically.
        fileList.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));

        // Clean up old-format files in-place (non JSON; only those that match encrypt naming)
        int removedCount = cleanupOldFormatFiles(fileList);
        if (removedCount > 0) {
            Log.i("SCREENOMICS_UPLOAD", "Cleaned up " + removedCount + " old format files");
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int batchSize = prefs.getInt("batchSize", Constants.BATCH_SIZE_DEFAULT);
        int maxToSend = prefs.getInt("maxSend", Constants.MAX_TO_SEND_DEFAULT);

        if (batchSize <= 0) {
            batchSize = Constants.BATCH_SIZE_DEFAULT;
        }
        if (maxToSend < 0) {
            maxToSend = Constants.MAX_TO_SEND_DEFAULT;
        }
        if (backlogDrainMode) {
            batchSize = Math.min(batchSize, BACKLOG_DRAIN_BATCH_SIZE);
            // Force full drain when backlog is large.
            maxToSend = 0;
        }

        numToUpload = 0;

        Log.d("SCREENOMICS_UPLOAD", "Creating batches - Batch size: " + batchSize + ", Max to send: " + maxToSend);
        while (!fileList.isEmpty() && (maxToSend == 0 || numToUpload < maxToSend)) {
            List<File> nextBatch = new LinkedList<>();
            for (int i = 0; i < batchSize; i++) {
                if (fileList.peek() == null) break;
                if (maxToSend != 0 && numToUpload == maxToSend) break;

                File file = fileList.remove();

                // Skip .meta sidecars (they are processed alongside their .enc partner by Batch)
                String nameLower = file.getName().toLowerCase();
                if (nameLower.endsWith(".meta")) {
                    Log.d("SCREENOMICS_UPLOAD", "Skipping .meta sidecar: " + file.getName());
                    continue;
                }

                Log.d("SCREENOMICS_UPLOAD", "Adding to batch: " + file.getName());
                numToUpload++;
                nextBatch.add(file);
            }
            if (!nextBatch.isEmpty()) {
                Batch batch = new Batch(this, nextBatch, client);
                batches.add(batch);
                Log.d("SCREENOMICS_UPLOAD", "Created batch " + batches.size() + " with " + nextBatch.size() + " files");
            }
        }

        numTotal = numToUpload;
        numUploaded = 0;
        numFailed = 0;
        numBatchesToSend = 1;

        Log.i("SCREENOMICS_UPLOAD", "Upload initialized:");
        Log.i("SCREENOMICS_UPLOAD", "  - Total batches: " + batches.size());
        Log.i("SCREENOMICS_UPLOAD", "  - Total files to upload: " + numToUpload);
        Log.i("SCREENOMICS_UPLOAD", "  - Files remaining unqueued: " + fileList.size());

        if (batches.isEmpty()) {
            Log.i("SCREENOMICS_UPLOAD", "No uploadable batches found; finishing without upload");
            status = Status.SUCCESS;
            errorCode = "";
            setNotification("Upload Complete", "No eligible files to upload.");
            reset();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        status = Status.SENDING;
        uploading = true;

        setNotification("Uploading..", "Starting: 0/" + numTotal);
        sendNextBatch();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        try {
            executor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.w("SCREENOMICS_UPLOAD", "Interrupted while awaiting executor termination");
            Thread.currentThread().interrupt();
        }
    }

    public class LocalBinder extends Binder { UploadService getService() { return UploadService.this; } }

    @Override public IBinder onBind(Intent intent) { return new LocalBinder(); }

    private void createNotificationChannel() {
        // minSdk=29 > O=26, notification channels always available
        NotificationChannel serviceChannel = new NotificationChannel(
                "uploading-channel",
                "MindPulse Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        serviceChannel.setSound(null, null);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(serviceChannel);
    }

    private Notification setNotification(String title, String content) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);

        Notification notification = new Notification.Builder(this, "uploading-channel")
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.drawable.dna)
                .setOngoing(title != null && title.startsWith("Uploading"))
                .setContentIntent(pendingIntent)
                .build();

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(5, notification);
        return notification;
    }

    /**
     * Skip old format files that lack timestamp.
     * Old format: {hash}_{random_hex}_{type}.{ext}
     * New format: {hash}_{millis_timestamp}_{type}.{ext}
     *
     * Detection: new format uses System.currentTimeMillis() as the second underscore-
     * delimited part, which is a 13-digit numeric string. Old format used short hex strings.
     *
     * Returns the number of files removed from the upload queue (not deleted from disk).
     */
    private int cleanupOldFormatFiles(LinkedList<File> fileList) {
        int removedCount = 0;
        Iterator<File> iterator = fileList.iterator();

        while (iterator.hasNext()) {
            File file = iterator.next();
            String name = file.getName();
            // Don't touch JSONs in outbox or .meta sidecars
            String lower = name.toLowerCase();
            if (lower.endsWith(".json") || lower.endsWith(".meta")) continue;

            String[] parts = name.split("_");
            if (parts.length < 2) continue;

            // New format: parts[1] is a numeric millis timestamp (10+ digits)
            // Old format: parts[1] is a short hex string
            String secondPart = parts[1];
            boolean isNewFormat = secondPart.length() >= 10 && secondPart.matches("\\d+");
            if (!isNewFormat) {
                Log.w("SCREENOMICS_UPLOAD", "Skipping unrecognized file format (not uploading): " + name);
                iterator.remove();
                removedCount++;
            }
        }
        return removedCount;
    }

    private int extractCount(String summary, String key) {
        if (summary == null || summary.isEmpty()) return -1;
        Pattern pattern = Pattern.compile(key + "=(\\d+)");
        Matcher matcher = pattern.matcher(summary);
        if (!matcher.find()) return -1;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (Exception e) {
            return -1;
        }
    }

    private int maxConcurrentBatches() {
        return Math.max(1, Math.min(Constants.MAX_BATCHES_TO_SEND, MAX_CONCURRENT_BATCHES_SOFT_CAP));
    }

    /**
     * Delete .enc and .meta files whose 8-char hex prefix doesn't match the
     * current participant hash.  These are leftovers from a previous enrollment
     * and will always 500 on the server -- no point retrying them forever.
     */
    private int purgeStaleParticipantFiles(File encryptDir) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String hashFull = prefs.getString("hash", "");
        if (hashFull.length() < 8) return 0;
        String currentPrefix = hashFull.substring(0, 8);

        File[] files = encryptDir.listFiles();
        if (files == null) return 0;

        int purged = 0;
        Pattern hexPattern = Pattern.compile("[0-9a-f]{8}");
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(".enc") && !name.endsWith(".meta")) continue;

            String[] parts = name.split("_");
            if (parts.length < 2) continue;

            String prefix = parts[0];
            if (prefix.length() != 8) continue;
            if (!hexPattern.matcher(prefix).matches()) continue;
            if (prefix.equals(currentPrefix)) continue;

            if (file.delete()) {
                purged++;
                Log.d("SCREENOMICS_UPLOAD", "Purged stale file: " + name);
            }
        }
        return purged;
    }

    /**
     * Delete .enc files older than 7 days that have no matching .meta sidecar.
     * These are unrecoverable (the AES key metadata is missing).
     */
    private int purgeOrphanedEncFiles(File encryptDir) {
        File[] files = encryptDir.listFiles();
        if (files == null) return 0;
        long cutoffMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);
        int purged = 0;
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(".enc")) continue;
            if (file.lastModified() > cutoffMs) continue;
            String metaName = name.substring(0, name.length() - 4) + ".meta";
            if (new File(encryptDir, metaName).exists()) continue;
            if (file.delete()) {
                purged++;
                Log.d("SCREENOMICS_UPLOAD", "Purged orphaned .enc (>7d, no .meta): " + name);
            }
        }
        return purged;
    }

    private int countFiles(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return 0;
        File[] files = dir.listFiles();
        return files == null ? 0 : files.length;
    }
}
