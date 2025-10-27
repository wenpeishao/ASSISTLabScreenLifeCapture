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

import org.json.JSONObject;
import org.json.JSONException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** Main upload flow */
    public String[] sendFiles() {
        Log.d(TAG, "Starting encrypted batch upload of " + (files == null ? 0 : files.size()) + " files");
        if (files == null || files.isEmpty()) {
            Log.e(TAG, "No files to upload");
            return new String[]{"999", "NO FILES"};
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String baseUrl     = prefs.getString("base_url", Constants.BASE_URL);
        String pptId       = prefs.getString("ppt_id", "");
        String studyId     = prefs.getString("study_id", "");
        String bearerToken = prefs.getString("enrollment_token", "");
        String imagePubPem = prefs.getString("image_public_key", ""); // saved at enroll time

        if (studyId.isEmpty() || pptId.isEmpty()) {
            Log.e(TAG, "Missing study_id or ppt_id in SharedPreferences");
            return new String[]{"400", "MISSING_IDS"};
        }
        if (imagePubPem == null || imagePubPem.trim().isEmpty()) {
            Log.e(TAG, "Missing image_public_key PEM in SharedPreferences");
            return new String[]{"400", "MISSING_IMAGE_PUBKEY"};
        }

        // ---------- STEP 1: Create batch ----------
        int batchId = createBatch(baseUrl, pptId, studyId, bearerToken);
        if (batchId <= 0) {
            return new String[]{"999", "CREATE_BATCH_FAILED"};
        }

        // ---------- STEP 2: Encrypt + upload each file ----------
        int success = 0, fail = 0, skip = 0;
        String uploadBase = baseUrl + "/api/v1/batches/" + batchId + "/screenshots";

        for (File plain : files) {
            if (plain == null || !plain.isFile()) { skip++; continue; }

            String origName = plain.getName();
            String lower    = origName.toLowerCase(Locale.US);
            String type     = guessTypeFromName(lower);    // "image" / "video" / "metadata"
            String mime     = guessMimeFromName(lower);    // based on original filename
            String capturedIso = iso8601ZuluNow();         // swap for actual capture time if available

            // Encrypt plaintext -> .enc (nonce || ct||tag), get RSA-wrapped key
            File encFile = new File(plain.getParentFile(), origName + ".enc");
            Encryptor.Result encResult;
            try {
                encResult = Encryptor.encryptFileToEnc(plain, encFile, imagePubPem);
                Log.d(TAG, "Encrypted " + origName + " -> " + encFile.getName()
                        + " (aesKeyEncB64 len=" + encResult.aesKeyEncB64.length() + ")");
            } catch (Exception e) {
                fail++;
                Log.e(TAG, "Encryption failed for " + origName, e);
                continue;
            }

            // Metadata: nonce is prefixed, so omit gcm_nonce_b64
            int tagLenBits      = encResult.tagLenBits; // 128
            String aesKeyEncB64 = encResult.aesKeyEncB64;
            String gcmNonceB64  = null;

            String metaJson = buildMetadataJson(
                    pptId, mime, type, capturedIso, aesKeyEncB64, gcmNonceB64, tagLenBits
            );

            MultipartBody.Builder body = new MultipartBody.Builder().setType(MultipartBody.FORM);
            body.addFormDataPart("metadata", null, RequestBody.create(JSON_TYPE, metaJson));
            body.addFormDataPart("file", encFile.getName(), RequestBody.create(OCTET, encFile));

            Request.Builder rb = new Request.Builder()
                    .url(uploadBase)
                    .addHeader("Accept", "application/json")
                    .addHeader("X-Participant-ID", pptId)
                    .addHeader("X-Study-ID", studyId)
                    .addHeader("X-Request-Nonce", UUID.randomUUID().toString())
                    .addHeader("X-Request-Timestamp", iso8601ZuluNow())
                    .addHeader("X-Request-Id", "and-up-" + UUID.randomUUID());
            if (!bearerToken.isEmpty()) {
                rb.addHeader("Authorization", "Bearer " + bearerToken);
            }
            rb.post(body.build());

            try (Response resp = client.newCall(rb.build()).execute()) {
                int code = resp.code();
                String bodyStr = (resp.body() != null) ? resp.body().string() : "";
                Log.d(TAG, "Upload response: " + code + " body=" + bodyStr);

                if (code == 202 || (code == 409 && bodyStr.contains("duplicate_screenshot"))) {
                    success++;
                    // Clean up local files on success
                    boolean delPlain = plain.delete();
                    boolean delEnc   = encFile.delete();
                    Log.d(TAG, "Deleted plaintext " + plain.getName() + ": " + delPlain);
                    Log.d(TAG, "Deleted ciphertext " + encFile.getName() + ": " + delEnc);
                } else {
                    fail++;
                    Log.e(TAG, "Upload failed for " + encFile.getName() + " code=" + code);
                }
            } catch (Exception ex) {
                fail++;
                Log.e(TAG, "Upload exception for " + encFile.getName() + ": " + ex.getMessage(), ex);
            }
        }

        String summary = "OK=" + success + " FAIL=" + fail + " SKIP=" + skip;
        Log.i(TAG, "Batch upload summary: " + summary);
        return new String[]{(fail == 0 ? "202" : "207"), summary};
    }

    /** Create batch and return ID */
    private int createBatch(String baseUrl, String pptId, String studyId, String bearerToken) {
        final String createUrl = baseUrl + "/api/v1/batches";
        final String json = "{\"client_id\":\"" + pptId + "\"}";

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
                    JSONObject obj = new JSONObject(sanitizePossiblyWeirdJson(body));
                    JSONObject details = obj.optJSONObject("details");
                    if (details != null) {
                        int dupId = safeInt(details.opt("batch_id"));
                        if (dupId > 0) {
                            Log.w(TAG, "Reusing existing batch (duplicate): " + dupId);
                            return dupId;
                        }
                    }
                } catch (Exception ignored) {}
                Log.e(TAG, "Create batch duplicate without usable id: " + body);
                return -1;
            }

            if (code != 201 && code != 200) {
                Log.e(TAG, "Create batch failed: " + code + " body=" + body);
                return -1;
            }

            // Happy path: parse batch_id from JSON
            try {
                String clean = sanitizePossiblyWeirdJson(body);
                JSONObject obj = new JSONObject(clean);

                // Primary: batch_id
                int batchId = safeInt(obj.opt("batch_id"));
                if (batchId > 0) {
                    Log.i(TAG, "Created batch id=" + batchId);
                    return batchId;
                }

                // Fallbacks: sometimes APIs include "id" only, or "details.batch_id"
                batchId = safeInt(obj.opt("id"));
                if (batchId > 0) {
                    Log.i(TAG, "Created batch id=" + batchId + " (from 'id')");
                    return batchId;
                }
                JSONObject details = obj.optJSONObject("details");
                if (details != null) {
                    batchId = safeInt(details.opt("batch_id"));
                    if (batchId > 0) {
                        Log.i(TAG, "Created batch id=" + batchId + " (from details.batch_id)");
                        return batchId;
                    }
                }

                // Last-ditch: regex search for "batch_id": <digits>
                Matcher m = Pattern.compile("\"batch_id\"\\s*:\\s*(\\d+)").matcher(clean);
                if (m.find()) {
                    batchId = Integer.parseInt(m.group(1));
                    Log.i(TAG, "Created batch id=" + batchId + " (regex recovery)");
                    return batchId;
                }

                Log.e(TAG, "Create batch succeeded but no batch_id present: " + clean);
                return -1;

            } catch (JSONException je) {
                Log.e(TAG, "JSON parse error on batch create body", je);
                return -1;
            }

        } catch (Exception e) {
            Log.e(TAG, "Batch creation failed", e);
            return -1;
        }
    }

    /** Remove any standalone numeric lines that can corrupt JSON (e.g., a lone "3017"). */
    private static String sanitizePossiblyWeirdJson(String body) {
        if (body == null) return "";
        // Remove lines that are only digits (and whitespace), often inserted by proxies/loggers
        String cleaned = body.replaceAll("(?m)^\\s*\\d+\\s*$", "");
        // Also trim any BOM or stray control chars
        return cleaned.trim();
    }

    /** Safely coerce a value to int (supports Integer, Long, String) else 0. */
    private static int safeInt(Object val) {
        if (val == null) return 0;
        if (val instanceof Number) return ((Number) val).intValue();
        try {
            String s = String.valueOf(val).trim();
            // Extract first integer token if present
            Matcher m = Pattern.compile("(-?\\d+)").matcher(s);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Exception ignored) {}
        return 0;
    }

    /** Build metadata JSON string */
    private static String buildMetadataJson(String pptId, String mime, String type, String capturedIso,
                                            String aesKeyEncB64, String gcmNonceB64, int tagLenBits) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"ppt_id\":\"").append(pptId).append("\",");
        sb.append("\"mime\":\"").append(mime).append("\",");
        sb.append("\"type\":\"").append(type).append("\",");
        sb.append("\"captured_at\":\"").append(capturedIso).append("\",");
        sb.append("\"aes_key_encrypted_b64\":\"").append(aesKeyEncB64).append("\",");
        sb.append("\"tag_len_bits\":").append(tagLenBits);
        if (gcmNonceB64 != null && !gcmNonceB64.isEmpty()) {
            sb.append(",\"gcm_nonce_b64\":\"").append(gcmNonceB64).append('"');
        }
        sb.append('}');
        return sb.toString();
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

    public void deleteFiles() {
        if (files == null) return;
        for (File f : files) {
            if (f != null && f.exists()) {
                boolean deleted = f.delete();
                Log.d(TAG, "Deleted " + f.getName() + ": " + deleted);
            }
        }
    }

    public int size() {
        return files != null ? files.size() : 0;
    }
}
