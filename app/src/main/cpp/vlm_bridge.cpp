#include <jni.h>
#include <android/log.h>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define LOG_TAG "VLM_BRIDGE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global state — single model loaded at a time. All access is serialized by
// g_mutex: load/unload/inference can be called from different Java threads
// (worker thread restarts, headless benchmark threads) and must never overlap.
static std::mutex      g_mutex;
static llama_model   * g_model   = nullptr;
static llama_context * g_ctx     = nullptr;
static mtmd_context  * g_mtmd    = nullptr;
static llama_sampler * g_sampler = nullptr;
static bool            g_use_gpu = false;  // toggled via nativeSetUseGpu(); offloads the ViT (mtmd) to a GPU backend when a GPU backend is compiled in (GGML_VULKAN)
static bool            g_think   = false;  // toggled via nativeSetThinking(); when false, prefill an empty <think></think> so the model skips reasoning

// Timing stats
static double g_last_load_ms     = 0.0;
static double g_last_encode_ms   = 0.0;   // vision encoder
static double g_last_decode_ms   = 0.0;   // text generation
static double g_last_total_ms    = 0.0;
static int    g_last_n_tokens    = 0;
static int    g_last_n_embd      = 0;
static long   g_peak_mem_bytes   = 0;

static double get_time_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

// Free all model state. Caller must hold g_mutex.
static void unload_locked() {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_mtmd)    { mtmd_free(g_mtmd);             g_mtmd    = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);              g_ctx     = nullptr; }
    if (g_model)   { llama_model_free(g_model);      g_model   = nullptr; }
    llama_backend_free();
}

// NewStringUTF requires *modified* UTF-8 and aborts under CheckJNI on 4-byte
// sequences (emoji are common in screen-derived model output). Build the
// String from raw bytes + charset instead.
static jstring make_jstring_utf8(JNIEnv *env, const std::string &s) {
    jbyteArray bytes = env->NewByteArray((jsize)s.size());
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, (jsize)s.size(), (const jbyte *)s.data());
    jclass cls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    jstring out = (jstring)env->NewObject(cls, ctor, bytes, charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(cls);
    return out;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_screenomics_VlmBridge_nativeLoadModel(
    JNIEnv *env, jclass clazz,
    jstring model_path_j, jstring mmproj_path_j,
    jint n_threads, jint n_ctx)
{
    std::lock_guard<std::mutex> lock(g_mutex);

    const char *model_path  = model_path_j ? env->GetStringUTFChars(model_path_j, nullptr) : nullptr;
    if (!model_path) { LOGE("nativeLoadModel: null model path"); return JNI_FALSE; }
    const char *mmproj_path = mmproj_path_j ? env->GetStringUTFChars(mmproj_path_j, nullptr) : nullptr;

    LOGI("Loading model: %s", model_path);
    LOGI("mmproj: %s", mmproj_path ? mmproj_path : "(unified/none)");
    LOGI("threads=%d ctx=%d", n_threads, n_ctx);

    // A second load without an unload must not leak the previous model
    // (hundreds of MB of native memory).
    if (g_model || g_ctx || g_mtmd || g_sampler) {
        LOGI("Model already loaded; unloading previous instance first");
        unload_locked();
    }

    double t0 = get_time_ms();

    // Initialize llama backend
    llama_backend_init();

    // Load model
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU only on Android
    g_model = llama_model_load_from_file(model_path, model_params);
    if (!g_model) {
        LOGE("Failed to load model from %s", model_path);
        env->ReleaseStringUTFChars(model_path_j, model_path);
        if (mmproj_path) env->ReleaseStringUTFChars(mmproj_path_j, mmproj_path);
        return JNI_FALSE;
    }

    // Create context
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx     = n_ctx > 0 ? n_ctx : 2048;
    ctx_params.n_threads = n_threads > 0 ? n_threads : 4;
    // n_batch must cover the largest single prompt submission: nativeInferText
    // decodes the whole prompt in one llama_batch, so a batch smaller than
    // n_ctx makes prompts over that size fail outright.
    ctx_params.n_batch   = ctx_params.n_ctx;
    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("Failed to create llama context");
        llama_model_free(g_model);
        g_model = nullptr;
        env->ReleaseStringUTFChars(model_path_j, model_path);
        if (mmproj_path) env->ReleaseStringUTFChars(mmproj_path_j, mmproj_path);
        return JNI_FALSE;
    }

    // Initialize multimodal context (for vision)
    if (mmproj_path) {
        mtmd_context_params mtmd_params = mtmd_context_params_default();
        mtmd_params.use_gpu = g_use_gpu;   // ViT on GPU when true + a GPU backend is compiled in
        mtmd_params.n_threads = n_threads > 0 ? n_threads : 4;
        mtmd_params.warmup = false; // skip warmup to speed up load
        g_mtmd = mtmd_init_from_file(mmproj_path, g_model, mtmd_params);
        if (!g_mtmd) {
            LOGE("Failed to create mtmd context from %s", mmproj_path);
            // Continue without multimodal — text-only mode
        } else {
            LOGI("Multimodal context created, vision=%d", mtmd_support_vision(g_mtmd));
        }
    }

    // Create sampler (greedy for benchmark — we just want any output)
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_sampler, llama_sampler_init_greedy());

    double t1 = get_time_ms();
    g_last_load_ms = t1 - t0;
    LOGI("Model loaded in %.1f ms", g_last_load_ms);

    env->ReleaseStringUTFChars(model_path_j, model_path);
    if (mmproj_path) env->ReleaseStringUTFChars(mmproj_path_j, mmproj_path);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_screenomics_VlmBridge_nativeInferImage(
    JNIEnv *env, jclass clazz,
    jbyteArray image_data_j, jint width, jint height,
    jstring prompt_j, jint max_tokens)
{
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_model || !g_ctx) {
        LOGE("Model not loaded");
        return env->NewStringUTF("");
    }

    double t_start = get_time_ms();

    const char *prompt_str = prompt_j ? env->GetStringUTFChars(prompt_j, nullptr) : nullptr;
    if (!prompt_str) { LOGE("nativeInferImage: null prompt"); return env->NewStringUTF(""); }
    jbyte *image_bytes = image_data_j ? env->GetByteArrayElements(image_data_j, nullptr) : nullptr;
    jsize image_len = image_data_j ? env->GetArrayLength(image_data_j) : 0;

    std::string result_text;
    double t_encode = 0, t_decode = 0;

    // Clear KV cache for fresh inference
    llama_memory_clear(llama_get_memory(g_ctx), true);

    if (g_mtmd && image_bytes && image_len > 0) {
        // --- Multimodal path: image + text ---
        double t0 = get_time_ms();

        // Create bitmap from raw RGB data (width * height * 3 bytes)
        // If image_data is JPEG/PNG bytes, use helper instead
        mtmd_bitmap *bitmap = mtmd_helper_bitmap_init_from_buf(
            g_mtmd, (const unsigned char *)image_bytes, image_len);

        if (!bitmap) {
            LOGE("Failed to create bitmap from image data (%d bytes)", image_len);
            env->ReleaseByteArrayElements(image_data_j, image_bytes, JNI_ABORT);
            env->ReleaseStringUTFChars(prompt_j, prompt_str);
            return env->NewStringUTF("");
        }

        // Build prompt with the Qwen-VL chat template (marker = where the image goes).
        // Without the chat template the instruct model emits EOS immediately (0 tokens).
        // When g_think is false, prefill an empty <think></think> so the (Qwen3) model
        // skips reasoning and answers directly (saves decode tokens/time). When true, let
        // it reason first (may help on hard/low-quality frames, at higher token cost).
        std::string assistant_prefix = g_think
            ? "<|im_start|>assistant\n"
            : "<|im_start|>assistant\n<think>\n\n</think>\n\n";
        std::string full_prompt =
            "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n"
            "<|im_start|>user\n" + std::string(mtmd_default_marker()) + prompt_str + "<|im_end|>\n"
            + assistant_prefix;

        // Tokenize text + image
        mtmd_input_chunks *chunks = mtmd_input_chunks_init();
        mtmd_input_text input_text;
        input_text.text = full_prompt.c_str();
        input_text.add_special = true;
        input_text.parse_special = true;

        const mtmd_bitmap *bitmap_ptr = bitmap;
        int32_t tok_result = mtmd_tokenize(g_mtmd, chunks, &input_text, &bitmap_ptr, 1);
        if (tok_result != 0) {
            LOGE("mtmd_tokenize failed: %d", tok_result);
            mtmd_bitmap_free(bitmap);
            mtmd_input_chunks_free(chunks);
            env->ReleaseByteArrayElements(image_data_j, image_bytes, JNI_ABORT);
            env->ReleaseStringUTFChars(prompt_j, prompt_str);
            return env->NewStringUTF("");
        }

        double t1 = get_time_ms();

        // Eval all chunks (vision encode + text tokens)
        llama_pos n_past = 0;
        int32_t eval_result = mtmd_helper_eval_chunks(
            g_mtmd, g_ctx, chunks, n_past, 0, 512, true, &n_past);

        double t2 = get_time_ms();
        t_encode = t2 - t0;

        mtmd_bitmap_free(bitmap);
        mtmd_input_chunks_free(chunks);

        if (eval_result != 0) {
            LOGE("mtmd_helper_eval_chunks failed: %d", eval_result);
            env->ReleaseByteArrayElements(image_data_j, image_bytes, JNI_ABORT);
            env->ReleaseStringUTFChars(prompt_j, prompt_str);
            return env->NewStringUTF("");
        }

        // Generate text tokens
        double t_dec_start = get_time_ms();
        int n_generated = 0;
        const llama_vocab *vocab = llama_model_get_vocab(g_model);

        for (int i = 0; i < max_tokens; i++) {
            llama_token token = llama_sampler_sample(g_sampler, g_ctx, -1);

            if (llama_vocab_is_eog(vocab, token)) break;

            char buf[256];
            int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
            if (n > 0) {
                result_text.append(buf, n);
            }
            n_generated++;

            // Prepare next decode
            llama_batch batch = llama_batch_get_one(&token, 1);
            if (llama_decode(g_ctx, batch) != 0) {
                LOGE("llama_decode failed at token %d", i);
                break;
            }
        }
        t_decode = get_time_ms() - t_dec_start;
        g_last_n_tokens = n_generated;

    } else {
        // --- Text-only path (fallback if no mmproj) ---
        double t0 = get_time_ms();

        const llama_vocab *vocab = llama_model_get_vocab(g_model);
        std::string full_prompt = std::string(prompt_str);

        // Tokenize
        std::vector<llama_token> tokens(full_prompt.size() + 64);
        int n_tokens = llama_tokenize(vocab, full_prompt.c_str(), full_prompt.size(),
                                      tokens.data(), tokens.size(), true, true);
        if (n_tokens < 0) {
            tokens.resize(-n_tokens);
            n_tokens = llama_tokenize(vocab, full_prompt.c_str(), full_prompt.size(),
                                      tokens.data(), tokens.size(), true, true);
        }
        tokens.resize(n_tokens);

        // Eval prompt
        llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("llama_decode (prompt) failed");
            if (image_bytes) env->ReleaseByteArrayElements(image_data_j, image_bytes, JNI_ABORT);
            env->ReleaseStringUTFChars(prompt_j, prompt_str);
            return env->NewStringUTF("");
        }

        t_encode = get_time_ms() - t0;

        // Generate
        double t_dec_start = get_time_ms();
        int n_generated = 0;
        for (int i = 0; i < max_tokens; i++) {
            llama_token token = llama_sampler_sample(g_sampler, g_ctx, -1);
            if (llama_vocab_is_eog(vocab, token)) break;

            char buf[256];
            int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
            if (n > 0) result_text.append(buf, n);
            n_generated++;

            llama_batch next_batch = llama_batch_get_one(&token, 1);
            if (llama_decode(g_ctx, next_batch) != 0) break;
        }
        t_decode = get_time_ms() - t_dec_start;
        g_last_n_tokens = n_generated;
    }

    double t_total = get_time_ms() - t_start;

    g_last_encode_ms = t_encode;
    g_last_decode_ms = t_decode;
    g_last_total_ms  = t_total;

    LOGI("Inference done: encode=%.0fms decode=%.0fms total=%.0fms tokens=%d (%.1f tok/s)",
         t_encode, t_decode, t_total, g_last_n_tokens,
         g_last_n_tokens > 0 ? g_last_n_tokens / (t_decode / 1000.0) : 0);

    if (image_bytes) env->ReleaseByteArrayElements(image_data_j, image_bytes, JNI_ABORT);
    env->ReleaseStringUTFChars(prompt_j, prompt_str);

    return make_jstring_utf8(env, result_text);
}

// Vision-encoder-only path: runs the image through the mmproj (ViT + projector)
// and returns the projected embedding tokens WITHOUT any LLM decode. This is the
// "extract embedding on device" cost the caller cares about. If return_embd is
// false, we skip the (potentially multi-MB) float copy and only record timing —
// used for the sustained power benchmark loop.
JNIEXPORT jfloatArray JNICALL
Java_com_screenomics_VlmBridge_nativeEncodeImageToEmbedding(
    JNIEnv *env, jclass clazz, jbyteArray image_data_j, jboolean return_embd)
{
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_mtmd || !g_model) {
        LOGE("nativeEncodeImageToEmbedding: mtmd/model not loaded");
        return nullptr;
    }
    if (!image_data_j) { LOGE("nativeEncodeImageToEmbedding: null image"); return nullptr; }

    jbyte *image_bytes = env->GetByteArrayElements(image_data_j, nullptr);
    if (!image_bytes) { LOGE("nativeEncodeImageToEmbedding: pin failed"); return nullptr; }
    jsize image_len = env->GetArrayLength(image_data_j);

    double t0 = get_time_ms();

    mtmd_bitmap *bitmap = mtmd_helper_bitmap_init_from_buf(
        g_mtmd, (const unsigned char *)image_bytes, image_len);
    if (!bitmap) {
        LOGE("nativeEncodeImageToEmbedding: bitmap init failed (%d bytes)", image_len);
        env->ReleaseByteArrayElements(image_data_j, image_bytes, JNI_ABORT);
        return nullptr;
    }

    std::string marker = std::string(mtmd_default_marker());
    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    mtmd_input_text input_text;
    input_text.text = marker.c_str();
    input_text.add_special = false;
    input_text.parse_special = true;

    const mtmd_bitmap *bp = bitmap;
    int32_t tr = mtmd_tokenize(g_mtmd, chunks, &input_text, &bp, 1);
    if (tr != 0) {
        LOGE("nativeEncodeImageToEmbedding: mtmd_tokenize failed: %d", tr);
        mtmd_bitmap_free(bitmap);
        mtmd_input_chunks_free(chunks);
        env->ReleaseByteArrayElements(image_data_j, image_bytes, JNI_ABORT);
        return nullptr;
    }

    double t_enc0 = get_time_ms();   // after jpeg-decode + preprocess + tokenize
    jfloatArray result = nullptr;
    size_t n_chunks = mtmd_input_chunks_size(chunks);
    for (size_t i = 0; i < n_chunks; i++) {
        const mtmd_input_chunk *ch = mtmd_input_chunks_get(chunks, i);
        if (mtmd_input_chunk_get_type(ch) != MTMD_INPUT_CHUNK_TYPE_IMAGE) continue;

        int32_t enc = mtmd_encode_chunk(g_mtmd, ch);   // <-- the ViT forward pass
        if (enc != 0) { LOGE("mtmd_encode_chunk failed: %d", enc); continue; }

        size_t n_tokens = mtmd_input_chunk_get_n_tokens(ch);
        int n_embd = llama_model_n_embd_inp(g_model);
        g_last_n_tokens = (int)n_tokens;
        g_last_n_embd   = n_embd;

        if (return_embd) {
            float *embd = mtmd_get_output_embd(g_mtmd);
            size_t total = n_tokens * (size_t)n_embd;
            if (result) env->DeleteLocalRef(result); // multi-chunk: drop the previous array
            result = env->NewFloatArray((jsize)total);
            if (!result) {
                // allocation failed (pending OutOfMemoryError) — clear it and
                // return null instead of continuing with an exception pending
                env->ExceptionClear();
                LOGE("nativeEncodeImageToEmbedding: NewFloatArray(%zu) failed", total);
                continue;
            }
            if (embd) env->SetFloatArrayRegion(result, 0, (jsize)total, embd);
        }
    }
    double t1 = get_time_ms();
    g_last_encode_ms = t1 - t_enc0;   // pure vision-encode (ViT+projector) time
    g_last_total_ms  = t1 - t0;       // incl. jpeg decode + preprocess + tokenize

    mtmd_bitmap_free(bitmap);
    mtmd_input_chunks_free(chunks);
    env->ReleaseByteArrayElements(image_data_j, image_bytes, JNI_ABORT);

    LOGI("encode-only: n_tokens=%d n_embd=%d encode=%.1fms total(incl.preproc)=%.1fms",
         g_last_n_tokens, g_last_n_embd, g_last_encode_ms, g_last_total_ms);
    return result;
}

JNIEXPORT jdoubleArray JNICALL
Java_com_screenomics_VlmBridge_nativeGetStats(JNIEnv *env, jclass clazz) {
    // Returns: [load_ms, encode_ms, decode_ms, total_ms, n_tokens, tok_per_sec]
    jdoubleArray arr = env->NewDoubleArray(6);
    double vals[6] = {
        g_last_load_ms,
        g_last_encode_ms,
        g_last_decode_ms,
        g_last_total_ms,
        (double)g_last_n_tokens,
        g_last_n_tokens > 0 ? g_last_n_tokens / (g_last_decode_ms / 1000.0) : 0
    };
    env->SetDoubleArrayRegion(arr, 0, 6, vals);
    return arr;
}

JNIEXPORT void JNICALL
Java_com_screenomics_VlmBridge_nativeUnloadModel(JNIEnv *env, jclass clazz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    LOGI("Unloading model...");
    unload_locked();
    LOGI("Model unloaded");
}

JNIEXPORT jboolean JNICALL
Java_com_screenomics_VlmBridge_nativeIsLoaded(JNIEnv *env, jclass clazz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return (g_model != nullptr && g_ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_screenomics_VlmBridge_nativeSetUseGpu(JNIEnv *env, jclass clazz, jboolean use_gpu) {
    g_use_gpu = (use_gpu == JNI_TRUE);
    LOGI("nativeSetUseGpu: %d", (int)g_use_gpu);
}

JNIEXPORT void JNICALL
Java_com_screenomics_VlmBridge_nativeSetThinking(JNIEnv *env, jclass clazz, jboolean think) {
    g_think = (think == JNI_TRUE);
    LOGI("nativeSetThinking: %d", (int)g_think);
}

JNIEXPORT jstring JNICALL
Java_com_screenomics_VlmBridge_nativeInferText(
    JNIEnv *env, jclass clazz,
    jstring prompt_j, jint max_tokens)
{
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_model || !g_ctx) {
        LOGE("Model not loaded");
        return env->NewStringUTF("");
    }

    double t_start = get_time_ms();
    const char *prompt_str = prompt_j ? env->GetStringUTFChars(prompt_j, nullptr) : nullptr;
    if (!prompt_str) { LOGE("nativeInferText: null prompt"); return env->NewStringUTF(""); }

    // Clear KV cache
    llama_memory_clear(llama_get_memory(g_ctx), true);

    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    std::string full_prompt(prompt_str);

    // Tokenize
    std::vector<llama_token> tokens(full_prompt.size() + 128);
    int n_tokens = llama_tokenize(vocab, full_prompt.c_str(), full_prompt.size(),
                                  tokens.data(), tokens.size(), true, true);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(vocab, full_prompt.c_str(), full_prompt.size(),
                                  tokens.data(), tokens.size(), true, true);
    }
    tokens.resize(n_tokens);

    double t_tok = get_time_ms();
    LOGI("Tokenized %d tokens in %.0fms", n_tokens, t_tok - t_start);

    // Eval prompt
    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("llama_decode (prompt) failed");
        env->ReleaseStringUTFChars(prompt_j, prompt_str);
        return env->NewStringUTF("");
    }

    double t_prompt = get_time_ms();
    g_last_encode_ms = t_prompt - t_start;

    // Generate
    std::string result_text;
    int n_generated = 0;
    for (int i = 0; i < max_tokens; i++) {
        llama_token token = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;

        char buf[256];
        int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
        if (n > 0) result_text.append(buf, n);
        n_generated++;

        llama_batch next_batch = llama_batch_get_one(&token, 1);
        if (llama_decode(g_ctx, next_batch) != 0) break;
    }

    double t_end = get_time_ms();
    g_last_decode_ms = t_end - t_prompt;
    g_last_total_ms = t_end - t_start;
    g_last_n_tokens = n_generated;

    LOGI("Text inference done: prompt=%.0fms decode=%.0fms total=%.0fms tokens=%d (%.1f tok/s)",
         g_last_encode_ms, g_last_decode_ms, g_last_total_ms, n_generated,
         n_generated > 0 ? n_generated / (g_last_decode_ms / 1000.0) : 0);

    env->ReleaseStringUTFChars(prompt_j, prompt_str);
    return make_jstring_utf8(env, result_text);
}

} // extern "C"
