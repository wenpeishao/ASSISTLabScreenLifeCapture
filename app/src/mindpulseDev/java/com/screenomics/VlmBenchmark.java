package com.screenomics;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages VLM benchmark lifecycle: model loading, async inference, and metrics logging.
 * Runs inference on a background thread so it never blocks the capture loop.
 */
public class VlmBenchmark {
    private static final String TAG = "VlmBenchmark";

    public static final boolean AVAILABLE = true;

    private final Context context;
    private HandlerThread workerThread;
    private Handler workerHandler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean modelLoaded = new AtomicBoolean(false);
    private final AtomicBoolean busy = new AtomicBoolean(false);

    // Metrics tracking
    private final AtomicInteger totalInferences = new AtomicInteger(0);
    private final AtomicInteger skippedFrames = new AtomicInteger(0);
    private volatile double lastInferenceMs = 0;
    private volatile double avgInferenceMs = 0;
    private volatile double sumInferenceMs = 0;
    private volatile float startBatteryPct = -1;
    private volatile long startTimeMs = 0;

    private PrintWriter csvWriter;
    private File logFile;
    private OcrProcessor ocrProcessor;
    private BehaviorContext behaviorContext;
    private Handler windowHandler;

    // Window duration: accumulate context for 60s, then run LLM once
    private static final long WINDOW_DURATION_MS = 60_000;

    // Max tokens to generate per window
    private static final int MAX_TOKENS = 128;

    public interface StatusListener {
        void onStatusUpdate(String status);
        void onMetricsUpdate(double lastMs, double avgMs, int total, int skipped, float batteryDrain);
    }

    private StatusListener statusListener;

    public VlmBenchmark(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setStatusListener(StatusListener listener) {
        this.statusListener = listener;
    }

    /**
     * Start the benchmark: load model and begin processing.
     * @param modelPath  path to GGUF model file on device
     * @param mmprojPath path to mmproj file (null if unified model)
     * @param nThreads   number of CPU threads
     */
    public void start(String modelPath, String mmprojPath, int nThreads) {
        if (running.get()) {
            Log.w(TAG, "Already running");
            return;
        }
        if (!VlmBridge.isAvailable()) {
            notifyStatus("FAILED: native VLM library unavailable on this device");
            return;
        }

        workerThread = new HandlerThread("VlmBenchmarkThread");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        running.set(true);
        startTimeMs = System.currentTimeMillis();
        startBatteryPct = getBatteryPct();
        totalInferences.set(0);
        skippedFrames.set(0);
        sumInferenceMs = 0;

        // Initialize OCR and behavior context
        ocrProcessor = new OcrProcessor();
        behaviorContext = new BehaviorContext(context, ocrProcessor);

        // Initialize CSV log
        initCsvLog();

        // Start window flush timer
        windowHandler = new Handler(workerThread.getLooper());
        scheduleWindowFlush();

        // Load model on worker thread (text-only for windowed mode)
        notifyStatus("Loading model (windowed OCR+LLM)...");
        workerHandler.post(() -> {
            boolean ok = VlmBridge.nativeLoadModel(modelPath, null, nThreads, 4096);
            modelLoaded.set(ok);
            if (ok) {
                double[] stats = VlmBridge.nativeGetStats();
                notifyStatus("Model loaded in " + (int)stats[0] + "ms. Ready.");
                logCsv("MODEL_LOAD", stats[0], 0, 0, 0, 0, getBatteryPct(), getTemperature());
            } else {
                notifyStatus("FAILED to load model");
                running.set(false);
            }
        });
    }

    /**
     * Submit a captured screenshot to the behavioral context buffer.
     * OCR runs immediately (~200ms), but LLM only runs when the window flushes (every 60s).
     */
    public void submitFrame(Bitmap bitmap) {
        submitFrame(bitmap, "", 0, 0);
    }

    public void submitFrame(Bitmap bitmap, String foregroundApp, double lat, double lon) {
        Handler h = workerHandler;
        if (!running.get() || !modelLoaded.get() || h == null) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            return;
        }

        // OCR must NOT run on the caller's thread: on the accessibility path the
        // caller is the main thread, where Tasks.await() throws (silently yielding
        // "" before this fix) and would risk ANR; on the capture path it would
        // block the capture loop. Post to the worker thread instead.
        boolean posted = h.post(() -> {
            BehaviorContext bc = behaviorContext;
            if (!running.get() || bc == null) {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                return;
            }
            bc.addFrame(bitmap, foregroundApp, lat, lon);
            int count = bc.getSnapshotCount();
            notifyStatus(String.format(Locale.US, "Collecting... %d snapshots in window", count));
        });
        if (!posted) {
            skippedFrames.incrementAndGet();
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    /** Schedule periodic window flush */
    private void scheduleWindowFlush() {
        if (!running.get() || windowHandler == null) return;
        windowHandler.postDelayed(this::flushWindow, WINDOW_DURATION_MS);
    }

    /** Flush the behavior window and run LLM inference */
    private void flushWindow() {
        if (!running.get() || !modelLoaded.get() || behaviorContext == null) return;

        String prompt = behaviorContext.flushAndBuildPrompt();
        if (prompt == null || prompt.isEmpty()) {
            scheduleWindowFlush();
            return;
        }

        Log.i(TAG, "=== WINDOW FLUSH === Prompt length: " + prompt.length() + " chars (~" + prompt.length()/4 + " tokens)");

        long t0 = System.currentTimeMillis();
        String output = VlmBridge.nativeInferText(prompt, MAX_TOKENS);
        long totalMs = System.currentTimeMillis() - t0;

        double[] stats = VlmBridge.nativeGetStats();
        double promptMs = stats[1];
        double decodeMs = stats[2];
        int nTokens = (int) stats[4];
        double tokPerSec = stats[5];

        int count = totalInferences.incrementAndGet();
        lastInferenceMs = totalMs;
        sumInferenceMs += totalMs;
        avgInferenceMs = sumInferenceMs / count;

        float batteryAfter = getBatteryPct();
        float batteryDrain = startBatteryPct - batteryAfter;

        logCsv("WINDOW", promptMs, decodeMs, totalMs, nTokens, tokPerSec,
               batteryAfter, getTemperature());

        // Log the full output (behavioral narrative)
        Log.i(TAG, String.format(Locale.US,
            "Window %d: prompt=%.0fms decode=%.0fms total=%dms tokens=%d (%.1f t/s) bat=%.1f%% temp=%.1f",
            count, promptMs, decodeMs, totalMs, nTokens, tokPerSec,
            batteryAfter, getTemperature()));
        // Do NOT log the narrative text itself: it describes the participant's
        // screen content, and this app uploads its own logcat as diagnostics.
        Log.i(TAG, "Narrative generated: " + output.trim().length() + " chars");

        notifyMetrics(lastInferenceMs, avgInferenceMs, count, skippedFrames.get(), batteryDrain);
        notifyStatus(String.format(Locale.US, "Window %d: %dms, %d tok (%.0f t/s)", count, totalMs, nTokens, tokPerSec));

        // Schedule next flush
        scheduleWindowFlush();
    }

    /** Stop benchmark and unload model. Teardown runs ON the worker thread so it
     *  serializes after any in-flight OCR/inference (no close/unload races), and
     *  the caller (often the main thread) is never blocked on a join. */
    public void stop() {
        running.set(false);
        if (windowHandler != null) {
            windowHandler.removeCallbacksAndMessages(null);
            windowHandler = null;
        }
        HandlerThread thread = workerThread;
        Handler h = workerHandler;
        workerThread = null;
        workerHandler = null;
        if (h != null) {
            h.post(() -> {
                behaviorContext = null;
                if (ocrProcessor != null) {
                    ocrProcessor.close();
                    ocrProcessor = null;
                }
                if (modelLoaded.getAndSet(false)) {
                    VlmBridge.nativeUnloadModel();
                }
                closeCsvLog();
            });
        }
        if (thread != null) {
            thread.quitSafely(); // processes the teardown post above, then exits
        }
        notifyStatus("Stopped. Total=" + totalInferences.get()
            + " skipped=" + skippedFrames.get()
            + " avg=" + (int) avgInferenceMs + "ms");
    }

    public boolean isRunning() { return running.get(); }
    public double getLastInferenceMs() { return lastInferenceMs; }
    public double getAvgInferenceMs() { return avgInferenceMs; }
    public int getTotalInferences() { return totalInferences.get(); }
    public int getSkippedFrames() { return skippedFrames.get(); }

    public File getLogFile() { return logFile; }

    // --- Battery & thermal helpers ---

    private float getBatteryPct() {
        try {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private float getTemperature() {
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = context.registerReceiver(null, ifilter);
            if (batteryStatus != null) {
                return batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0f;
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private long getUsedMemMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }

    // --- CSV logging ---

    private void initCsvLog() {
        try {
            File dir = new File(context.getExternalFilesDir(null), "vlm_benchmark");
            if (!dir.exists()) dir.mkdirs();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            logFile = new File(dir, "benchmark_" + ts + ".csv");
            csvWriter = new PrintWriter(new FileWriter(logFile, true));
            csvWriter.println("timestamp,event,encode_ms,decode_ms,total_ms,n_tokens,tok_per_sec,battery_pct,temp_c");
            csvWriter.flush();
            Log.i(TAG, "Benchmark log: " + logFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to create benchmark CSV", e);
        }
    }

    private void logCsv(String event, double encodeMs, double decodeMs, double totalMs,
                         int nTokens, double tokPerSec, float batteryPct, float tempC) {
        if (csvWriter == null) return;
        String ts = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(new Date());
        csvWriter.printf(Locale.US, "%s,%s,%.1f,%.1f,%.1f,%d,%.2f,%.1f,%.1f%n",
            ts, event, encodeMs, decodeMs, totalMs, nTokens, tokPerSec, batteryPct, tempC);
        csvWriter.flush();
    }

    private void closeCsvLog() {
        if (csvWriter != null) {
            float endBattery = getBatteryPct();
            long elapsedMin = (System.currentTimeMillis() - startTimeMs) / 60000;
            csvWriter.printf(Locale.US, "# Summary: total=%d skipped=%d avg_ms=%.1f battery_drain=%.1f%% elapsed_min=%d%n",
                totalInferences.get(), skippedFrames.get(), avgInferenceMs,
                startBatteryPct - endBattery, elapsedMin);
            csvWriter.close();
            csvWriter = null;
            Log.i(TAG, "Benchmark log closed: " + logFile.getAbsolutePath());
        }
    }

    // ============================================================================
    // Embedding-only benchmark: run the vision encoder (mmproj) on one image in a
    // sustained loop, with NO LLM decode, and log latency + power draw. Used to
    // answer "can the phone extract embeddings, and what does it cost in battery".
    // Triggered headlessly from DevToolsActivity via an intent extra.
    // ============================================================================
    public static void runEmbeddingBenchmark(final Context ctx, final String modelPath,
                                             final String mmprojPath, final String imagePath,
                                             final int threads, final long durationMs,
                                             final boolean useGpu) {
        if (!VlmBridge.isAvailable()) { Log.e(TAG, "EMBED_BENCH: native library unavailable"); return; }
        new Thread(() -> {
            try {
                Log.i(TAG, "EMBED_BENCH: model=" + modelPath + " mmproj=" + mmprojPath
                        + " image=" + imagePath + " threads=" + threads + " dur_ms=" + durationMs
                        + " use_gpu=" + useGpu);

                if (!new File(modelPath).exists() || !new File(mmprojPath).exists()
                        || !new File(imagePath).exists()) {
                    Log.e(TAG, "EMBED_BENCH: a required file is missing (model/mmproj/image)");
                    return;
                }

                VlmBridge.nativeSetUseGpu(useGpu);
                boolean ok = VlmBridge.nativeLoadModel(modelPath, mmprojPath, threads, 4096);
                if (!ok) { Log.e(TAG, "EMBED_BENCH: nativeLoadModel FAILED"); return; }
                double[] ls = VlmBridge.nativeGetStats();
                Log.i(TAG, "EMBED_BENCH: model+mmproj loaded in " + (int) ls[0] + "ms");

                byte[] jpeg = readAllBytes(imagePath);
                if (jpeg == null) { Log.e(TAG, "EMBED_BENCH: could not read image"); VlmBridge.nativeUnloadModel(); return; }
                Log.i(TAG, "EMBED_BENCH: image bytes=" + jpeg.length);

                // One validation encode that returns the embedding, to record shape/size.
                float[] embd = VlmBridge.nativeEncodeImageToEmbedding(jpeg, true);
                double[] s0 = VlmBridge.nativeGetStats();
                int nTok = (int) s0[4];
                int floats = embd == null ? 0 : embd.length;
                int nEmbd = nTok > 0 ? floats / nTok : 0;
                Log.i(TAG, "EMBED_BENCH: VALIDATE n_tokens=" + nTok + " n_embd=" + nEmbd
                        + " floats=" + floats + " bytes_fp32=" + ((long) floats * 4)
                        + " bytes_fp16=" + ((long) floats * 2) + " first_encode_ms=" + (int) s0[1]);
                if (floats == 0) { Log.e(TAG, "EMBED_BENCH: encode produced no embedding — aborting"); VlmBridge.nativeUnloadModel(); return; }

                // Dump the validation embedding so CPU vs GPU output can be compared numerically
                // (guards against silent GPU MUL_MAT corruption on Mali).
                try {
                    File embDir = new File(ctx.getExternalFilesDir(null), "vlm_benchmark");
                    if (!embDir.exists()) embDir.mkdirs();
                    File embFile = new File(embDir, "embd_" + (useGpu ? "gpu" : "cpu") + ".f32");
                    java.io.DataOutputStream dos = new java.io.DataOutputStream(
                            new java.io.BufferedOutputStream(new java.io.FileOutputStream(embFile)));
                    for (float v : embd) dos.writeFloat(v);
                    dos.close();
                    Log.i(TAG, "EMBED_BENCH: wrote embedding to " + embFile.getAbsolutePath());
                } catch (Exception e) { Log.e(TAG, "EMBED_BENCH: embedding dump failed", e); }

                File dir = new File(ctx.getExternalFilesDir(null), "vlm_benchmark");
                if (!dir.exists()) dir.mkdirs();
                File csv = new File(dir, "embed_bench_" + System.currentTimeMillis() + ".csv");
                PrintWriter w = new PrintWriter(new FileWriter(csv, true));
                w.println("iter,encode_ms,total_ms,battery_pct,charge_uah,current_ua,temp_c,elapsed_ms");

                BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);

                // Warmup (fills caches, lets clocks spin up) — not counted.
                for (int i = 0; i < 5; i++) VlmBridge.nativeEncodeImageToEmbedding(jpeg, false);

                long chargeStart = bm != null ? bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) : 0;
                float batStart = getBatteryPctStatic(bm);
                float tempStart = getTempStatic(ctx);
                long tStart = System.currentTimeMillis();
                Log.i(TAG, "EMBED_BENCH: LOOP START charge_uah=" + chargeStart + " bat=" + batStart + " temp=" + tempStart);

                double sumEnc = 0, minEnc = Double.MAX_VALUE, maxEnc = 0;
                int iter = 0;
                while (System.currentTimeMillis() - tStart < durationMs) {
                    VlmBridge.nativeEncodeImageToEmbedding(jpeg, false);
                    double[] s = VlmBridge.nativeGetStats();
                    double enc = s[1];
                    double tot = s[3];
                    sumEnc += enc;
                    if (enc < minEnc) minEnc = enc;
                    if (enc > maxEnc) maxEnc = enc;
                    if (iter % 10 == 0) {
                        long elapsed = System.currentTimeMillis() - tStart;
                        long chargeNow = bm != null ? bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) : 0;
                        int curNow = bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) : 0;
                        w.printf(Locale.US, "%d,%.1f,%.1f,%.1f,%d,%d,%.1f,%d%n",
                                iter, enc, tot, getBatteryPctStatic(bm), chargeNow, curNow, getTempStatic(ctx), elapsed);
                        w.flush();
                        Log.i(TAG, "EMBED_BENCH: iter=" + iter + " enc=" + (int) enc + "ms temp=" + getTempStatic(ctx));
                    }
                    iter++;
                }

                long elapsed = System.currentTimeMillis() - tStart;
                long chargeEnd = bm != null ? bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) : 0;
                float batEnd = getBatteryPctStatic(bm);
                float tempEnd = getTempStatic(ctx);
                double avgEnc = iter > 0 ? sumEnc / iter : 0;
                double encPerSec = elapsed > 0 ? iter * 1000.0 / elapsed : 0;

                w.printf(Locale.US,
                        "# DONE iters=%d elapsed_ms=%d avg_enc_ms=%.1f min_ms=%.1f max_ms=%.1f enc_per_sec=%.2f "
                                + "n_tokens=%d n_embd=%d embd_bytes_fp16=%d charge_start_uah=%d charge_end_uah=%d "
                                + "dcharge_uah=%d bat_start=%.1f bat_end=%.1f temp_start=%.1f temp_end=%.1f%n",
                        iter, elapsed, avgEnc, minEnc, maxEnc, encPerSec, nTok, nEmbd, (long) floats * 2,
                        chargeStart, chargeEnd, (chargeStart - chargeEnd), batStart, batEnd, tempStart, tempEnd);
                w.close();

                VlmBridge.nativeUnloadModel();

                Log.i(TAG, String.format(Locale.US,
                        "EMBED_BENCH_DONE iters=%d elapsed_ms=%d avg_enc_ms=%.1f min_ms=%.1f max_ms=%.1f enc_per_sec=%.2f "
                                + "n_tokens=%d n_embd=%d embd_bytes_fp16=%d dcharge_uAh=%d bat=%.1f->%.1f temp=%.1f->%.1f csv=%s",
                        iter, elapsed, avgEnc, minEnc, maxEnc, encPerSec, nTok, nEmbd, (long) floats * 2,
                        (chargeStart - chargeEnd), batStart, batEnd, tempStart, tempEnd, csv.getAbsolutePath()));
            } catch (Exception e) {
                Log.e(TAG, "EMBED_BENCH: exception", e);
            }
        }, "EmbedBench").start();
    }

    private static byte[] readAllBytes(String path) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(path);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "readAllBytes failed: " + path, e);
            return null;
        }
    }

    private static float getBatteryPctStatic(BatteryManager bm) {
        try { return bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1; }
        catch (Exception e) { return -1; }
    }

    private static float getTempStatic(Context ctx) {
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent bs = ctx.registerReceiver(null, ifilter);
            if (bs != null) return bs.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0f;
        } catch (Exception ignored) {}
        return -1;
    }

    // ============================================================================
    // Caption benchmark: run the FULL on-device VLM (vision encode + LLM decode) to
    // produce a short text description, and log latency/tokens-per-sec/energy + the
    // actual text. Tests the "small VLM on device -> short caption -> send text" idea.
    // ============================================================================
    public static void runCaptionBenchmark(final Context ctx, final String modelPath,
                                           final String mmprojPath, final String imagePath,
                                           final int threads, final long durationMs,
                                           final String prompt, final int maxTokens,
                                           final boolean think) {
        if (!VlmBridge.isAvailable()) { Log.e(TAG, "CAP_BENCH: native library unavailable"); return; }
        new Thread(() -> {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO); } catch (Throwable ignored) {}
            VlmBridge.nativeSetThinking(think);
            try {
                Log.i(TAG, "CAP_BENCH: model=" + modelPath + " mmproj=" + mmprojPath + " image=" + imagePath
                        + " threads=" + threads + " dur_ms=" + durationMs + " maxTok=" + maxTokens + " prompt=" + prompt);
                if (!new File(modelPath).exists() || !new File(mmprojPath).exists() || !new File(imagePath).exists()) {
                    Log.e(TAG, "CAP_BENCH: a required file is missing"); return;
                }
                VlmBridge.nativeSetUseGpu(false);
                boolean ok = VlmBridge.nativeLoadModel(modelPath, mmprojPath, threads, 4096);
                if (!ok) { Log.e(TAG, "CAP_BENCH: load FAILED"); return; }
                double[] ls = VlmBridge.nativeGetStats();
                Log.i(TAG, "CAP_BENCH: loaded in " + (int) ls[0] + "ms");

                byte[] jpeg = readAllBytes(imagePath);
                if (jpeg == null) { Log.e(TAG, "CAP_BENCH: image read failed"); VlmBridge.nativeUnloadModel(); return; }

                BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
                long chargeStart = bm != null ? bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) : 0;
                float tempStart = getTempStatic(ctx);
                long tStart = System.currentTimeMillis();

                int iter = 0;
                double sumEncode = 0, sumDecode = 0, sumTotal = 0, sumTokPerSec = 0;
                int sumTokens = 0;
                do {
                    long t0 = System.currentTimeMillis();
                    String caption = VlmBridge.nativeInferImage(jpeg, 0, 0, prompt, maxTokens);
                    long wall = System.currentTimeMillis() - t0;
                    double[] s = VlmBridge.nativeGetStats();
                    double encMs = s[1], decMs = s[2], totMs = s[3]; int nTok = (int) s[4]; double tps = s[5];
                    sumEncode += encMs; sumDecode += decMs; sumTotal += totMs; sumTokens += nTok; sumTokPerSec += tps;
                    // Caption text is screen-derived content; log only its length
                    // (logcat is uploaded as diagnostics).
                    Log.i(TAG, String.format(Locale.US,
                        "CAP_BENCH: iter=%d wall=%dms encode=%.0fms decode=%.0fms tokens=%d (%.1f tok/s) temp=%.1f | caption_chars=%d",
                        iter, wall, encMs, decMs, nTok, tps, getTempStatic(ctx), caption == null ? -1 : caption.trim().length()));
                    iter++;
                } while (System.currentTimeMillis() - tStart < durationMs);

                long elapsed = System.currentTimeMillis() - tStart;
                long chargeEnd = bm != null ? bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) : 0;
                float tempEnd = getTempStatic(ctx);
                VlmBridge.nativeUnloadModel();
                Log.i(TAG, String.format(Locale.US,
                    "CAP_BENCH_DONE iters=%d elapsed_ms=%d avg_encode=%.0fms avg_decode=%.0fms avg_total=%.0fms avg_tokens=%.1f avg_tok_per_sec=%.1f dcharge_uAh=%d mAh_per_cap=%.2f temp=%.1f->%.1f",
                    iter, elapsed, sumEncode/iter, sumDecode/iter, sumTotal/iter, (double)sumTokens/iter, sumTokPerSec/iter,
                    (chargeStart-chargeEnd), iter>0 ? (chargeStart-chargeEnd)/1000.0/iter : 0, tempStart, tempEnd));
            } catch (Exception e) { Log.e(TAG, "CAP_BENCH: exception", e); }
        }, "CapBench").start();
    }

    // --- Callbacks ---

    private void notifyStatus(String status) {
        Log.i(TAG, status);
        if (statusListener != null) {
            new Handler(context.getMainLooper()).post(() -> statusListener.onStatusUpdate(status));
        }
    }

    private void notifyMetrics(double lastMs, double avgMs, int total, int skipped, float batteryDrain) {
        if (statusListener != null) {
            new Handler(context.getMainLooper()).post(() ->
                statusListener.onMetricsUpdate(lastMs, avgMs, total, skipped, batteryDrain));
        }
    }
}
