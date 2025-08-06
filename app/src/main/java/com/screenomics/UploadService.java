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

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import android.os.AsyncTask;
import androidx.preference.PreferenceManager;
import android.util.Log;

import java.io.File;
import java.io.FileFilter;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;


public class UploadService extends Service {

    enum Status {
        IDLE, SENDING, FAILED, SUCCESS
    }

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
    private List<Batch> batches = new ArrayList<>();

    private final OkHttpClient client = new OkHttpClient.Builder().readTimeout(Constants.REQ_TIMEOUT, TimeUnit.SECONDS).build();
    private LocalDateTime startDateTime;

    private final FileFilter onlyFilesBeforeStart = new FileFilter() {
        @Override
        public boolean accept(File file) {
            return true;
        }
    };



    @Override
    public void onCreate() {
        super.onCreate();
    }

    public class Sender extends AsyncTask<Batch, Integer, Void> {
        @Override
        protected Void doInBackground(Batch... batches) {
            if(null != batches && batches.length > 0){
                Log.d("SCREENOMICS_UPLOAD", "Sender AsyncTask executing for batch with " + batches[0].size() + " files");
                String[] response = batches[0].sendFiles();
                String code = response[0];
                String body = response[1];
                Log.d("SCREENOMICS_UPLOAD", "Sender AsyncTask received response code: " + code);
                if (code.equals("201")) {
                    batches[0].deleteFiles();
                    sendSuccessful(batches[0], body);
                } else {
                    sendBatchFailure(batches[0], code, body);
                }
            } else {
                Log.e("SCREENOMICS_UPLOAD", "Sender AsyncTask called with null or empty batches");
            }
            return null;
        }
    }

    private void sendNextBatch() {
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
        numBatchesSending ++;
        Log.i("SCREENOMICS_UPLOAD", "Sending batch " + numBatchesSending + "/" + numBatchesToSend + " with " + batch.size() + " files");
        Log.d("SCREENOMICS_UPLOAD", "Files to upload: " + numToUpload + ", Uploaded: " + numUploaded);
        new Sender().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, batch);
    }

    private void sendSuccessful(Batch batch, String responseBody) {
        numBatchesSending --;
        numUploaded += batch.size();
        numToUpload -= batch.size();
        
        Log.i("SCREENOMICS_UPLOAD", "Batch upload successful!");
        Log.i("SCREENOMICS_UPLOAD", "Progress: " + numUploaded + "/" + numTotal + " uploaded");
        Log.d("SCREENOMICS_UPLOAD", "Remaining to upload: " + numToUpload);
        Log.d("SCREENOMICS_UPLOAD", "Active batches: " + numBatchesSending);

        Logger.i(getApplicationContext(), "Upload success for " + batch.size() + " files. Server msg: " + responseBody);
        
        // Update notification with progress
        setNotification("Uploading..", "Progress: " + numUploaded + "/" + numTotal);

        if (numBatchesToSend < Constants.MAX_BATCHES_TO_SEND) numBatchesToSend ++;
        for (int i = 0; i < numBatchesToSend; i++) { sendNextBatch(); }
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
        
        // Update notification with current progress
        setNotification("Uploading..", "Progress: " + numUploaded + "/" + numTotal + " (" + numFailed + " failed)");
        
        // Continue with next batches
        for (int i = 0; i < numBatchesToSend; i++) { 
            sendNextBatch(); 
        }
        
        // Check if all batches are processed (successful or failed)  
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

        // Notification ID cannot be 0.
        startForeground(5, notification);

        if (intent == null || status == Status.SENDING) {
            stopForeground(true);
            stopSelf();
            return super.onStartCommand(intent, flags, startId);
        }

        String dirPath = intent.getStringExtra("dirPath");
        continueWithoutWifi = intent.getBooleanExtra("continueWithoutWifi", false);
        
        Log.i("SCREENOMICS_UPLOAD", "UploadService started - Directory: " + dirPath);
        Log.d("SCREENOMICS_UPLOAD", "Continue without WiFi: " + continueWithoutWifi);

        startDateTime = LocalDateTime.now();
        File dir = new File(dirPath);
        File[] files = dir.listFiles(onlyFilesBeforeStart);
        
        Log.d("SCREENOMICS_UPLOAD", "Found " + (files != null ? files.length : 0) + " files in directory");


        if (files == null || files.length == 0) {
            stopForeground(true);
            stopSelf();
            return super.onStartCommand(intent, flags, startId);
        }

        LinkedList<File> fileList = new LinkedList<>(Arrays.asList(files));

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int batchSize = prefs.getInt("batchSize", Constants.BATCH_SIZE_DEFAULT);
        int maxToSend = prefs.getInt("maxSend", Constants.MAX_TO_SEND_DEFAULT);

        numToUpload = 0;

        // Split the files into batches.
        Log.d("SCREENOMICS_UPLOAD", "Creating batches - Batch size: " + batchSize + ", Max to send: " + maxToSend);
        while (fileList.size() > 0 && (maxToSend == 0 || numToUpload < maxToSend)) {
            List<File> nextBatch = new LinkedList<>();
            for (int i = 0; i < batchSize; i++) {
                if (fileList.peek() == null) { break; }
                if (maxToSend != 0 && numToUpload == maxToSend) { break; }
                File file = fileList.remove();
                Log.d("SCREENOMICS_UPLOAD", "Adding to batch: " + file.getName());
                numToUpload ++;
                nextBatch.add(file);
            }
            Batch batch = new Batch(nextBatch, client);
            batches.add(batch);
            Log.d("SCREENOMICS_UPLOAD", "Created batch " + batches.size() + " with " + nextBatch.size() + " files");
        }

        numTotal = numToUpload;
        numUploaded = 0;
        numFailed = 0;
        numBatchesToSend = 1;
        Log.i("SCREENOMICS_UPLOAD", "Upload initialized:");
        Log.i("SCREENOMICS_UPLOAD", "  - Total batches: " + batches.size());
        Log.i("SCREENOMICS_UPLOAD", "  - Total files to upload: " + numToUpload);
        Log.i("SCREENOMICS_UPLOAD", "  - Files remaining in directory: " + fileList.size());

        status = Status.SENDING;
        uploading = true;
        // Send the first batch.
        Log.i("SCREENOMICS_UPLOAD", "Starting upload process...");
        setNotification("Uploading..", "Starting: 0/" + numTotal);
        sendNextBatch();

        return super.onStartCommand(intent, flags, startId);
    }

    public class LocalBinder extends Binder {
        UploadService getService() { return UploadService.this; }
    }

    @Override public IBinder onBind(Intent intent) { return new LocalBinder(); }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    "uploading-channel",
                    "Screenomics Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setSound(null, null);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(serviceChannel);
        }
    }

    private Notification setNotification(String title, String content) {
        Intent notificationIntent = new Intent(this, UploadService.class);

        int intentflags;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
            intentflags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        }else{
            intentflags = PendingIntent.FLAG_UPDATE_CURRENT;
        }

        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0, notificationIntent, intentflags);
        Notification notification =
                new Notification.Builder(this, "uploading-channel")
                        .setContentTitle(title)
                        .setContentText(content)
                        .setSmallIcon(R.drawable.dna)
                        .setContentIntent(pendingIntent)
                        .build();

        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.notify(5, notification);
        return notification;
    }
}
