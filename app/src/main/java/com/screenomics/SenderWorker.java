package com.screenomics;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.util.Log;
import androidx.preference.PreferenceManager;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.joda.time.DateTime;

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

import okhttp3.OkHttpClient;

public class SenderWorker extends Worker {
    public SenderWorker(
            @NonNull Context context,
            @NonNull WorkerParameters params) {
        super(context, params);
    }
    enum Status {
        IDLE, SENDING, FAILED, SUCCESS
    }

    public int numToUpload = 0;
    public int numUploaded = 0;
    public int numTotal = 0;
    public boolean uploading = false;
    public String errorCode = "";
    public String lastActivityTime = "";
    public boolean continueWithoutWifi = false;

    private int numBatchesSending = 0;
    private int numBatchesToSend = 1;
    private List<Batch> batches = new ArrayList<>();

    private final OkHttpClient client = new OkHttpClient.Builder().readTimeout(Constants.REQ_TIMEOUT, TimeUnit.SECONDS).build();
    private LocalDateTime startDateTime;

    @Override
    public Result doWork() {
        /*
        final List<Number> hours = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8);
        if (!hours.contains(DateTime.now().getHourOfDay())) {
            return Result.success();
        }
        */
        Log.i("SCREENOMICS_WORKER", "SenderWorker started - Beginning upload process");
        Context context = getApplicationContext();
        File f_encrypt = new File(context.getExternalFilesDir(null).getAbsolutePath() + File.separator + "encrypt");
        Log.d("SCREENOMICS_WORKER", "Encrypt directory: " + f_encrypt.getAbsolutePath());
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        startDateTime = LocalDateTime.now();
        File dir = new File(f_encrypt.getAbsolutePath());
        File[] files = dir.listFiles(onlyFilesBeforeStart);

        if (files == null || files.length == 0) {
            Log.d("SCREENOMICS_WORKER", "No files found to upload");
            return Result.success();
        }
        
        Log.i("SCREENOMICS_WORKER", "Found " + files.length + " files to upload");

        LinkedList<File> fileList = new LinkedList<>(Arrays.asList(files));

        int batchSize = prefs.getInt("batchSize", Constants.BATCH_SIZE_DEFAULT);
        int maxToSend = prefs.getInt("maxSend", Constants.MAX_TO_SEND_DEFAULT);

        numToUpload = 0;

        // Split the files into batches.
        while (fileList.size() > 0 && (maxToSend == 0 || numToUpload < maxToSend)) {
            List<File> nextBatch = new LinkedList<>();
            for (int i = 0; i < batchSize; i++) {
                if (fileList.peek() == null) { break; }
                if (maxToSend != 0 && numToUpload == maxToSend) { break; }
                numToUpload ++;
                nextBatch.add(fileList.remove());
            }
            Batch batch = new Batch(nextBatch, client);
            batches.add(batch);
        }

        numTotal = numToUpload;
        Log.i("SCREENOMICS_WORKER", "Created " + batches.size() + " batches with " + numToUpload + " files to upload");
        Log.d("SCREENOMICS_WORKER", "Files remaining in directory: " + fileList.size());

        int successCount = 0;
        int failCount = 0;
        
        for (int i = 0; i < batches.size(); i++) {
            Batch batch = batches.get(i);
            Log.d("SCREENOMICS_WORKER", "Processing batch " + (i + 1) + "/" + batches.size() + " with " + batch.size() + " files");
            
            String[] response = batch.sendFiles();
            String code = response[0];
            String body = response[1];
            
            if (code.equals("201")) {
                successCount += batch.size();
                Log.i("SCREENOMICS_WORKER", "Batch " + (i + 1) + " uploaded successfully. Response: " + body);
                Logger.i(context, "SenderWorker: Upload success for " + batch.size() + " files. Server msg: " + body);
                batch.deleteFiles();
            } else {
                failCount += batch.size();
                Log.e("SCREENOMICS_WORKER", "Batch " + (i + 1) + " failed with code " + code + ". Response: " + body);
                Logger.e(context, "SenderWorker: Upload failed with code " + code + ". Server msg: " + body);
            }
        }
        
        Log.i("SCREENOMICS_WORKER", "Upload completed - Success: " + successCount + ", Failed: " + failCount);

        // ZonedDateTime dateTime = ZonedDateTime.now();
        // DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
        // lastActivityTime = dateTime.format(formatter);
        return Result.success();
    }

    private final FileFilter onlyFilesBeforeStart = new FileFilter() {
        @Override
        public boolean accept(File file) {
            return true;
        }
    };
}
