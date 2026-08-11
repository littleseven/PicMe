//
//  MnnFaceDetectorBridge.mm
//  PoLang
//
//  两阶段 MNN 人脸检测（RetinaFace det_500m → 2D106 关键点）的 iOS 实现。
//  忠实移植 Android 生产路径：
//   - RetinaFace box 检测：`mnn_face_detector.cpp` detectRetinaFace + processRetinaFaceOutput + NMS
//     + `MnnRoiDetector.kt` letterbox 逆变换 + ROI ×1.2 扩展
//   - 2D106 关键点：`MnnLandmarkDetector.kt` centered-scale 裁剪 + (x+1)*96 逆变换解析
//
//  iOS 专属改动（见 mnn-ios-integration skill 补验 A，已 spike 验证）：
//   1. 后端固定 MNN_FORWARD_CPU + numThread=4（Metal 在本设备精度异常；CPU 为正确性基准）。
//   2. 显式 BackendConfig（成员，生命周期 ≥ session）+ precision=Precision_High
//      （默认 nullptr→SIGSEGV；默认 Normal/fp16→数值错误）。
//   3. 输入源为 BGRA（AVFoundation YUV→BGRA via CIContext），先转全帧 RGB 再做 letterbox/裁剪。
//   4. 去掉 OpenCL/NV21 路径（iOS 无 OpenCL；相机帧已是 BGRA）。
//

#import "MnnFaceDetectorBridge.h"

#import <MNN/Interpreter.hpp>
#import <MNN/Tensor.hpp>
#import <MNN/MNNForwardType.h>
#import <MNN/MNNDefine.h>
#import <MNN/ErrorCode.hpp>

#include <vector>
#include <string>
#include <memory>
#include <algorithm>
#include <cmath>
#include <cstring>

namespace polang_mnn {

// ───────────────────────── 数据结构 ─────────────────────────

struct FaceBox {
    float x1, y1, x2, y2, confidence;
    // 5-point landmarks (x0,y0,...,x4,y4) in model input space (320×320).
    // Decoded as cx + delta*stride, matching Android mnn_face_detector.cpp:1073-1078.
    float landmarks[10] = {};
    float area() const {
        float w = x2 - x1, h = y2 - y1;
        return (w > 0 && h > 0) ? w * h : 0;
    }
};

// 检测结果（原始图像像素坐标）—— detectAll 返回
struct PixelFace {
    float roiX, roiY, roiW, roiH;   // ROI 原点 + 尺寸（已 clamp 到图像边界内）
    float confidence;
    float landmarks[10];             // 5-point 像素坐标（逆 letterbox 变换后）
};

static inline float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

// ───────────────────────── 单阶段封装 ─────────────────────────

struct Stage {
    std::shared_ptr<MNN::Interpreter> interp;
    MNN::Session *session = nullptr;
    MNN::Tensor *input = nullptr;
    std::vector<std::string> outputNames;
    int inputSize = 0;
    std::string inputName;
    bool isNCHW = true;
    bool hasBuiltInNorm = false;
    // 输入归一化参数（与 Android mnn_face_detector.cpp normMean/normStd 同语义）。
    // hasBuiltInNorm=true → 模型内置归一化生效，喂 raw（mean=0/std=1）；
    // hasBuiltInNorm=false → 手动预归一化 (x-127.5)/128。
    // 🔴 iOS 关键：2d106det 的内置归一化算子在 iOS MNN CPU 上不生效（见 loadBoth 注释），
    //   故 landmark 一律走 hasBuiltInNorm=false（预归一化）。
    float normMean = 127.5f;
    float normStd = 128.0f;
    bool ok = false;
    // 成员 BackendConfig：保证指针生命周期 ≥ session（iOS 关键坑：默认 nullptr 解引用 SIGSEGV）
    MNN::BackendConfig backendCfg;
    MNNForwardType backend = MNN_FORWARD_CPU;   // retina=CPU(已知正确)；landmark 可切 Metal 实验

    bool init(const std::string &path,
              int inputSizeVal,
              const std::string &inName,
              const std::vector<std::string> &outNames,
              bool builtInNorm,
              std::string &errOut) {
        interp.reset(MNN::Interpreter::createFromFile(path.c_str()));
        if (!interp) { errOut = "createFromFile failed"; return false; }

        MNN::ScheduleConfig sc;
        sc.type = backend;                  // retina=CPU(已知正确)；landmark=Metal 实验
        if (backend == MNN_FORWARD_CPU) sc.numThread = 4;
        backendCfg.precision = MNN::BackendConfig::Precision_High;  // iOS 关键：默认 Normal/fp16 数值错误
        sc.backendConfig = &backendCfg;

        session = interp->createSession(sc);
        if (!session) { errOut = "createSession failed"; return false; }

        input = interp->getSessionInput(session, inName.c_str());
        if (!input) input = interp->getSessionInput(session, nullptr);
        if (!input) { errOut = "no input tensor"; return false; }

        // 动态输入维度 → resize 固定尺寸（ONNX→MNN 转换常见）
        if (input->height() <= 0 || input->width() <= 0) {
            interp->resizeTensor(input, {1, 3, inputSizeVal, inputSizeVal});
            interp->resizeSession(session);
            input = interp->getSessionInput(session, inName.c_str());
            if (!input) input = interp->getSessionInput(session, nullptr);
            if (!input) { errOut = "input rebind failed after resize"; return false; }
        }

        isNCHW = (input->getDimensionType() == MNN::Tensor::CAFFE);
        inputSize = inputSizeVal;
        inputName = inName;
        outputNames = outNames;
        hasBuiltInNorm = builtInNorm;
        normMean = builtInNorm ? 0.0f : 127.5f;
        normStd = builtInNorm ? 1.0f : 128.0f;

        // 校验输出张量存在（名称不匹配会静默返回 null → 检测失败）
        for (const auto &n : outputNames) {
            MNN::Tensor *t = interp->getSessionOutput(session, n.c_str());
            if (!t) { errOut = "missing output '" + n + "'"; return false; }
        }

        ok = true;
        return true;
    }
};

// ───────────────────────── 两阶段检测器 ─────────────────────────

class Detector {
public:
    Stage retina;
    Stage landmark;
    std::vector<uint8_t> rgbBuf;   // 复用：全帧 BGRA→RGB
    std::string debugInfo;

    // Stage-1 诊断（-galleryFace 用）：裁决「人脸框竖向压扁」根因在 Stage-1 还是 Stage-2。
    // box320 aspect≈1 → Stage-1 正常、压扁在 Stage-2；box320 aspect>1.5 → Stage-1 本身压扁。
    float dbgBoxX1 = 0, dbgBoxY1 = 0, dbgBoxX2 = 0, dbgBoxY2 = 0;   // 320 空间 RetinaFace box
    float dbgScale = 0, dbgPadL = 0, dbgPadT = 0;                    // 逆 letterbox 参数
    float dbgMx1 = 0, dbgMy1 = 0, dbgMx2 = 0, dbgMy2 = 0;            // 图像空间 box（逆变换后，×1.2 前）
    float dbgRoiL = 0, dbgRoiT = 0, dbgRoiR = 0, dbgRoiB = 0;        // 图像空间 ROI（×1.2 扩展后）
    int dbgW = 0, dbgH = 0;
    bool dbgHasStage1 = false;
    // Stage-2 诊断：模型原始输出 ox/oy 范围 + 裁剪参数（裁决 Y 压扁是否来自模型本身）
    float dbgCenterX = 0, dbgCenterY = 0, dbgLooseSize = 0;
    float dbgOxMin = 0, dbgOxMax = 0, dbgOyMin = 0, dbgOyMax = 0;
    int dbgOutElements = 0;
    // 输入裁剪朝向诊断（隐私安全：仅聚合方差统计，无像素）：行特征跨度 vs 列特征跨度。
    // 正向高脸 → rowExt(竖向特征跨度) > colExt；若 colExt > rowExt → 输入被转置。
    int dbgRowExtent = 0, dbgColExtent = 0;
    // 16×16 输入亮度 ASCII 图（隐私安全：16×16 远低于人脸可辨识分辨率，仅供离线裁决
    // 「喂给模型的输入人脸是高脸还是被压扁/转置」）。' '=暗 → '@'=亮；首行=输入顶部。
    std::string dbgGrid;
    // 输入朝向探针：对同一裁剪做 4 种旋转喂模型，记录各自原始输出 ox/oy 跨度。
    // 裁决「模型输出的宽扁形变是否源于输入 X/Y 转置」——若某旋转得到 oy>ox（高脸），
    // 而正向 0° 得到 ox>oy（宽扁），则根因是输入朝向转置，应在源头旋转输入（非仿射标定）。
    bool dbgOrientProbe = false;
    std::string dbgOrientResult;

    bool loadBoth(const std::string &rp, const std::string &lp);
    bool detect(const uint8_t *bgra, int w, int h, int bpr, float *out212);

    /// 多人脸检测（仅 RetinaFace 第一阶段，不做 2D106 关键点）。
    /// 对标 Android MnnFaceDetector.detectRetinaFaces() (MnnFaceDetector.kt:272-295)。
    /// 返回所有 NMS 保留的人脸，ROI + 5 点关键点已逆变换为原图像素坐标。
    std::vector<PixelFace> detectAll(const uint8_t *bgra, int w, int h, int bpr);

private:
    void bgraToRgb(const uint8_t *bgra, int w, int h, int bpr);
    inline void sampleRgb(float fx, float fy, int w, int h, float &R, float &G, float &B) const;

    /// RetinaFace 推理 + 3-scale 解码 + NMS。
    /// 返回所有 NMS 保留的人脸（320×320 模型空间，含 5 点关键点）。
    std::vector<FaceBox> runRetinaInfer(int w, int h);

    /// 单脸：从 runRetinaInfer 结果中取面积最大者。
    bool runRetina(int w, int h, FaceBox &outBox);
    void processScale(int nameIdx, int stride, float threshold, std::vector<FaceBox> &out);
    std::vector<FaceBox> nms(std::vector<FaceBox> &faces, float threshold);
    bool runLandmark(int w, int h, float roiL, float roiT, float roiR, float roiB, float *out212);

    /// 输入朝向探针：把裁剪采样到正向 192×192 缓冲 U，再以 4 种旋转（0/90/180/270）填入
    /// 模型输入并推理，记录每种方向的原始输出 ox/oy 跨度。返回可读诊断串。
    /// 判读：使「oy跨度 > ox跨度」(高脸) 的旋转 = 模型实际期望的输入朝向。
    ///      若该方向 ≠ 0°，则根因是输入 X/Y 转置（修复=按该方向旋转输入，而非标定输出）。
    std::string diagnoseInputOrientation(int w, int h, float roiL, float roiT, float roiR, float roiB);
};

bool Detector::loadBoth(const std::string &rp, const std::string &lp) {
    std::string e1, e2;
    // det_500m：320×320，input.1，9 输出，无内置归一化（mean=127.5/std=128）
    bool r = retina.init(rp, 320, "input.1",
                         {"443", "468", "493", "446", "471", "496", "449", "474", "499"},
                         false, e1);
    // 2d106det：192×192，data/fc1。模型图含 _minusscalar0/_mulscalar0（「内置归一化」算子）。
    // 🔴 [根因·已定位并验证] iOS MNN CPU **不执行** 2d106det 的内置归一化算子：
    //   - 喂 raw 0-255（依赖内置归一化）→ conv 层吃到未归一化输入 → 106 点呈「宽扁」畸变
    //     （ORIENT-PROBE 实测 raw0: ox=1.41 oy=0.72 wide；用户所见「人脸框不含脸/似旋转」即此症状）。
    //   - 改喂预归一化 (x-127.5)/128 → 输出恢复正向高脸（norm0: ox=1.14 oy=1.23 TALL；
    //     ox=1.14 与裁剪中脸占宽 57%→跨度 1.14 精确吻合）。
    //   - 4 种旋转喂模型均得 wide（ORIENT-PROBE），排除输入转置/旋转；问题在归一化，非朝向。
    //   对照：det_500m(retina) 本就手动预归一化 (x-127.5)/128（hasBuiltInNorm=false）故一直正确；
    //         Android OpenCL 会执行 2d106det 内置归一化故喂 raw。iOS CPU 不执行 → 必须预归一化。
    //   [修复] landmark 传 builtInNorm=false → Stage 预归一化 (x-127.5)/128（runLandmark 填充处）。
    landmark.backend = MNN_FORWARD_CPU;
    bool l = landmark.init(lp, 192, "data", {"fc1"}, false, e2);

    debugInfo = std::string("retina=") + (r ? "ok" : "FAIL") + "(" + e1 + ") " +
                "landmark=" + (l ? "ok" : "FAIL") + "(" + e2 + ")";
    // 诊断：两阶段的输入维度类型（isNCHW）—— landmark 若与 retina 不同且与模型实际期望不符，
    // 会导致输入张量空间布局错位（人脸被转置），表现为关键点竖向压扁。
    debugInfo += " | layout retina=" + std::string(retina.isNCHW ? "NCHW" : "NHWC") +
                 " landmark=" + std::string(landmark.isNCHW ? "NCHW" : "NHWC");
    NSLog(@"[PoLang] MNN bridge load: %s", debugInfo.c_str());
    return r && l;
}

void Detector::bgraToRgb(const uint8_t *bgra, int w, int h, int bpr) {
    size_t need = (size_t)w * h * 3;
    if (rgbBuf.size() < need) rgbBuf.resize(need);
    for (int y = 0; y < h; y++) {
        const uint8_t *row = bgra + (size_t)y * bpr;
        uint8_t *dst = rgbBuf.data() + (size_t)y * w * 3;
        for (int x = 0; x < w; x++) {
            const uint8_t *px = row + (size_t)x * 4;
            dst[x * 3 + 0] = px[2];  // R
            dst[x * 3 + 1] = px[1];  // G
            dst[x * 3 + 2] = px[0];  // B
        }
    }
}

inline void Detector::sampleRgb(float fx, float fy, int w, int h,
                                float &R, float &G, float &B) const {
    fx = clampf(fx, 0, w - 1);
    fy = clampf(fy, 0, h - 1);
    int x0 = (int)fx, y0 = (int)fy;
    int x1 = std::min(x0 + 1, w - 1);
    int y1 = std::min(y0 + 1, h - 1);
    float ax = fx - x0, ay = fy - y0;
    const uint8_t *rgb = rgbBuf.data();
    float out[3];
    for (int c = 0; c < 3; c++) {
        float v00 = rgb[(y0 * w + x0) * 3 + c];
        float v01 = rgb[(y0 * w + x1) * 3 + c];
        float v10 = rgb[(y1 * w + x0) * 3 + c];
        float v11 = rgb[(y1 * w + x1) * 3 + c];
        out[c] = v00 * (1 - ax) * (1 - ay) + v01 * ax * (1 - ay) +
                 v10 * (1 - ax) * ay + v11 * ax * ay;
    }
    R = out[0]; G = out[1]; B = out[2];
}

// ───────────────────────── Stage 1: RetinaFace ─────────────────────────

std::vector<FaceBox> Detector::runRetinaInfer(int w, int h) {
    // RetinaFace 推理：letterbox 预处理 → 前向 → 3-scale 解码 → NMS
    // 对标 Android detectRetinaFace() (mnn_face_detector.cpp:244-614)，
    // 返回所有 NMS 保留的人脸（320×320 模型空间，含 5 点关键点）。
    Stage *st = &retina;
    int S = st->inputSize;  // 320
    int totalPx = S * S;
    float mean = 127.5f, stdv = 128.0f;  // det_500m：无内置归一化

    MNN::Tensor tmpIn(st->input, st->input->getDimensionType());
    float *in = tmpIn.host<float>();
    if (!in) return {};

    // 填充归一化黑底
    float black = (0.0f - mean) / stdv;
    for (int i = 0; i < totalPx * 3; i++) in[i] = black;

    // letterbox：保持宽高比缩放 + 居中（与 Android detectRetinaFace 完全一致）
    float scale = std::min((float)S / w, (float)S / h);
    int sW = (int)(w * scale);
    int sH = (int)(h * scale);
    int padL = (S - sW) / 2;
    int padT = (S - sH) / 2;

    for (int y = 0; y < sH; y++) {
        for (int x = 0; x < sW; x++) {
            float srcX = (x + 0.5f) * w / sW - 0.5f;
            float srcY = (y + 0.5f) * h / sH - 0.5f;
            float R, G, B;
            sampleRgb(srcX, srcY, w, h, R, G, B);
            int dIdx = (padT + y) * S + (padL + x);
            if (st->isNCHW) {
                in[0 * totalPx + dIdx] = (R - mean) / stdv;
                in[1 * totalPx + dIdx] = (G - mean) / stdv;
                in[2 * totalPx + dIdx] = (B - mean) / stdv;
            } else {
                in[dIdx * 3 + 0] = (R - mean) / stdv;
                in[dIdx * 3 + 1] = (G - mean) / stdv;
                in[dIdx * 3 + 2] = (B - mean) / stdv;
            }
        }
    }

    st->input->copyFromHostTensor(&tmpIn);
    st->interp->runSession(st->session);

    // 3-scale 解码（confidence=0.5, NMS IoU=0.4 —— Android 默认值）
    std::vector<FaceBox> all;
    processScale(0, 8, 0.5f, all);
    processScale(1, 16, 0.5f, all);
    processScale(2, 32, 0.5f, all);

    return nms(all, 0.4f);  // 已按置信度降序排序
}

bool Detector::runRetina(int w, int h, FaceBox &outBox) {
    auto kept = runRetinaInfer(w, h);
    if (kept.empty()) return false;

    // 取面积最大的脸（与原逻辑一致）
    FaceBox best = kept[0];
    for (const auto &b : kept) {
        if (b.area() > best.area()) best = b;
    }
    outBox = best;
    return true;
}

void Detector::processScale(int nameIdx, int stride, float threshold,
                            std::vector<FaceBox> &out) {
    Stage *st = &retina;
    MNN::Tensor *scoreOut = st->interp->getSessionOutput(st->session, st->outputNames[nameIdx].c_str());
    MNN::Tensor *bboxOut = st->interp->getSessionOutput(st->session, st->outputNames[nameIdx + 3].c_str());
    if (!scoreOut || !bboxOut) return;

    // 推导 featureSize / scoreChannels（移植自 Android，兼容 NCHW 与扁平化输出）
    int featH = scoreOut->height(), featW = scoreOut->width();
    int featureSize;
    int scoreChannels = scoreOut->channel();
    if (featH > 1 && featW > 1) {
        featureSize = featH;
    } else {
        int total = scoreOut->elementSize();
        if (scoreOut->batch() > 1 && scoreOut->channel() >= 1) {
            scoreChannels = scoreOut->channel();
            featureSize = (scoreChannels == 1)
                ? (int)std::sqrt(scoreOut->batch() / 2.0)
                : (int)std::sqrt(scoreOut->batch());
        } else {
            scoreChannels = 2;
            featureSize = (int)std::sqrt(total / 2.0);
        }
        if (featureSize <= 0) featureSize = st->inputSize / stride;
    }

    // 强制 CAFFE(NCHW) 布局读取，保证 [anchorIdx*c+ch] 线性索引正确
    MNN::Tensor scoreH(scoreOut, MNN::Tensor::CAFFE);
    MNN::Tensor bboxH(bboxOut, MNN::Tensor::CAFFE);
    scoreOut->copyToHostTensor(&scoreH);
    bboxOut->copyToHostTensor(&bboxH);
    const float *sd = scoreH.host<float>();
    const float *bd = bboxH.host<float>();
    if (!sd || !bd) return;

    // 5-point landmark 输出（Android mnn_face_detector.cpp:370, outputNames[6..8]）
    // CAFFE 布局读取，保证 [anchorIdx*10+ch] 线性索引正确
    const float *ld = nullptr;
    std::unique_ptr<MNN::Tensor> landmarkH;
    if (st->outputNames.size() > (size_t)(nameIdx + 6)) {
        MNN::Tensor *landmarkOut = st->interp->getSessionOutput(
            st->session, st->outputNames[nameIdx + 6].c_str());
        if (landmarkOut) {
            landmarkH = std::make_unique<MNN::Tensor>(landmarkOut, MNN::Tensor::CAFFE);
            landmarkOut->copyToHostTensor(landmarkH.get());
            ld = landmarkH->host<float>();
        }
    }

    int spatial = featureSize * featureSize;
    int numAnchor = 2;
    int totalAnchor = spatial * numAnchor;
    float maxSize = (float)st->inputSize;

    for (int y = 0; y < featureSize; y++) {
        for (int x = 0; x < featureSize; x++) {
            int spatialIdx = y * featureSize + x;
            float acx = (x + 0.5f) * stride;
            float acy = (y + 0.5f) * stride;
            for (int a = 0; a < numAnchor; a++) {
                int ai = spatialIdx * numAnchor + a;
                float faceScore = (scoreChannels == 1) ? sd[ai] : sd[totalAnchor + ai];
                if (faceScore < threshold) continue;

                float dx = bd[ai * 4 + 0];
                float dy = bd[ai * 4 + 1];
                float dw = bd[ai * 4 + 2];
                float dh = bd[ai * 4 + 3];
                // 与 InsightFace/ONNX 一致：x1=cx-dx*stride, x2=cx+dw*stride
                float x1 = clampf(acx - dx * stride, 0, maxSize);
                float y1 = clampf(acy - dy * stride, 0, maxSize);
                float x2 = clampf(acx + dw * stride, 0, maxSize);
                float y2 = clampf(acy + dh * stride, 0, maxSize);
                if (x1 >= x2 || y1 >= y2) continue;

                FaceBox box;
                box.x1 = x1;
                box.y1 = y1;
                box.x2 = x2;
                box.y2 = y2;
                box.confidence = faceScore;
                // Decode 5-point landmarks: cx + delta*stride
                // (Android mnn_face_detector.cpp:1073-1078, processRetinaFaceOutput)
                if (ld) {
                    for (int i = 0; i < 5; i++) {
                        box.landmarks[i * 2]     = acx + ld[ai * 10 + i * 2]     * stride;
                        box.landmarks[i * 2 + 1] = acy + ld[ai * 10 + i * 2 + 1] * stride;
                    }
                }
                out.push_back(box);
            }
        }
    }
}

std::vector<FaceBox> Detector::nms(std::vector<FaceBox> &faces, float threshold) {
    if (faces.empty()) return {};
    std::sort(faces.begin(), faces.end(),
              [](const FaceBox &a, const FaceBox &b) { return a.confidence > b.confidence; });
    std::vector<FaceBox> result;
    std::vector<bool> suppressed(faces.size(), false);
    for (size_t i = 0; i < faces.size(); i++) {
        if (suppressed[i]) continue;
        result.push_back(faces[i]);
        for (size_t j = i + 1; j < faces.size(); j++) {
            if (suppressed[j]) continue;
            // IoU
            float ix1 = std::max(faces[i].x1, faces[j].x1);
            float iy1 = std::max(faces[i].y1, faces[j].y1);
            float ix2 = std::min(faces[i].x2, faces[j].x2);
            float iy2 = std::min(faces[i].y2, faces[j].y2);
            float iw = std::max(0.0f, ix2 - ix1);
            float ih = std::max(0.0f, iy2 - iy1);
            float inter = iw * ih;
            float uni = faces[i].area() + faces[j].area() - inter;
            if (uni > 0 && inter / uni > threshold) suppressed[j] = true;
        }
    }
    return result;
}

// ───────────────────────── Stage 2: 2D106 关键点 ─────────────────────────

bool Detector::runLandmark(int w, int h, float roiL, float roiT,
                           float roiR, float roiB, float *out212) {
    Stage *st = &landmark;
    int S = st->inputSize;  // 192
    float roiW = roiR - roiL;
    float roiH = roiB - roiT;
    if (roiW <= 0 || roiH <= 0) return false;
    float looseSize = std::max(roiW, roiH);
    float centerX = (roiL + roiR) / 2.0f;
    float centerY = (roiT + roiB) / 2.0f;
    float inputScale = S / looseSize;
    int totalPx = S * S;

    MNN::Tensor tmpIn(st->input, st->input->getDimensionType());
    float *in = tmpIn.host<float>();
    if (!in) return false;

    // 预归一化（iOS：2d106det 内置归一化不生效，须手动 (x-mean)/std；黑底=(0-mean)/std）。
    // normMean/normStd 由 Stage.init 按 hasBuiltInNorm 设定（landmark=false → 127.5/128）。
    float black = (0.0f - st->normMean) / st->normStd;
    for (int i = 0; i < totalPx * 3; i++) in[i] = black;

    // centered-scale 裁剪（移植自 MnnLandmarkDetector.prepareInputBitmap）：
    // 正向 dst = inputScale*src + (96 - center*inputScale)
    // 逆向 src = (dst - 96)/inputScale + center
    // 同时累计每行/每列亮度的一阶/二阶矩，事后算「特征跨度」裁决输入是否被转置。
    std::vector<float> rowSum(S, 0.0f), rowSumSq(S, 0.0f);
    std::vector<float> colSum(S, 0.0f), colSumSq(S, 0.0f);
    // 16×16 输入亮度聚合（与 cropOrient 同源，无额外像素读取）
    const int G = 16;
    std::vector<float> gridSum(G * G, 0.0f);
    std::vector<int> gridCnt(G * G, 0);
    for (int dy = 0; dy < S; dy++) {
        for (int dx = 0; dx < S; dx++) {
            float srcX = (dx - S / 2.0f) / inputScale + centerX;
            float srcY = (dy - S / 2.0f) / inputScale + centerY;
            float R, Gc, B;
            sampleRgb(srcX, srcY, w, h, R, Gc, B);
            float lum = 0.299f * R + 0.587f * Gc + 0.114f * B;
            rowSum[dy] += lum; rowSumSq[dy] += lum * lum;
            colSum[dx] += lum; colSumSq[dx] += lum * lum;
            int gy = std::min(G - 1, dy * G / S);
            int gx = std::min(G - 1, dx * G / S);
            gridSum[gy * G + gx] += lum;
            gridCnt[gy * G + gx] += 1;
            int idx = dy * S + dx;
            float nr = (R - st->normMean) / st->normStd;
            float ng = (Gc - st->normMean) / st->normStd;
            float nb = (B - st->normMean) / st->normStd;
            if (st->isNCHW) {
                in[0 * totalPx + idx] = nr;
                in[1 * totalPx + idx] = ng;
                in[2 * totalPx + idx] = nb;
            } else {
                in[idx * 3 + 0] = nr;
                in[idx * 3 + 1] = ng;
                in[idx * 3 + 2] = nb;
            }
        }
    }
    // 行内方差（该行跨列的亮度变化）高 = 该行含人脸横向特征；列内方差高 = 该列含纵向特征。
    // 统计方差 > 0.4*max 的行/列数 = 人脸在输入中的竖向/横向特征跨度。
    float rowVarMax = 0.0f, colVarMax = 0.0f;
    std::vector<float> rowVar(S, 0.0f), colVar(S, 0.0f);
    for (int i = 0; i < S; i++) {
        float mr = rowSum[i] / S; rowVar[i] = rowSumSq[i] / S - mr * mr;
        if (rowVar[i] > rowVarMax) rowVarMax = rowVar[i];
        float mc = colSum[i] / S; colVar[i] = colSumSq[i] / S - mc * mc;
        if (colVar[i] > colVarMax) colVarMax = colVar[i];
    }
    int rowExt = 0, colExt = 0;
    for (int i = 0; i < S; i++) {
        if (rowVar[i] > 0.4f * rowVarMax) rowExt++;
        if (colVar[i] > 0.4f * colVarMax) colExt++;
    }
    dbgRowExtent = rowExt; dbgColExtent = colExt;

    // 构建 16×16 亮度 ASCII 图（首行=输入顶部，Y-down；' '=暗 → '@'=亮）
    static const char *ramp = " .:-=+*#%@";
    dbgGrid.clear();
    for (int gy = 0; gy < G; gy++) {
        for (int gx = 0; gx < G; gx++) {
            float avg = gridCnt[gy * G + gx] > 0 ? gridSum[gy * G + gx] / gridCnt[gy * G + gx] : 0;
            int lvl = (int)(avg * 10.0f / 256.0f);
            if (lvl < 0) lvl = 0;
            if (lvl > 9) lvl = 9;
            dbgGrid += ramp[lvl];
        }
        dbgGrid += '|';
    }

    st->input->copyFromHostTensor(&tmpIn);
    st->interp->runSession(st->session);

    MNN::Tensor *outT = st->interp->getSessionOutput(st->session, st->outputNames[0].c_str());
    if (!outT) outT = st->interp->getSessionOutput(st->session, nullptr);
    if (!outT) return false;
    MNN::Tensor outH(outT, outT->getDimensionType());
    outT->copyToHostTensor(&outH);
    const float *d = outH.host<float>();
    if (!d) return false;

    // 解析：模型输出 [-1,1] → (out+1)*96 = dst 像素 → 逆变换 → 原图像素
    //   imgX = centerX + outX * looseSize / 2   （96/inputScale = looseSize/2）
    // 归一化到 [0,1]，输出为 InsightFace 原始点序（未 FULL_REMAP/镜像，交给 Swift）
    int n = std::min(outH.elementSize() / 2, 106);
    dbgOutElements = (int)outH.elementSize();
    dbgCenterX = centerX; dbgCenterY = centerY; dbgLooseSize = looseSize;
    bool firstPt = true;
    for (int i = 0; i < n; i++) {
        float ox = d[i * 2];
        float oy = d[i * 2 + 1];
        if (firstPt) { dbgOxMin = dbgOxMax = ox; dbgOyMin = dbgOyMax = oy; firstPt = false; }
        else {
            if (ox < dbgOxMin) dbgOxMin = ox; if (ox > dbgOxMax) dbgOxMax = ox;
            if (oy < dbgOyMin) dbgOyMin = oy; if (oy > dbgOyMax) dbgOyMax = oy;
        }
        float imgX = centerX + ox * looseSize / 2.0f;
        float imgY = centerY + oy * looseSize / 2.0f;
        out212[i * 2] = clampf(imgX / (float)w, 0.0f, 1.0f);
        out212[i * 2 + 1] = clampf(imgY / (float)h, 0.0f, 1.0f);
    }
    for (int i = n; i < 106; i++) { out212[i * 2] = 0; out212[i * 2 + 1] = 0; }
    return n > 0;
}

// 输入朝向探针实现（见声明注释）
std::string Detector::diagnoseInputOrientation(int w, int h, float roiL, float roiT,
                                               float roiR, float roiB) {
    Stage *st = &landmark;
    int S = st->inputSize;  // 192
    float looseSize = std::max(roiR - roiL, roiB - roiT);
    if (looseSize <= 0) return "loose<=0";
    float centerX = (roiL + roiR) / 2.0f;
    float centerY = (roiT + roiB) / 2.0f;
    float inputScale = S / looseSize;
    int totalPx = S * S;

    // 1) 采样「正向裁剪」到 U[S][S][3]（与 runLandmark 相同的采样几何，Y-down）
    std::vector<uint8_t> U((size_t)S * S * 3);
    for (int dy = 0; dy < S; dy++) {
        for (int dx = 0; dx < S; dx++) {
            float srcX = (dx - S / 2.0f) / inputScale + centerX;
            float srcY = (dy - S / 2.0f) / inputScale + centerY;
            float R, G, B;
            sampleRgb(srcX, srcY, w, h, R, G, B);
            size_t o = ((size_t)dy * S + dx) * 3;
            U[o] = (uint8_t)R; U[o + 1] = (uint8_t)G; U[o + 2] = (uint8_t)B;
        }
    }

    // 旋转映射：给定模型输入格点 (dx,dy)，返回 U 中读取的 (ux,uy)。
    // U 为正向（chin 在大 uy=底部）。各旋转把 U 旋转后填入模型输入。
    auto rotSrc = [](int rot, int dx, int dy, int S, int &ux, int &uy) {
        switch (rot) {
            case 90:  ux = dy;         uy = S - 1 - dx; break;  // 顺时针 90°
            case 180: ux = S - 1 - dx; uy = S - 1 - dy; break;
            case 270: ux = S - 1 - dy; uy = dx;         break;  // 逆时针 90°
            default:  ux = dx;         uy = dy;         break;  // 0° 原样
        }
    };

    std::string out;
    MNN::Tensor tmpIn(st->input, st->input->getDimensionType());
    float *in = tmpIn.host<float>();
    if (!in) return "no-host";

    // 归一化探针：2d106det 声称有内置归一化（_minusscalar0/_mulscalar0），当前喂 raw 0-255。
    // 若 iOS MNN 未执行这些算子，conv 层吃到未归一化输入 → 关键点畸变（Y 压扁）。
    // 测试喂「预归一化 (x-127.5)/128」是否使输出变高（oy>ox）——若是，则根因=内置归一化未生效，
    // 修复=iOS 改喂预归一化（hasBuiltInNorm=false）。同时检测下巴是否在底部（anatomy）。
    auto runOnce = [&](int rot, bool preNorm, const char *tag) {
        for (int i = 0; i < totalPx * 3; i++) in[i] = 0.0f;
        for (int dy = 0; dy < S; dy++) {
            for (int dx = 0; dx < S; dx++) {
                int ux, uy;
                rotSrc(rot, dx, dy, S, ux, uy);
                size_t u = ((size_t)uy * S + ux) * 3;
                float R = U[u], G = U[u + 1], B = U[u + 2];
                if (preNorm) { R = (R - 127.5f) / 128.0f; G = (G - 127.5f) / 128.0f; B = (B - 127.5f) / 128.0f; }
                int idx = dy * S + dx;
                if (st->isNCHW) {
                    in[0 * totalPx + idx] = R;
                    in[1 * totalPx + idx] = G;
                    in[2 * totalPx + idx] = B;
                } else {
                    in[idx * 3 + 0] = R;
                    in[idx * 3 + 1] = G;
                    in[idx * 3 + 2] = B;
                }
            }
        }
        st->input->copyFromHostTensor(&tmpIn);
        st->interp->runSession(st->session);
        MNN::Tensor *oT = st->interp->getSessionOutput(st->session, st->outputNames[0].c_str());
        if (!oT) { out += std::string(tag) + ":no-out | "; return; }
        MNN::Tensor oH(oT, oT->getDimensionType());
        oT->copyToHostTensor(&oH);
        const float *d = oH.host<float>();
        if (!d) { out += std::string(tag) + ":no-host | "; return; }
        float oxmin = 1e9f, oxmax = -1e9f, oymin = 1e9f, oymax = -1e9f;
        int n = std::min((int)oH.elementSize() / 2, 106);
        for (int i = 0; i < n; i++) {
            float ox = d[i * 2], oy = d[i * 2 + 1];
            if (ox < oxmin) oxmin = ox; if (ox > oxmax) oxmax = ox;
            if (oy < oymin) oymin = oy; if (oy > oymax) oymax = oy;
        }
        float oxspan = oxmax - oxmin, oyspan = oymax - oymin;
        char buf[128];
        snprintf(buf, sizeof(buf), "%s: ox=%.2f oy=%.2f %s | ",
                 tag, oxspan, oyspan, oyspan > oxspan ? "TALL" : "wide");
        out += buf;
    };

    for (int rot : {0, 90, 180, 270}) {
        char tag[16]; snprintf(tag, sizeof(tag), "rot%d", rot);
        runOnce(rot, false, tag);
    }
    // 归一化对比（rot0 正向脸）：raw vs 预归一化
    runOnce(0, false, "raw0");
    runOnce(0, true, "norm0");
    return out;
}

// ───────────────────────── 顶层 detect ─────────────────────────

bool Detector::detect(const uint8_t *bgra, int w, int h, int bpr, float *out212) {
    if (!retina.ok || !landmark.ok) { debugInfo = "not-ready"; return false; }

    bgraToRgb(bgra, w, h, bpr);

    FaceBox box;
    if (!runRetina(w, h, box)) {
        debugInfo = "retina:no-face";
        dbgHasStage1 = false;
        return false;
    }

    // letterbox 逆变换：320 空间 box → 原图像素（移植自 MnnRoiDetector.kt）
    float scale = 320.0f / std::max((float)w, (float)h);
    int scaledW = (int)((float)w * scale);
    int scaledH = (int)((float)h * scale);
    float padLeft = (320 - scaledW) / 2.0f;
    float padTop = (320 - scaledH) / 2.0f;
    float mx1 = (box.x1 - padLeft) / scale;
    float my1 = (box.y1 - padTop) / scale;
    float mx2 = (box.x2 - padLeft) / scale;
    float my2 = (box.y2 - padTop) / scale;

    // 记录 Stage-1 诊断快照（裁决压扁根因用）
    dbgBoxX1 = box.x1; dbgBoxY1 = box.y1; dbgBoxX2 = box.x2; dbgBoxY2 = box.y2;
    dbgScale = scale; dbgPadL = padLeft; dbgPadT = padTop;
    dbgMx1 = mx1; dbgMy1 = my1; dbgMx2 = mx2; dbgMy2 = my2;
    dbgW = w; dbgH = h;

    // ROI ×1.2 居中扩展 + clamp（与 MnnRoiDetector 一致）
    float ccx = (mx1 + mx2) / 2.0f;
    float ccy = (my1 + my2) / 2.0f;
    float fw = (mx2 - mx1) * 1.2f;
    float fh = (my2 - my1) * 1.2f;
    float roiL = clampf(ccx - fw / 2.0f, 0.0f, (float)w);
    float roiT = clampf(ccy - fh / 2.0f, 0.0f, (float)h);
    float roiR = clampf(ccx + fw / 2.0f, 0.0f, (float)w);
    float roiB = clampf(ccy + fh / 2.0f, 0.0f, (float)h);

    dbgRoiL = roiL; dbgRoiT = roiT; dbgRoiR = roiR; dbgRoiB = roiB;
    dbgHasStage1 = true;

    bool ok = runLandmark(w, h, roiL, roiT, roiR, roiB, out212);
    // 输入朝向探针（仅诊断开启时运行）：4 种旋转喂模型，找出使输出变「高」的朝向，
    // 裁决宽扁形变是否源于输入 X/Y 转置。详见 diagnoseInputOrientation 注释。
    if (ok && dbgOrientProbe) {
        dbgOrientResult = diagnoseInputOrientation(w, h, roiL, roiT, roiR, roiB);
    }
    debugInfo = ok ? "ok" : "landmark:fail";
    return ok;
}

// ───────────────────────── 多人脸 detectAll ─────────────────────────

std::vector<PixelFace> Detector::detectAll(const uint8_t *bgra, int w, int h, int bpr) {
    // 仅需 RetinaFace 第一阶段（不需 landmark 模型）。
    // 对标 Android MnnFaceDetector.detectRetinaFaces() (MnnFaceDetector.kt:272-295)。
    if (!retina.ok) { debugInfo = "not-ready"; return {}; }

    bgraToRgb(bgra, w, h, bpr);

    auto kept = runRetinaInfer(w, h);  // 320×320 模型空间，含 5 点关键点
    if (kept.empty()) { debugInfo = "retina:no-face"; return {}; }

    // letterbox 逆变换：320 空间 → 原图像素（与 detect() 中相同的逆变换）
    float scale = 320.0f / std::max((float)w, (float)h);
    int scaledW = (int)((float)w * scale);
    int scaledH = (int)((float)h * scale);
    float padLeft = (320 - scaledW) / 2.0f;
    float padTop = (320 - scaledH) / 2.0f;

    std::vector<PixelFace> result;
    result.reserve(kept.size());
    for (const auto &box : kept) {
        PixelFace pf;
        // ROI 逆变换 + clamp 到图像边界
        float mx1 = clampf((box.x1 - padLeft) / scale, 0.0f, (float)w);
        float my1 = clampf((box.y1 - padTop) / scale, 0.0f, (float)h);
        float mx2 = clampf((box.x2 - padLeft) / scale, 0.0f, (float)w);
        float my2 = clampf((box.y2 - padTop) / scale, 0.0f, (float)h);
        pf.roiX = mx1;
        pf.roiY = my1;
        pf.roiW = mx2 - mx1;
        pf.roiH = my2 - my1;
        pf.confidence = box.confidence;
        // 5-point landmark 逆变换 + clamp
        for (int i = 0; i < 5; i++) {
            pf.landmarks[i * 2]     = clampf((box.landmarks[i * 2]     - padLeft) / scale, 0.0f, (float)w);
            pf.landmarks[i * 2 + 1] = clampf((box.landmarks[i * 2 + 1] - padTop)  / scale, 0.0f, (float)h);
        }
        result.push_back(pf);
    }

    debugInfo = std::string("ok faces=") + std::to_string(kept.size());
    return result;
}

} // namespace polang_mnn

// ───────────────────────── ObjC 桥接 ─────────────────────────

// PLDetectedFace 私有工厂（仅 .mm 内部使用）
@interface PLDetectedFace ()
+ (instancetype)faceWithRoi:(CGRect)roi
                confidence:(float)confidence
                 landmarks:(const float *)landmarks;
@end

@implementation PLDetectedFace {
    CGRect _roi;
    float _confidence;
    float _landmarks[10];
}

- (CGRect)roi { return _roi; }
- (float)confidence { return _confidence; }

- (void)getLandmarks:(float *)outLandmarks {
    if (outLandmarks) {
        memcpy(outLandmarks, _landmarks, sizeof(float) * 10);
    }
}

+ (instancetype)faceWithRoi:(CGRect)roi
                confidence:(float)confidence
                 landmarks:(const float *)landmarks {
    PLDetectedFace *f = [[PLDetectedFace alloc] init];
    f->_roi = roi;
    f->_confidence = confidence;
    if (landmarks) {
        memcpy(f->_landmarks, landmarks, sizeof(float) * 10);
    }
    return f;
}

@end

@interface PLMnnFaceDetector () {
    polang_mnn::Detector *_det;
}
@end

@implementation PLMnnFaceDetector

- (instancetype)init {
    self = [super init];
    if (self) {
        _det = new polang_mnn::Detector();
    }
    return self;
}

- (void)dealloc {
    delete _det;
    _det = nullptr;
}

- (BOOL)loadRetinaModel:(NSString *)retinaPath landmarkModel:(NSString *)landmarkPath {
    return _det->loadBoth([retinaPath UTF8String], [landmarkPath UTF8String]);
}

- (BOOL)ready {
    return _det->retina.ok && _det->landmark.ok;
}

- (BOOL)detect:(const uint8_t *)bgra
        width:(int)width
       height:(int)height
   bytesPerRow:(int)bytesPerRow
     outPoints:(float *)outPoints {
    return _det->detect(bgra, width, height, bytesPerRow, outPoints);
}

/// 开启输入朝向探针（仅静态诊断用）：detect 后会额外以 4 种旋转跑 landmark 模型，
/// 把每种方向的原始输出 ox/oy 跨度写入 stage1Dump 的 ORIENT-PROBE 段。
- (void)setOrientProbe:(BOOL)on {
    _det->dbgOrientProbe = on;
}

- (NSArray<PLDetectedFace *> *)detectAllFaces:(const uint8_t *)bgra
                                        width:(int)width
                                       height:(int)height
                                  bytesPerRow:(int)bytesPerRow {
    auto faces = _det->detectAll(bgra, width, height, bytesPerRow);
    if (faces.empty()) return @[];

    NSMutableArray *result = [NSMutableArray arrayWithCapacity:faces.size()];
    for (const auto &f : faces) {
        CGRect roi = CGRectMake(f.roiX, f.roiY, f.roiW, f.roiH);
        [result addObject:[PLDetectedFace faceWithRoi:roi
                                          confidence:f.confidence
                                           landmarks:f.landmarks]];
    }
    return result;
}

- (int)detectAllFacesFlat:(const uint8_t *)bgra
                    width:(int)width
                   height:(int)height
              bytesPerRow:(int)bytesPerRow
                   outBuf:(float *)outBuf
                 maxFaces:(int)maxFaces {
    auto faces = _det->detectAll(bgra, width, height, bytesPerRow);
    int n = (int)std::min((size_t)maxFaces, faces.size());
    for (int i = 0; i < n; i++) {
        const auto &f = faces[i];
        float *p = outBuf + (size_t)i * 15;
        p[0] = f.roiX; p[1] = f.roiY; p[2] = f.roiW; p[3] = f.roiH;
        p[4] = f.confidence;
        for (int j = 0; j < 10; j++) p[5 + j] = f.landmarks[j];
    }
    return n;
}

- (NSString *)debugInfo {
    return [NSString stringWithUTF8String:_det->debugInfo.c_str()];
}

- (NSString *)stage1Dump {
    if (!_det->dbgHasStage1) return @"no-stage1";
    char buf[700];
    float bw = _det->dbgBoxX2 - _det->dbgBoxX1;
    float bh = _det->dbgBoxY2 - _det->dbgBoxY1;
    float iw = _det->dbgMx2 - _det->dbgMx1;
    float ih = _det->dbgMy2 - _det->dbgMy1;
    float rw = _det->dbgRoiR - _det->dbgRoiL;
    float rh = _det->dbgRoiB - _det->dbgRoiT;
    float oxspan = _det->dbgOxMax - _det->dbgOxMin;
    float oyspan = _det->dbgOyMax - _det->dbgOyMin;
    // 布局裁决：retina(工作) vs landmark(压扁) 的输入/输出张量形状 + 维度类型。
    // 关键假设：若两阶段 dimtype 不同，或 fc1 输出形状非 [1,212]/[1,106,2] 而是 [1,2,106]，
    // 则 NCHW 填充/copyFromHostTensor/copyToHostTensor 会把方形输入或交错输出扭曲 → Y 压扁。
    auto dtStr = [](MNN::Tensor *t) -> const char * {
        if (!t) return "null";
        auto dt = t->getDimensionType();
        return (dt == MNN::Tensor::CAFFE) ? "CAFFE" :
               (dt == MNN::Tensor::TENSORFLOW) ? "TF" : "OTHER";
    };
    auto shapeStr = [](MNN::Tensor *t, char *out, size_t n) {
        if (!t) { snprintf(out, n, "null"); return; }
        snprintf(out, n, "%dx%dx%dx%d", t->batch(), t->channel(), t->height(), t->width());
    };
    char rs[32], ls[32], os[32];
    shapeStr(_det->retina.input, rs, sizeof(rs));
    shapeStr(_det->landmark.input, ls, sizeof(ls));
    MNN::Tensor *fc1 = _det->landmark.interp
        ? _det->landmark.interp->getSessionOutput(_det->landmark.session, "fc1") : nullptr;
    shapeStr(fc1, os, sizeof(os));
    const char *lbk = (_det->landmark.backend == MNN_FORWARD_METAL) ? "Metal" : "CPU";
    snprintf(buf, sizeof(buf),
        "img=%dx%d | box320=[%.1f,%.1f-%.1f,%.1f] w=%.1f h=%.1f asp=%.2f | inv sc=%.4f padL=%.1f padT=%.1f | boxImg=[%.0f,%.0f-%.0f,%.0f] w=%.0f h=%.0f asp=%.2f | roi=[%.0f,%.0f-%.0f,%.0f] w=%.0f h=%.0f | s2 ctr=(%.0f,%.0f) loose=%.0f outElem=%d | rawOut ox=[%.3f,%.3f]%.3f oy=[%.3f,%.3f]%.3f | cropOrient rE=%d cE=%d | LAYOUT retina[%s]=%s landmark[%s]=%s fc1[%s]=%s(isNCHW r=%d l=%d) landBackend=%s",
        _det->dbgW, _det->dbgH,
        _det->dbgBoxX1, _det->dbgBoxY1, _det->dbgBoxX2, _det->dbgBoxY2,
        bw, bh, bh > 1e-6f ? bw / bh : 0.0f,
        _det->dbgScale, _det->dbgPadL, _det->dbgPadT,
        _det->dbgMx1, _det->dbgMy1, _det->dbgMx2, _det->dbgMy2, iw, ih,
        ih > 1e-6f ? iw / ih : 0.0f,
        _det->dbgRoiL, _det->dbgRoiT, _det->dbgRoiR, _det->dbgRoiB, rw, rh,
        _det->dbgCenterX, _det->dbgCenterY, _det->dbgLooseSize, _det->dbgOutElements,
        _det->dbgOxMin, _det->dbgOxMax, oxspan,
        _det->dbgOyMin, _det->dbgOyMax, oyspan,
        _det->dbgRowExtent, _det->dbgColExtent,
        rs, dtStr(_det->retina.input),
        ls, dtStr(_det->landmark.input),
        os, dtStr(fc1),
        _det->retina.isNCHW ? 1 : 0, _det->landmark.isNCHW ? 1 : 0, lbk);
    // 注：lbk (landmark backend: CPU/Metal) 末尾 %s 由 snprintf 尾参提供
    NSString *main = [NSString stringWithUTF8String:buf];
    if (!_det->dbgOrientResult.empty()) {
        main = [main stringByAppendingFormat:@" | ORIENT-PROBE %s", _det->dbgOrientResult.c_str()];
    }
    if (!_det->dbgGrid.empty()) {
        NSString *grid = [NSString stringWithUTF8String:_det->dbgGrid.c_str()];
        main = [NSString stringWithFormat:@"%@\nINPUT-GRID(16x16 top→bot, ' '=暗→'@'=亮):\n%@", main, grid];
    }
    return main;
}

@end
