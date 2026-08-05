package com.screenomics;

/**
 * JNI bridge to llama.cpp for on-device VLM inference.
 * All methods are static — single model loaded at a time.
 */
public class VlmBridge {

    private static final boolean LIBRARY_LOADED;

    static {
        boolean ok = false;
        try {
            System.loadLibrary("vlm_bridge");
            ok = true;
        } catch (UnsatisfiedLinkError e) {
            // Missing .so (wrong ABI / emulator) must degrade, not kill the process.
            android.util.Log.e("VlmBridge", "vlm_bridge native library unavailable", e);
        }
        LIBRARY_LOADED = ok;
    }

    /** True when the native library loaded; all native methods require this. */
    public static boolean isAvailable() {
        return LIBRARY_LOADED;
    }

    /** Load model + optional mmproj for vision. Returns true on success. */
    public static native boolean nativeLoadModel(String modelPath, String mmprojPath, int nThreads, int nCtx);

    /**
     * Run inference on a JPEG/PNG image.
     * @param imageData  raw JPEG or PNG bytes
     * @param width      image width (informational)
     * @param height     image height (informational)
     * @param prompt     text prompt for the model
     * @param maxTokens  max tokens to generate
     * @return generated text
     */
    public static native String nativeInferImage(byte[] imageData, int width, int height, String prompt, int maxTokens);

    /**
     * Run the vision encoder ONLY (mmproj ViT + projector) and return the projected
     * embedding tokens, with NO LLM decode. This isolates the "extract embedding on
     * device" cost. Requires a model loaded WITH an mmproj.
     * @param imageData   raw JPEG/PNG bytes
     * @param returnEmbd  if true, return the float[] (n_tokens * n_embd); if false,
     *                    run the encode but skip the copy and return null (for the
     *                    sustained power benchmark loop). Timing is recorded either way
     *                    (nativeGetStats()[1] = encode_ms, [3] = total_ms, [4] = n_tokens).
     * @return float[n_tokens * n_embd] projected embeddings, or null.
     */
    public static native float[] nativeEncodeImageToEmbedding(byte[] imageData, boolean returnEmbd);

    /**
     * Get timing stats from last inference.
     * @return double[6]: {load_ms, encode_ms, decode_ms, total_ms, n_tokens, tok_per_sec}
     */
    public static native double[] nativeGetStats();

    /** Unload model and free all resources. */
    public static native void nativeUnloadModel();

    /**
     * Run text-only inference (no vision encoder).
     * @param prompt  full text prompt including OCR text
     * @param maxTokens  max tokens to generate
     * @return generated text
     */
    public static native String nativeInferText(String prompt, int maxTokens);

    /** Check if a model is currently loaded. */
    public static native boolean nativeIsLoaded();

    /**
     * Toggle GPU offload of the vision encoder (mtmd/clip ViT). Must be called BEFORE
     * nativeLoadModel(). Only has effect if a GPU backend (Vulkan) was compiled in;
     * otherwise the load silently stays on CPU.
     */
    public static native void nativeSetUseGpu(boolean useGpu);

    /** Enable/disable the model's thinking phase (default off). Call before nativeInferImage. */
    public static native void nativeSetThinking(boolean think);
}
