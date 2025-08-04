package com.screenomics;

import android.content.SharedPreferences;
import android.os.AsyncTask;
import androidx.preference.PreferenceManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class Batch {

    private final MediaType PNG = MediaType.parse("image/*");
    private final List<File> files;
    private final OkHttpClient client;

    Batch(List<File> files, OkHttpClient client) {
        this.files = files;
        this.client = client;
    }

    public String[] sendFiles() {
        Log.d("SCREENOMICS_UPLOAD", "Starting upload of " + files.size() + " files");

        if(files.size() < 1){
            Log.e("SCREENOMICS_UPLOAD", "No files to upload in batch");
            return new String[]{"999", "NO FILES"};
        }

        MultipartBody.Builder bodyPart = new MultipartBody.Builder()
                .setType(MultipartBody.FORM);

        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).isFile()) {
                String fileName = files.get(i).getName();
                long fileSize = files.get(i).length();
                Log.d("SCREENOMICS_UPLOAD", "Adding file" + (i + 1) + ": " + fileName + " (" + fileSize + " bytes)");
                bodyPart.addFormDataPart("file" + (i + 1), fileName, RequestBody.create(PNG, files.get(i)));
            } else {
                Log.w("SCREENOMICS_UPLOAD", "Skipping non-file: " + files.get(i).getAbsolutePath());
            }
        }

        RequestBody body = bodyPart.build();
        Request request = new Request.Builder()
                .addHeader("Content-Type", "multipart/form-data")
                .url(Constants.UPLOAD_ADDRESS)
                .post(body)
                .build();
        
        Log.d("SCREENOMICS_UPLOAD", "Sending POST request to: " + Constants.UPLOAD_ADDRESS);
        Log.d("SCREENOMICS_UPLOAD", "Request headers: " + request.headers());

        Response response = null;
        String responseBody = "";
        try {
            long startTime = System.nanoTime();
            Log.d("SCREENOMICS_UPLOAD", "Executing HTTP request...");
            response = client.newCall(request).execute();
            responseBody = response.body().string();
            long uploadTime = (System.nanoTime() - startTime)/1000000;
            Log.d("SCREENOMICS_UPLOAD", "Upload completed in " + uploadTime + "ms");
            Log.d("SCREENOMICS_UPLOAD", "Response code: " + response.code());
            Log.d("SCREENOMICS_UPLOAD", "Response body: " + responseBody);
        } catch (Exception e) {
            Log.e("SCREENOMICS_UPLOAD", "Upload failed with exception: " + e.getMessage());
            Log.e("SCREENOMICS_UPLOAD", "Exception type: " + e.getClass().getName());
            e.printStackTrace();
        }
        int code = response != null ? response.code() : 999;
        if (code >= 400  && code < 500) {
            Log.e("SCREENOMICS_UPLOAD", "Client error - Response code: " + code);
            Log.e("SCREENOMICS_UPLOAD", "Response headers: " + (response != null ? response.headers() : "null"));
            Log.e("SCREENOMICS_UPLOAD", "Response body: " + responseBody);
        } else if (code >= 500) {
            Log.e("SCREENOMICS_UPLOAD", "Server error - Response code: " + code);
            Log.e("SCREENOMICS_UPLOAD", "Response body: " + responseBody);
        } else if (code == 201) {
            Log.i("SCREENOMICS_UPLOAD", "Upload successful - Response code: " + code);
        }
        
        if (response != null) {
            response.close();
        }else{
            Log.e("SCREENOMICS_UPLOAD", "No response received from server");
            return new String[]{"999", "NO RESPONSE"};
        }
        return new String[]{String.valueOf(code), responseBody};
    }

    public void deleteFiles() {
        Log.d("SCREENOMICS_UPLOAD", "Deleting " + files.size() + " uploaded files");
        files.forEach(file -> {
            boolean deleted = file.delete();
            Log.d("SCREENOMICS_UPLOAD", "Deleted " + file.getName() + ": " + deleted);
        });
    }

    public int size() {
        return files.size();
    }

}
