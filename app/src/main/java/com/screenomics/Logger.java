package com.screenomics;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.BufferedReader;

import org.json.JSONArray;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class Logger {

    private static final String TAG = "SCREENOMICS_LOGGER";
    private static final DateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss:");
    private static final Object LOG_LOCK = new Object();

    static {
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public static void i(Context context, String msg) {
        synchronized (LOG_LOCK) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            SharedPreferences.Editor editor = prefs.edit();
            String existing = prefs.getString("logs", "");
            if (existing.length() - existing.replaceAll("\n", "").length() > 18) {
                int index = existing.indexOf("\n");
                existing = existing.substring(index + 1);
            }
            editor.putString("logs", existing + "\ni" + sdf.format(new Date()) + msg);
            editor.apply();
        }
    }

    public static void e(Context context, String msg) {
        synchronized (LOG_LOCK) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            SharedPreferences.Editor editor = prefs.edit();
            String existing = prefs.getString("logs", "");
            if (existing.length() - existing.replaceAll("\n", "").length() > 18) {
                int index = existing.indexOf("\n");
                existing = existing.substring(index + 1);
            }
            editor.putString("logs", existing + "\ne" + sdf.format(new Date()) + msg);
            editor.apply();
        }
    }

    public static void reset(Context context) {
        synchronized (LOG_LOCK) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("logs", "");
            editor.apply();
        }
    }

    public static String getLatestError(Context context) {
        synchronized (LOG_LOCK) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            String logs = prefs.getString("logs", "");
            if (logs == null || logs.isEmpty()) return "";

            String[] lines = logs.split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i];
                if (line != null && line.startsWith("e")) {
                    return line;
                }
            }
            return "";
        }
    }

    public static String getAll(Context context) {
        synchronized (LOG_LOCK) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            return prefs.getString("logs", "");
        }
    }

    /**
     * Snapshot both in-app logs and own-process logcat into a single encrypted
     * diagnostics file in /encrypt for the normal upload flow.
     * Returns true when a diagnostics snapshot was queued.
     */
    public static boolean queueDiagnosticsForUpload(Context context) {
        synchronized (LOG_LOCK) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            String logs = prefs.getString("logs", "");
            if (logs == null) logs = "";

            String logcat = captureOwnProcessLogcat();
            if (logcat == null) logcat = "";

            try {
                JSONObject payload = new JSONObject();
                payload.put("generated_at", utcIsoMillis().format(new Date()));
                payload.put("pid", android.os.Process.myPid());
                payload.put("app_logs", logs);
                payload.put("logcat", logcat);
                payload.put("device_state", DeviceStateCollector.collectSnapshot(context));

                boolean queued = queueTextForUpload(context, payload.toString(), "diagnostics", "application/json");
                if (queued) {
                    prefs.edit().putString("logs", "").apply();
                }
                return queued;
            } catch (Exception e) {
                Log.e(TAG, "Failed to build diagnostics JSON payload", e);
                return false;
            }
        }
    }

    /**
     * Defense-in-depth: delete stale plaintext temp files (tmp_*.jpg / tmp_*.log) left in the
     * files/ root if the process was killed between writing and deleting during encryption.
     * Normally these are deleted in a finally block within milliseconds; this only catches
     * crash-orphaned files. Call on collection-service startup.
     */
    public static void sweepStaleTempFiles(Context context) {
        try {
            File extDir = context.getApplicationContext().getExternalFilesDir(null);
            if (extDir == null) return;
            File[] files = extDir.listFiles((dir, name) ->
                    name.startsWith("tmp_") && (name.endsWith(".jpg") || name.endsWith(".log")));
            if (files == null) return;
            long cutoff = System.currentTimeMillis() - 5 * 60 * 1000L; // older than 5 minutes
            for (File f : files) {
                if (f.lastModified() < cutoff) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        } catch (Exception ignored) {
            // best-effort cleanup; never fail the caller
        }
    }

    static boolean queueTextForUpload(
            Context context,
            String content,
            String descriptor,
            String mime
    ) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        String pubKeyPem = prefs.getString("image_public_key", "");
        if (pubKeyPem == null || pubKeyPem.trim().isEmpty()) {
            Log.w(TAG, "No image_public_key available, skipping " + descriptor + " upload snapshot");
            return false;
        }

        File extDir = context.getApplicationContext().getExternalFilesDir(null);
        if (extDir == null) {
            Log.e(TAG, "getExternalFilesDir returned null, cannot queue " + descriptor + " upload snapshot");
            return false;
        }

        File encryptDir = new File(extDir, "encrypt");
        if (!encryptDir.exists() && !encryptDir.mkdirs()) {
            Log.e(TAG, "Failed to create encrypt directory for " + descriptor + " upload");
            return false;
        }

        String hashFull = prefs.getString("hash", "00000000");
        String hash = hashFull.substring(0, Math.min(8, hashFull.length()));
        String baseName = hash + "_" + System.currentTimeMillis() + "_" + descriptor;

        File tempLogFile = new File(extDir, "tmp_" + UUID.randomUUID() + ".log");
        try {
            try (FileWriter fw = new FileWriter(tempLogFile, false)) {
                fw.write(content);
            }

            File encFile = new File(encryptDir, baseName + ".enc");
            Encryptor.Result result = Encryptor.encryptFileToEnc(tempLogFile, encFile, pubKeyPem);

            JSONObject metaObj = new JSONObject();
            metaObj.put("aes_key_encrypted_b64", result.aesKeyEncB64);
            metaObj.put("tag_len_bits", result.tagLenBits);
            metaObj.put("mime", mime);
            metaObj.put("type", descriptor);
            metaObj.put("captured_at", utcIsoMillis().format(new Date()));
            metaObj.put("epoch_ms", System.currentTimeMillis());

            File metaFile = new File(encryptDir, baseName + ".meta");
            try (FileWriter fw = new FileWriter(metaFile, false)) {
                fw.write(metaObj.toString());
            }

            Log.i(TAG, "Queued encrypted " + descriptor + " snapshot: " + encFile.getName());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to queue " + descriptor + " for upload", e);
            return false;
        } finally {
            if (tempLogFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tempLogFile.delete();
            }
        }
    }

    /**
     * Binary sibling of {@link #queueTextForUpload}: encrypt a JPEG (e.g. an OMI
     * Glass photo received over BLE) into the same /encrypt upload queue used by
     * screenshots. Tagged meta type="image" so the Receiver ingests it like a
     * screenshot, plus source/orientation for provenance. The existing
     * UploadService/Batch pipeline carries it with no changes.
     *
     * @param descriptor  filename component (e.g. "glass")
     * @param source      provenance tag written to meta (e.g. "omi_glass")
     * @param orientation image orientation byte from the device, or -1 if unknown
     * @return true if an .enc + .meta pair was queued
     */
    public static boolean queueImageForUpload(
            Context context,
            byte[] jpeg,
            String descriptor,
            String source,
            int orientation
    ) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        String pubKeyPem = prefs.getString("image_public_key", "");
        if (pubKeyPem == null || pubKeyPem.trim().isEmpty()) {
            Log.w(TAG, "No image_public_key available, skipping " + descriptor + " image upload");
            return false;
        }
        if (jpeg == null || jpeg.length == 0) {
            Log.w(TAG, "Empty jpeg, skipping " + descriptor + " image upload");
            return false;
        }

        File extDir = context.getApplicationContext().getExternalFilesDir(null);
        if (extDir == null) {
            Log.e(TAG, "getExternalFilesDir returned null, cannot queue " + descriptor + " image");
            return false;
        }

        File encryptDir = new File(extDir, "encrypt");
        if (!encryptDir.exists() && !encryptDir.mkdirs()) {
            Log.e(TAG, "Failed to create encrypt directory for " + descriptor + " image");
            return false;
        }

        String hashFull = prefs.getString("hash", "00000000");
        String hash = hashFull.substring(0, Math.min(8, hashFull.length()));
        String baseName = hash + "_" + System.currentTimeMillis() + "_" + descriptor;

        File tempFile = new File(extDir, "tmp_" + UUID.randomUUID() + ".jpg");
        try {
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile, false)) {
                fos.write(jpeg);
            }

            File encFile = new File(encryptDir, baseName + ".enc");
            Encryptor.Result result = Encryptor.encryptFileToEnc(tempFile, encFile, pubKeyPem);

            JSONObject metaObj = new JSONObject();
            metaObj.put("aes_key_encrypted_b64", result.aesKeyEncB64);
            metaObj.put("tag_len_bits", result.tagLenBits);
            metaObj.put("mime", "image/jpeg");
            metaObj.put("type", "image");
            metaObj.put("captured_at", utcIsoMillis().format(new Date()));
            metaObj.put("epoch_ms", System.currentTimeMillis());
            if (source != null && !source.isEmpty()) {
                metaObj.put("source", source);
            }
            if (orientation >= 0) {
                metaObj.put("orientation", orientation);
            }

            File metaFile = new File(encryptDir, baseName + ".meta");
            try (FileWriter fw = new FileWriter(metaFile, false)) {
                fw.write(metaObj.toString());
            }

            Log.i(TAG, "Queued encrypted " + descriptor + " image: " + encFile.getName());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to queue " + descriptor + " image for upload", e);
            return false;
        } finally {
            if (tempFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
        }
    }

    static String captureOwnProcessLogcat() {
        String pidArg = "--pid=" + android.os.Process.myPid();
        List<String> primaryCmd = Arrays.asList("logcat", "-d", "-t", "800", pidArg, "*:V");
        String out = runCommand(primaryCmd, 4);
        if (out != null && !out.trim().isEmpty()) {
            return out;
        }

        // Fallback for devices where --pid isn't supported by logcat binary.
        List<String> fallbackCmd = Arrays.asList(
                "logcat", "-d", "-t", "600",
                "SCREENOMICS*:V",
                "A11yCaptureService:V",
                "GPS:V",
                "MainActivity:V",
                "UploadService:V",
                "UploadScheduler:V",
                "Batch:V",
                "AndroidRuntime:E",
                "*:S"
        );
        return runCommand(fallbackCmd, 4);
    }

    private static String runCommand(List<String> command, int timeoutSeconds) {
        StringBuilder output = new StringBuilder();
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "";
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            return output.toString();
        } catch (Exception e) {
            Log.w(TAG, "Failed to execute logcat command: " + command, e);
            return "";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /**
     * Read gps_buffer.jsonl and appusage_buffer.jsonl from disk, queue each
     * as a separate encrypted file (gps / appusage), then truncate on success.
     * Returns the total number of entries flushed, or 0 on failure / empty files.
     */
    public static int flushPersistedSensorBuffers(Context context) {
        File extDir = context.getApplicationContext().getExternalFilesDir(null);
        if (extDir == null) return 0;

        File gpsFile = new File(extDir, "gps_buffer.jsonl");
        File appUsageFile = new File(extDir, "appusage_buffer.jsonl");

        JSONArray gpsArray = readJsonlFile(gpsFile);
        JSONArray appUsageArray = readJsonlFile(appUsageFile);

        if (gpsArray.length() == 0 && appUsageArray.length() == 0) return 0;

        int total = 0;
        try {
            if (gpsArray.length() > 0) {
                if (queueTextForUpload(context, gpsArray.toString(), "gps", "application/json")) {
                    if (gpsFile.exists()) { try (FileWriter fw = new FileWriter(gpsFile, false)) { /* truncate */ } }
                    total += gpsArray.length();
                }
            }
            if (appUsageArray.length() > 0) {
                if (queueTextForUpload(context, appUsageArray.toString(), "appusage", "application/json")) {
                    if (appUsageFile.exists()) { try (FileWriter fw = new FileWriter(appUsageFile, false)) { /* truncate */ } }
                    total += appUsageArray.length();
                }
            }
            if (total > 0) {
                Log.i(TAG, "Flushed " + total + " persisted sensor entries from buffer files");
            }
            return total;
        } catch (Exception e) {
            Log.e(TAG, "Failed to flush persisted sensor buffers", e);
            return 0;
        }
    }

    private static JSONArray readJsonlFile(File file) {
        JSONArray array = new JSONArray();
        if (file == null || !file.exists() || file.length() == 0) return array;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    array.put(new JSONObject(line));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read JSONL file: " + file.getName(), e);
        }
        return array;
    }

    private static DateFormat utcIsoMillis() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf;
    }
}
