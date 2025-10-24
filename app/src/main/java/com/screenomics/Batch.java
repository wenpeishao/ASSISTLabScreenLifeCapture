package com.screenomics;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Updated Batch.java — sends X-Request-Nonce and X-Request-Timestamp headers,
 * and includes .json sidecars in the multipart upload.
 * Uses signing-aware OkHttpClient from HttpClientProvider (Date/Digest/Signature via interceptor).
 */
public class Batch {

    private static final String TAG = "SCREENOMICS_UPLOAD";

    private static final MediaType IMAGE_TYPE = MediaType.parse("image/*");
    private static final MediaType VIDEO_TYPE = MediaType.parse("video/mp4");
    private static final MediaType JSON_TYPE  = MediaType.parse("application/json");

    private final Context context;
    private final List<File> files;
    private final OkHttpClient client;

    Batch(Context context, List<File> files) {
        this.context = context.getApplicationContext();
        this.files   = files;
        this.client  = HttpClientProvider.get(context);
    }

    // Legacy ctor kept for compatibility
    Batch(Context context, List<File> files, OkHttpClient ignoredClient) {
        this(context, files);
    }

    public String[] sendFiles() {
        Log.d(TAG, "Starting upload of " + (files == null ? 0 : files.size()) + " files");

        if (files == null || files.isEmpty()) {
            Log.e(TAG, "No files to upload in batch");
            return new String[]{"999", "NO FILES"};
        }

        // Build multipart body
        MultipartBody.Builder bodyPart = new MultipartBody.Builder().setType(MultipartBody.FORM);
        int imgCount = 0, vidCount = 0, jsonCount = 0, skipped = 0;

        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            if (f == null || !f.isFile()) {
                if (f != null) Log.w(TAG, "Skipping non-file: " + f.getAbsolutePath());
                skipped++;
                continue;
            }

            String fileName = f.getName();
            long fileSize = f.length();
            MediaType mediaType = detectMediaType(fileName);

            if (mediaType == VIDEO_TYPE) vidCount++;
            else if (mediaType == JSON_TYPE) jsonCount++;
            else imgCount++;

            Log.d(TAG, "Adding file " + (i + 1) + ": " + fileName + " (" + fileSize + " bytes) as " + mediaType);
            bodyPart.addFormDataPart("file", fileName, RequestBody.create(mediaType, f));
        }

        Log.d(TAG, "Multipart composition: images=" + imgCount + " videos=" + vidCount + " json=" + jsonCount + " skipped=" + skipped);
        RequestBody body = bodyPart.build();

        // Enrollment / receiver config
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String baseUrl     = prefs.getString("base_url", Constants.BASE_URL);
        String pptId       = prefs.getString("ppt_id", "");
        String studyId     = prefs.getString("study_id", "");
        String bearerToken = prefs.getString("enrollment_token", "");

        if (studyId.isEmpty() || pptId.isEmpty()) {
            Log.e(TAG, "Missing study_id or ppt_id in SharedPreferences");
            return new String[]{"400", "MISSING_IDS"};
        }

        String uploadUrl = baseUrl + "/api/v1/img/" + studyId + "/" + pptId;

        // ---- Anti-replay headers ----
        String nonce      = UUID.randomUUID().toString();
        String requestId  = "and-" + UUID.randomUUID();
        String isoNowZulu = iso8601ZuluNow();  // must be close to server time

        Request.Builder rb = new Request.Builder()
                .url(uploadUrl)
                .addHeader("Accept", "application/json")
                .addHeader("X-Participant-ID", pptId)
                .addHeader("X-Study-ID", studyId)
                .addHeader("X-Request-Nonce", nonce)
                .addHeader("X-Request-Timestamp", isoNowZulu)
                .addHeader("X-Request-Id", requestId);

        if (!bearerToken.isEmpty()) {
            rb.addHeader("Authorization", "Bearer " + bearerToken);
        }

        Request request = rb.post(body).build();

        Log.d(TAG, "Sending POST request to: " + uploadUrl);
        Log.d(TAG, "Request headers: " + request.headers());

        Response response = null;
        String responseBody = "";
        try {
            long startTime = System.nanoTime();
            Log.d(TAG, "Executing HTTP request...");
            response = client.newCall(request).execute();
            responseBody = (response.body() != null) ? response.body().string() : "";
            long uploadTime = (System.nanoTime() - startTime) / 1_000_000;
            Log.d(TAG, "Upload completed in " + uploadTime + "ms");
            Log.d(TAG, "Response code: " + response.code());
            Log.d(TAG, "Response body: " + responseBody);
        } catch (Exception e) {
            Log.e(TAG, "Upload failed with exception: " + e.getMessage());
            Log.e(TAG, "Exception type: " + e.getClass().getName(), e);
        }

        int code = (response != null) ? response.code() : 999;

        if (code >= 400 && code < 500) {
            Log.e(TAG, "Client error - Response code: " + code);
            Log.e(TAG, "Response headers: " + (response != null ? response.headers() : "null"));
            Log.e(TAG, "Response body: " + responseBody);
        } else if (code >= 500) {
            Log.e(TAG, "Server error - Response code: " + code);
            Log.e(TAG, "Response body: " + responseBody);
        } else if (code == 200 || code == 201) {
            Log.i(TAG, "Upload successful - Response code: " + code);
        }

        if (response != null) {
            response.close();
        } else {
            Log.e(TAG, "No response received from server");
            return new String[]{"999", "NO RESPONSE"};
        }

        return new String[]{ String.valueOf(code), responseBody };
    }

    public void deleteFiles() {
        Log.d(TAG, "Deleting " + (files == null ? 0 : files.size()) + " uploaded files");
        if (files == null) return;
        for (File file : files) {
            if (file == null) continue;
            boolean deleted = file.delete();
            Log.d(TAG, "Deleted " + file.getName() + ": " + deleted);
        }
    }

    public int size() {
        return files != null ? files.size() : 0;
    }

    /** RFC 3339 / ISO-8601 UTC like 2025-10-24T04:11:14Z */
    private static String iso8601ZuluNow() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date());
    }

    /** Pick media type by file extension; default to image/* for screenshots. */
    private static MediaType detectMediaType(String name) {
        if (name == null) return IMAGE_TYPE;
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".json")) return JSON_TYPE;
        if (lower.endsWith(".mp4"))  return VIDEO_TYPE;
        // (png/jpg/webp/etc.) fall through to generic image/*
        return IMAGE_TYPE;
    }
}
