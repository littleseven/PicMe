#include "mnn_face_embedder.h"
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstring>

#define LOG_TAG "PoLang:MnnFaceEmbedder"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace picme {

MnnFaceEmbedder::MnnFaceEmbedder()
    : session_(nullptr), inputTensor_(nullptr), outputTensor_(nullptr),
      inputSize_(112), embeddingDim_(512), loaded_(false), useGpu_(false) {
}

MnnFaceEmbedder::~MnnFaceEmbedder() {
    release();
}

bool MnnFaceEmbedder::load(const std::string &modelPath,
                           int inputSize,
                           int embeddingDim,
                           const std::string &inputName,
                           const std::string &preferredOutputName,
                           bool useGpu) {
    release();

    inputSize_ = inputSize;
    embeddingDim_ = embeddingDim;
    inputName_ = inputName;
    preferredOutputName_ = preferredOutputName;
    useGpu_ = useGpu;

    interpreter_.reset(MNN::Interpreter::createFromFile(modelPath.c_str()));
    if (!interpreter_) {
        LOGE("Failed to create MNN interpreter from: %s", modelPath.c_str());
        return false;
    }

    LOGI("MNN embedder model loaded: %s", modelPath.c_str());

    if (!createSession()) {
        return false;
    }

    loaded_ = true;
    LOGI("MNN embedder ready: inputSize=%d, embeddingDim=%d, inputName=%s, outputName=%s",
         inputSize_, embeddingDim_, inputName_.c_str(), outputName_.c_str());
    return true;
}

bool MnnFaceEmbedder::createSession() {
    if (!interpreter_) {
        LOGE("createSession failed: interpreter is null");
        return false;
    }

    MNN::ScheduleConfig config;
    config.numThread = 4;
    if (useGpu_) {
        config.type = MNN_FORWARD_OPENCL;
        LOGI("Requesting OpenCL GPU backend for face embedder...");
    } else {
        config.type = MNN_FORWARD_CPU;
        LOGI("Using CPU backend with %d threads", config.numThread);
    }

    session_ = interpreter_->createSession(config);
    if (!session_) {
        LOGE("Failed to create MNN session");
        return false;
    }

    // 校验 GPU 请求是否被静默降级
    if (useGpu_) {
        int backendInfo[4] = {0};
        bool ok = interpreter_->getSessionInfo(session_, MNN::Interpreter::BACKENDS, backendInfo);
        if (ok && backendInfo[0] > 0) {
            int actualBackend = backendInfo[1];
            const char* backendName = "Unknown";
            switch (actualBackend) {
                case MNN_FORWARD_VULKAN: backendName = "Vulkan"; break;
                case MNN_FORWARD_CPU:    backendName = "CPU"; break;
                case MNN_FORWARD_OPENCL: backendName = "OpenCL"; break;
                case MNN_FORWARD_OPENGL: backendName = "OpenGL"; break;
                default: break;
            }
            LOGI("MNN embedder actual backend: %s (type=%d)", backendName, actualBackend);
            if (actualBackend != MNN_FORWARD_OPENCL) {
                LOGE("MNN embedder OpenCL request was SILENTLY degraded to %s", backendName);
                useGpu_ = false;
            }
        } else {
            LOGI("Cannot query MNN embedder backend type");
        }
    }

    return bindInputOutput();
}

bool MnnFaceEmbedder::bindInputOutput() {
    if (!interpreter_ || !session_) {
        return false;
    }

    inputTensor_ = interpreter_->getSessionInput(session_, inputName_.c_str());
    if (!inputTensor_) {
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

    // 处理动态输入尺寸
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

    // 获取所有输出并定位 embedding 张量
    auto outputs = interpreter_->getSessionOutputAll(session_);
    outputTensor_ = findEmbeddingOutput(outputs);
    if (!outputTensor_) {
        LOGE("Failed to locate embedding output tensor (expected %d dims)", embeddingDim_);
        return false;
    }

    LOGI("Output tensor '%s': [%d, %d, %d, %d]",
         outputName_.c_str(),
         outputTensor_->batch(), outputTensor_->channel(),
         outputTensor_->height(), outputTensor_->width());
    return true;
}

MNN::Tensor *MnnFaceEmbedder::findEmbeddingOutput(const std::map<std::string, MNN::Tensor *> &outputs) {
    outputName_.clear();

    // 列出所有可用输出供诊断
    LOGI("Available MNN outputs (%zu):", outputs.size());
    for (const auto &kv : outputs) {
        MNN::Tensor *t = kv.second;
        if (t) {
            LOGI("  '%s': [%d, %d, %d, %d], elements=%d",
                 kv.first.c_str(), t->batch(), t->channel(), t->height(), t->width(), t->elementSize());
        }
    }

    // 1. 严格使用用户指定的输出名（ArcFace R100 应为 fc1）
    if (!preferredOutputName_.empty()) {
        auto it = outputs.find(preferredOutputName_);
        if (it != outputs.end() && it->second) {
            outputName_ = preferredOutputName_;
            LOGI("Using preferred output tensor: '%s' (elements=%d)",
                 outputName_.c_str(), it->second->elementSize());
            return it->second;
        }
        LOGE("Preferred output tensor '%s' NOT found, falling back to auto-select", preferredOutputName_.c_str());
    }

    // 2. 按 elementSize == embeddingDim_ 筛选候选
    std::vector<std::pair<std::string, MNN::Tensor *>> candidates;
    for (const auto &kv : outputs) {
        MNN::Tensor *tensor = kv.second;
        if (!tensor) continue;
        if (tensor->elementSize() == embeddingDim_) {
            candidates.emplace_back(kv.first, tensor);
        }
    }

    if (candidates.empty()) {
        LOGE("No output tensor with elementSize=%d found.", embeddingDim_);
        return nullptr;
    }

    // 3. 优先选择名字包含 embedding 相关关键字的输出
    auto preferredIt = std::find_if(candidates.begin(), candidates.end(),
                                    [](const std::pair<std::string, MNN::Tensor *> &p) {
                                        const std::string &name = p.first;
                                        return name.find("matmul") != std::string::npos ||
                                               name.find("Reshape") != std::string::npos ||
                                               name.find("fc1") != std::string::npos;
                                    });
    if (preferredIt != candidates.end()) {
        outputName_ = preferredIt->first;
        LOGI("Using auto-selected output tensor: '%s'", outputName_.c_str());
        return preferredIt->second;
    }

    // 4. 回退到第一个候选
    outputName_ = candidates[0].first;
    LOGI("Using fallback output tensor: '%s'", outputName_.c_str());
    return candidates[0].second;
}

std::vector<float> MnnFaceEmbedder::extract(const unsigned char *imageData,
                                            int width,
                                            int height,
                                            int channels) {
    if (!loaded_ || !inputTensor_ || !outputTensor_) {
        LOGE("Embedder not ready");
        return {};
    }

    if (width != inputSize_ || height != inputSize_ || channels != 3) {
        LOGE("Invalid input size: expected %dx%dx3, got %dx%dx%d",
             inputSize_, inputSize_, width, height, channels);
        return {};
    }

    // 填充输入张量：归一化 (x - 127.5) / 128.0
    MNN::Tensor::DimensionType inputDimType = inputTensor_->getDimensionType();
    MNN::Tensor tmpInput(inputTensor_, inputDimType);
    float *inputData = tmpInput.host<float>();
    if (!inputData) {
        LOGE("Failed to get input tensor host buffer");
        return {};
    }

    const int totalPixels = inputSize_ * inputSize_;
    // InsightFace ArcFace R100 原始预处理：
    // cv2.dnn.blobFromImage(scalefactor=1.0/128.0, mean=(127.5,127.5,127.5), swapRB=false)
    // 等价于 (pixel - 127.5) / 128.0，输出范围约 [-0.996, 0.992]
    constexpr float normMean = 127.5f;
    constexpr float normStd = 128.0f;
    const bool isNCHW = (inputDimType == MNN::Tensor::DimensionType::CAFFE);

    for (int i = 0; i < totalPixels; i++) {
        for (int c = 0; c < 3; c++) {
            float val = static_cast<float>(imageData[i * 3 + c]);
            float normalized = (val - normMean) / normStd;
            if (isNCHW) {
                inputData[c * totalPixels + i] = normalized;
            } else {
                inputData[i * 3 + c] = normalized;
            }
        }
    }

    inputTensor_->copyFromHostTensor(&tmpInput);
    interpreter_->runSession(session_);

    // 读取输出
    MNN::Tensor::DimensionType outputDimType = outputTensor_->getDimensionType();
    MNN::Tensor tmpOutput(outputTensor_, outputDimType);
    outputTensor_->copyToHostTensor(&tmpOutput);

    const float *outData = tmpOutput.host<float>();
    if (!outData) {
        LOGE("Failed to get output tensor host buffer");
        return {};
    }

    int elementSize = tmpOutput.elementSize();
    if (elementSize < embeddingDim_) {
        LOGE("Output elementSize too small: %d < %d", elementSize, embeddingDim_);
        return {};
    }

    resultBuffer_.resize(embeddingDim_);
    std::memcpy(resultBuffer_.data(), outData, embeddingDim_ * sizeof(float));

    // L2 归一化前记录原始统计
    float rawMin = resultBuffer_[0], rawMax = resultBuffer_[0];
    double rawSum = 0.0, rawSumSq = 0.0;
    for (int i = 0; i < embeddingDim_; i++) {
        float v = resultBuffer_[i];
        if (v < rawMin) rawMin = v;
        if (v > rawMax) rawMax = v;
        rawSum += v;
        rawSumSq += static_cast<double>(v) * v;
    }
    double rawMean = rawSum / embeddingDim_;
    double rawStd = std::sqrt(rawSumSq / embeddingDim_ - rawMean * rawMean);

    // L2 归一化
    l2Normalize(resultBuffer_.data(), embeddingDim_);

    // 诊断：检查 NaN/Inf 和分布
    bool hasNan = false;
    for (int i = 0; i < embeddingDim_; i++) {
        if (std::isnan(resultBuffer_[i]) || std::isinf(resultBuffer_[i])) {
            hasNan = true;
            break;
        }
    }
    if (hasNan) {
        LOGE("Embedding contains NaN/Inf!");
    } else {
        LOGD("Embedding extracted: dim=%d, raw=[min=%.4f,max=%.4f,mean=%.4f,std=%.4f], "
             "norm1 first5=[%.4f,%.4f,%.4f,%.4f,%.4f]",
             embeddingDim_, rawMin, rawMax, rawMean, rawStd,
             resultBuffer_[0], resultBuffer_[1], resultBuffer_[2], resultBuffer_[3], resultBuffer_[4]);
    }

    return resultBuffer_;
}

void MnnFaceEmbedder::l2Normalize(float *data, int size) {
    double sum = 0.0;
    for (int i = 0; i < size; i++) {
        sum += static_cast<double>(data[i]) * data[i];
    }
    float norm = static_cast<float>(std::sqrt(sum));
    if (norm > 1e-12f) {
        for (int i = 0; i < size; i++) {
            data[i] /= norm;
        }
    }
}

void MnnFaceEmbedder::release() {
    if (interpreter_ && session_) {
        interpreter_->releaseSession(session_);
        session_ = nullptr;
    }
    interpreter_.reset();
    inputTensor_ = nullptr;
    outputTensor_ = nullptr;
    loaded_ = false;
    useGpu_ = false;
    inputSize_ = 112;
    embeddingDim_ = 512;
    inputName_.clear();
    outputName_.clear();
    preferredOutputName_.clear();
    resultBuffer_.clear();
}

} // namespace picme
