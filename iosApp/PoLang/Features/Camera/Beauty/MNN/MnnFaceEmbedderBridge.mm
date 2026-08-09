//
//  MnnFaceEmbedderBridge.mm
//  PoLang
//
//  Glint360K-R100 ArcFace 人脸 embedding 的 iOS ObjC++ 实现。
//  忠实移植 Android 生产路径 (`mnn_face_embedder.cpp`)：
//   - 输入归一化: (pixel - 127.5) / 128.0（等价 cv2.dnn.blobFromImage scalefactor=1/128, mean=127.5, swapRB=false）
//   - 通道序: RGB（不交换）
//   - 输出: 512 维 float, L2 归一化
//
//  iOS 专属改动（见 MnnFaceDetectorBridge.mm 同套规则，已 spike 验证）：
//   1. 后端固定 MNN_FORWARD_CPU + numThread=4（Metal 在本机精度异常；CPU 为正确性基准）。
//   2. 显式 BackendConfig（成员，生命周期 ≥ session）+ precision=Precision_High
//      （默认 nullptr→SIGSEGV；默认 Normal/fp16→数值错误）。
//   3. 去掉 OpenCL 路径（iOS 无 OpenCL）。
//

#import "MnnFaceEmbedderBridge.h"

#import <MNN/Interpreter.hpp>
#import <MNN/Tensor.hpp>
#import <MNN/MNNForwardType.h>
#import <MNN/MNNDefine.h>
#import <MNN/ErrorCode.hpp>

#include <memory>
#include <string>
#include <cmath>
#include <cstring>
#include <map>
#include <vector>

static const int kInputSize = 112;
static const int kEmbeddingDim = 512;
static const NSString *kInputName = @"input.1";

@interface PLMnnFaceEmbedder () {
    std::shared_ptr<MNN::Interpreter> _interpreter;
    MNN::Session *_session;
    MNN::Tensor *_inputTensor;
    MNN::Tensor *_outputTensor;

    // 成员 BackendConfig：保证指针生命周期 ≥ session（iOS 关键坑：默认 nullptr 解引用 SIGSEGV）
    MNN::BackendConfig _backendCfg;
}
@end

@implementation PLMnnFaceEmbedder

- (instancetype)init {
    self = [super init];
    if (self) {
        _session = nullptr;
        _inputTensor = nullptr;
        _outputTensor = nullptr;
    }
    return self;
}

- (void)dealloc {
    [self releaseResources];
}

- (void)releaseResources {
    if (_interpreter && _session) {
        _interpreter->releaseSession(_session);
        _session = nullptr;
    }
    _interpreter.reset();
    _inputTensor = nullptr;
    _outputTensor = nullptr;
}

- (BOOL)ready {
    return _interpreter != nullptr && _session != nullptr && _inputTensor != nullptr && _outputTensor != nullptr;
}

#pragma mark - Load

- (BOOL)loadModel:(NSString *)modelPath {
    [self releaseResources];

    if (!modelPath.length) {
        NSLog(@"[PoLang] MnnFaceEmbedder: modelPath is empty");
        return NO;
    }

    std::string path = std::string(modelPath.UTF8String);
    _interpreter.reset(MNN::Interpreter::createFromFile(path.c_str()));
    if (!_interpreter) {
        NSLog(@"[PoLang] MnnFaceEmbedder: createFromFile failed: %@", modelPath);
        return NO;
    }

    // ── 创建 session ──
    // iOS 关键：显式 BackendConfig + Precision_High（默认 nullptr→SIGSEGV；Normal/fp16→数值错误）
    MNN::ScheduleConfig config;
    config.type = MNN_FORWARD_CPU;
    config.numThread = 4;
    _backendCfg.precision = MNN::BackendConfig::Precision_High;
    config.backendConfig = &_backendCfg;

    _session = _interpreter->createSession(config);
    if (!_session) {
        NSLog(@"[PoLang] MnnFaceEmbedder: createSession failed");
        _interpreter.reset();
        return NO;
    }

    // ── 绑定输入张量 ──
    const std::string inputName(kInputName.UTF8String);
    _inputTensor = _interpreter->getSessionInput(_session, inputName.c_str());
    if (!_inputTensor) {
        _inputTensor = _interpreter->getSessionInput(_session, nullptr);
    }
    if (!_inputTensor) {
        NSLog(@"[PoLang] MnnFaceEmbedder: no input tensor");
        [self releaseResources];
        return NO;
    }

    // 动态输入维度 → resize 固定 [1,3,112,112]
    if (_inputTensor->height() <= 0 || _inputTensor->width() <= 0) {
        NSLog(@"[PoLang] MnnFaceEmbedder: dynamic input detected, resizing to %d×%d", kInputSize, kInputSize);
        _interpreter->resizeTensor(_inputTensor, {1, 3, kInputSize, kInputSize});
        _interpreter->resizeSession(_session);
        _inputTensor = _interpreter->getSessionInput(_session, inputName.c_str());
        if (!_inputTensor) {
            _inputTensor = _interpreter->getSessionInput(_session, nullptr);
        }
        if (!_inputTensor) {
            NSLog(@"[PoLang] MnnFaceEmbedder: input rebind failed after resize");
            [self releaseResources];
            return NO;
        }
    }

    NSLog(@"[PoLang] MnnFaceEmbedder: input '%s' [%d,%d,%d,%d]",
          inputName.c_str(),
          _inputTensor->batch(), _inputTensor->channel(),
          _inputTensor->height(), _inputTensor->width());

    // ── 定位输出张量（512 维 embedding）──
    _outputTensor = [self findEmbeddingOutput];
    if (!_outputTensor) {
        NSLog(@"[PoLang] MnnFaceEmbedder: failed to locate 512-dim embedding output");
        [self releaseResources];
        return NO;
    }

    NSLog(@"[PoLang] MnnFaceEmbedder: output found, elements=%d, ready=YES", _outputTensor->elementSize());
    return YES;
}

/// 从所有输出中定位 elementSize==512 的 embedding 张量。
/// 优先名字含 matmul/Reshape/fc1 的输出（对标 Android findEmbeddingOutput 逻辑）。
- (MNN::Tensor *)findEmbeddingOutput {
    auto outputs = _interpreter->getSessionOutputAll(_session);

    NSLog(@"[PoLang] MnnFaceEmbedder: available outputs (%zu):", outputs.size());
    for (const auto &kv : outputs) {
        MNN::Tensor *t = kv.second;
        if (t) {
            NSLog(@"[PoLang]   '%s': [%d,%d,%d,%d] elements=%d",
                  kv.first.c_str(), t->batch(), t->channel(), t->height(), t->width(), t->elementSize());
        }
    }

    // 按 elementSize == 512 筛选
    std::vector<std::pair<std::string, MNN::Tensor *>> candidates;
    for (const auto &kv : outputs) {
        MNN::Tensor *t = kv.second;
        if (t && t->elementSize() == kEmbeddingDim) {
            candidates.emplace_back(kv.first, t);
        }
    }
    if (candidates.empty()) {
        NSLog(@"[PoLang] MnnFaceEmbedder: no output tensor with elementSize=%d", kEmbeddingDim);
        return nullptr;
    }

    // 优先名字含 embedding 关键字
    for (const auto &c : candidates) {
        const std::string &name = c.first;
        if (name.find("matmul") != std::string::npos ||
            name.find("Reshape") != std::string::npos ||
            name.find("fc1") != std::string::npos) {
            NSLog(@"[PoLang] MnnFaceEmbedder: selected output '%s'", name.c_str());
            return c.second;
        }
    }

    // 回退第一个候选
    NSLog(@"[PoLang] MnnFaceEmbedder: fallback output '%s'", candidates[0].first.c_str());
    return candidates[0].second;
}

#pragma mark - Extract

- (nullable NSData *)extractEmbedding:(const uint8_t *)rgb width:(int)w height:(int)h {
    if (!self.ready) {
        NSLog(@"[PoLang] MnnFaceEmbedder: not ready");
        return nil;
    }
    if (!rgb || w != kInputSize || h != kInputSize) {
        NSLog(@"[PoLang] MnnFaceEmbedder: invalid input %dx%d (expected %dx%d)", w, h, kInputSize, kInputSize);
        return nil;
    }

    // ── 填充输入张量：归一化 (x - 127.5) / 128.0 ──
    MNN::Tensor::DimensionType inputDimType = _inputTensor->getDimensionType();
    MNN::Tensor tmpInput(_inputTensor, inputDimType);
    float *inputData = tmpInput.host<float>();
    if (!inputData) {
        NSLog(@"[PoLang] MnnFaceEmbedder: failed to get input host buffer");
        return nil;
    }

    const int totalPixels = kInputSize * kInputSize;
    constexpr float normMean = 127.5f;
    constexpr float normStd = 128.0f;
    const bool isNCHW = (inputDimType == MNN::Tensor::DimensionType::CAFFE);

    // InsightFace ArcFace R100 预处理：等价 cv2.dnn.blobFromImage(scalefactor=1/128, mean=127.5, swapRB=false)
    // 通道序 RGB（不交换），输出范围约 [-0.996, 0.992]
    for (int i = 0; i < totalPixels; i++) {
        for (int c = 0; c < 3; c++) {
            float val = static_cast<float>(rgb[i * 3 + c]);
            float normalized = (val - normMean) / normStd;
            if (isNCHW) {
                inputData[c * totalPixels + i] = normalized;
            } else {
                inputData[i * 3 + c] = normalized;
            }
        }
    }

    _inputTensor->copyFromHostTensor(&tmpInput);
    _interpreter->runSession(_session);

    // ── 读取输出 ──
    MNN::Tensor::DimensionType outputDimType = _outputTensor->getDimensionType();
    MNN::Tensor tmpOutput(_outputTensor, outputDimType);
    _outputTensor->copyToHostTensor(&tmpOutput);

    const float *outData = tmpOutput.host<float>();
    if (!outData) {
        NSLog(@"[PoLang] MnnFaceEmbedder: failed to get output host buffer");
        return nil;
    }

    int elementSize = tmpOutput.elementSize();
    if (elementSize < kEmbeddingDim) {
        NSLog(@"[PoLang] MnnFaceEmbedder: output too small %d < %d", elementSize, kEmbeddingDim);
        return nil;
    }

    // ── L2 归一化 ──
    float embedding[kEmbeddingDim];
    std::memcpy(embedding, outData, kEmbeddingDim * sizeof(float));

    double sumSq = 0.0;
    for (int i = 0; i < kEmbeddingDim; i++) {
        sumSq += static_cast<double>(embedding[i]) * embedding[i];
    }
    float norm = static_cast<float>(std::sqrt(sumSq));
    if (norm > 1e-12f) {
        for (int i = 0; i < kEmbeddingDim; i++) {
            embedding[i] /= norm;
        }
    } else {
        NSLog(@"[PoLang] MnnFaceEmbedder: near-zero norm (%f), skipping normalization", norm);
    }

    // NaN/Inf 检测
    for (int i = 0; i < kEmbeddingDim; i++) {
        if (std::isnan(embedding[i]) || std::isinf(embedding[i])) {
            NSLog(@"[PoLang] MnnFaceEmbedder: embedding contains NaN/Inf at index %d", i);
            return nil;
        }
    }

    return [NSData dataWithBytes:embedding length:kEmbeddingDim * sizeof(float)];
}

@end
