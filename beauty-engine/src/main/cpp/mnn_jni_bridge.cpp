#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "mnn_face_detector.h"
#include "mnn_face_embedder.h"

#define LOG_TAG "PicMe:MnnJNI"
#define LOGD(...) do { if (picme::MnnFaceDetector::isLogEnabled()) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__); } while(0)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT void JNICALL
Java_com_mamba_picme_beauty_internal_facedetect_mnn_MnnFaceDetector_nativeSetLogEnabled(
        JNIEnv *env,
        jclass clazz,
        jboolean enabled) {
    picme::MnnFaceDetector::setLogEnabled(enabled);
    LOGD("Native log enabled set to: %d", enabled);
}

JNIEXPORT jlong JNICALL
Java_com_mamba_picme_beauty_internal_facedetect_mnn_MnnFaceDetector_nativeCreate(
        JNIEnv *env,
        jclass clazz,
        jstring modelPath,
        jint inputSize,
        jboolean useGpu,
        jstring inputName,
        jobjectArray outputNames) {

    const char *modelCStr = env->GetStringUTFChars(modelPath, nullptr);
    const char *inputCStr = env->GetStringUTFChars(inputName, nullptr);

    // 解析输出名称数组
    std::vector<std::string> outputNameList;
    if (outputNames != nullptr) {
        jsize len = env->GetArrayLength(outputNames);
        for (jsize i = 0; i < len; i++) {
            jstring str = (jstring) env->GetObjectArrayElement(outputNames, i);
            const char *cstr = env->GetStringUTFChars(str, nullptr);
            outputNameList.push_back(std::string(cstr));
            env->ReleaseStringUTFChars(str, cstr);
            env->DeleteLocalRef(str);
        }
    }

    auto *detector = new picme::MnnFaceDetector();
    bool success = detector->load(modelCStr, inputSize, useGpu, inputCStr, outputNameList);

    env->ReleaseStringUTFChars(modelPath, modelCStr);
    env->ReleaseStringUTFChars(inputName, inputCStr);

    if (!success) {
        delete detector;
        LOGE("Failed to load MNN model");
        return 0;
    }

    LOGD("MnnFaceDetector created: inputSize=%d, useGpu=%d, outputs=%zu",
         inputSize, useGpu, outputNameList.size());
    return reinterpret_cast<jlong>(detector);
}

JNIEXPORT void JNICALL
Java_com_mamba_picme_beauty_internal_facedetect_mnn_MnnFaceDetector_nativeDestroy(
        JNIEnv *env,
        jclass clazz,
        jlong handle) {
    auto *detector = reinterpret_cast<picme::MnnFaceDetector *>(handle);
    delete detector;
    LOGD("MnnFaceDetector destroyed");
}

JNIEXPORT void JNICALL
Java_com_mamba_picme_beauty_internal_facedetect_mnn_MnnFaceDetector_nativeReleaseSession(
        JNIEnv *env,
        jclass clazz,
        jlong handle) {
    auto *detector = reinterpret_cast<picme::MnnFaceDetector *>(handle);
    if (!detector) {
        return;
    }
    detector->release(picme::MnnFaceDetector::RELEASE_TENSORS | picme::MnnFaceDetector::RELEASE_SESSION);
    LOGD("MnnFaceDetector session+tensors released");
}

JNIEXPORT jboolean JNICALL
Java_com_mamba_picme_beauty_internal_facedetect_mnn_MnnFaceDetector_nativeRebuildSession(
        JNIEnv *env,
        jclass clazz,
        jlong handle) {
    auto *detector = reinterpret_cast<picme::MnnFaceDetector *>(handle);
    if (!detector) {
        return JNI_FALSE;
    }
    bool ok = detector->rebuildSession();
    LOGD("MnnFaceDetector rebuild session result=%d", ok ? 1 : 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_mamba_picme_beauty_internal_facedetect_mnn_MnnFaceDetector_nativeReleaseModelBuffer(
        JNIEnv *env,
        jclass clazz,
        jlong handle) {
    auto *detector = reinterpret_cast<picme::MnnFaceDetector *>(handle);
    if (!detector) {
        return;
    }
    detector->release(picme::MnnFaceDetector::RELEASE_TENSORS |
                      picme::MnnFaceDetector::RELEASE_SESSION |
                      picme::MnnFaceDetector::RELEASE_MODEL);
    LOGD("MnnFaceDetector model buffer released");
}

JNIEXPORT jint JNICALL
Java_com_mamba_picme_beauty_internal_facedetect_mnn_MnnFaceDetector_nativeDetect(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jobject imageData,      // DirectByteBuffer
        jint width,
        jint height,
        jint channels,
        jfloatArray outResult) {  // 预分配结果缓冲区
    auto *detector = reinterpret_cast<picme::MnnFaceDetector *>(handle);
    if (!detector) {
        return 0;
    }

    unsigned char *data = static_cast<unsigned char *>(env->GetDirectBufferAddress(imageData));
    if (!data) {
        LOGE("nativeDetect: GetDirectBufferAddress returned null");
        return 0;
    }

    // detect() 内部使用 resultBuffer_ 成员，返回引用，无拷贝
    const std::vector<float>& result = detector->detect(data, width, height, channels);

    if (result.empty()) {
        return 0;
    }

    jsize maxSize = env->GetArrayLength(outResult);
    jsize copySize = static_cast<jsize>(std::min(result.size(), static_cast<size_t>(maxSize)));
    env->SetFloatArrayRegion(outResult, 0, copySize, result.data());
    return copySize;
}

JNIEXPORT jobjectArray JNICALL
Java_com_mamba_picme_beauty_internal_facedetect_mnn_MnnFaceDetector_nativeDetectRetinaFaces(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jobject imageData,      // DirectByteBuffer
        jint width,
        jint height,
        jint channels,
        jfloat confidenceThreshold,
        jfloat nmsThreshold) {
    auto *detector = reinterpret_cast<picme::MnnFaceDetector *>(handle);
    if (!detector) {
        return nullptr;
    }

    unsigned char *data = static_cast<unsigned char *>(env->GetDirectBufferAddress(imageData));
    if (!data) {
        LOGE("nativeDetectRetinaFaces: GetDirectBufferAddress returned null");
        return nullptr;
    }

    std::vector<picme::FaceBox> faces = detector->detectRetinaFace(
            data, width, height, channels,
            confidenceThreshold, nmsThreshold);

    if (faces.empty()) {
        return nullptr;
    }

    // 查找 FaceBox Kotlin 类
    jclass faceBoxClass = env->FindClass("com/mamba/picme/beauty/internal/facedetect/mnn/FaceBox");
    if (!faceBoxClass) {
        LOGE("nativeDetectRetinaFaces: FaceBox class not found");
        return nullptr;
    }

    jmethodID constructor = env->GetMethodID(faceBoxClass, "<init>",
        "(FFFFF[F)V");
    if (!constructor) {
        LOGE("nativeDetectRetinaFaces: FaceBox constructor not found");
        return nullptr;
    }

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(faces.size()), faceBoxClass, nullptr);
    if (!result) {
        LOGE("nativeDetectRetinaFaces: failed to create object array");
        return nullptr;
    }

    for (size_t i = 0; i < faces.size(); i++) {
        const picme::FaceBox &face = faces[i];
        // 创建 landmarks FloatArray
        jfloatArray landmarksArray = env->NewFloatArray(10);
        if (landmarksArray) {
            env->SetFloatArrayRegion(landmarksArray, 0, 10, face.landmarks);
        }

        jobject faceBox = env->NewObject(faceBoxClass, constructor,
            face.x1, face.y1, face.x2, face.y2, face.confidence, landmarksArray);
        env->SetObjectArrayElement(result, static_cast<jsize>(i), faceBox);
        env->DeleteLocalRef(faceBox);
        if (landmarksArray) {
            env->DeleteLocalRef(landmarksArray);
        }
    }

    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_mamba_picme_beauty_internal_facedetect_mnn_MnnFaceDetector_nativeDetectRetinaFaceFromNv21(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jobject nv21Data,       // DirectByteBuffer — 紧凑 NV21 (Y + 交错 VU)
        jint width,
        jint height,
        jint rotationDegrees,  // 旋转角度 (0/90/180/270)
        jfloat confidenceThreshold,
        jfloat nmsThreshold,
        jfloatArray outResult) {  // 预分配 [15 floats]
    auto *detector = reinterpret_cast<picme::MnnFaceDetector *>(handle);
    if (!detector) {
        return JNI_FALSE;
    }

    unsigned char *data = static_cast<unsigned char *>(env->GetDirectBufferAddress(nv21Data));
    if (!data) {
        LOGE("nativeDetectRetinaFaceFromNv21: GetDirectBufferAddress returned null");
        return JNI_FALSE;
    }

    size_t dataSize = env->GetDirectBufferCapacity(nv21Data);
    size_t expectedSize = static_cast<size_t>(width * height * 3 / 2);
    if (dataSize < expectedSize) {
        LOGE("nativeDetectRetinaFaceFromNv21: buffer too small: %zu < %zu", dataSize, expectedSize);
        return JNI_FALSE;
    }

    std::vector<picme::FaceBox> faces = detector->detectRetinaFaceFromNv21(
            data, width, height, rotationDegrees, confidenceThreshold, nmsThreshold);

    if (faces.empty()) {
        return JNI_FALSE;
    }

    const picme::FaceBox *selectedFace = &faces[0];
    float maxScore = faces[0].confidence * faces[0].area();
    for (size_t i = 1; i < faces.size(); i++) {
        float score = faces[i].confidence * faces[i].area();
        if (score > maxScore) {
            maxScore = score;
            selectedFace = &faces[i];
        }
    }

    jfloat output[15];
    output[0] = selectedFace->x1;
    output[1] = selectedFace->y1;
    output[2] = selectedFace->x2;
    output[3] = selectedFace->y2;
    output[4] = selectedFace->confidence;
    for (int i = 0; i < 10; i++) {
        output[5 + i] = selectedFace->landmarks[i];
    }

    env->SetFloatArrayRegion(outResult, 0, 15, output);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_mamba_picme_beauty_internal_facedetect_mnn_MnnFaceDetector_nativeDetectFromNv21(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jobject nv21Data,       // DirectByteBuffer
        jint width,
        jint height,
        jint rotationDegrees,  // 旋转角度 (0/90/180/270)
        jfloatArray outResult) {
    auto *detector = reinterpret_cast<picme::MnnFaceDetector *>(handle);
    if (!detector) {
        return 0;
    }

    unsigned char *data = static_cast<unsigned char *>(env->GetDirectBufferAddress(nv21Data));
    if (!data) {
        LOGE("nativeDetectFromNv21: GetDirectBufferAddress returned null");
        return 0;
    }

    const std::vector<float>& result = detector->detectFromNv21(data, width, height, rotationDegrees);
    if (result.empty()) {
        return 0;
    }

    jsize maxSize = env->GetArrayLength(outResult);
    jsize copySize = static_cast<jsize>(std::min(result.size(), static_cast<size_t>(maxSize)));
    env->SetFloatArrayRegion(outResult, 0, copySize, result.data());
    return copySize;
}

JNIEXPORT jint JNICALL
Java_com_mamba_picme_beauty_internal_facedetect_mnn_MnnFaceDetector_nativeDetectLandmarksFromNv21(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jobject nv21Data,
        jint nv21Width,
        jint nv21Height,
        jint rotationDegrees,  // 旋转角度 (0/90/180/270)
        jint roiLeft,
        jint roiTop,
        jint roiRight,
        jint roiBottom,
        jfloatArray outResult) {
    auto *detector = reinterpret_cast<picme::MnnFaceDetector *>(handle);
    if (!detector) {
        return 0;
    }

    unsigned char *data = static_cast<unsigned char *>(env->GetDirectBufferAddress(nv21Data));
    if (!data) {
        LOGE("nativeDetectLandmarksFromNv21: GetDirectBufferAddress returned null");
        return 0;
    }

    const std::vector<float>& result = detector->detectFromNv21(
        data, nv21Width, nv21Height, rotationDegrees,
        roiLeft, roiTop, roiRight, roiBottom);
    if (result.empty()) {
        return 0;
    }

    jsize maxSize = env->GetArrayLength(outResult);
    jsize copySize = static_cast<jsize>(std::min(result.size(), static_cast<size_t>(maxSize)));
    env->SetFloatArrayRegion(outResult, 0, copySize, result.data());
    return copySize;
}

JNIEXPORT jlong JNICALL
Java_com_mamba_picme_beauty_internal_facedetect_mnn_MnnFaceEmbedder_nativeCreate(
        JNIEnv *env,
        jclass clazz,
        jstring modelPath,
        jint inputSize,
        jint embeddingDim,
        jboolean useGpu,
        jstring inputName,
        jstring outputName) {

    const char *modelCStr = env->GetStringUTFChars(modelPath, nullptr);
    const char *inputCStr = env->GetStringUTFChars(inputName, nullptr);
    const char *outputCStr = env->GetStringUTFChars(outputName, nullptr);

    auto *embedder = new picme::MnnFaceEmbedder();
    bool success = embedder->load(
        std::string(modelCStr),
        static_cast<int>(inputSize),
        static_cast<int>(embeddingDim),
        std::string(inputCStr),
        std::string(outputCStr),
        static_cast<bool>(useGpu)
    );

    env->ReleaseStringUTFChars(modelPath, modelCStr);
    env->ReleaseStringUTFChars(inputName, inputCStr);
    env->ReleaseStringUTFChars(outputName, outputCStr);

    if (!success) {
        delete embedder;
        LOGE("Failed to load MNN face embedder");
        return 0;
    }

    LOGI("MnnFaceEmbedder created: inputSize=%d, embeddingDim=%d, useGpu=%d",
         inputSize, embeddingDim, useGpu);
    return reinterpret_cast<jlong>(embedder);
}

JNIEXPORT void JNICALL
Java_com_mamba_picme_beauty_internal_facedetect_mnn_MnnFaceEmbedder_nativeDestroy(
        JNIEnv *env,
        jclass clazz,
        jlong handle) {
    auto *embedder = reinterpret_cast<picme::MnnFaceEmbedder *>(handle);
    delete embedder;
    LOGD("MnnFaceEmbedder destroyed");
}

JNIEXPORT jint JNICALL
Java_com_mamba_picme_beauty_internal_facedetect_mnn_MnnFaceEmbedder_nativeExtract(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jobject imageData,      // DirectByteBuffer RGB
        jint width,
        jint height,
        jint channels,
        jfloatArray outResult) {  // 预分配结果缓冲区 [embeddingDim]

    auto *embedder = reinterpret_cast<picme::MnnFaceEmbedder *>(handle);
    if (!embedder) {
        return 0;
    }

    unsigned char *data = static_cast<unsigned char *>(env->GetDirectBufferAddress(imageData));
    if (!data) {
        LOGE("nativeExtract: GetDirectBufferAddress returned null");
        return 0;
    }

    const std::vector<float>& result = embedder->extract(data, width, height, channels);
    if (result.empty()) {
        return 0;
    }

    jsize maxSize = env->GetArrayLength(outResult);
    jsize copySize = static_cast<jsize>(std::min(result.size(), static_cast<size_t>(maxSize)));
    env->SetFloatArrayRegion(outResult, 0, copySize, result.data());
    return copySize;
}

} // extern "C"
