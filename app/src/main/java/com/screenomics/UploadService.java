package com.screenomics;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileFilter;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

public class UploadService extends Service {

    enum Status { IDLE, SENDING, FAILED, SUCCESS }

    public int numToUpload = 0;
    public int numUploaded = 0;
    public int numFailed = 0;
    public int numTotal = 0;
    public boolean uploading = false;
    public Status status = Status.IDLE;
    public String errorCode = "";
    public String lastActivityTime = "";
    public boolean continueWithoutWifi = false;

    private int numBatchesSending = 0;
    private int numBatchesToSend = 1;
    private final List<Batch> batches = new ArrayList<>();

    private final OkHttpClient client =
            new OkHttpClient.Builder().readTimeout(Constants.REQ_TIMEOUT, TimeUnit.SECONDS).build();
    private LocalDateTime startDateTime;

    private final FileFilter onlyFilesBeforeStart = file -> true;

    @Override public void onCreate() { super.onCreate(); }

    public class Sender extends AsyncTask<Batch, Integer, Void> {
        @Override
        protected Void doInBackground(Batch... batchesIn) {
            if (batchesIn != null && batchesIn.length > 0) {
                Batch b = batchesIn[0];
                Log.d("SCREENOMICS_UPLOAD", "Sender AsyncTask executing for batch with " + b.size() + " files");
                String[] response = b.sendFiles();
                String code = response[0];
                String body = response[1];
                Log.d("SCREENOMICS_UPLOAD", "Sender AsyncTask received response code: " + code);

                // Treat 202 (Accepted) and 207 (Multi-Status / partial) as success.
                boolean ok = "200".equals(code) || "201".equals(code) || "202".equals(code) || "207".equals(code);
                if (ok) {
                    // Batch.java already deletes each file on success, but this is safe if the files are gone.
                    b.deleteFiles();
                    sendSuccessful(b, body);
                } else {
                    sendBatchFailure(b, code, body);
                }
            } else {
                Log.e("SCREENOMICS_UPLOAD", "Sender AsyncTask called with null or empty batches");
            }
            return null;
        }
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
        new Sender().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, batch);
    }

    private void sendSuccessful(Batch batch, String responseBody) {
        numBatchesSending--;
        numUploaded += batch.size();
        numToUpload -= batch.size();

        Log.i("SCREENOMICS_UPLOAD", "Batch upload successful!");
        Log.i("SCREENOMICS_UPLOAD", "Progress: " + numUploaded + "/" + numTotal + " uploaded");
        Log.d("SCREENOMICS_UPLOAD", "Remaining to upload: " + numToUpload);
        Log.d("SCREENOMICS_UPLOAD", "Active batches: " + numBatchesSending);

        Logger.i(getApplicationContext(), "Upload success for " + batch.size() + " files. Server msg: " + responseBody);

        setNotification("Uploading..", "Progress: " + numUploaded + "/" + numTotal);

        if (numBatchesToSend < Constants.MAX_BATCHES_TO_SEND) numBatchesToSend++;
        for (int i = 0; i < numBatchesToSend; i++) sendNextBatch();

        if (numToUpload <= 0) {
            Log.i("SCREENOMICS_UPLOAD", "All files uploaded successfully!");
            status = Status.SUCCESS;
            reset();
            stopForeground(true);
            stopSelf();
        }
    }

    private void sendBatchFailure(Batch failedBatch, String code, String responseBody) {
        numBatchesSending--;
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

    private void sendFailure(String code, String responseBody) {
        Log.e("SCREENOMICS_UPLOAD", "sendFailure called - Code: " + code + ", Body: " + responseBody);
        Log.e("SCREENOMICS_UPLOAD", "Upload progress at failure: " + numUploaded + "/" + numTotal);
        status = Status.FAILED;
        errorCode = code;
        Logger.e(getApplicationContext(), "Upload failed with code " + code + ". Server msg: " + responseBody);
        setNotification("Failure in Uploading", "Error code: " + errorCode + " (" + numUploaded + "/" + numTotal + ")");
        reset();
    }

    private void reset() {
        ZonedDateTime dateTime = ZonedDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
        lastActivityTime = dateTime.format(formatter);
        uploading = false;
        numBatchesToSend = 0;
        numBatchesSending = 0;
        Log.d("SCREENOMICS_UPLOAD", "Service reset - Final stats: Uploaded " + numUploaded + "/" + numTotal);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.cancelAll();

        createNotificationChannel();
        Notification notification = setNotification("Uploading..", "Preparing..");
        startForeground(5, notification); // Notification ID cannot be 0.

        if (intent == null || status == Status.SENDING) {
            stopForeground(true);
            stopSelf();
            return super.onStartCommand(intent, flags, startId);
        }

        String dirPath = intent.getStringExtra("dirPath"); // typically ".../files/encrypt"
        continueWithoutWifi = intent.getBooleanExtra("continueWithoutWifi", false);

        Log.i("SCREENOMICS_UPLOAD", "UploadService started - Directory: " + dirPath);
        Log.d("SCREENOMICS_UPLOAD", "Continue without WiFi: " + continueWithoutWifi);

        startDateTime = LocalDateTime.now();

        // --- Collect files from the main directory AND the outbox (JSON) ---
        Set<File> allFiles = new LinkedHashSet<>();

        File mainDir = new File(dirPath);
        File[] files = mainDir.listFiles(onlyFilesBeforeStart);
        if (files != null) allFiles.addAll(Arrays.asList(files));
        Log.d("SCREENOMICS_UPLOAD", "Found " + (files != null ? files.length : 0) + " files in main dir");

        // If dirPath ends with /encrypt, also pull JSONs from sibling /outbox
        File base = getExternalFilesDir(null);
        File outboxDir = (base == null) ? null : new File(base, "outbox");
        int jsonAdded = 0;
        if (outboxDir != null && outboxDir.exists()) {
            File[] jsons = outboxDir.listFiles(f ->
                    f.isFile() && f.getName().toLowerCase().endsWith(".json"));
            if (jsons != null) {
                for (File j : jsons) allFiles.add(j);
                jsonAdded = jsons.length;
            }
        }
        Log.i("SCREENOMICS_UPLOAD", "Collected " + jsonAdded + " JSON metadata files from outbox");

        if (allFiles.isEmpty()) {
            stopForeground(true);
            stopSelf();
            return super.onStartCommand(intent, flags, startId);
        }

        // Prepare working list
        LinkedList<File> fileList = new LinkedList<>(allFiles);

        // Clean up old-format files in-place (non JSON; only those that match encrypt naming)
        int removedCount = cleanupOldFormatFiles(fileList);
        if (removedCount > 0) {
            Log.i("SCREENOMICS_UPLOAD", "Cleaned up " + removedCount + " old format files");
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int batchSize = prefs.getInt("batchSize", Constants.BATCH_SIZE_DEFAULT);
        int maxToSend = prefs.getInt("maxSend", Constants.MAX_TO_SEND_DEFAULT);

        numToUpload = 0;

        Log.d("SCREENOMICS_UPLOAD", "Creating batches - Batch size: " + batchSize + ", Max to send: " + maxToSend);
        while (!fileList.isEmpty() && (maxToSend == 0 || numToUpload < maxToSend)) {
            List<File> nextBatch = new LinkedList<>();
            for (int i = 0; i < batchSize; i++) {
                if (fileList.peek() == null) break;
                if (maxToSend != 0 && numToUpload == maxToSend) break;

                File file = fileList.remove();

                // Skip already-encrypted files if any (.enc); Batch expects PLAINTEXT or JSON
                String nameLower = file.getName().toLowerCase();
                if (nameLower.endsWith(".enc")) {
                    Log.w("SCREENOMICS_UPLOAD", "Skipping .enc file in queue: " + file.getName());
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

        status = Status.SENDING;
        uploading = true;

        setNotification("Uploading..", "Starting: 0/" + numTotal);
        sendNextBatch();

        return super.onStartCommand(intent, flags, startId);
    }

    public class LocalBinder extends Binder { UploadService getService() { return UploadService.this; } }

    @Override public IBinder onBind(Intent intent) { return new LocalBinder(); }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    "uploading-channel",
                    "Screenomics Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(serviceChannel);
        }
    }

    private Notification setNotification(String title, String content) {
        Intent notificationIntent = new Intent(this, UploadService.class);
        int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);

        Notification notification = new Notification.Builder(this, "uploading-channel")
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.drawable.dna)
                .setContentIntent(pendingIntent)
                .build();

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(5, notification);
        return notification;
    }

    /**
     * Clean up old format files that lack timestamp and IV.
     * Old format: {hash}_{random_hex}_{type}.{ext}
     * New format: {hash}_{timestamp}_{type}_{iv}.{ext}
     */
    private int cleanupOldFormatFiles(LinkedList<File> fileList) {
        int removedCount = 0;
        Iterator<File> iterator = fileList.iterator();

        while (iterator.hasNext()) {
            File file = iterator.next();
            String name = file.getName();
            // Don't touch JSONs in outbox
            if (name.toLowerCase().endsWith(".json")) continue;

            String[] parts = name.split("_");
            boolean isOldFormat = parts.length < 4 || !name.contains("T") || !name.contains("-");
            if (isOldFormat) {
                Log.w("SCREENOMICS_UPLOAD", "Deleting old format file: " + name);
                if (file.delete()) {
                    iterator.remove();
                    removedCount++;
                } else {
                    Log.e("SCREENOMICS_UPLOAD", "Failed to delete old format file: " + name);
                }
            }
        }
        return removedCount;
    }
}
