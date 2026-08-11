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
// Glint360K R100 最终 embedding 输出层名（对标 Android outputName="1333"，FaceClusterEngine.kt:81）。
// ⚠️ 此前 iOS 漏了按名精确选取这步，回退启发式会错选中间 512 维层 → embedding 无判别力 → 聚类全错。
static const NSString *kOutputName = @"1333";

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
    // 匹配 Android + MNN demo 默认配置（不设显式 precision/backendConfig）。
    // 此前用 Precision_High + 显式 BackendConfig → 可能导致 MNN 走不同算子路径 → embedding 与 Android 正交。
    MNN::ScheduleConfig config;
    config.type = MNN_FORWARD_CPU;
    config.numThread = 4;

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

/// 从所有输出中定位 embedding 张量（对标 Android mnn_face_embedder.cpp findEmbeddingOutput）：
///   ① 优先精确名 kOutputName("1333")——Android 主选路径（此前 iOS 漏了这步）；
///   ② 兜底：elementSize==512 + 名字含 matmul/Reshape/fc1；
///   ③ 最后兜底：第一个 512 元素张量。
- (MNN::Tensor *)findEmbeddingOutput {
    auto outputs = _interpreter->getSessionOutputAll(_session);

    // 诊断字符串：列出全部输出 + 最终选中名，写入 Documents/embedder_diag.txt（供 devicectl pull 验证）
    NSMutableString *diag = [NSMutableString stringWithFormat:@"MnnFaceEmbedder available outputs (%zu):\n", outputs.size()];
    for (const auto &kv : outputs) {
        MNN::Tensor *t = kv.second;
        if (t) {
            [diag appendFormat:@"  '%s': [%d,%d,%d,%d] elements=%d\n",
                kv.first.c_str(), t->batch(), t->channel(), t->height(), t->width(), t->elementSize()];
            NSLog(@"[PoLang] MnnFaceEmbedder:   '%s': [%d,%d,%d,%d] elements=%d",
                  kv.first.c_str(), t->batch(), t->channel(), t->height(), t->width(), t->elementSize());
        }
    }

    MNN::Tensor *result = nullptr;
    std::string selName = "(none)";

    // ① 精确名 "1333" 优先（Android 主选）。Glint360K R100 最终 embedding 层名为 "1333"。
    const std::string preferred(kOutputName.UTF8String);
    auto it = outputs.find(preferred);
    if (it != outputs.end() && it->second != nullptr) {
        result = it->second;
        selName = it->first;
    } else {
        [diag appendFormat:@"preferred '%s' NOT found, fallback heuristic\n", preferred.c_str()];
        NSLog(@"[PoLang] MnnFaceEmbedder: preferred '%s' not found, fallback to heuristic", preferred.c_str());
        // ② elementSize == 512 筛选
        std::vector<std::pair<std::string, MNN::Tensor *>> candidates;
        for (const auto &kv : outputs) {
            MNN::Tensor *t = kv.second;
            if (t && t->elementSize() == kEmbeddingDim) {
                candidates.emplace_back(kv.first, t);
            }
        }
        if (candidates.empty()) {
            NSLog(@"[PoLang] MnnFaceEmbedder: no output tensor with elementSize=%d", kEmbeddingDim);
            selName = "(none 512)";
        } else {
            // ③ 名字含 embedding 关键字
            bool found = false;
            for (const auto &c : candidates) {
                const std::string &name = c.first;
                if (name.find("matmul") != std::string::npos ||
                    name.find("Reshape") != std::string::npos ||
                    name.find("fc1") != std::string::npos) {
                    result = c.second; selName = name; found = true; break;
                }
            }
            // ④ 最后兜底第一个候选
            if (!found) { result = candidates[0].second; selName = candidates[0].first; }
        }
    }

    [diag appendFormat:@"SELECTED: '%s'\n", selName.c_str()];
    NSLog(@"[PoLang] MnnFaceEmbedder: SELECTED '%s'", selName.c_str());
    NSString *docs = [NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES) firstObject];
    if (docs) {
        [diag writeToFile:[docs stringByAppendingPathComponent:@"embedder_diag.txt"]
               atomically:YES encoding:NSUTF8StringEncoding error:nil];
    }
    return result;
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
    // CAFFE_C4 有 4 通道（3 RGB + 1 padding），先清零防止第 4 通道残留垃圾影响推理
    std::memset(inputData, 0, tmpInput.elementSize() * sizeof(float));

    const int totalPixels = kInputSize * kInputSize;
    constexpr float normMean = 127.5f;
    constexpr float normStd = 128.0f;
    // ⚠️ iOS MNN 对此模型报告 inputDimType=1（TENSORFLOW/NHWC）。
    // 模型期望交错布局（RGB-RGB-RGB...），必须以 NHWC 填充。
    // 此前 isNCHW=true 导致平面填充（R-R-R...G-G-G...B-B-B...）→ 像素乱序 → embedding 正交。
    const bool isNCHW = false;  // 强制 NHWC（交错），匹配模型期望

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

    // [诊断] 首次推理时写入输入 dimension type + shape + output 样本值
    static bool diagOnce = false;
    if (!diagOnce) {
        diagOnce = true;
        int dt = (int)inputDimType;  // CAFFE=0, TENSORFLOW=1, CAFFE_C4=2
        NSString *dtName = dt == 0 ? @"CAFFE" : dt == 1 ? @"TENSORFLOW" : dt == 2 ? @"CAFFE_C4" : @"UNKNOWN";
        NSMutableString *d = [NSMutableString stringWithFormat:
            @"\n=== EXTRACT DIAG ===\n"
            @"inputDimType=%d (%@)  isNCHW=%d\n"
            @"inputShape: batch=%d channel=%d height=%d width=%d elementSize=%d\n"
            @"outputShape: batch=%d channel=%d height=%d width=%d elementSize=%d\n"
            @"outputDimType=%d\n"
            @"outputFirst10:",
            dt, dtName, (int)isNCHW,
            _inputTensor->batch(), _inputTensor->channel(), _inputTensor->height(), _inputTensor->width(), _inputTensor->elementSize(),
            _outputTensor->batch(), _outputTensor->channel(), _outputTensor->height(), _outputTensor->width(), elementSize,
            (int)outputDimType];
        for (int i = 0; i < 10 && i < elementSize; i++) [d appendFormat:@" %.4f", outData[i]];
        double rawSq = 0;
        for (int i = 0; i < kEmbeddingDim; i++) rawSq += (double)outData[i] * outData[i];
        [d appendFormat:@", rawNorm=%.4f\n", std::sqrt(rawSq)];
        NSLog(@"[PoLang] MnnFaceEmbedder %@", d);
        NSString *docs = [NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES) firstObject];
        if (docs) {
            NSString *path = [docs stringByAppendingPathComponent:@"embedder_diag.txt"];
            NSString *existing = [NSString stringWithContentsOfFile:path encoding:NSUTF8StringEncoding error:nil];
            [[existing ?: @"" stringByAppendingString:d] writeToFile:path atomically:YES encoding:NSUTF8StringEncoding error:nil];
        }
    }

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
