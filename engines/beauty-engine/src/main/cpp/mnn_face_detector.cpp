#include "mnn_face_detector.h"
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <fstream>

#define LOG_TAG "PoLang:MnnFaceDetect"
#define LOGD(...) do { if (picme::MnnFaceDetector::isLogEnabled()) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__); } while(0)
#define LOGI(...) do { if (picme::MnnFaceDetector::isLogEnabled()) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__); } while(0)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#if defined(NDEBUG)
#define LOGD_VERBOSE(...) do {} while(0)
#else
#define LOGD_VERBOSE(...) LOGD(__VA_ARGS__)
#endif

namespace picme {

namespace {
constexpr uint32_t kMatrixLogInterval = 30U;
constexpr uint32_t kClampLogInterval = 30U;
std::atomic<uint32_t> gMatrixLogCounter{0U};
std::atomic<uint32_t> gClampLogCounter{0U};

inline bool shouldLogSample(std::atomic<uint32_t>& counter, uint32_t interval) {
    if (interval <= 1U) {
        return true;
    }
    return (counter.fetch_add(1, std::memory_order_relaxed) % interval) == 0U;
}
} // namespace

bool MnnFaceDetector::logEnabled_ = true;

MnnFaceDetector::MnnFaceDetector()
    : session_(nullptr), inputTensor_(nullptr), inputSize_(0), useGpu_(false), loaded_(false), hasBuiltInNormalization_(false) {
}

MnnFaceDetector::~MnnFaceDetector() {
    release();
}

bool MnnFaceDetector::load(const std::string &modelPath,
                           int inputSize,
                           bool useGpu,
                           const std::string &inputName,
                           const std::vector<std::string> &outputNames) {
    release(RELEASE_ALL);

    inputSize_ = inputSize;
    useGpu_ = useGpu;
    inputName_ = inputName;
    outputNames_ = outputNames;
    modelPath_ = modelPath;
    modelBufferReleased_ = false;

    // 创建 MNN 解释器
    interpreter_.reset(MNN::Interpreter::createFromFile(modelPath.c_str()));
    if (!interpreter_) {
        LOGE("Failed to create MNN interpreter from: %s", modelPath.c_str());
        return false;
    }

    LOGI("MNN model loaded: %s", modelPath.c_str());

    if (!createSession()) {
        return false;
    }
    
    // 配置图像预处理（用于 BGR/RGB 归一化）
    MNN::CV::ImageProcess::Config pretreatConfig;
    pretreatConfig.sourceFormat = MNN::CV::ImageFormat::RGB;
    pretreatConfig.destFormat = MNN::CV::ImageFormat::RGB;
    // RetinaFace 和 2D106 都使用 mean=127.5, std=128.0 (即 /128.0 - 1.0)
    pretreatConfig.mean[0] = 127.5f;
    pretreatConfig.mean[1] = 127.5f;
    pretreatConfig.mean[2] = 127.5f;
    pretreatConfig.normal[0] = 0.0078125f; // 1/128
    pretreatConfig.normal[1] = 0.0078125f;
    pretreatConfig.normal[2] = 0.0078125f;

    pretreat_.reset(MNN::CV::ImageProcess::create(pretreatConfig));

    // [Zero-Copy] 创建 NV21 YUV 预处理配置
    // YUV_NV21 → RGB，同时完成 resize + 归一化，替代手动 pixel loop
    MNN::CV::ImageProcess::Config nv21Config;
    nv21Config.sourceFormat = MNN::CV::YUV_NV21;
    nv21Config.destFormat = MNN::CV::RGB;
    nv21Config.mean[0] = 127.5f;
    nv21Config.mean[1] = 127.5f;
    nv21Config.mean[2] = 127.5f;
    nv21Config.normal[0] = 0.0078125f; // 1/128
    nv21Config.normal[1] = 0.0078125f;
    nv21Config.normal[2] = 0.0078125f;
    pretreatNv21_.reset(MNN::CV::ImageProcess::create(nv21Config));

    // [关键修复] 检测模型是否包含内置归一化节点
    // 2d106det.mnn 包含 _minusscalar0 和 _mulscalar0，有内置归一化
    // det_10g.mnn 没有这些节点，需要外部归一化
    std::ifstream modelCheck(modelPath.c_str(), std::ios::binary);
    if (modelCheck.is_open()) {
        std::string modelContent((std::istreambuf_iterator<char>(modelCheck)),
                                  std::istreambuf_iterator<char>());
        hasBuiltInNormalization_ = (modelContent.find("_minusscalar0") != std::string::npos) &&
                                   (modelContent.find("_mulscalar0") != std::string::npos);
        modelCheck.close();
        LOGI("Model normalization check: hasBuiltIn=%s", hasBuiltInNormalization_ ? "true" : "false");
    }

    loaded_ = true;
    LOGI("MNN detector ready: inputSize=%d, useGpu=%s, outputs=%zu, builtInNorm=%s",
         inputSize_, useGpu_ ? "true" : "false", outputTensors_.size(),
         hasBuiltInNormalization_ ? "true" : "false");
    return true;
}

std::vector<float> MnnFaceDetector::detect(const unsigned char *imageData,
                                           int width,
                                           int height,
                                           int channels) {
    if (!loaded_ || !inputTensor_ || outputTensors_.empty()) {
        return {};
    }

    // [关键修复] 使用与输入张量相同的维度类型，避免 copyFromHostTensor 时数据重排
    MNN::Tensor::DimensionType inputDimType = inputTensor_->getDimensionType();
    MNN::Tensor tmpInput(inputTensor_, inputDimType);
    float *inputData = tmpInput.host<float>();
    int totalPixels = inputSize_ * inputSize_;

    // [关键修复] 根据模型是否有内置归一化选择归一化方式
    // 2d106det.mnn 有内置归一化 (_minusscalar0, _mulscalar0) -> 直接传递原始像素值
    //   ONNX 版本也检测到内置归一化 (mean=0, std=1)，直接传递 0-255
    // det_10g.mnn 无内置归一化 -> 做 (x-127.5)/128.0
    float normMean = hasBuiltInNormalization_ ? 0.0f : 127.5f;
    float normStd = hasBuiltInNormalization_ ? 1.0f : 128.0f;

    bool isNCHW = (inputDimType == MNN::Tensor::DimensionType::CAFFE);

    if (width == inputSize_ && height == inputSize_) {
        // [关键修复] 根据维度类型选择正确的数据布局
        // CAFFE (NCHW): inputData[c * totalPixels + i]
        // TENSORFLOW (NHWC): inputData[i * 3 + c]
        for (int i = 0; i < totalPixels; i++) {
            for (int c = 0; c < 3; c++) {
                float val = imageData[i * 3 + c];
                if (isNCHW) {
                    inputData[c * totalPixels + i] = (val - normMean) / normStd;
                } else {
                    inputData[i * 3 + c] = (val - normMean) / normStd;
                }
            }
        }
    } else {
        // [关键修复] 使用与 ONNX 完全相同的 letterbox 预处理
        // 1. 计算保持宽高比的缩放比例
        float scale = std::min((float)inputSize_ / width, (float)inputSize_ / height);
        int scaledW = static_cast<int>(width * scale);
        int scaledH = static_cast<int>(height * scale);
        int padLeft = (inputSize_ - scaledW) / 2;
        int padTop = (inputSize_ - scaledH) / 2;

        // 2. 填充黑色背景
        float blackValue = (0.0f - normMean) / normStd;
        for (int i = 0; i < totalPixels * 3; i++) {
            inputData[i] = blackValue;
        }

        // 3. 手动进行 letterbox resize + normalize
        bool isNCHW = (inputDimType == MNN::Tensor::DimensionType::CAFFE);
        for (int y = 0; y < scaledH; y++) {
            for (int x = 0; x < scaledW; x++) {
                float srcX = (x + 0.5f) * width / scaledW - 0.5f;
                float srcY = (y + 0.5f) * height / scaledH - 0.5f;

                int srcX0 = static_cast<int>(srcX);
                int srcY0 = static_cast<int>(srcY);
                int srcX1 = std::min(srcX0 + 1, width - 1);
                int srcY1 = std::min(srcY0 + 1, height - 1);

                float fx = srcX - srcX0;
                float fy = srcY - srcY0;

                int dstX = padLeft + x;
                int dstY = padTop + y;
                int dstIdx = dstY * inputSize_ + dstX;

                for (int c = 0; c < 3; c++) {
                    int srcIdx00 = (srcY0 * width + srcX0) * 3 + c;
                    int srcIdx01 = (srcY0 * width + srcX1) * 3 + c;
                    int srcIdx10 = (srcY1 * width + srcX0) * 3 + c;
                    int srcIdx11 = (srcY1 * width + srcX1) * 3 + c;

                    float val00 = imageData[srcIdx00];
                    float val01 = imageData[srcIdx01];
                    float val10 = imageData[srcIdx10];
                    float val11 = imageData[srcIdx11];

                    float val = val00 * (1-fx) * (1-fy) +
                               val01 * fx * (1-fy) +
                               val10 * (1-fx) * fy +
                               val11 * fx * fy;

                    if (isNCHW) {
                        inputData[c * totalPixels + dstIdx] = (val - normMean) / normStd;
                    } else {
                        inputData[dstIdx * 3 + c] = (val - normMean) / normStd;
                    }
                }
            }
        }
    }

    // 拷贝到会话输入
    inputTensor_->copyFromHostTensor(&tmpInput);

    // 运行推理
    interpreter_->runSession(session_);

    // 获取输出
    MNN::Tensor *output = outputTensors_[0];
    // [关键修复] 使用与输出张量相同的维度类型
    MNN::Tensor::DimensionType outputDimType = output->getDimensionType();
    MNN::Tensor tmpOutput(output, outputDimType);
    output->copyToHostTensor(&tmpOutput);

    // 转为 vector 使用成员缓冲区复用
    int elementSize = tmpOutput.elementSize();
    resultBuffer_.clear();
    resultBuffer_.reserve(elementSize);
    const float *data = tmpOutput.host<float>();

    for (int i = 0; i < elementSize; i++) {
        resultBuffer_.push_back(data[i]);
    }

    return resultBuffer_;
}

std::vector<FaceBox> MnnFaceDetector::detectRetinaFace(const unsigned char *imageData,
                                                       int width,
                                                       int height,
                                                       int channels,
                                                       float confidenceThreshold,
                                                       float nmsThreshold) {
    if (!loaded_ || !inputTensor_) {
        LOGE("Detector not ready");
        return {};
    }

    auto totalStart = std::chrono::high_resolution_clock::now();

    // [关键修复] 如果输入已经是 inputSize_ x inputSize_，直接复制并归一化
    // 因为 Kotlin 层已经做了 letterbox 预处理
    // [关键修复] 使用与输入张量相同的维度类型
    MNN::Tensor::DimensionType inputDimType = inputTensor_->getDimensionType();
    MNN::Tensor tmpInput(inputTensor_, inputDimType);
    float *inputData = tmpInput.host<float>();
    int totalPixels = inputSize_ * inputSize_;
    bool isNCHW = (inputDimType == MNN::Tensor::DimensionType::CAFFE);

    // [关键修复] 根据模型是否有内置归一化选择归一化方式
    float normMean = hasBuiltInNormalization_ ? 0.0f : 127.5f;
    float normStd = hasBuiltInNormalization_ ? 1.0f : 128.0f;

    if (width == inputSize_ && height == inputSize_) {
        // 直接复制并归一化，无需 letterbox
        for (int i = 0; i < totalPixels; i++) {
            for (int c = 0; c < 3; c++) {
                float val = imageData[i * 3 + c];
                if (isNCHW) {
                    inputData[c * totalPixels + i] = (val - normMean) / normStd;
                } else {
                    inputData[i * 3 + c] = (val - normMean) / normStd;
                }
            }
        }
    } else {
        // [关键修复] 使用与 ONNX 完全相同的 letterbox 预处理
        // 1. 计算保持宽高比的缩放比例
        float scale = std::min((float)inputSize_ / width, (float)inputSize_ / height);
        int scaledW = static_cast<int>(width * scale);
        int scaledH = static_cast<int>(height * scale);
        int padLeft = (inputSize_ - scaledW) / 2;
        int padTop = (inputSize_ - scaledH) / 2;

        // 2. 填充黑色背景
        float blackValue = (0.0f - normMean) / normStd;
        for (int i = 0; i < totalPixels * 3; i++) {
            inputData[i] = blackValue;
        }

        // 3. 手动进行 letterbox resize + normalize
        for (int y = 0; y < scaledH; y++) {
            for (int x = 0; x < scaledW; x++) {
                float srcX = (x + 0.5f) * width / scaledW - 0.5f;
                float srcY = (y + 0.5f) * height / scaledH - 0.5f;

                int srcX0 = static_cast<int>(srcX);
                int srcY0 = static_cast<int>(srcY);
                int srcX1 = std::min(srcX0 + 1, width - 1);
                int srcY1 = std::min(srcY0 + 1, height - 1);

                float fx = srcX - srcX0;
                float fy = srcY - srcY0;

                int dstX = padLeft + x;
                int dstY = padTop + y;
                int dstIdx = dstY * inputSize_ + dstX;

                for (int c = 0; c < 3; c++) {
                    int srcIdx00 = (srcY0 * width + srcX0) * 3 + c;
                    int srcIdx01 = (srcY0 * width + srcX1) * 3 + c;
                    int srcIdx10 = (srcY1 * width + srcX0) * 3 + c;
                    int srcIdx11 = (srcY1 * width + srcX1) * 3 + c;

                    float val00 = imageData[srcIdx00];
                    float val01 = imageData[srcIdx01];
                    float val10 = imageData[srcIdx10];
                    float val11 = imageData[srcIdx11];

                    float val = val00 * (1-fx) * (1-fy) +
                               val01 * fx * (1-fy) +
                               val10 * (1-fx) * fy +
                               val11 * fx * fy;

                    if (isNCHW) {
                        inputData[c * totalPixels + dstIdx] = (val - normMean) / normStd;
                    } else {
                        inputData[dstIdx * 3 + c] = (val - normMean) / normStd;
                    }
                }
            }
        }
    }

    // 拷贝到会话输入
    auto tCopyStart = std::chrono::high_resolution_clock::now();
    inputTensor_->copyFromHostTensor(&tmpInput);
    auto tCopyEnd = std::chrono::high_resolution_clock::now();
    auto copyMs = std::chrono::duration_cast<std::chrono::milliseconds>(tCopyEnd - tCopyStart).count();

    // 推理
    auto tInferStart = std::chrono::high_resolution_clock::now();
    interpreter_->runSession(session_);
    auto tInferEnd = std::chrono::high_resolution_clock::now();
    auto inferMs = std::chrono::duration_cast<std::chrono::milliseconds>(tInferEnd - tInferStart).count();

    // RetinaFace 输出：9 个张量
    // [关键修复] MNN 转换后的输出顺序是按类型分组，不是按尺度分组：
    // score  outputs: 448 (stride=8, 3200), 471 (stride=16, 800), 494 (stride=32, 200)
    // bbox   outputs: 451 (stride=8, 3200*4), 474 (stride=16, 800*4), 497 (stride=32, 200*4)
    // landmark outputs: 454 (stride=8, 3200*10), 477 (stride=16, 800*10), 500 (stride=32, 200*10)

    std::vector<FaceBox> allFaces;

    // 使用动态输出名称（从 outputNames_ 获取）
    // outputNames_[0-2]: stride 8, 16, 32 scores
    // outputNames_[3-5]: stride 8, 16, 32 bboxes
    // outputNames_[6-8]: stride 8, 16, 32 landmarks

    // 尺度 1: stride=8
    {
        MNN::Tensor *scoreOut = outputNames_.size() > 0 ? interpreter_->getSessionOutput(session_, outputNames_[0].c_str()) : nullptr;
        MNN::Tensor *bboxOut = outputNames_.size() > 3 ? interpreter_->getSessionOutput(session_, outputNames_[3].c_str()) : nullptr;
        MNN::Tensor *landmarkOut = outputNames_.size() > 6 ? interpreter_->getSessionOutput(session_, outputNames_[6].c_str()) : nullptr;
        if (!scoreOut || !bboxOut || !landmarkOut) {
            LOGE("Failed to get scale 1 outputs");
        } else {
            int featH = scoreOut->height();
            int featW = scoreOut->width();
            int featureSize;
            int scoreChannels = scoreOut->channel();
            if (featH > 1 && featW > 1) {
                featureSize = featH;
                scoreChannels = scoreOut->channel();
            } else {
                int totalElements = scoreOut->elementSize();
                if (scoreOut->batch() > 1 && scoreOut->channel() >= 1) {
                    scoreChannels = scoreOut->channel();
                    // [关键修复] 扁平化输出: [anchors*2, channels, 1, 1] 或 [anchors, channels, 1, 1]
                    // 对于 RetinaFace，anchors = featureSize * featureSize * numAnchorsPerLocation
                    // 每个位置有 2 个 anchor，所以 batch = featureSize^2 * 2 (如果 channel=1)
                    // 或 batch = featureSize^2 (如果 channel=2，包含 bg+face)
                    if (scoreChannels == 1) {
                        // 只有 face score: batch = featureSize^2 * 2
                        featureSize = static_cast<int>(std::sqrt(scoreOut->batch() / 2.0f));
                    } else {
                        // 包含 bg+face: batch = featureSize^2
                        featureSize = static_cast<int>(std::sqrt(scoreOut->batch()));
                    }
                } else {
                    scoreChannels = 2;
                    featureSize = static_cast<int>(std::sqrt(totalElements / 2.0f));
                }
                if (featureSize <= 0) featureSize = inputSize_ / 8;
            }
            LOGD("Scale 1 (stride=8): featureSize=%d, scoreChannels=%d, scoreShape=[%d,%d,%d,%d]",
                 featureSize, scoreChannels, scoreOut->batch(), scoreOut->channel(),
                 scoreOut->height(), scoreOut->width());

            MNN::Tensor scoreTensor(scoreOut, MNN::Tensor::DimensionType::CAFFE);
            MNN::Tensor bboxTensor(bboxOut, MNN::Tensor::DimensionType::CAFFE);
            MNN::Tensor landmarkTensor(landmarkOut, MNN::Tensor::DimensionType::CAFFE);
            scoreOut->copyToHostTensor(&scoreTensor);
            bboxOut->copyToHostTensor(&bboxTensor);
            landmarkOut->copyToHostTensor(&landmarkTensor);

            const float *scoreData = scoreTensor.host<float>();
            const float *bboxData = bboxTensor.host<float>();
            const float *landmarkData = landmarkTensor.host<float>();
            if (!scoreData || !bboxData || !landmarkData) {
                LOGE("Scale 1 host data is null, skipping");
            } else {
                // [调试] 打印该 scale 的最高 score
                int totalAnchors = featureSize * featureSize * 2;
                float maxScore = 0.0f;
                int maxScoreIdx = -1;
                for (int i = 0; i < totalAnchors; i++) {
                    float s = scoreChannels == 1 ? scoreData[i] : scoreData[totalAnchors + i];
                    if (s > maxScore) {
                        maxScore = s;
                        maxScoreIdx = i;
                    }
                }
                if (maxScoreIdx >= 0) {
                    float dx = bboxData[maxScoreIdx * 4 + 0];
                    float dy = bboxData[maxScoreIdx * 4 + 1];
                    float dw = bboxData[maxScoreIdx * 4 + 2];
                    float dh = bboxData[maxScoreIdx * 4 + 3];
                    int y = maxScoreIdx / (featureSize * 2);
                    int x = (maxScoreIdx % (featureSize * 2)) / 2;
                    float cx = (x + 0.5f) * 8;
                    float cy = (y + 0.5f) * 8;
                    LOGD("  Scale 1 max score: idx=%d, score=%.4f, cx=%.1f, cy=%.1f, dx=%.3f, dy=%.3f, dw=%.3f, dh=%.3f",
                         maxScoreIdx, maxScore, cx, cy, dx, dy, dw, dh);
                }
                processRetinaFaceOutput(scoreData, bboxData, landmarkData, featureSize, 8, scoreChannels, allFaces, confidenceThreshold);
            }
        }
    }

    // 尺度 2: stride=16
    {
        MNN::Tensor *scoreOut = outputNames_.size() > 1 ? interpreter_->getSessionOutput(session_, outputNames_[1].c_str()) : nullptr;
        MNN::Tensor *bboxOut = outputNames_.size() > 4 ? interpreter_->getSessionOutput(session_, outputNames_[4].c_str()) : nullptr;
        MNN::Tensor *landmarkOut = outputNames_.size() > 7 ? interpreter_->getSessionOutput(session_, outputNames_[7].c_str()) : nullptr;
        if (!scoreOut || !bboxOut || !landmarkOut) {
            LOGE("Failed to get scale 2 outputs");
        } else {
            int featH = scoreOut->height();
            int featW = scoreOut->width();
            int featureSize;
            int scoreChannels = scoreOut->channel();
            if (featH > 1 && featW > 1) {
                featureSize = featH;
                scoreChannels = scoreOut->channel();
            } else {
                int totalElements = scoreOut->elementSize();
                if (scoreOut->batch() > 1 && scoreOut->channel() >= 1) {
                    scoreChannels = scoreOut->channel();
                    if (scoreChannels == 1) {
                        featureSize = static_cast<int>(std::sqrt(scoreOut->batch() / 2.0f));
                    } else {
                        featureSize = static_cast<int>(std::sqrt(scoreOut->batch()));
                    }
                } else {
                    scoreChannels = 2;
                    featureSize = static_cast<int>(std::sqrt(totalElements / 2.0f));
                }
                if (featureSize <= 0) featureSize = inputSize_ / 16;
            }
            LOGD("Scale 2 (stride=16): featureSize=%d, scoreChannels=%d, scoreShape=[%d,%d,%d,%d]",
                 featureSize, scoreChannels, scoreOut->batch(), scoreOut->channel(),
                 scoreOut->height(), scoreOut->width());

            MNN::Tensor scoreTensor(scoreOut, MNN::Tensor::DimensionType::CAFFE);
            MNN::Tensor bboxTensor(bboxOut, MNN::Tensor::DimensionType::CAFFE);
            MNN::Tensor landmarkTensor(landmarkOut, MNN::Tensor::DimensionType::CAFFE);
            scoreOut->copyToHostTensor(&scoreTensor);
            bboxOut->copyToHostTensor(&bboxTensor);
            landmarkOut->copyToHostTensor(&landmarkTensor);

            const float *scoreData = scoreTensor.host<float>();
            const float *bboxData = bboxTensor.host<float>();
            const float *landmarkData = landmarkTensor.host<float>();
            if (!scoreData || !bboxData || !landmarkData) {
                LOGE("Scale 2 host data is null, skipping");
            } else {
                // [调试] 打印该 scale 的最高 score
                int totalAnchors = featureSize * featureSize * 2;
                float maxScore = 0.0f;
                int maxScoreIdx = -1;
                for (int i = 0; i < totalAnchors; i++) {
                    float s = scoreChannels == 1 ? scoreData[i] : scoreData[totalAnchors + i];
                    if (s > maxScore) {
                        maxScore = s;
                        maxScoreIdx = i;
                    }
                }
                if (maxScoreIdx >= 0) {
                    float dx = bboxData[maxScoreIdx * 4 + 0];
                    float dy = bboxData[maxScoreIdx * 4 + 1];
                    float dw = bboxData[maxScoreIdx * 4 + 2];
                    float dh = bboxData[maxScoreIdx * 4 + 3];
                    int y = maxScoreIdx / (featureSize * 2);
                    int x = (maxScoreIdx % (featureSize * 2)) / 2;
                    float cx = (x + 0.5f) * 16;
                    float cy = (y + 0.5f) * 16;
                    LOGD("  Scale 2 max score: idx=%d, score=%.4f, cx=%.1f, cy=%.1f, dx=%.3f, dy=%.3f, dw=%.3f, dh=%.3f",
                         maxScoreIdx, maxScore, cx, cy, dx, dy, dw, dh);
                }
                processRetinaFaceOutput(scoreData, bboxData, landmarkData, featureSize, 16, scoreChannels, allFaces, confidenceThreshold);
            }
        }
    }

    // 尺度 3: stride=32
    {
        MNN::Tensor *scoreOut = outputNames_.size() > 2 ? interpreter_->getSessionOutput(session_, outputNames_[2].c_str()) : nullptr;
        MNN::Tensor *bboxOut = outputNames_.size() > 5 ? interpreter_->getSessionOutput(session_, outputNames_[5].c_str()) : nullptr;
        MNN::Tensor *landmarkOut = outputNames_.size() > 8 ? interpreter_->getSessionOutput(session_, outputNames_[8].c_str()) : nullptr;
        if (!scoreOut || !bboxOut || !landmarkOut) {
            LOGE("Failed to get scale 3 outputs");
        } else {
            int featH = scoreOut->height();
            int featW = scoreOut->width();
            int featureSize;
            int scoreChannels = scoreOut->channel();
            if (featH > 1 && featW > 1) {
                featureSize = featH;
                scoreChannels = scoreOut->channel();
            } else {
                int totalElements = scoreOut->elementSize();
                if (scoreOut->batch() > 1 && scoreOut->channel() >= 1) {
                    scoreChannels = scoreOut->channel();
                    if (scoreChannels == 1) {
                        featureSize = static_cast<int>(std::sqrt(scoreOut->batch() / 2.0f));
                    } else {
                        featureSize = static_cast<int>(std::sqrt(scoreOut->batch()));
                    }
                } else {
                    scoreChannels = 2;
                    featureSize = static_cast<int>(std::sqrt(totalElements / 2.0f));
                }
                if (featureSize <= 0) featureSize = inputSize_ / 32;
            }
            LOGD("Scale 3 (stride=32): featureSize=%d, scoreChannels=%d, scoreShape=[%d,%d,%d,%d]",
                 featureSize, scoreChannels, scoreOut->batch(), scoreOut->channel(),
                 scoreOut->height(), scoreOut->width());

            MNN::Tensor scoreTensor(scoreOut, MNN::Tensor::DimensionType::CAFFE);
            MNN::Tensor bboxTensor(bboxOut, MNN::Tensor::DimensionType::CAFFE);
            MNN::Tensor landmarkTensor(landmarkOut, MNN::Tensor::DimensionType::CAFFE);
            scoreOut->copyToHostTensor(&scoreTensor);
            bboxOut->copyToHostTensor(&bboxTensor);
            landmarkOut->copyToHostTensor(&landmarkTensor);

            const float *scoreData = scoreTensor.host<float>();
            const float *bboxData = bboxTensor.host<float>();
            const float *landmarkData = landmarkTensor.host<float>();
            if (!scoreData || !bboxData || !landmarkData) {
                LOGE("Scale 3 host data is null, skipping");
            } else {
                // [调试] 打印该 scale 的最高 score
                int totalAnchors = featureSize * featureSize * 2;
                float maxScore = 0.0f;
                int maxScoreIdx = -1;
                for (int i = 0; i < totalAnchors; i++) {
                    float s = scoreChannels == 1 ? scoreData[i] : scoreData[totalAnchors + i];
                    if (s > maxScore) {
                        maxScore = s;
                        maxScoreIdx = i;
                    }
                }
                if (maxScoreIdx >= 0) {
                    float dx = bboxData[maxScoreIdx * 4 + 0];
                    float dy = bboxData[maxScoreIdx * 4 + 1];
                    float dw = bboxData[maxScoreIdx * 4 + 2];
                    float dh = bboxData[maxScoreIdx * 4 + 3];
                    int y = maxScoreIdx / (featureSize * 2);
                    int x = (maxScoreIdx % (featureSize * 2)) / 2;
                    float cx = (x + 0.5f) * 32;
                    float cy = (y + 0.5f) * 32;
                    LOGD("  Scale 3 max score: idx=%d, score=%.4f, cx=%.1f, cy=%.1f, dx=%.3f, dy=%.3f, dw=%.3f, dh=%.3f",
                         maxScoreIdx, maxScore, cx, cy, dx, dy, dw, dh);
                }
                processRetinaFaceOutput(scoreData, bboxData, landmarkData, featureSize, 32, scoreChannels, allFaces, confidenceThreshold);
            }
        }
    }

    LOGD("RetinaFace raw detections: %zu", allFaces.size());

    // NMS
    auto tNmsStart = std::chrono::high_resolution_clock::now();
    auto result = applyNMS(allFaces, nmsThreshold);
    auto tNmsEnd = std::chrono::high_resolution_clock::now();
    auto nmsMs = std::chrono::duration_cast<std::chrono::milliseconds>(tNmsEnd - tNmsStart).count();

    auto totalEnd = std::chrono::high_resolution_clock::now();
    auto totalMs = std::chrono::duration_cast<std::chrono::milliseconds>(totalEnd - totalStart).count();

    LOGI("[Perf] MNN RetinaFace DONE: total=%ldms (preprocess+fill=%ldms, copyToDevice=%ldms, infer=%ldms, postprocess=%ldms, nms=%ldms), faces=%zu, backend=%s",
         totalMs, totalMs - copyMs - inferMs - nmsMs, copyMs, inferMs, totalMs - copyMs - inferMs - nmsMs - (totalMs - copyMs - inferMs - nmsMs), nmsMs, result.size(),
         useGpu_ ? "OpenCL" : "CPU");

    LOGD("RetinaFace after NMS: %zu", result.size());
    return result;
}

MNN::CV::Matrix MnnFaceDetector::buildNv21TransformMatrix(int srcWidth, int srcHeight,
                                                            int rotationDegrees,
                                                            int roiLeft, int roiTop,
                                                            int roiRight, int roiBottom) {
    // MNN Matrix 是「目标→源」的仿射变换。
    // 目标空间 = inputSize_×inputSize_（模型输入）
    // 源空间   = 原始 NV21 像素坐标 (srcWidth×srcHeight，未旋转)
    //
    // 变换链（从目标到源，每一步都是 post 组合）：
    //   1. ROI 逆变换：目标 inputSize → 旋转后的 ROI 区域
    //   2. Letterbox 逆变换：减去 padding → 旋转后全图
    //   3. 旋转逆变换：旋转后坐标 → 原始 NV21 坐标
    //
    // MNN Matrix 的 post 操作是右乘（在当前变换之后追加），
    // 但因为 Matrix 是「目标→源」方向，post 操作的语义是"先做当前变换，再做新变换"。
    // 我们按 "目标 → 中间 → 源" 的顺序组合，使用 setScale + postTranslate 构建。

    // 旋转后的逻辑尺寸
    bool needSwap = (rotationDegrees == 90 || rotationDegrees == 270);
    int rotatedW = needSwap ? srcHeight : srcWidth;
    int rotatedH = needSwap ? srcWidth : srcHeight;

    // ROI 区域（旋转后坐标），-1 表示不裁剪
    bool hasRoi = (roiLeft >= 0 && roiTop >= 0 && roiRight > roiLeft && roiBottom > roiTop);
    float regionW = hasRoi ? (float)(roiRight - roiLeft) : (float)rotatedW;
    float regionH = hasRoi ? (float)(roiBottom - roiTop) : (float)rotatedH;
    float regionLeft = hasRoi ? (float)roiLeft : 0.0f;
    float regionTop = hasRoi ? (float)roiTop : 0.0f;

    // Letterbox 缩放参数（基于旋转后的区域尺寸）
    float scale = std::min((float)inputSize_ / regionW, (float)inputSize_ / regionH);
    int scaledW = static_cast<int>(regionW * scale);
    int scaledH = static_cast<int>(regionH * scale);
    float padLeft = ((float)inputSize_ - scaledW) / 2.0f;
    float padTop = ((float)inputSize_ - scaledH) / 2.0f;

    // Step 1: 目标像素 (inputSize 空间) → 旋转后 ROI 区域像素
    // dst → rotatedROI: x = (dstX - padLeft) / scale + regionLeft
    //                     y = (dstY - padTop) / scale + regionTop
    // 用 MNN Matrix 表示（目标→源）:
    //   先 setScale(1/scale, 1/scale)  -- 缩放逆
    //   再 postTranslate(-padLeft/scale + regionLeft, -padTop/scale + regionTop)  -- 平移逆+ROI偏移
    MNN::CV::Matrix trans;
    float txOffset = -padLeft / scale + regionLeft;
    float tyOffset = -padTop / scale + regionTop;

    // Step 2: 旋转后坐标 → 原始 NV21 坐标
    // MNN Matrix setScale + postTranslate 的组合等价于：
    //   srcX = dstX * scaleX + txOffset
    //   srcY = dstY * scaleY + tyOffset
    // 其中 dst 是旋转后空间，src 是 NV21 空间
    //
    // 但旋转不能简单用 scale+translate 表达，需要完整的仿射矩阵。
    // MNN::CV::Matrix 内部是 3x3 矩阵，可通过 setValues 直接设置。
    //
    // 完整变换（目标inputSize → 旋转后 → 原始NV21）：
    //   rotatedX = dstX / scale - padLeft/scale + regionLeft
    //   rotatedY = dstY / scale - padTop/scale + regionTop
    //   然后根据旋转角度映射到 NV21 坐标：
    //
    // 旋转 0°:  nv21X = rotatedX,               nv21Y = rotatedY
    // 旋转 90°: nv21X = rotatedY,               nv21Y = srcHeight - 1 - rotatedX
    // 旋转 180°: nv21X = srcWidth - 1 - rotatedX, nv21Y = srcHeight - 1 - rotatedY
    // 旋转 270°: nv21X = srcWidth - 1 - rotatedY, nv21Y = rotatedX
    //
    // 合并为一步：dst → NV21
    float invScale = 1.0f / scale;
    float rx = -padLeft / scale + regionLeft;  // rotatedX offset
    float ry = -padTop / scale + regionTop;    // rotatedY offset

    // 3x3 仿射矩阵 [a, b, tx; c, d, ty; 0, 0, 1]
    // nv21X = a * dstX + b * dstY + tx
    // nv21Y = c * dstX + d * dstY + ty
    float a, b, tx, c, d, ty;
    switch (rotationDegrees) {
        case 90:
            // nv21X = rotatedY = dstY * invScale + ry
            // nv21Y = srcHeight - 1 - rotatedX = srcHeight - 1 - (dstX * invScale + rx)
            a = 0.0f;         b = invScale;     tx = ry;
            c = -invScale;    d = 0.0f;         ty = (float)(srcHeight - 1) - rx;
            break;
        case 180:
            // nv21X = srcWidth - 1 - rotatedX = srcWidth - 1 - (dstX * invScale + rx)
            // nv21Y = srcHeight - 1 - rotatedY = srcHeight - 1 - (dstY * invScale + ry)
            a = -invScale;    b = 0.0f;         tx = (float)(srcWidth - 1) - rx;
            c = 0.0f;         d = -invScale;    ty = (float)(srcHeight - 1) - ry;
            break;
        case 270:
            // nv21X = srcWidth - 1 - rotatedY = srcWidth - 1 - (dstY * invScale + ry)
            // nv21Y = rotatedX = dstX * invScale + rx
            a = 0.0f;         b = -invScale;    tx = (float)(srcWidth - 1) - ry;
            c = invScale;     d = 0.0f;         ty = rx;
            break;
        default: // 0°
            a = invScale;     b = 0.0f;         tx = rx;
            c = 0.0f;         d = invScale;     ty = ry;
            break;
    }

    // MNN::CV::Matrix::setAll 接受 9 个参数（行优先 3x3 矩阵）：
    //   scaleX, skewX, transX, skewY, scaleY, transY, persp0, persp1, persp2
    // 对应：| a  b  tx |   | 0  0  1 |
    //       | c  d  ty | = | 0  0  1 |（仿射变换，persp=0,0,1）
    //       | 0  0   1 |   | 0  0  1 |
    trans.setAll(a, b, tx, c, d, ty, 0.0f, 0.0f, 1.0f);

    if (shouldLogSample(gMatrixLogCounter, kMatrixLogInterval)) {
        LOGD_VERBOSE("buildNv21TransformMatrix: src=%dx%d, rot=%d, roi=[%d,%d,%d,%d], "
                     "rotated=%dx%d, scale=%.4f, pad=[%.1f,%.1f], matrix=[%.4f,%.4f,%.2f,%.4f,%.4f,%.2f]",
                     srcWidth, srcHeight, rotationDegrees, roiLeft, roiTop, roiRight, roiBottom,
                     rotatedW, rotatedH, scale, padLeft, padTop, a, b, tx, c, d, ty);
    }

    return trans;
}

std::vector<FaceBox> MnnFaceDetector::detectRetinaFaceFromNv21(const unsigned char *nv21Data,
                                                                int width,
                                                                int height,
                                                                int rotationDegrees,
                                                                float confidenceThreshold,
                                                                float nmsThreshold) {
    if (!loaded_ || !inputTensor_ || !pretreatNv21_) {
        LOGE("Detector or NV21 pretreat not ready");
        return {};
    }

    auto totalStart = std::chrono::high_resolution_clock::now();
    int totalPixels = inputSize_ * inputSize_;

    // [Zero-Copy] NV21 stride = width (紧凑布局，无 padding)
    int nv21Stride = width;

    // 1. 先把 input tensor 填成归一化后的黑色
    MNN::Tensor::DimensionType inputDimType = inputTensor_->getDimensionType();
    MNN::Tensor tmpInput(inputTensor_, inputDimType);
    float *inputData = tmpInput.host<float>();
    float normMean = hasBuiltInNormalization_ ? 0.0f : 127.5f;
    float normStd = hasBuiltInNormalization_ ? 1.0f : 128.0f;
    float blackVal = (0.0f - normMean) / normStd;
    std::fill_n(inputData, tmpInput.elementSize(), blackVal);

    // 2. 构造旋转 + letterbox 变换矩阵（目标→源坐标映射）
    auto tPreStart = std::chrono::high_resolution_clock::now();
    MNN::CV::Matrix trans = buildNv21TransformMatrix(width, height, rotationDegrees);

    // 3. 调用 MNN ImageProcess::convert —— 内部做 NV21→RGB + 旋转 + resize + letterbox + 归一化
    pretreatNv21_->setMatrix(trans);
    auto result = pretreatNv21_->convert(nv21Data, width, height, nv21Stride,
                                         &tmpInput);
    auto tPreEnd = std::chrono::high_resolution_clock::now();

    if (result != MNN::NO_ERROR) {
        LOGE("NV21 ImageProcess::convert failed: error=%d", result);
        return {};
    }

    // 注意：pretreatNv21_->convert() 直接写入 inputTensor_（GPU 后端时为 device tensor）
    // 无需额外 copyFromHostTensor，因为 convert 已经处理了 device 写入
    // 但当前实现使用 tmpInput 的 host 数据，所以仍需 copyFromHostTensor
    // [关键] 如果 MNN 后端是 GPU，convert 写入的是 host tensor，需要 copyToDevice
    // 对于 CPU 后端则不需要

    // 重新构建 tmpInput（因为 convert 可能改变了 inputTensor_ 的 device 内存）
    // 实际上 pretreatNv21_->convert 写入的是 inputTensor_ 的 host 内存区域
    // 需要 copyFromHostTensor 来同步到 device（如果是 GPU 后端）
    inputTensor_->copyFromHostTensor(&tmpInput);

    auto preprocessMs = std::chrono::duration_cast<std::chrono::milliseconds>(tPreEnd - tPreStart).count();

    // 4. 推理
    auto tInferStart = std::chrono::high_resolution_clock::now();
    interpreter_->runSession(session_);
    auto tInferEnd = std::chrono::high_resolution_clock::now();
    auto inferMs = std::chrono::duration_cast<std::chrono::milliseconds>(tInferEnd - tInferStart).count();

    // 5. 输出处理（与 detectRetinaFace 相同）
    std::vector<FaceBox> allFaces;

    // Scale 1,2,3 处理（与 detectRetinaFace 相同）
    for (int scaleIdx = 0; scaleIdx < 3; scaleIdx++) {
        MNN::Tensor *scoreOut = outputNames_.size() > (size_t)scaleIdx ? interpreter_->getSessionOutput(session_, outputNames_[scaleIdx].c_str()) : nullptr;
        MNN::Tensor *bboxOut = outputNames_.size() > (size_t)(scaleIdx + 3) ? interpreter_->getSessionOutput(session_, outputNames_[scaleIdx + 3].c_str()) : nullptr;
        MNN::Tensor *landmarkOut = outputNames_.size() > (size_t)(scaleIdx + 6) ? interpreter_->getSessionOutput(session_, outputNames_[scaleIdx + 6].c_str()) : nullptr;
        if (!scoreOut || !bboxOut || !landmarkOut) {
            LOGE("Failed to get scale %d outputs", scaleIdx + 1);
            continue;
        }

        int featH = scoreOut->height(), featW = scoreOut->width();
        int featureSize;
        int scoreChannels = scoreOut->channel();
        if (featH > 1 && featW > 1) {
            featureSize = featH;
        } else {
            if (scoreOut->batch() > 1 && scoreOut->channel() >= 1) {
                scoreChannels = scoreOut->channel();
                featureSize = scoreChannels == 1 ?
                    static_cast<int>(std::sqrt(scoreOut->batch() / 2.0f)) :
                    static_cast<int>(std::sqrt(scoreOut->batch()));
            } else {
                scoreChannels = 2;
                featureSize = static_cast<int>(std::sqrt(scoreOut->elementSize() / 2.0f));
            }
            if (featureSize <= 0) featureSize = inputSize_ / (8 << scaleIdx);
        }

        MNN::Tensor scoreTensor(scoreOut, MNN::Tensor::DimensionType::CAFFE);
        MNN::Tensor bboxTensor(bboxOut, MNN::Tensor::DimensionType::CAFFE);
        MNN::Tensor landmarkTensor(landmarkOut, MNN::Tensor::DimensionType::CAFFE);
        scoreOut->copyToHostTensor(&scoreTensor);
        bboxOut->copyToHostTensor(&bboxTensor);
        landmarkOut->copyToHostTensor(&landmarkTensor);

        const float *scoreData = scoreTensor.host<float>();
        const float *bboxData = bboxTensor.host<float>();
        const float *landmarkData = landmarkTensor.host<float>();
        if (scoreData && bboxData && landmarkData) {
            processRetinaFaceOutput(scoreData, bboxData, landmarkData, featureSize,
                                    8 << scaleIdx, scoreChannels, allFaces, confidenceThreshold);
        }
    }

    auto tPostEnd = std::chrono::high_resolution_clock::now();
    auto postMs = std::chrono::duration_cast<std::chrono::milliseconds>(tPostEnd - tInferEnd).count();

    // NMS
    auto resultNms = applyNMS(allFaces, nmsThreshold);
    auto tTotalEnd = std::chrono::high_resolution_clock::now();
    auto totalMs = std::chrono::duration_cast<std::chrono::milliseconds>(tTotalEnd - totalStart).count();

    LOGI("[Perf] MNN RetinaFace[NV21→ZERO-COPY] DONE: total=%lldms (preprocess=%lldms, infer=%lldms, postprocess=%lldms), faces=%zu",
         totalMs, preprocessMs, inferMs, postMs, resultNms.size());

    return resultNms;
}

std::vector<float> MnnFaceDetector::detectFromNv21(const unsigned char *nv21Data,
                                                    int width,
                                                    int height,
                                                    int rotationDegrees) {
    if (!loaded_ || !inputTensor_ || !pretreatNv21_ || outputTensors_.empty()) {
        return {};
    }

    int nv21Stride = width;

    // 填充黑色
    MNN::Tensor::DimensionType inputDimType = inputTensor_->getDimensionType();
    MNN::Tensor tmpInput(inputTensor_, inputDimType);
    float *inputData = tmpInput.host<float>();
    float normMean = hasBuiltInNormalization_ ? 0.0f : 127.5f;
    float normStd = hasBuiltInNormalization_ ? 1.0f : 128.0f;
    float blackVal = (0.0f - normMean) / normStd;
    std::fill_n(inputData, tmpInput.elementSize(), blackVal);

    // [旋转+letterbox] 使用统一的变换矩阵构建
    MNN::CV::Matrix trans = buildNv21TransformMatrix(width, height, rotationDegrees);
    pretreatNv21_->setMatrix(trans);

    auto cvtResult = pretreatNv21_->convert(nv21Data, width, height, nv21Stride, &tmpInput);
    if (cvtResult != MNN::NO_ERROR) {
        LOGE("NV21 ImageProcess::convert failed for landmark detection: error=%d", cvtResult);
        return {};
    }

    inputTensor_->copyFromHostTensor(&tmpInput);
    interpreter_->runSession(session_);

    MNN::Tensor *output = outputTensors_[0];
    MNN::Tensor::DimensionType outputDimType = output->getDimensionType();
    MNN::Tensor tmpOutput(output, outputDimType);
    output->copyToHostTensor(&tmpOutput);

    int elementSize = tmpOutput.elementSize();
    resultBuffer_.clear();
    resultBuffer_.reserve(elementSize);
    const float *data = tmpOutput.host<float>();
    for (int i = 0; i < elementSize; i++) {
        resultBuffer_.push_back(data[i]);
    }
    return resultBuffer_;
}

std::vector<float> MnnFaceDetector::detectFromNv21(const unsigned char *nv21Data,
                                                    int nv21Width, int nv21Height,
                                                    int rotationDegrees,
                                                    int roiLeft, int roiTop,
                                                    int roiRight, int roiBottom) {
    if (!loaded_ || !inputTensor_ || !pretreatNv21_ || outputTensors_.empty()) {
        return {};
    }

    // Clamp ROI in rotated coordinate space.
    // For rotation 90/270, ROI uses rotated dimensions: width=nv21Height, height=nv21Width.
    bool needSwap = (rotationDegrees == 90 || rotationDegrees == 270);
    int roiMaxX = needSwap ? nv21Height : nv21Width;
    int roiMaxY = needSwap ? nv21Width : nv21Height;

    int rawRoiLeft = roiLeft;
    int rawRoiTop = roiTop;
    int rawRoiRight = roiRight;
    int rawRoiBottom = roiBottom;

    roiLeft = std::max(0, std::min(roiLeft, roiMaxX));
    roiTop = std::max(0, std::min(roiTop, roiMaxY));
    roiRight = std::max(0, std::min(roiRight, roiMaxX));
    roiBottom = std::max(0, std::min(roiBottom, roiMaxY));

    if ((rawRoiLeft != roiLeft || rawRoiTop != roiTop ||
         rawRoiRight != roiRight || rawRoiBottom != roiBottom) &&
        shouldLogSample(gClampLogCounter, kClampLogInterval)) {
        LOGD_VERBOSE("detectFromNv21(ROI) clamp: raw=[%d,%d,%d,%d], clamped=[%d,%d,%d,%d], rot=%d, max=[%d,%d]",
                     rawRoiLeft, rawRoiTop, rawRoiRight, rawRoiBottom,
                     roiLeft, roiTop, roiRight, roiBottom,
                     rotationDegrees, roiMaxX, roiMaxY);
    }

    int roiW = roiRight - roiLeft;
    int roiH = roiBottom - roiTop;
    if (roiW <= 0 || roiH <= 0) {
        return {};
    }

    int nv21Stride = nv21Width;

    // Fill input tensor with normalized black
    MNN::Tensor::DimensionType inputDimType = inputTensor_->getDimensionType();
    MNN::Tensor tmpInput(inputTensor_, inputDimType);
    float *inputData = tmpInput.host<float>();
    float normMean = hasBuiltInNormalization_ ? 0.0f : 127.5f;
    float normStd = hasBuiltInNormalization_ ? 1.0f : 128.0f;
    float blackVal = (0.0f - normMean) / normStd;
    std::fill_n(inputData, tmpInput.elementSize(), blackVal);

    // [旋转+letterbox+ROI] 使用统一的变换矩阵构建
    MNN::CV::Matrix trans = buildNv21TransformMatrix(nv21Width, nv21Height, rotationDegrees,
                                                       roiLeft, roiTop, roiRight, roiBottom);

    pretreatNv21_->setMatrix(trans);
    auto result = pretreatNv21_->convert(nv21Data, nv21Width, nv21Height, nv21Stride,
                                         &tmpInput);
    if (result != MNN::NO_ERROR) {
        LOGE("detectFromNv21(ROI) ImageProcess::convert failed: error=%d", result);
        return {};
    }

    inputTensor_->copyFromHostTensor(&tmpInput);
    interpreter_->runSession(session_);

    MNN::Tensor *output = outputTensors_[0];
    MNN::Tensor::DimensionType outputDimType = output->getDimensionType();
    MNN::Tensor tmpOutput(output, outputDimType);
    output->copyToHostTensor(&tmpOutput);

    int elementSize = tmpOutput.elementSize();
    resultBuffer_.clear();
    resultBuffer_.reserve(elementSize);
    const float *data = tmpOutput.host<float>();
    for (int i = 0; i < elementSize; i++) {
        resultBuffer_.push_back(data[i]);
    }
    return resultBuffer_;
}

void MnnFaceDetector::processRetinaFaceOutput(const float *score,
                                              const float *bbox,
                                              const float *landmark,
                                              int featureSize,
                                              int stride,
                                              int scoreChannels,
                                              std::vector<FaceBox> &faces,
                                              float threshold) {
    // MNN 输出格式：NCHW (或扁平化 [anchors, channels, 1, 1])
    // score: [anchors, C, 1, 1] 或 [1, C, H, W] -> C=1 (face only) 或 C=2 (bg+face)
    // bbox: [anchors, 4, 1, 1] 或 [1, 4, H, W] -> dx, dy, dw, dh
    //        [关键修复] MNN 模型从 ONNX 转换而来，bbox 输出格式与 ONNX 一致：
    //        dx = 从 anchor 中心到 bbox 左边界的距离（已除以 stride）
    //        dy = 从 anchor 中心到 bbox 上边界的距离（已除以 stride）
    //        dw = 从 anchor 中心到 bbox 右边界的距离（已除以 stride）
    //        dh = 从 anchor 中心到 bbox 下边界的距离（已除以 stride）
    // landmark: [anchors, 10, 1, 1] 或 [1, 10, H, W] -> 5 points * 2
    //
    // [关键修复] 每个位置有 2 个 anchor（不同 minSize），所以总 anchor 数 = spatialSize * 2
    // 输出数据按 [anchor0, anchor1, anchor0, anchor1, ...] 排列

    int spatialSize = featureSize * featureSize;
    int numAnchorsPerLocation = 2;
    int totalAnchors = spatialSize * numAnchorsPerLocation;

    // [对齐 ONNX] 使用与 InsightFaceDet10GDetector 相同的 minSizes
    float minSizes[2];
    if (stride == 8) {
        minSizes[0] = 16.0f;
        minSizes[1] = 32.0f;
    } else if (stride == 16) {
        minSizes[0] = 64.0f;
        minSizes[1] = 128.0f;
    } else { // stride == 32
        minSizes[0] = 256.0f;
        minSizes[1] = 512.0f;
    }

    for (int y = 0; y < featureSize; y++) {
        for (int x = 0; x < featureSize; x++) {
            int spatialIdx = y * featureSize + x;
            float cx = (x + 0.5f) * stride;
            float cy = (y + 0.5f) * stride;

            for (int a = 0; a < numAnchorsPerLocation; a++) {
                int anchorIdx = spatialIdx * numAnchorsPerLocation + a;

                // [关键修复] 根据实际的 scoreChannels 读取 face score
                float faceScore;
                if (scoreChannels == 1) {
                    // MNN 转换后的模型: 只有 face score
                    faceScore = score[anchorIdx];
                } else {
                    // 原始 ONNX 格式: channel 0=bg, channel 1=face
                    faceScore = score[totalAnchors + anchorIdx];
                }
                
                if (faceScore < threshold) continue;

                // [关键修复] MNN NCHW 布局: [totalAnchors, 4, 1, 1]
                // 线性索引 = anchorIdx * 4 + channel
                // dx = bbox[anchorIdx * 4 + 0], dy = bbox[anchorIdx * 4 + 1]
                // dw = bbox[anchorIdx * 4 + 2], dh = bbox[anchorIdx * 4 + 3]
                float dx = bbox[anchorIdx * 4 + 0];
                float dy = bbox[anchorIdx * 4 + 1];
                float dw = bbox[anchorIdx * 4 + 2];
                float dh = bbox[anchorIdx * 4 + 3];

                // [对齐 ONNX] x1 = cx - dx * stride, x2 = cx + dw * stride
                float x1 = cx - dx * stride;
                float y1 = cy - dy * stride;
                float x2 = cx + dw * stride;
                float y2 = cy + dh * stride;

                // [关键修复] 限制坐标在有效范围内
                float maxSize = static_cast<float>(inputSize_);
                x1 = std::max(0.0f, std::min(x1, maxSize));
                y1 = std::max(0.0f, std::min(y1, maxSize));
                x2 = std::max(0.0f, std::min(x2, maxSize));
                y2 = std::max(0.0f, std::min(y2, maxSize));

                if (x1 >= x2 || y1 >= y2) continue;

                FaceBox box;
                box.x1 = x1;
                box.y1 = y1;
                box.x2 = x2;
                box.y2 = y2;
                box.confidence = faceScore;
                            
                // [关键修复] landmark NCHW 布局: [totalAnchors, 10, 1, 1]
                // 线性索引 = anchorIdx * 10 + channel
                for (int i = 0; i < 5; i++) {
                    float lx = landmark[anchorIdx * 10 + i * 2];
                    float ly = landmark[anchorIdx * 10 + i * 2 + 1];
                    box.landmarks[i * 2] = cx + lx * stride;
                    box.landmarks[i * 2 + 1] = cy + ly * stride;
                }

                faces.push_back(box);
            }
        }
    }
}

float MnnFaceDetector::calculateIoU(const FaceBox &a, const FaceBox &b) {
    float x1 = std::max(a.x1, b.x1);
    float y1 = std::max(a.y1, b.y1);
    float x2 = std::min(a.x2, b.x2);
    float y2 = std::min(a.y2, b.y2);

    float interW = std::max(0.0f, x2 - x1);
    float interH = std::max(0.0f, y2 - y1);
    float interArea = interW * interH;

    float unionArea = a.area() + b.area() - interArea;
    return unionArea > 0 ? interArea / unionArea : 0.0f;
}

std::vector<FaceBox> MnnFaceDetector::applyNMS(std::vector<FaceBox> &faces, float threshold) {
    if (faces.empty()) return {};

    std::sort(faces.begin(), faces.end(), [](const FaceBox &a, const FaceBox &b) {
        return a.confidence > b.confidence;
    });

    std::vector<FaceBox> result;
    std::vector<bool> suppressed(faces.size(), false);

    for (size_t i = 0; i < faces.size(); i++) {
        if (suppressed[i]) continue;
        result.push_back(faces[i]);

        for (size_t j = i + 1; j < faces.size(); j++) {
            if (suppressed[j]) continue;
            if (calculateIoU(faces[i], faces[j]) > threshold) {
                suppressed[j] = true;
            }
        }
    }

    return result;
}

bool MnnFaceDetector::bindSessionTensors() {
    if (!interpreter_ || !session_) {
        return false;
    }

    outputTensors_.clear();

    // 获取输入张量
    inputTensor_ = interpreter_->getSessionInput(session_, inputName_.c_str());
    if (!inputTensor_) {
        // 尝试用 nullptr 获取默认输入
        inputTensor_ = interpreter_->getSessionInput(session_, nullptr);
        if (!inputTensor_) {
            LOGE("Failed to get input tensor");
            return false;
        }
        LOGI("Using default input tensor");
    }

    LOGI("Input tensor shape: [%d, %d, %d, %d]",
         inputTensor_->batch(), inputTensor_->channel(),
         inputTensor_->height(), inputTensor_->width());

    // [关键修复] 如果输入张量有动态维度 (-1)，reshape 为固定尺寸
    // 这修复了 ONNX→MNN 转换时未指定固定输入尺寸的问题
    if (inputTensor_->height() <= 0 || inputTensor_->width() <= 0) {
        LOGI("Dynamic input detected, reshaping to fixed size: %d x %d", inputSize_, inputSize_);
        interpreter_->resizeTensor(inputTensor_, {1, 3, inputSize_, inputSize_});
        interpreter_->resizeSession(session_);
        inputTensor_ = interpreter_->getSessionInput(session_, inputName_.c_str());
        if (!inputTensor_) {
            inputTensor_ = interpreter_->getSessionInput(session_, nullptr);
        }
        if (!inputTensor_) {
            LOGE("Failed to rebind input tensor after resize");
            return false;
        }
        LOGI("Reshaped input tensor: [%d, %d, %d, %d]",
             inputTensor_->batch(), inputTensor_->channel(),
             inputTensor_->height(), inputTensor_->width());
    }

    // 获取输出张量
    if (!outputNames_.empty()) {
        for (const auto &name : outputNames_) {
            MNN::Tensor *tensor = interpreter_->getSessionOutput(session_, name.c_str());
            if (tensor) {
                outputTensors_.push_back(tensor);
                LOGI("Output tensor '%s': [%d, %d, %d, %d]",
                     name.c_str(), tensor->batch(), tensor->channel(),
                     tensor->height(), tensor->width());
            } else {
                LOGE("Failed to get output tensor: %s", name.c_str());
            }
        }
    } else {
        // 单输出模式：获取默认输出
        MNN::Tensor *tensor = interpreter_->getSessionOutput(session_, nullptr);
        if (tensor) {
            outputTensors_.push_back(tensor);
            LOGI("Default output tensor: [%d, %d, %d, %d]",
                 tensor->batch(), tensor->channel(),
                 tensor->height(), tensor->width());
        }
    }

    return !outputTensors_.empty();
}

bool MnnFaceDetector::createSession() {
    if (!interpreter_) {
        LOGE("createSession failed: interpreter is null");
        return false;
    }

    if (modelBufferReleased_) {
        LOGE("createSession failed: model buffer already released, reload model required");
        return false;
    }

    release(RELEASE_TENSORS | RELEASE_SESSION);

    MNN::ScheduleConfig config;
    config.numThread = 4;
    if (useGpu_) {
        config.type = MNN_FORWARD_OPENCL;
        LOGI("Requesting OpenCL GPU backend...");
    } else {
        config.type = MNN_FORWARD_CPU;
        LOGI("Using CPU backend with %d threads", config.numThread);
    }

    auto sessionCreateStart = std::chrono::high_resolution_clock::now();
    session_ = interpreter_->createSession(config);
    auto sessionCreateElapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::high_resolution_clock::now() - sessionCreateStart).count();

    if (!session_) {
        LOGE("Failed to create MNN session - GPU not supported or model incompatible!");
        return false;
    }

    LOGI("MNN session created in %lldms, requested=%s",
         sessionCreateElapsed,
         useGpu_ ? "OpenCL" : "CPU");

    // [诊断] 验证实际后端，检测静默 CPU 回退
    if (useGpu_) {
        // [API v2] getSessionInfo 新签名：bool getSessionInfo(session, code, void* ptr)
        // BACKENDS code: ptr 指向 int[]，ptr[0]=数量, ptr[1..]=MNNForwardType 枚举值
        int backendInfo[4] = {0};
        bool ok = interpreter_->getSessionInfo(session_,
            MNN::Interpreter::BACKENDS, backendInfo);
        if (ok && backendInfo[0] > 0) {
            int actualBackend = backendInfo[1]; // 第一个后端类型
            const char* backendName = "Unknown";
            switch (actualBackend) {
                case MNN_FORWARD_VULKAN: backendName = "Vulkan"; break;
                case MNN_FORWARD_CPU:    backendName = "CPU"; break;
                case MNN_FORWARD_OPENCL: backendName = "OpenCL"; break;
                case MNN_FORWARD_OPENGL: backendName = "OpenGL"; break;
                default: break;
            }
            LOGI("MNN session actual backend: %s (type=%d)", backendName, actualBackend);
            if (actualBackend != MNN_FORWARD_OPENCL) {
                LOGE("MNN OpenCL request was SILENTLY degraded! Actual backend=%s."
                     " Inference will be slow.", backendName);
                useGpu_ = false;
            }
        } else {
            LOGI("Cannot query MNN backend type (getSessionInfo failed)."
                 " If inference is >20ms for 192x192, likely CPU fallback.");
        }
    }

    return bindSessionTensors();
}

bool MnnFaceDetector::rebuildSession() {
    if (!interpreter_) {
        LOGE("rebuildSession failed: interpreter is null");
        return false;
    }
    if (modelBufferReleased_) {
        LOGE("rebuildSession failed: model buffer released, cannot rebuild without reload");
        return false;
    }
    return createSession();
}

void MnnFaceDetector::release(int flags) {
    const bool releaseInterpreter = (flags & RELEASE_INTERPRETER) != 0;
    const bool releaseModel = (flags & RELEASE_MODEL) != 0;
    const bool releaseSession = ((flags & RELEASE_SESSION) != 0) || releaseInterpreter;
    const bool releaseTensors = ((flags & RELEASE_TENSORS) != 0) || releaseSession;

    if (releaseTensors) {
        inputTensor_ = nullptr;
        outputTensors_.clear();
    }

    if (releaseSession && interpreter_ && session_) {
        interpreter_->releaseSession(session_);
        session_ = nullptr;
    }

    // [关键修复] 释放模型权重缓冲区（mmap 的模型文件内存）
    // releaseModel() 必须在 releaseSession() 之后、reset() 之前调用
    // 否则模型文件映射的内存不会被释放，导致 Native Heap 不下降
    if (releaseModel && interpreter_) {
        LOGI("Releasing model buffer...");
        interpreter_->releaseModel();
        modelBufferReleased_ = true;
    }

    if (releaseInterpreter) {
        interpreter_.reset();
        modelBufferReleased_ = false;
        modelPath_.clear();
    }

    if (releaseTensors || releaseInterpreter) {
        resultBuffer_.clear();
        retinaResultBuffer_.clear();
    }

    if (releaseInterpreter) {
        pretreat_.reset();
        pretreatNv21_.reset();
        loaded_ = false;
        useGpu_ = false;
        inputName_.clear();
        outputNames_.clear();
        inputSize_ = 0;
        hasBuiltInNormalization_ = false;
    }
}

} // namespace picme
