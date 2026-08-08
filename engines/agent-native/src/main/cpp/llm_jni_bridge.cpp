#include <jni.h>
#include <android/log.h>
#include <string>
#include <sstream>
#include <mutex>
#include <streambuf>
#include <dlfcn.h>
#include <chrono>
#include <vector>
#include <signal.h>
#include <setjmp.h>
#include <android/bitmap.h>
#include <future>
#include <thread>
#include <cstdint>

#include <MNN/llm/llm.hpp>

#define LOG_TAG "PoLang:LlmJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

static std::mutex g_llm_mutex;

// ════════════════════════════════════════════════════════════
// SIGSEGV 崩溃容错保护
// ════════════════════════════════════════════════════════════
// MNN-LLM 的 response(multimodal) 在多模态推理时可能触发
// SIGSEGV（libMNN.so 内部 generate() 中的内存越界）。
// 此机制捕获崩溃并返回错误，防止进程被彻底杀死。

static thread_local sigjmp_buf g_mnn_jmp_buf;
static thread_local bool g_mnn_jmp_active = false;

/** SIGSEGV 信号处理函数 */
static void mnn_sigsegv_handler(int /*sig*/) {
    if (g_mnn_jmp_active) {
        siglongjmp(g_mnn_jmp_buf, 1);
    }
}

/**
 * 安装 MNN 崩溃容错的信号处理器。
 * 与 [MnnCrashGuard] 配对使用。
 */
static void installCrashGuard(struct sigaction* old_action) {
    struct sigaction crash_action;
    memset(&crash_action, 0, sizeof(crash_action));
    crash_action.sa_handler = mnn_sigsegv_handler;
    sigemptyset(&crash_action.sa_mask);
    crash_action.sa_flags = 0;
    sigaction(SIGSEGV, &crash_action, old_action);
    g_mnn_jmp_active = true;
}

/**
 * 卸载 MNN 崩溃容错的信号处理器。
 * 与 [installCrashGuard] 配对使用。
 */
static void uninstallCrashGuard(const struct sigaction* old_action) {
    g_mnn_jmp_active = false;
    sigaction(SIGSEGV, old_action, nullptr);
}

// ── 常数定义 ──────────────────────────────────────────

// 图像推理时最大允许的边长（像素）
// 超过此值应提前缩放，防止 OOM / SIGSEGV
// 安全闸门：拒绝异常大的图防 OOM。真正按模型 image_size 的缩放在 Kotlin preprocessBitmap
// （读 llm_config.image_size：Qwen=420 / SmolVLM=512），这里取 2048 仅兜底。
static constexpr int MAX_IMAGE_DIM = 2048;

// ── 辅助函数 ──────────────────────────────────────────

/**
 * 创建一个包含错误信息的 HashMap（JNI）
 * 用于替代重复的 15+ 行 error hashmap 创建代码
 */
static jobject createErrorHashMap(JNIEnv* env, const char* errorMsg) {
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
    jobject hashMap = env->NewObject(hashMapClass, hashMapInit);
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                           "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("error"),
                          env->NewStringUTF(errorMsg));
    return hashMap;
}

/**
 * 将字节串清理为 JNI NewStringUTF 可接受的 Modified UTF-8。
 *
 * 部分端侧模型（如 SmolVLM-256M）在特定输入下会输出含 0x00 或非法 UTF-8
 * 续字节的字节流，直接调用 NewStringUTF 会触发 JNI 致命错误：
 *   "JNI DETECTED ERROR IN APPLICATION: input is not valid Modified UTF-8"
 *
 * 该函数把非法字节替换为 '?'，保留合法 UTF-8 内容，避免崩溃。
 */
static std::string sanitizeModifiedUtf8(const std::string& input) {
    std::string output;
    output.reserve(input.size());
    for (size_t i = 0; i < input.size(); ) {
        unsigned char c = static_cast<unsigned char>(input[i]);
        // 0x00 在 Modified UTF-8 中非法
        if (c == 0x00) {
            output.push_back('?');
            ++i;
            continue;
        }
        // ASCII
        if (c < 0x80) {
            output.push_back(static_cast<char>(c));
            ++i;
            continue;
        }
        // 多字节 UTF-8 序列长度与初始码点
        size_t seqLen = 0;
        uint32_t codePoint = 0;
        if ((c & 0xE0) == 0xC0) { seqLen = 2; codePoint = c & 0x1F; }
        else if ((c & 0xF0) == 0xE0) { seqLen = 3; codePoint = c & 0x0F; }
        else if ((c & 0xF8) == 0xF0) { seqLen = 4; codePoint = c & 0x07; }
        else { output.push_back('?'); ++i; continue; }

        bool valid = true;
        for (size_t j = 1; j < seqLen && (i + j) < input.size(); ++j) {
            unsigned char cc = static_cast<unsigned char>(input[i + j]);
            if ((cc & 0xC0) != 0x80) { valid = false; break; }
            codePoint = (codePoint << 6) | (cc & 0x3F);
        }
        if (!valid || (i + seqLen) > input.size()) {
            output.push_back('?');
            ++i;
            continue;
        }
        // 码点范围校验，防止 overlong / 超出 Unicode
        if (seqLen == 2 && codePoint < 0x80) valid = false;
        if (seqLen == 3 && codePoint < 0x800) valid = false;
        if (seqLen == 4 && (codePoint < 0x10000 || codePoint > 0x10FFFF)) valid = false;
        if (!valid) {
            output.push_back('?');
            ++i;
            continue;
        }
        output.append(input, i, seqLen);
        i += seqLen;
    }
    return output;
}

// ── 性能指标结构 ──────────────────────────────────────
struct LlmMetrics {
    int64_t prompt_len = 0;
    int64_t decode_len = 0;
    int64_t vision_time = 0;
    int64_t audio_time = 0;
    int64_t prefill_time = 0;
    int64_t decode_time = 0;
};

/**
 * 多模态图片推理结果（C++ 侧，用于 timeout 变体跨线程传递）
 */
struct ImageInferenceResult {
    std::string response;
    LlmMetrics metrics;
    std::string error;
    bool crashed = false;
};

// 前向声明：在 doLockedImageInference 之前定义，避免 C++ 未声明标识符错误
static void restoreLlmStatusIfNeeded(MNN::Transformer::Llm* llm);

/**
 * 在持有 g_llm_mutex 的情况下执行多模态图片推理。
 * 此函数可在线程中调用，但不涉及 JNIEnv，因此线程安全。
 */
static ImageInferenceResult doLockedImageInference(
        MNN::Transformer::Llm *llm,
        MNN::Transformer::MultimodalPrompt multimodal,
        int maxNewTokens,
        int width,
        int height,
        size_t systemLen,
        size_t userLen) {
    ImageInferenceResult result;
    std::ostringstream oss;
    auto start = std::chrono::high_resolution_clock::now();
    bool mnn_crashed = false;

    g_llm_mutex.lock();
    restoreLlmStatusIfNeeded(llm);

    struct sigaction old_action;
    if (sigsetjmp(g_mnn_jmp_buf, 1) == 0) {
        installCrashGuard(&old_action);

        // 阶段 1: Prefill（max_new_tokens=0 + "<eop>" 结束标记）
        llm->response(multimodal, &oss, "<eop>", 0);

        // 阶段 2: Decode loop（逐 token 生成）
        for (int gen_i = 0; gen_i < maxNewTokens; gen_i++) {
            if (llm->stoped()) break;
            llm->generate(1);
        }

        uninstallCrashGuard(&old_action);
        g_llm_mutex.unlock();
    } else {
        uninstallCrashGuard(&old_action);
        g_llm_mutex.unlock();
        mnn_crashed = true;
        LOGE("[CRASH] ═══════════════════════════════════════════");
        LOGE("[CRASH] SIGSEGV in llm->response(multimodal) / generate()!");
        LOGE("[CRASH] Image: %dx%d, prompt: system=%zu, user=%zu",
             width, height, systemLen, userLen);
        LOGE("[CRASH] ═══════════════════════════════════════════");
    }

    if (mnn_crashed) {
        result.crashed = true;
        result.error = "MNN LLM crashed: SIGSEGV in multimodal inference";
        return result;
    }

    auto end = std::chrono::high_resolution_clock::now();
    result.metrics.decode_time = std::chrono::duration_cast<std::chrono::microseconds>(
            end - start).count();

    auto* ctx = llm->getContext();
    if (ctx != nullptr) {
        result.metrics.prompt_len = ctx->prompt_len;
        result.metrics.decode_len = ctx->gen_seq_len;
        result.metrics.vision_time = ctx->vision_us;
        result.metrics.audio_time = ctx->audio_us;
    }

    std::string rawResponse = oss.str();
    size_t eopPos = rawResponse.find("<eop>");
    if (eopPos != std::string::npos) {
        rawResponse.erase(eopPos, 5);
    }
    result.response = rawResponse;

    LOGD("[Vision] result: %s...", result.response.substr(0, 100).c_str());
    return result;
}

/**
 * 将 ImageInferenceResult 转换为 Java HashMap
 */
static jobject inferenceResultToHashMap(JNIEnv *env, const ImageInferenceResult &result) {
    if (!result.error.empty()) {
        return createErrorHashMap(env, result.error.c_str());
    }

    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                           "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jobject hashMap = env->NewObject(hashMapClass, hashMapInit);

    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID longInit = env->GetMethodID(longClass, "<init>", "(J)V");

    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("response"),
                          env->NewStringUTF(result.response.c_str()));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prompt_len"),
                          env->NewObject(longClass, longInit, result.metrics.prompt_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_len"),
                          env->NewObject(longClass, longInit, result.metrics.decode_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("vision_time"),
                          env->NewObject(longClass, longInit, result.metrics.vision_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("audio_time"),
                          env->NewObject(longClass, longInit, result.metrics.audio_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prefill_time"),
                          env->NewObject(longClass, longInit, result.metrics.prefill_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_time"),
                          env->NewObject(longClass, longInit, result.metrics.decode_time));

    return hashMap;
}

/**
 * 恢复 Android 预编译 MNN 库的 stepping 状态。
 *
 * 背景：Android 预编译的 libMNN.so 在每次 generate() 后可能将 context->status
 * 设置为 MAX_TOKENS_FINISHED 或 NORMAL_FINISHED。如果不重置为 RUNNING，
 * 下次调用 response()/generate() 会失败。
 *
 * 参考：官方 MnnLlmChat demo 的 llm_session.cpp restoreAndroidSteppingStatusIfNeeded()
 */
static void restoreLlmStatusIfNeeded(MNN::Transformer::Llm* llm) {
    if (llm == nullptr) {
        return;
    }
    const auto* context = llm->getContext();
    if (context == nullptr) {
        return;
    }
    if (context->status == MNN::Transformer::LlmStatus::MAX_TOKENS_FINISHED ||
        context->status == MNN::Transformer::LlmStatus::NORMAL_FINISHED) {
        auto* mutableContext = const_cast<MNN::Transformer::LlmContext*>(context);
        mutableContext->status = MNN::Transformer::LlmStatus::RUNNING;
        LOGD("LLM status restored to RUNNING (was FINISHED)");
    }
}

/**
 * 自定义 streambuf，用于收集 generate() 输出的 token
 * 参考官方 MnnLlmChat 实现，通过回调机制实时传递数据
 */
class LlmStreamBuffer : public std::streambuf {
public:
    using CallBack = std::function<void(const char *str, size_t len)>;

    explicit LlmStreamBuffer(CallBack callback) : callback_(std::move(callback)) {}

protected:
    std::streamsize xsputn(const char *s, std::streamsize n) override {
        if (callback_) {
            callback_(s, n);
        }
        return n;
    }

    virtual int_type overflow(int_type c) override {
        if (c != traits_type::eof() && callback_) {
            char ch = static_cast<char>(c);
            callback_(&ch, 1);
        }
        return c;
    }

private:
    CallBack callback_ = nullptr;
};

// ── 流式生成辅助：将 C++ token 回调桥接到 Java ──────────
static void streamTokensToJava(
        JNIEnv* env,
        jobject progressListener,
        jmethodID onTokenMethod,
        const std::string& token,
        bool isEop,
        bool& stopRequested) {
    if (progressListener == nullptr || onTokenMethod == nullptr) {
        return;
    }
    jstring tokenStr = isEop ? nullptr : env->NewStringUTF(sanitizeModifiedUtf8(token).c_str());
    jboolean shouldStop = env->CallBooleanMethod(progressListener, onTokenMethod, tokenStr, isEop);
    if (tokenStr != nullptr) {
        env->DeleteLocalRef(tokenStr);
    }
    if (shouldStop == JNI_TRUE) {
        stopRequested = true;
    }
}

JNIEXPORT jlong JNICALL
Java_com_mamba_picme_agent_core_inference_local_llm_MnnLlmClient_nativeCreate(
        JNIEnv *env,
        jclass clazz,
        jstring configPath) {

    const char *configCStr = env->GetStringUTFChars(configPath, nullptr);
    LOGD("Creating LLM with config: %s", configCStr);

    MNN::Transformer::Llm *llm = MNN::Transformer::Llm::createLLM(std::string(configCStr));
    env->ReleaseStringUTFChars(configPath, configCStr);

    if (llm == nullptr) {
        LOGE("Failed to create LLM");
        return 0;
    }

    bool loaded = llm->load();
    if (!loaded) {
        LOGE("Failed to load LLM model");
        MNN::Transformer::Llm::destroy(llm);
        return 0;
    }

    LOGD("LLM created and loaded successfully");
    return reinterpret_cast<jlong>(llm);
}

JNIEXPORT void JNICALL
Java_com_mamba_picme_agent_core_inference_local_llm_MnnLlmClient_nativeDestroy(
        JNIEnv *env,
        jclass clazz,
        jlong handle) {

    auto *llm = reinterpret_cast<MNN::Transformer::Llm *>(handle);
    if (llm != nullptr) {
        MNN::Transformer::Llm::destroy(llm);
        LOGD("LLM destroyed");
    }
}

JNIEXPORT void JNICALL
Java_com_mamba_picme_agent_core_inference_local_llm_MnnLlmClient_nativeReset(
        JNIEnv *env,
        jclass clazz,
        jlong handle) {

    auto *llm = reinterpret_cast<MNN::Transformer::Llm *>(handle);
    if (llm != nullptr) {
        llm->reset();
        LOGD("LLM reset (history cleared)");
    }
}

// ── 同步生成（保留原有实现）─────────────────────────────
JNIEXPORT jstring JNICALL
Java_com_mamba_picme_agent_core_inference_local_llm_MnnLlmClient_nativeGenerate(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jstring prompt,
        jint maxNewTokens) {

    auto *llm = reinterpret_cast<MNN::Transformer::Llm *>(handle);
    if (llm == nullptr) {
        LOGE("LLM handle is null");
        return env->NewStringUTF("");
    }

    const char *promptCStr = env->GetStringUTFChars(prompt, nullptr);
    std::string promptStr(promptCStr);
    env->ReleaseStringUTFChars(prompt, promptCStr);

    LOGD("Generating response for prompt: %s", promptStr.c_str());

    std::ostringstream oss;
    {
        std::lock_guard<std::mutex> lock(g_llm_mutex);
        restoreLlmStatusIfNeeded(llm);
        llm->response(promptStr, &oss, nullptr, maxNewTokens);
    }

    std::string result = sanitizeModifiedUtf8(oss.str());

    // 分段打印长 response，避免 Android 日志长度限制截断
    const size_t LOG_CHUNK_SIZE = 1024;
    if (result.length() <= LOG_CHUNK_SIZE) {
        LOGD("Generated response: %s", result.c_str());
    } else {
        LOGD("Generated response (len=%zu, chunked):", result.length());
        for (size_t i = 0; i < result.length(); i += LOG_CHUNK_SIZE) {
            size_t len = (i + LOG_CHUNK_SIZE <= result.length()) ? LOG_CHUNK_SIZE : (result.length() - i);
            std::string chunk = result.substr(i, len);
            LOGD("  [chunk %zu/%zu]: %s", i / LOG_CHUNK_SIZE + 1,
                 (result.length() + LOG_CHUNK_SIZE - 1) / LOG_CHUNK_SIZE, chunk.c_str());
        }
    }
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jobject JNICALL
Java_com_mamba_picme_agent_core_inference_local_llm_MnnLlmClient_nativeGenerateWithSystem(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jstring systemPrompt,
        jstring userPrompt,
        jint maxNewTokens) {

    auto *llm = reinterpret_cast<MNN::Transformer::Llm *>(handle);
    if (llm == nullptr) {
        LOGE("LLM handle is null");
        jclass hashMapClass = env->FindClass("java/util/HashMap");
        jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
        jobject hashMap = env->NewObject(hashMapClass, hashMapInit);
        jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                               "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("error"),
                              env->NewStringUTF("Failed, LLM handle is null"));
        return hashMap;
    }

    const char *systemCStr = env->GetStringUTFChars(systemPrompt, nullptr);
    const char *userCStr = env->GetStringUTFChars(userPrompt, nullptr);

    std::string systemStr(systemCStr);
    std::string userStr(userCStr);

    env->ReleaseStringUTFChars(systemPrompt, systemCStr);
    env->ReleaseStringUTFChars(userPrompt, userCStr);

    MNN::Transformer::ChatMessages messages;
    messages.emplace_back("system", systemStr);
    messages.emplace_back("user", userStr);

    LOGD("Generating with system prompt + user prompt");

    LlmMetrics metrics;
    std::ostringstream oss;
    auto start = std::chrono::high_resolution_clock::now();
    {
        std::lock_guard<std::mutex> lock(g_llm_mutex);
        restoreLlmStatusIfNeeded(llm);
        llm->response(messages, &oss, nullptr, maxNewTokens);
    }
    auto end = std::chrono::high_resolution_clock::now();
    // 同步生成无法区分 prefill/decode，将总耗时全部计入 decode_time
    metrics.decode_time = std::chrono::duration_cast<std::chrono::microseconds>(
            end - start).count();

    auto* ctx = llm->getContext();
    if (ctx != nullptr) {
        metrics.prompt_len = ctx->prompt_len;
        metrics.decode_len = ctx->gen_seq_len;
        metrics.vision_time = ctx->vision_us;
        metrics.audio_time = ctx->audio_us;
    }

    std::string result = sanitizeModifiedUtf8(oss.str());
    // 分段打印长 response，避免 Android 日志长度限制截断
    const size_t LOG_CHUNK_SIZE = 1024;
    if (result.length() <= LOG_CHUNK_SIZE) {
        LOGD("Generated response: %s", result.c_str());
    } else {
        LOGD("Generated response (len=%zu, chunked):", result.length());
        for (size_t i = 0; i < result.length(); i += LOG_CHUNK_SIZE) {
            size_t len = (i + LOG_CHUNK_SIZE <= result.length()) ? LOG_CHUNK_SIZE : (result.length() - i);
            std::string chunk = result.substr(i, len);
            LOGD("  [chunk %zu/%zu]: %s", i / LOG_CHUNK_SIZE + 1,
                 (result.length() + LOG_CHUNK_SIZE - 1) / LOG_CHUNK_SIZE, chunk.c_str());
        }
    }

    // 构建返回 HashMap
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                           "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jobject hashMap = env->NewObject(hashMapClass, hashMapInit);

    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID longInit = env->GetMethodID(longClass, "<init>", "(J)V");

    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("response"),
                          env->NewStringUTF(result.c_str()));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prompt_len"),
                          env->NewObject(longClass, longInit, metrics.prompt_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_len"),
                          env->NewObject(longClass, longInit, metrics.decode_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("vision_time"),
                          env->NewObject(longClass, longInit, metrics.vision_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("audio_time"),
                          env->NewObject(longClass, longInit, metrics.audio_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prefill_time"),
                          env->NewObject(longClass, longInit, metrics.prefill_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_time"),
                          env->NewObject(longClass, longInit, metrics.decode_time));

    return hashMap;
}

// ── 多模态图片生成 + 性能指标 ─────────────────────────────
JNIEXPORT jobject JNICALL
Java_com_mamba_picme_agent_core_inference_local_llm_MnnLlmClient_nativeGenerateWithImage(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jstring systemPrompt,
        jstring userPrompt,
        jstring promptTemplate,
        jobject bitmap,
        jint maxNewTokens) {

    auto *llm = reinterpret_cast<MNN::Transformer::Llm *>(handle);
    if (llm == nullptr) {
        LOGE("LLM handle is null");
        return createErrorHashMap(env, "LLM handle is null");
    }

    // 1. 获取 system / user prompt
    const char *systemCStr = env->GetStringUTFChars(systemPrompt, nullptr);
    const char *userCStr = env->GetStringUTFChars(userPrompt, nullptr);
    std::string systemStr(systemCStr);
    std::string userStr(userCStr);
    env->ReleaseStringUTFChars(systemPrompt, systemCStr);
    env->ReleaseStringUTFChars(userPrompt, userCStr);

    // 1b. prompt_template（Kotlin 按模型 llm_config 拼好；空则 JNI 兜底 ChatML）
    const char *promptTplCStr = env->GetStringUTFChars(promptTemplate, nullptr);
    std::string promptTplStr(promptTplCStr);
    env->ReleaseStringUTFChars(promptTemplate, promptTplCStr);

    // 2. 从 Android Bitmap 提取像素数据
    AndroidBitmapInfo bitmapInfo;
    if (AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) < 0) {
        LOGE("Failed to get bitmap info");
        return createErrorHashMap(env, "Failed to get bitmap info");
    }

    // ── 图像尺寸检查 ────────────────────────────────────
    int width = bitmapInfo.width;
    int height = bitmapInfo.height;
    if (width <= 0 || height <= 0 || width > MAX_IMAGE_DIM || height > MAX_IMAGE_DIM) {
        LOGE("[Vision] Image dimensions %dx%d exceed limit %d or invalid",
             width, height, MAX_IMAGE_DIM);
        return createErrorHashMap(env, "Image too large, max 420px per side");
    }

    // ── Bitmap 格式检查 ─────────────────────────────────
    if (bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("[Vision] Unsupported bitmap format: %d (require RGBA_8888)", bitmapInfo.format);
        return createErrorHashMap(env, "Unsupported bitmap format, require RGBA_8888");
    }

    void *bitmapPixels;
    if (AndroidBitmap_lockPixels(env, bitmap, &bitmapPixels) < 0) {
        LOGE("Failed to lock bitmap pixels");
        return createErrorHashMap(env, "Failed to lock bitmap pixels");
    }

    LOGD("[Vision] Image: %dx%d, format=%d, stride=%d", width, height, bitmapInfo.format, bitmapInfo.stride);

    // Android Bitmap ARGB_8888 像素转为 RGB uint8 NHWC
    //
    // 【设计决策】使用 uint32_t 位运算提取通道，而非直接按字节索引。
    //
    // Android Bitmap.Config.ARGB_8888 的每个像素是一个 32 位值:
    //   0xAARRGGBB  (A=Alpha, R=Red, G=Green, B=Blue)
    //
    // AndroidBitmap_lockPixels 返回的 uint32_t* 已经解析为 host 字节序,
    // C 语言的位运算 (pixel >> 16) & 0xFF 在逻辑层面提取 Red 分量,
    // 完全不受底层内存的 big/little-endian 影响。
    //
    // 对比按字节索引的方式 (pixels[offset+0], pixels[offset+1], ...):
    // 字节索引依赖 ARM little-endian 的物理排列 [B,G,R,A]，
    // 可读性差且平台相关。位运算方案语义明确、跨平台安全。
    //
    // MNN 视觉编码器使用 _Input + writeMap<uint8_t>，传入 uint8 原始像素
    // 编码器内部自行处理归一化 (mean=[127.5], norm=[1/127.5])
    // 参考 MNN Demo: video/video_processor.cpp CreateTensorFromRgb()
    //
    // 注意：之前代码按字节索引错误地交换了 R↔B 通道, 已于 2026-06-23 修复。
    std::vector<uint8_t> rgbData(height * width * 3);  // HWC: H × W × 3
    uint32_t *pixels = static_cast<uint32_t *>(bitmapPixels);
    int pixelStride = bitmapInfo.stride / 4;  // stride 按字节, 除以 4 得到 uint32_t 步长
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            uint32_t pixel = pixels[y * pixelStride + x];
            int dstIdx = (y * width + x) * 3;
            // 0xAARRGGBB → 位运算提取, 不受字节序影响
            rgbData[dstIdx]     = (pixel >> 16) & 0xFF;  // R
            rgbData[dstIdx + 1] = (pixel >>  8) & 0xFF;  // G
            rgbData[dstIdx + 2] =  pixel        & 0xFF;  // B
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);

    // 创建 MNN VARP: _Input({H,W,3}, NHWC, uint8) + writeMap + memcpy
    // 严格匹配 MNN Demo: video_processor.cpp CreateTensorFromRgb() 第 254-260 行
    auto imageVar = MNN::Express::_Input({height, width, 3}, MNN::Express::NHWC,
                                         halide_type_of<uint8_t>());
    auto ptr = imageVar->writeMap<uint8_t>();
    memcpy(ptr, rgbData.data(), height * width * 3);

    // 3. 构建 MultimodalPrompt
    //
    // 重要：prompt_template 必须使用 <img>...</img> 标签作为图片占位符，
    // MNN 的 tokenizer_encode(MultimodalPrompt) 通过该标签定位图片嵌入的插入位置。
    // 不能使用 Qwen 的特殊 token（<|vision_start|> 等），MNN 内部会自行处理这些。
    //
    // 参考：MNN 官方 Demo processor.cpp HandleImageTags()
    MNN::Transformer::MultimodalPrompt multimodal;
    multimodal.prompt_template = promptTplStr.empty()
        ? ("<|im_start|>system\n" + systemStr + "<|im_end|>\n"
           "<|im_start|>user\n"
           "<img>image_0</img>" + userStr + "<|im_end|>\n"
           "<|im_start|>assistant\n")
        : promptTplStr;
    multimodal.images["image_0"] = {imageVar, 0, 0};

    LOGD("[Vision] Generating: system=%zu chars, user=%zu chars, maxTokens=%d",
         systemStr.size(), userStr.size(), maxNewTokens);

    // 4. 两阶段多模态生成（匹配 MNN 官方 Demo 模式）
    //    官方 Demo: llm->response(multimodal, &oss, "<eop>", 0) → prefill
    //              循环 llm->generate(1) → decode one token at a time
    //
    //    使用 "<eop>" 作为结束标记，避免 nullptr 触发默认换行符行为。
    //    此模式在 Android 预编译 libMNN.so 上更稳定。
    //
    //    [重要] imageVar（_Input 的 writeMap 数据）必须在此作用域内保持有效，
    //    直到所有 generate() 返回，因为 MNN Express 可能延迟评估。
    //
    //    SIGSEGV 容错：使用 sigsetjmp/siglongjmp 捕获崩溃。
    LlmMetrics metrics;
    std::ostringstream oss;
    auto start = std::chrono::high_resolution_clock::now();
    bool mnn_crashed = false;

    g_llm_mutex.lock();
    restoreLlmStatusIfNeeded(llm);

    struct sigaction old_action;
    if (sigsetjmp(g_mnn_jmp_buf, 1) == 0) {
        installCrashGuard(&old_action);

        // 阶段 1: Prefill（max_new_tokens=0 + "<eop>" 结束标记）
        llm->response(multimodal, &oss, "<eop>", 0);

        // 阶段 2: Decode loop（逐 token 生成）
        for (int gen_i = 0; gen_i < maxNewTokens; gen_i++) {
            if (llm->stoped()) break;
            llm->generate(1);
        }

        uninstallCrashGuard(&old_action);
        g_llm_mutex.unlock();
    } else {
        // SIGSEGV 已捕获。siglongjmp 跳过 C++ 析构函数，
        // 需要手动解锁（g_llm_mutex 在 .bss 段，不受栈回退影响）。
        uninstallCrashGuard(&old_action);
        g_llm_mutex.unlock();
        mnn_crashed = true;
        LOGE("[CRASH] ═══════════════════════════════════════════");
        LOGE("[CRASH] SIGSEGV in llm->response(multimodal) / generate()!");
        LOGE("[CRASH] Image: %dx%d, prompt: system=%zu, user=%zu",
             width, height, systemStr.size(), userStr.size());
        LOGE("[CRASH] ═══════════════════════════════════════════");
    }

    if (mnn_crashed) {
        // rgbData 和 multimodal 仍在作用域内（栈上变量）
        return createErrorHashMap(env, "MNN LLM crashed: SIGSEGV in multimodal inference");
    }
    auto end = std::chrono::high_resolution_clock::now();
    metrics.decode_time = std::chrono::duration_cast<std::chrono::microseconds>(
            end - start).count();

    auto* ctx = llm->getContext();
    if (ctx != nullptr) {
        metrics.prompt_len = ctx->prompt_len;
        metrics.decode_len = ctx->gen_seq_len;
        metrics.vision_time = ctx->vision_us;
        metrics.audio_time = ctx->audio_us;
    }

    std::string result = sanitizeModifiedUtf8(oss.str());

    // 移除 <eop> 结束标记（MNN 通过 ostream 输出时可能包含该标记）
    size_t eopPos = result.find("<eop>");
    if (eopPos != std::string::npos) {
        result.erase(eopPos, 5);  // strlen("<eop>") == 5
    }

    // 日志：打印识别结果 + 性能指标
    LOGD("═══════════════════════════════════════════");
    LOGD("[Vision] ║ 图片识别结果 (Image Recognition Result)");
    LOGD("[Vision] ╠═══════════════════════════════════════");
    const size_t LOG_CHUNK_SIZE = 512;
    if (result.length() <= LOG_CHUNK_SIZE) {
        LOGD("[Vision] ║ %s", result.c_str());
    } else {
        LOGD("[Vision] ║ (len=%zu chars):", result.length());
        for (size_t i = 0; i < result.length(); i += LOG_CHUNK_SIZE) {
            size_t chunkLen = (i + LOG_CHUNK_SIZE <= result.length())
                ? LOG_CHUNK_SIZE : (result.length() - i);
            std::string chunk = result.substr(i, chunkLen);
            LOGD("[Vision] ║   %s", chunk.c_str());
        }
    }
    LOGD("[Vision] ╠═══════════════════════════════════════");
    LOGD("[Vision] ║ 性能: prompt=%lld tokens, decode=%lld tokens",
         (long long)metrics.prompt_len, (long long)metrics.decode_len);
    LOGD("[Vision] ║ 耗时: vision=%lld us, decode=%lld us",
         (long long)metrics.vision_time, (long long)metrics.decode_time);
    if (metrics.prompt_len > 0 && metrics.decode_time > 0) {
        float totalSpeed = (metrics.prompt_len + metrics.decode_len) * 1000000.0f / metrics.decode_time;
        LOGD("[Vision] ║ 速度: %.1f tokens/s", totalSpeed);
    }
    LOGD("═══════════════════════════════════════════");

    // 5. 构建返回 HashMap
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                           "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jobject hashMap = env->NewObject(hashMapClass, hashMapInit);

    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID longInit = env->GetMethodID(longClass, "<init>", "(J)V");

    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("response"),
                          env->NewStringUTF(result.c_str()));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prompt_len"),
                          env->NewObject(longClass, longInit, metrics.prompt_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_len"),
                          env->NewObject(longClass, longInit, metrics.decode_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("vision_time"),
                          env->NewObject(longClass, longInit, metrics.vision_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("audio_time"),
                          env->NewObject(longClass, longInit, metrics.audio_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prefill_time"),
                          env->NewObject(longClass, longInit, metrics.prefill_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_time"),
                          env->NewObject(longClass, longInit, metrics.decode_time));

    return hashMap;
}

// ── 多模态图片生成 + 超时守护（新增）────────────────────
JNIEXPORT jobject JNICALL
Java_com_mamba_picme_agent_core_inference_local_llm_MnnLlmClient_nativeGenerateWithImageTimeout(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jstring systemPrompt,
        jstring userPrompt,
        jstring promptTemplate,
        jobject bitmap,
        jint maxNewTokens,
        jint timeoutMs) {

    auto *llm = reinterpret_cast<MNN::Transformer::Llm *>(handle);
    if (llm == nullptr) {
        LOGE("LLM handle is null");
        return createErrorHashMap(env, "LLM handle is null");
    }

    // 1. 获取 system / user prompt
    const char *systemCStr = env->GetStringUTFChars(systemPrompt, nullptr);
    const char *userCStr = env->GetStringUTFChars(userPrompt, nullptr);
    std::string systemStr(systemCStr);
    std::string userStr(userCStr);
    env->ReleaseStringUTFChars(systemPrompt, systemCStr);
    env->ReleaseStringUTFChars(userPrompt, userCStr);

    // 1b. prompt_template（Kotlin 按模型 llm_config 拼好；空则 JNI 兜底 ChatML）
    const char *promptTplCStr = env->GetStringUTFChars(promptTemplate, nullptr);
    std::string promptTplStr(promptTplCStr);
    env->ReleaseStringUTFChars(promptTemplate, promptTplCStr);

    // 2. 从 Android Bitmap 提取像素数据
    AndroidBitmapInfo bitmapInfo;
    if (AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) < 0) {
        LOGE("Failed to get bitmap info");
        return createErrorHashMap(env, "Failed to get bitmap info");
    }

    int width = bitmapInfo.width;
    int height = bitmapInfo.height;
    if (width <= 0 || height <= 0 || width > MAX_IMAGE_DIM || height > MAX_IMAGE_DIM) {
        LOGE("[Vision] Image dimensions %dx%d exceed limit %d or invalid",
             width, height, MAX_IMAGE_DIM);
        return createErrorHashMap(env, "Image too large, max 420px per side");
    }

    if (bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("[Vision] Unsupported bitmap format: %d (require RGBA_8888)", bitmapInfo.format);
        return createErrorHashMap(env, "Unsupported bitmap format, require RGBA_8888");
    }

    void *bitmapPixels;
    if (AndroidBitmap_lockPixels(env, bitmap, &bitmapPixels) < 0) {
        LOGE("Failed to lock bitmap pixels");
        return createErrorHashMap(env, "Failed to lock bitmap pixels");
    }

    std::vector<uint8_t> rgbData(height * width * 3);
    uint32_t *pixels = static_cast<uint32_t *>(bitmapPixels);
    int pixelStride = bitmapInfo.stride / 4;
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            uint32_t pixel = pixels[y * pixelStride + x];
            int dstIdx = (y * width + x) * 3;
            rgbData[dstIdx]     = (pixel >> 16) & 0xFF;
            rgbData[dstIdx + 1] = (pixel >>  8) & 0xFF;
            rgbData[dstIdx + 2] =  pixel        & 0xFF;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);

    // 3. 创建 MNN VARP
    auto imageVar = MNN::Express::_Input({height, width, 3}, MNN::Express::NHWC,
                                         halide_type_of<uint8_t>());
    auto ptr = imageVar->writeMap<uint8_t>();
    memcpy(ptr, rgbData.data(), height * width * 3);

    // 4. 构建 MultimodalPrompt
    MNN::Transformer::MultimodalPrompt multimodal;
    multimodal.prompt_template = promptTplStr.empty()
        ? ("<|im_start|>system\n" + systemStr + "<|im_end|>\n"
           "<|im_start|>user\n"
           "<img>image_0</img>" + userStr + "<|im_end|>\n"
           "<|im_start|>assistant\n")
        : promptTplStr;
    multimodal.images["image_0"] = {imageVar, 0, 0};

    LOGD("[Vision] Generating with timeout: system=%zu chars, user=%zu chars, maxTokens=%d, timeout=%dms",
         systemStr.size(), userStr.size(), maxNewTokens, timeoutMs);

    // 5. 在 worker 线程中执行推理，主线程 watchdog 等待
    std::packaged_task<ImageInferenceResult()> task([
        llm, multimodal = std::move(multimodal), maxNewTokens, width, height,
        systemLen = systemStr.size(), userLen = userStr.size()]() mutable {
        return doLockedImageInference(llm, std::move(multimodal), maxNewTokens,
                                      width, height, systemLen, userLen);
    });

    auto future = task.get_future();
    std::thread(std::move(task)).detach();

    auto status = future.wait_for(std::chrono::milliseconds(timeoutMs));
    if (status == std::future_status::timeout) {
        LOGE("[Vision] OpenCL inference timed out after %d ms", timeoutMs);
        return createErrorHashMap(env, "OPENCL_TIMEOUT");
    }

    ImageInferenceResult result;
    try {
        result = future.get();
    } catch (const std::exception &e) {
        LOGE("[Vision] Worker exception: %s", e.what());
        return createErrorHashMap(env, "Worker exception");
    }

    return inferenceResultToHashMap(env, result);
}

// ── 流式生成 + 性能指标（新增）──────────────────────────
JNIEXPORT jobject JNICALL
Java_com_mamba_picme_agent_core_inference_local_llm_MnnLlmClient_nativeGenerateStream(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jstring prompt,
        jint maxNewTokens,
        jobject progressListener) {

    auto *llm = reinterpret_cast<MNN::Transformer::Llm *>(handle);
    if (llm == nullptr) {
        LOGE("LLM handle is null");
        jclass hashMapClass = env->FindClass("java/util/HashMap");
        jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
        jobject hashMap = env->NewObject(hashMapClass, hashMapInit);
        jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                               "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("error"),
                              env->NewStringUTF("Failed, LLM handle is null"));
        return hashMap;
    }

    const char *promptCStr = env->GetStringUTFChars(prompt, nullptr);
    std::string promptStr(promptCStr);
    env->ReleaseStringUTFChars(prompt, promptCStr);

    LOGD("Stream generating for prompt: %s", promptStr.c_str());

    // 获取 Java 回调方法
    jclass listenerClass = env->GetObjectClass(progressListener);
    jmethodID onTokenMethod = env->GetMethodID(listenerClass, "onToken",
                                               "(Ljava/lang/String;Z)Z");
    if (onTokenMethod == nullptr) {
        LOGE("ProgressListener onToken method not found");
    }

    LlmMetrics metrics;
    std::stringstream response_buffer;
    bool stopRequested = false;
    bool generateTextEnd = false;

    {
        std::lock_guard<std::mutex> lock(g_llm_mutex);
        restoreLlmStatusIfNeeded(llm);

        auto prefill_start = std::chrono::high_resolution_clock::now();

        // 使用 Utf8StreamProcessor 处理流式输出，确保中文字符完整
        std::string utf8Buffer;
        auto processUtf8Chunk = [&](const char *str, size_t len) {
            utf8Buffer.append(str, len);

            size_t i = 0;
            std::string completeChars;
            while (i < utf8Buffer.size()) {
                unsigned char byte = static_cast<unsigned char>(utf8Buffer[i]);
                int charLen = 0;
                if ((byte & 0x80) == 0) charLen = 1;
                else if ((byte & 0xE0) == 0xC0) charLen = 2;
                else if ((byte & 0xF0) == 0xE0) charLen = 3;
                else if ((byte & 0xF8) == 0xF0) charLen = 4;
                else break;

                if (i + charLen > utf8Buffer.size()) break;
                completeChars.append(utf8Buffer, i, charLen);
                i += charLen;
            }
            utf8Buffer = utf8Buffer.substr(i);

            if (!completeChars.empty()) {
                // 检查是否包含 <eop>（end-of-prefill 标记）
                bool isEop = (completeChars.find("<eop>") != std::string::npos);
                if (isEop) {
                    generateTextEnd = true;
                }

                // 将 <eop> 从展示文本/最终回复中移除，避免 UI 显示出来
                std::string displayChars = completeChars;
                size_t eopPos = displayChars.find("<eop>");
                if (eopPos != std::string::npos) {
                    displayChars.erase(eopPos, 5); // strlen("<eop>") == 5
                }
                if (!displayChars.empty()) {
                    response_buffer << displayChars;
                }

                streamTokensToJava(env, progressListener, onTokenMethod,
                                   displayChars, isEop, stopRequested);
            }
        };

        LlmStreamBuffer stream_buffer(processUtf8Chunk);
        std::ostream output_ostream(&stream_buffer);

        // prefill 阶段：response(..., 0) 只编码不生成
        llm->response(promptStr, &output_ostream, "<eop>", 0);

        auto prefill_end = std::chrono::high_resolution_clock::now();
        metrics.prefill_time = std::chrono::duration_cast<std::chrono::microseconds>(
                prefill_end - prefill_start).count();

        // 获取 prefill 后的上下文信息
        auto* ctx = llm->getContext();
        if (ctx != nullptr) {
            metrics.prompt_len = ctx->prompt_len;
        }

        // decode 阶段：逐 token 生成
        auto decode_start = std::chrono::high_resolution_clock::now();
        int current_size = 0;

        while (!stopRequested && !generateTextEnd && current_size < maxNewTokens) {
            llm->generate(1);
            current_size++;

            // 检查状态
            ctx = llm->getContext();
            if (ctx != nullptr) {
                if (ctx->status == MNN::Transformer::LlmStatus::NORMAL_FINISHED ||
                    ctx->status == MNN::Transformer::LlmStatus::MAX_TOKENS_FINISHED) {
                    generateTextEnd = true;
                }
            }
        }

        // 处理缓冲区中剩余的未完整 UTF-8 字符
        if (!utf8Buffer.empty() && !generateTextEnd) {
            response_buffer << utf8Buffer;
            streamTokensToJava(env, progressListener, onTokenMethod,
                               utf8Buffer, false, stopRequested);
        }

        auto decode_end = std::chrono::high_resolution_clock::now();
        metrics.decode_time = std::chrono::duration_cast<std::chrono::microseconds>(
                decode_end - decode_start).count();

        // 获取最终指标
        ctx = llm->getContext();
        if (ctx != nullptr) {
            metrics.decode_len = ctx->gen_seq_len;
            metrics.vision_time = ctx->vision_us;
            metrics.audio_time = ctx->audio_us;
        }
    }

    std::string result = sanitizeModifiedUtf8(response_buffer.str());
    LOGD("Stream generation complete. len=%zu, prompt=%ld, decode=%ld, prefill_time=%ldus, decode_time=%ldus",
         result.length(), metrics.prompt_len, metrics.decode_len,
         metrics.prefill_time, metrics.decode_time);

    // 构建返回 HashMap
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                           "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jobject hashMap = env->NewObject(hashMapClass, hashMapInit);

    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID longInit = env->GetMethodID(longClass, "<init>", "(J)V");

    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("response"),
                          env->NewStringUTF(result.c_str()));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prompt_len"),
                          env->NewObject(longClass, longInit, metrics.prompt_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_len"),
                          env->NewObject(longClass, longInit, metrics.decode_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("vision_time"),
                          env->NewObject(longClass, longInit, metrics.vision_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("audio_time"),
                          env->NewObject(longClass, longInit, metrics.audio_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prefill_time"),
                          env->NewObject(longClass, longInit, metrics.prefill_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_time"),
                          env->NewObject(longClass, longInit, metrics.decode_time));

    env->DeleteLocalRef(listenerClass);

    return hashMap;
}

// ── 多轮对话同步生成（含性能指标）───────────────────────
JNIEXPORT jobject JNICALL
Java_com_mamba_picme_agent_core_inference_local_llm_MnnLlmClient_nativeGenerateWithHistory(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jobject historyList,  // List<Pair<String, String>>
        jint maxNewTokens) {

    auto *llm = reinterpret_cast<MNN::Transformer::Llm *>(handle);
    if (llm == nullptr) {
        LOGE("LLM handle is null");
        jclass hashMapClass = env->FindClass("java/util/HashMap");
        jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
        jobject hashMap = env->NewObject(hashMapClass, hashMapInit);
        jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                               "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("error"),
                              env->NewStringUTF("Failed, LLM handle is null"));
        return hashMap;
    }

    // 解析 Java List<Pair<String, String>> 到 C++ ChatMessages
    MNN::Transformer::ChatMessages messages;

    jclass listClass = env->GetObjectClass(historyList);
    jmethodID sizeMethod = env->GetMethodID(listClass, "size", "()I");
    jmethodID getMethod = env->GetMethodID(listClass, "get", "(I)Ljava/lang/Object;");
    jint listSize = env->CallIntMethod(historyList, sizeMethod);

    jclass pairClass = env->FindClass("android/util/Pair");
    jfieldID firstField = env->GetFieldID(pairClass, "first", "Ljava/lang/Object;");
    jfieldID secondField = env->GetFieldID(pairClass, "second", "Ljava/lang/Object;");

    for (jint i = 0; i < listSize; i++) {
        jobject pairObj = env->CallObjectMethod(historyList, getMethod, i);
        if (pairObj == nullptr) continue;

        jobject roleObj = env->GetObjectField(pairObj, firstField);
        jobject contentObj = env->GetObjectField(pairObj, secondField);

        const char *role = nullptr;
        const char *content = nullptr;
        if (roleObj != nullptr) {
            role = env->GetStringUTFChars((jstring) roleObj, nullptr);
        }
        if (contentObj != nullptr) {
            content = env->GetStringUTFChars((jstring) contentObj, nullptr);
        }

        if (role && content) {
            messages.emplace_back(std::string(role), std::string(content));
        }

        if (role) env->ReleaseStringUTFChars((jstring) roleObj, role);
        if (content) env->ReleaseStringUTFChars((jstring) contentObj, content);
        env->DeleteLocalRef(pairObj);
        if (roleObj) env->DeleteLocalRef(roleObj);
        if (contentObj) env->DeleteLocalRef(contentObj);
    }

    env->DeleteLocalRef(listClass);
    env->DeleteLocalRef(pairClass);

    LOGD("Generating with history (sync), messages=%zu", messages.size());

    LlmMetrics metrics;
    std::ostringstream oss;
    auto start = std::chrono::high_resolution_clock::now();
    {
        std::lock_guard<std::mutex> lock(g_llm_mutex);
        restoreLlmStatusIfNeeded(llm);
        llm->response(messages, &oss, nullptr, maxNewTokens);
    }
    auto end = std::chrono::high_resolution_clock::now();
    metrics.decode_time = std::chrono::duration_cast<std::chrono::microseconds>(
            end - start).count();

    auto* ctx = llm->getContext();
    if (ctx != nullptr) {
        metrics.prompt_len = ctx->prompt_len;
        metrics.decode_len = ctx->gen_seq_len;
        metrics.vision_time = ctx->vision_us;
        metrics.audio_time = ctx->audio_us;
    }

    std::string result = sanitizeModifiedUtf8(oss.str());
    const size_t LOG_CHUNK_SIZE = 1024;
    if (result.length() <= LOG_CHUNK_SIZE) {
        LOGD("History sync generated (len=%zu): %s", result.length(), result.c_str());
    } else {
        LOGD("History sync generated (len=%zu, chunked):", result.length());
        for (size_t i = 0; i < result.length(); i += LOG_CHUNK_SIZE) {
            size_t len = (i + LOG_CHUNK_SIZE <= result.length()) ? LOG_CHUNK_SIZE : (result.length() - i);
            std::string chunk = result.substr(i, len);
            LOGD("  [chunk %zu/%zu]: %s", i / LOG_CHUNK_SIZE + 1,
                 (result.length() + LOG_CHUNK_SIZE - 1) / LOG_CHUNK_SIZE, chunk.c_str());
        }
    }

    // 构建返回 HashMap
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                           "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jobject hashMap = env->NewObject(hashMapClass, hashMapInit);

    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID longInit = env->GetMethodID(longClass, "<init>", "(J)V");

    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("response"),
                          env->NewStringUTF(result.c_str()));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prompt_len"),
                          env->NewObject(longClass, longInit, metrics.prompt_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_len"),
                          env->NewObject(longClass, longInit, metrics.decode_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("vision_time"),
                          env->NewObject(longClass, longInit, metrics.vision_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("audio_time"),
                          env->NewObject(longClass, longInit, metrics.audio_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prefill_time"),
                          env->NewObject(longClass, longInit, metrics.prefill_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_time"),
                          env->NewObject(longClass, longInit, metrics.decode_time));

    return hashMap;
}

// ── 多轮对话流式生成（已有）─────────────────────────────
JNIEXPORT jobject JNICALL
Java_com_mamba_picme_agent_core_inference_local_llm_MnnLlmClient_nativeGenerateWithHistoryStream(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jobject historyList,  // List<Pair<String, String>>
        jint maxNewTokens,
        jobject progressListener) {

    auto *llm = reinterpret_cast<MNN::Transformer::Llm *>(handle);
    if (llm == nullptr) {
        LOGE("LLM handle is null");
        jclass hashMapClass = env->FindClass("java/util/HashMap");
        jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
        jobject hashMap = env->NewObject(hashMapClass, hashMapInit);
        jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                               "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("error"),
                              env->NewStringUTF("Failed, LLM handle is null"));
        return hashMap;
    }

    // 解析 Java List<Pair<String, String>> 到 C++ ChatMessages
    MNN::Transformer::ChatMessages messages;

    jclass listClass = env->GetObjectClass(historyList);
    jmethodID sizeMethod = env->GetMethodID(listClass, "size", "()I");
    jmethodID getMethod = env->GetMethodID(listClass, "get", "(I)Ljava/lang/Object;");
    jint listSize = env->CallIntMethod(historyList, sizeMethod);

    jclass pairClass = env->FindClass("android/util/Pair");
    jfieldID firstField = env->GetFieldID(pairClass, "first", "Ljava/lang/Object;");
    jfieldID secondField = env->GetFieldID(pairClass, "second", "Ljava/lang/Object;");

    for (jint i = 0; i < listSize; i++) {
        jobject pairObj = env->CallObjectMethod(historyList, getMethod, i);
        if (pairObj == nullptr) continue;

        jobject roleObj = env->GetObjectField(pairObj, firstField);
        jobject contentObj = env->GetObjectField(pairObj, secondField);

        const char *role = nullptr;
        const char *content = nullptr;
        if (roleObj != nullptr) {
            role = env->GetStringUTFChars((jstring) roleObj, nullptr);
        }
        if (contentObj != nullptr) {
            content = env->GetStringUTFChars((jstring) contentObj, nullptr);
        }

        if (role && content) {
            messages.emplace_back(std::string(role), std::string(content));
        }

        if (role) env->ReleaseStringUTFChars((jstring) roleObj, role);
        if (content) env->ReleaseStringUTFChars((jstring) contentObj, content);
        env->DeleteLocalRef(pairObj);
        if (roleObj) env->DeleteLocalRef(roleObj);
        if (contentObj) env->DeleteLocalRef(contentObj);
    }

    env->DeleteLocalRef(listClass);
    env->DeleteLocalRef(pairClass);

    LOGD("Stream generating with history, messages=%zu", messages.size());

    // 获取 Java 回调方法
    jclass listenerClass = env->GetObjectClass(progressListener);
    jmethodID onTokenMethod = env->GetMethodID(listenerClass, "onToken",
                                               "(Ljava/lang/String;Z)Z");
    if (onTokenMethod == nullptr) {
        LOGE("ProgressListener onToken method not found");
    }

    LlmMetrics metrics;
    std::stringstream response_buffer;
    bool stopRequested = false;
    bool generateTextEnd = false;

    {
        std::lock_guard<std::mutex> lock(g_llm_mutex);
        restoreLlmStatusIfNeeded(llm);

        auto prefill_start = std::chrono::high_resolution_clock::now();

        // 使用 Utf8StreamProcessor 处理流式输出，确保中文字符完整
        std::string utf8Buffer;
        auto processUtf8Chunk = [&](const char *str, size_t len) {
            utf8Buffer.append(str, len);

            size_t i = 0;
            std::string completeChars;
            while (i < utf8Buffer.size()) {
                unsigned char byte = static_cast<unsigned char>(utf8Buffer[i]);
                int charLen = 0;
                if ((byte & 0x80) == 0) charLen = 1;
                else if ((byte & 0xE0) == 0xC0) charLen = 2;
                else if ((byte & 0xF0) == 0xE0) charLen = 3;
                else if ((byte & 0xF8) == 0xF0) charLen = 4;
                else break;

                if (i + charLen > utf8Buffer.size()) break;
                completeChars.append(utf8Buffer, i, charLen);
                i += charLen;
            }
            utf8Buffer = utf8Buffer.substr(i);

            if (!completeChars.empty()) {
                // 检查是否包含 <eop>（end-of-prefill 标记）
                bool isEop = (completeChars.find("<eop>") != std::string::npos);
                if (isEop) {
                    generateTextEnd = true;
                }

                // 将 <eop> 从展示文本/最终回复中移除，避免 UI 显示出来
                std::string displayChars = completeChars;
                size_t eopPos = displayChars.find("<eop>");
                if (eopPos != std::string::npos) {
                    displayChars.erase(eopPos, 5); // strlen("<eop>") == 5
                }
                if (!displayChars.empty()) {
                    response_buffer << displayChars;
                }

                streamTokensToJava(env, progressListener, onTokenMethod,
                                   displayChars, isEop, stopRequested);
            }
        };

        LlmStreamBuffer stream_buffer(processUtf8Chunk);
        std::ostream output_ostream(&stream_buffer);

        // prefill 阶段
        llm->response(messages, &output_ostream, "<eop>", 0);

        auto prefill_end = std::chrono::high_resolution_clock::now();
        metrics.prefill_time = std::chrono::duration_cast<std::chrono::microseconds>(
                prefill_end - prefill_start).count();

        auto* ctx = llm->getContext();
        if (ctx != nullptr) {
            metrics.prompt_len = ctx->prompt_len;
        }

        // decode 阶段
        auto decode_start = std::chrono::high_resolution_clock::now();
        int current_size = 0;

        while (!stopRequested && !generateTextEnd && current_size < maxNewTokens) {
            llm->generate(1);
            current_size++;

            ctx = llm->getContext();
            if (ctx != nullptr) {
                if (ctx->status == MNN::Transformer::LlmStatus::NORMAL_FINISHED ||
                    ctx->status == MNN::Transformer::LlmStatus::MAX_TOKENS_FINISHED) {
                    generateTextEnd = true;
                }
            }
        }

        // 处理缓冲区中剩余的未完整 UTF-8 字符
        if (!utf8Buffer.empty() && !generateTextEnd) {
            response_buffer << utf8Buffer;
            streamTokensToJava(env, progressListener, onTokenMethod,
                               utf8Buffer, false, stopRequested);
        }

        auto decode_end = std::chrono::high_resolution_clock::now();
        metrics.decode_time = std::chrono::duration_cast<std::chrono::microseconds>(
                decode_end - decode_start).count();

        ctx = llm->getContext();
        if (ctx != nullptr) {
            metrics.decode_len = ctx->gen_seq_len;
            metrics.vision_time = ctx->vision_us;
            metrics.audio_time = ctx->audio_us;
        }
    }

    std::string result = sanitizeModifiedUtf8(response_buffer.str());
    LOGD("History stream generation complete. len=%zu, prompt=%ld, decode=%ld",
         result.length(), metrics.prompt_len, metrics.decode_len);

    // 构建返回 HashMap
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                           "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jobject hashMap = env->NewObject(hashMapClass, hashMapInit);

    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID longInit = env->GetMethodID(longClass, "<init>", "(J)V");

    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("response"),
                          env->NewStringUTF(result.c_str()));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prompt_len"),
                          env->NewObject(longClass, longInit, metrics.prompt_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_len"),
                          env->NewObject(longClass, longInit, metrics.decode_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("vision_time"),
                          env->NewObject(longClass, longInit, metrics.vision_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("audio_time"),
                          env->NewObject(longClass, longInit, metrics.audio_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prefill_time"),
                          env->NewObject(longClass, longInit, metrics.prefill_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_time"),
                          env->NewObject(longClass, longInit, metrics.decode_time));

    env->DeleteLocalRef(listenerClass);

    return hashMap;
}

JNIEXPORT jboolean JNICALL
Java_com_mamba_picme_agent_core_inference_local_llm_MnnLlmClient_nativeIsLoaded(
        JNIEnv *env,
        jclass clazz,
        jlong handle) {

    auto *llm = reinterpret_cast<MNN::Transformer::Llm *>(handle);
    return (llm != nullptr) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
