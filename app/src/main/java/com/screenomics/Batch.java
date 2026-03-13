package com.screenomics;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import javax.net.ssl.SSLException;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONObject;
import org.json.JSONException;

/**
 * Batch.java — uploads encrypted screenshot files to the MindPulse Receiver API
 * using the /api/v1/batches and /api/v1/batches/{id}/screenshots endpoints.
 *
 * Flow:
 *  1) POST /api/v1/batches to create a new batch
 *  2) For each PLAINTEXT file in `files`:
 *       - Encrypt to .enc with Encryptor (AES-GCM; nonce is prefixed)
 *       - POST the .enc + JSON metadata to /batches/{id}/screenshots
 *       - On success, delete plaintext and ciphertext
 *
 * Metadata includes:
 *   - aes_key_encrypted_b64 (RSA-OAEP SHA-256)
 *   - tag_len_bits (128)
 *   - mime/type/captured_at
 *   - NO gcm_nonce_b64 (nonce is prefixed to .enc)
 */
public class Batch {

    private static final String TAG = "SCREENOMICS_UPLOAD";
    private static final int MAX_UPLOAD_RETRIES = 3;
    private static final long RETRY_BASE_DELAY_MS = 2000L;

    private static final MediaType OCTET = MediaType.parse("application/octet-stream");
    private static final MediaType JSON_TYPE = MediaType.parse("application/json");

    private final Context context;
    private final List<File> files;
    private final OkHttpClient client;

    // Primary ctor
    public Batch(Context context, List<File> files) {
        this.context = context.getApplicationContext();
        this.files   = files;
        this.client  = HttpClientProvider.get(this.context);
    }

    // Back-compat ctor used by existing call sites
    public Batch(Context context, List<File> files, OkHttpClient providedClient) {
        this.context = context.getApplicationContext();
        this.files   = files;
        this.client  = (providedClient != null) ? providedClient : HttpClientProvider.get(this.context);
    }

    /** Main upload flow -- handles pre-encrypted .enc+.meta pairs and legacy plaintext */
    public String[] sendFiles() {
        Log.d(TAG, "Starting batch upload of " + (files == null ? 0 : files.size()) + " files");
        if (files == null || files.isEmpty()) {
            Log.e(TAG, "No files to upload");
            return new String[]{"999", "NO FILES"};
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String baseUrl     = prefs.getString("base_url", Constants.BASE_URL);
        String pptId       = prefs.getString("ppt_id", "");
        String studyId     = prefs.getString("study_id", "");
        String bearerToken = prefs.getString("enrollment_token", "");
        String imagePubPem = prefs.getString("image_public_key", "");

        if (studyId.isEmpty() || pptId.isEmpty()) {
            Log.e(TAG, "Missing study_id or ppt_id in SharedPreferences");
            return new String[]{"400", "MISSING_IDS"};
        }

        // ---------- STEP 1: Create batch ----------
        int batchId = createBatch(baseUrl, pptId, studyId, bearerToken);
        if (batchId <= 0) {
            return new String[]{"999", "CREATE_BATCH_FAILED"};
        }

        // ---------- STEP 2: Upload each file ----------
        int success = 0, fail = 0, skip = 0;
        String uploadBase = baseUrl + "/api/v1/batches/" + batchId + "/screenshots";

        for (File file : files) {
            if (file == null || !file.isFile()) { skip++; continue; }

            String name  = file.getName();
            String lower = name.toLowerCase(Locale.US);

            // .meta files are sidecars, processed alongside their .enc partner
            if (lower.endsWith(".meta")) { skip++; continue; }

            if (lower.endsWith(".enc")) {
                // --- Pre-encrypted at capture time: read .meta sidecar ---
                String metaName = name.substring(0, name.length() - 4) + ".meta";
                File metaFile = new File(file.getParentFile(), metaName);
                if (!metaFile.isFile()) {
                    Log.e(TAG, "Orphaned .enc without .meta, cannot upload: " + name);
                    // .enc without wrapped AES key metadata is not recoverable server-side.
                    // Remove it to avoid permanent retry loops on every upload run.
                    boolean deleted = file.delete();
                    Log.w(TAG, "Deleted orphan .enc " + name + ": " + deleted);
                    fail++;
                    continue;
                }

                String metaJson;
                try {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(new FileReader(metaFile))) {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                    }
                    metaJson = sb.toString();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to read .meta sidecar for " + name, e);
                    fail++;
                    continue;
                }

                // Upload .enc directly (already encrypted, no re-encryption)
                if (uploadEncFile(file, metaJson, pptId, studyId, bearerToken, uploadBase)) {
                    success++;
                    file.delete();
                    metaFile.delete();
                } else {
                    fail++;
                }

            } else {
                // --- Legacy plaintext: encrypt then upload ---
                if (imagePubPem == null || imagePubPem.trim().isEmpty()) {
                    Log.e(TAG, "Cannot encrypt legacy plaintext, no image_public_key");
                    fail++;
                    continue;
                }

                String type = guessTypeFromName(lower);
                String mime = guessMimeFromName(lower);

                File encFile = new File(file.getParentFile(), name + ".enc");
                Encryptor.Result encResult;
                try {
                    encResult = Encryptor.encryptFileToEnc(file, encFile, imagePubPem);
                } catch (Exception e) {
                    fail++;
                    Log.e(TAG, "Encryption failed for " + name, e);
                    continue;
                }

                String metaJson = buildMetadataJson(
                        pptId, mime, type, iso8601ZuluNow(),
                        encResult.aesKeyEncB64, null, encResult.tagLenBits);

                if (uploadEncFile(encFile, metaJson, pptId, studyId, bearerToken, uploadBase)) {
                    success++;
                    file.delete();
                    encFile.delete();
                } else {
                    fail++;
                    encFile.delete();
                }
            }
        }

        String summary = "OK=" + success + " FAIL=" + fail + " SKIP=" + skip;
        Log.i(TAG, "Batch upload summary: " + summary);
        return new String[]{(fail == 0 ? "202" : "207"), summary};
    }

    /** Upload a single .enc file with its metadata JSON. Returns true on success. */
    private boolean uploadEncFile(File encFile, String metaJson,
                                  String pptId, String studyId, String bearerToken, String uploadUrl) {
        for (int attempt = 1; attempt <= MAX_UPLOAD_RETRIES; attempt++) {
            MultipartBody.Builder body = new MultipartBody.Builder().setType(MultipartBody.FORM);
            body.addFormDataPart("metadata", null, RequestBody.create(JSON_TYPE, metaJson));
            body.addFormDataPart("file", encFile.getName(), RequestBody.create(OCTET, encFile));

            Request.Builder rb = new Request.Builder()
                    .url(uploadUrl)
                    .addHeader("Accept", "application/json")
                    .addHeader("X-Participant-ID", pptId)
                    .addHeader("X-Study-ID", studyId)
                    .addHeader("X-Request-Nonce", UUID.randomUUID().toString())
                    .addHeader("X-Request-Timestamp", iso8601ZuluNow())
                    .addHeader("X-Request-Id", "and-up-" + UUID.randomUUID());
            if (bearerToken != null && !bearerToken.isEmpty()) {
                rb.addHeader("Authorization", "Bearer " + bearerToken);
            }
            rb.post(body.build());

            try (Response resp = client.newCall(rb.build()).execute()) {
                int code = resp.code();
                String bodyStr = (resp.body() != null) ? resp.body().string() : "";
                Log.d(TAG, "Upload response: " + code + " body=" + bodyStr);

                if (code == 202 || (code == 409 && bodyStr.contains("duplicate_screenshot"))) {
                    return true;
                }

                if (!isRetryableStatus(code) || attempt == MAX_UPLOAD_RETRIES) {
                    return false;
                }

                if (!sleepBeforeRetry(attempt, encFile.getName(), "http_" + code)) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            } catch (Exception ex) {
                boolean retryable = isRetryableException(ex);
                Log.e(TAG, "Upload exception for " + encFile.getName() + ": " + ex.getMessage(), ex);

                if (!retryable || attempt == MAX_UPLOAD_RETRIES) {
                    return false;
                }
                if (!sleepBeforeRetry(attempt, encFile.getName(), "exception")) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        return false;
    }

    private boolean sleepBeforeRetry(int attempt, String fileName, String reason) {
        long delayMs = RETRY_BASE_DELAY_MS * attempt;
        int nextAttempt = attempt + 1;
        Log.w(TAG, "Retrying " + fileName + " in " + delayMs + "ms (attempt "
                + nextAttempt + "/" + MAX_UPLOAD_RETRIES + ", reason=" + reason + ")");
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException ie) {
            Log.w(TAG, "Retry sleep interrupted for " + fileName);
            return false;
        }
    }

    private boolean isRetryableStatus(int code) {
        return code == 408 || code == 429 || code >= 500;
    }

    private boolean isRetryableException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof InterruptedIOException
                    || current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof SSLException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** Create batch and return ID */
    private int createBatch(String baseUrl, String pptId, String studyId, String bearerToken) {
        final String createUrl = baseUrl + "/api/v1/batches";
        String json;
        try {
            JSONObject reqBody = new JSONObject();
            reqBody.put("client_id", pptId);
            json = reqBody.toString();
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build createBatch JSON", e);
            return -1;
        }

        Request.Builder rb = new Request.Builder()
                .url(createUrl)
                .addHeader("Accept", "application/json")
                .addHeader("X-Participant-ID", pptId)
                .addHeader("X-Study-ID", studyId)
                .addHeader("X-Request-Nonce", java.util.UUID.randomUUID().toString())
                .addHeader("X-Request-Timestamp", iso8601ZuluNow())
                .addHeader("X-Request-Id", "and-" + java.util.UUID.randomUUID())
                .post(RequestBody.create(JSON_TYPE, json));

        if (!bearerToken.isEmpty()) {
            rb.addHeader("Authorization", "Bearer " + bearerToken);
        }

        try (Response r = client.newCall(rb.build()).execute()) {
            final int code = r.code();
            final String body = (r.body() != null) ? r.body().string() : "";
            Log.d(TAG, "Batch create -> " + code + " body=" + body);

            // 409: duplicate batch (server returns {"error":"duplicate_batch","details":{"batch_id":...}})
            if (code == 409) {
                try {
                    JSONObject obj = new JSONObject(body.trim());
                    JSONObject details = obj.optJSONObject("details");
                    if (details != null) {
                        int dupId = safeInt(details.opt("batch_id"));
                        if (dupId > 0) {
                            Log.w(TAG, "Reusing existing batch (duplicate): " + dupId);
                            return dupId;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to parse 409 response: " + body, e);
                }
                Log.e(TAG, "Create batch duplicate without usable id: " + body);
                return -1;
            }

            if (code != 201 && code != 200) {
                Log.e(TAG, "Create batch failed: " + code + " body=" + body);
                return -1;
            }

            // Happy path: parse batch_id from JSON
            try {
                JSONObject obj = new JSONObject(body.trim());

                // Primary: batch_id
                int batchId = safeInt(obj.opt("batch_id"));
                if (batchId > 0) {
                    Log.i(TAG, "Created batch id=" + batchId);
                    return batchId;
                }

                // Fallback: "id" field
                batchId = safeInt(obj.opt("id"));
                if (batchId > 0) {
                    Log.i(TAG, "Created batch id=" + batchId + " (from 'id')");
                    return batchId;
                }

                // Fallback: nested "details.batch_id"
                JSONObject details = obj.optJSONObject("details");
                if (details != null) {
                    batchId = safeInt(details.opt("batch_id"));
                    if (batchId > 0) {
                        Log.i(TAG, "Created batch id=" + batchId + " (from details.batch_id)");
                        return batchId;
                    }
                }

                Log.e(TAG, "Create batch succeeded but no batch_id in response: " + body);
                return -1;

            } catch (JSONException je) {
                Log.e(TAG, "JSON parse error on batch create body: " + body, je);
                return -1;
            }

        } catch (Exception e) {
            Log.e(TAG, "Batch creation failed", e);
            return -1;
        }
    }

    /** Safely coerce a JSON value to int. Only accepts numeric types. */
    private static int safeInt(Object val) {
        if (val instanceof Number) return ((Number) val).intValue();
        return 0;
    }

    /** Build metadata JSON string */
    private static String buildMetadataJson(String pptId, String mime, String type, String capturedIso,
                                            String aesKeyEncB64, String gcmNonceB64, int tagLenBits) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("ppt_id", pptId);
            obj.put("mime", mime);
            obj.put("type", type);
            obj.put("captured_at", capturedIso);
            obj.put("aes_key_encrypted_b64", aesKeyEncB64);
            obj.put("tag_len_bits", tagLenBits);
            if (gcmNonceB64 != null && !gcmNonceB64.isEmpty()) {
                obj.put("gcm_nonce_b64", gcmNonceB64);
            }
            return obj.toString();
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build metadata JSON", e);
            return "{}";
        }
    }

    /** Determine "type" primarily from extension. */
    private static String guessTypeFromName(String lower) {
        if (lower.endsWith(".json")) return "metadata";
        if (lower.endsWith(".mp4"))  return "video";
        return "image";
    }

    /** Guess MIME from original filename (not the .enc). */
    private static String guessMimeFromName(String lower) {
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    /** ISO 8601 UTC timestamp like 2025-10-24T04:11:14Z */
    private static String iso8601ZuluNow() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date());
    }

    public int size() {
        return files != null ? files.size() : 0;
    }
}
