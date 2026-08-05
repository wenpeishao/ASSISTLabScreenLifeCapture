package com.screenomics;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;

/**
 * No-op stub for the standard (production) flavor.
 *
 * The on-device VLM/OCR research stack ships only in mindpulseDev; see that
 * source set for the real implementation.
 */
public class VlmBenchmark {

    public static final boolean AVAILABLE = false;

    public interface StatusListener {
        void onStatusUpdate(String status);
        void onMetricsUpdate(double lastMs, double avgMs, int total, int skipped, float batteryDrain);
    }

    public VlmBenchmark(Context context) { }

    public void setStatusListener(StatusListener listener) { }

    public void start(String modelPath, String mmprojPath, int nThreads) { }

    public void stop() { }

    public boolean isRunning() { return false; }

    public void submitFrame(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    public void submitFrame(Bitmap bitmap, String foregroundApp, double lat, double lon) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    public File getLogFile() { return null; }

    public static void runEmbeddingBenchmark(Context ctx, String modelPath, String mmprojPath,
                                             String imagePath, int threads, long durationMs,
                                             boolean useGpu) { }

    public static void runCaptionBenchmark(Context ctx, String modelPath, String mmprojPath,
                                           String imagePath, int threads, long durationMs,
                                           String prompt, int maxTokens, boolean think) { }
}
