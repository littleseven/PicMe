#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#include "src/sentencepiece_processor.h"

#define LOG_TAG "SentencePieceJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using sentencepiece::SentencePieceProcessor;

// ── 工具函数 ──────────────────────────────────────────────

static inline jlong toJLong(SentencePieceProcessor* ptr) {
    return reinterpret_cast<jlong>(ptr);
}

static inline SentencePieceProcessor* fromJLong(jlong handle) {
    return reinterpret_cast<SentencePieceProcessor*>(handle);
}

static jintArray toIntArray(JNIEnv* env, const std::vector<int>& ids) {
    jintArray result = env->NewIntArray(static_cast<jsize>(ids.size()));
    if (result == nullptr) return nullptr;
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(ids.size()), ids.data());
    return result;
}

static jobjectArray toStringArray(JNIEnv* env, const std::vector<std::string>& pieces) {
    jobjectArray result = env->NewObjectArray(
        static_cast<jsize>(pieces.size()),
        env->FindClass("java/lang/String"),
        env->NewStringUTF("")
    );
    if (result == nullptr) return nullptr;
    for (size_t i = 0; i < pieces.size(); ++i) {
        env->SetObjectArrayElement(result, static_cast<jsize>(i), env->NewStringUTF(pieces[i].c_str()));
    }
    return result;
}

// ── JNI 方法实现 ──────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_com_mamba_picme_sentencepiece_SentencePieceProcessor_nativeLoadModel(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring modelPath
) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    auto* processor = new SentencePieceProcessor();
    auto status = processor->Load(path);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!status.ok()) {
        LOGE("Failed to load model: %s", status.ToString().c_str());
        delete processor;
        return 0;
    }

    LOGD("Model loaded successfully");
    return toJLong(processor);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_mamba_picme_sentencepiece_SentencePieceProcessor_nativeEncode(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring text
) {
    auto* processor = fromJLong(handle);
    if (processor == nullptr) return nullptr;

    const char* cstr = env->GetStringUTFChars(text, nullptr);
    std::string input(cstr);
    env->ReleaseStringUTFChars(text, cstr);

    std::vector<int> ids;
    auto status = processor->Encode(input, &ids);
    if (!status.ok()) {
        LOGE("Encode failed: %s", status.ToString().c_str());
        return nullptr;
    }

    return toIntArray(env, ids);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_mamba_picme_sentencepiece_SentencePieceProcessor_nativeEncodeAsPieces(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring text
) {
    auto* processor = fromJLong(handle);
    if (processor == nullptr) return nullptr;

    const char* cstr = env->GetStringUTFChars(text, nullptr);
    std::string input(cstr);
    env->ReleaseStringUTFChars(text, cstr);

    std::vector<std::string> pieces;
    auto status = processor->Encode(input, &pieces);
    if (!status.ok()) {
        LOGE("EncodeAsPieces failed: %s", status.ToString().c_str());
        return nullptr;
    }

    return toStringArray(env, pieces);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mamba_picme_sentencepiece_SentencePieceProcessor_nativeDecode(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jintArray ids
) {
    auto* processor = fromJLong(handle);
    if (processor == nullptr) return nullptr;

    jsize len = env->GetArrayLength(ids);
    jint* idArray = env->GetIntArrayElements(ids, nullptr);
    std::vector<int> idVec(idArray, idArray + len);
    env->ReleaseIntArrayElements(ids, idArray, JNI_ABORT);

    std::string text;
    auto status = processor->Decode(idVec, &text);
    if (!status.ok()) {
        LOGE("Decode failed: %s", status.ToString().c_str());
        return nullptr;
    }

    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mamba_picme_sentencepiece_SentencePieceProcessor_nativeVocabSize(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* processor = fromJLong(handle);
    if (processor == nullptr) return 0;
    return static_cast<jint>(processor->GetPieceSize());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mamba_picme_sentencepiece_SentencePieceProcessor_nativeIdToPiece(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jint id
) {
    auto* processor = fromJLong(handle);
    if (processor == nullptr) return nullptr;
    return env->NewStringUTF(processor->IdToPiece(static_cast<int>(id)).c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mamba_picme_sentencepiece_SentencePieceProcessor_nativePieceToId(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring piece
) {
    auto* processor = fromJLong(handle);
    if (processor == nullptr) return -1;

    const char* cstr = env->GetStringUTFChars(piece, nullptr);
    std::string pieceStr(cstr);
    env->ReleaseStringUTFChars(piece, cstr);

    return static_cast<jint>(processor->PieceToId(pieceStr));
}

extern "C" JNIEXPORT void JNICALL
Java_com_mamba_picme_sentencepiece_SentencePieceProcessor_nativeClose(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* processor = fromJLong(handle);
    if (processor != nullptr) {
        delete processor;
    }
}
