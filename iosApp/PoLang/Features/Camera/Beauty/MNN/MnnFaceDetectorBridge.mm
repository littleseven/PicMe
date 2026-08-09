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
    float area() const {
        float w = x2 - x1, h = y2 - y1;
        return (w > 0 && h > 0) ? w * h : 0;
    }
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
    bool ok = false;
    // 成员 BackendConfig：保证指针生命周期 ≥ session（iOS 关键坑：默认 nullptr 解引用 SIGSEGV）
    MNN::BackendConfig backendCfg;

    bool init(const std::string &path,
              int inputSizeVal,
              const std::string &inName,
              const std::vector<std::string> &outNames,
              bool builtInNorm,
              std::string &errOut) {
        interp.reset(MNN::Interpreter::createFromFile(path.c_str()));
        if (!interp) { errOut = "createFromFile failed"; return false; }

        MNN::ScheduleConfig sc;
        sc.type = MNN_FORWARD_CPU;          // iOS：CPU（Metal 精度异常）
        sc.numThread = 4;
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

    bool loadBoth(const std::string &rp, const std::string &lp);
    bool detect(const uint8_t *bgra, int w, int h, int bpr, float *out212);

private:
    void bgraToRgb(const uint8_t *bgra, int w, int h, int bpr);
    inline void sampleRgb(float fx, float fy, int w, int h, float &R, float &G, float &B) const;
    bool runRetina(int w, int h, FaceBox &outBox);
    void processScale(int nameIdx, int stride, float threshold, std::vector<FaceBox> &out);
    std::vector<FaceBox> nms(std::vector<FaceBox> &faces, float threshold);
    bool runLandmark(int w, int h, float roiL, float roiT, float roiR, float roiB, float *out212);
};

bool Detector::loadBoth(const std::string &rp, const std::string &lp) {
    std::string e1, e2;
    // det_500m：320×320，input.1，9 输出，无内置归一化（mean=127.5/std=128）
    bool r = retina.init(rp, 320, "input.1",
                         {"443", "468", "493", "446", "471", "496", "449", "474", "499"},
                         false, e1);
    // 2d106det：192×192，data/fc1，有内置归一化（mean=0/std=1 → 原始 0-255 像素）
    bool l = landmark.init(lp, 192, "data", {"fc1"}, true, e2);

    debugInfo = std::string("retina=") + (r ? "ok" : "FAIL") + "(" + e1 + ") " +
                "landmark=" + (l ? "ok" : "FAIL") + "(" + e2 + ")";
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

bool Detector::runRetina(int w, int h, FaceBox &outBox) {
    Stage *st = &retina;
    int S = st->inputSize;  // 320
    int totalPx = S * S;
    float mean = 127.5f, stdv = 128.0f;  // det_500m：无内置归一化

    MNN::Tensor tmpIn(st->input, st->input->getDimensionType());
    float *in = tmpIn.host<float>();
    if (!in) return false;

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

    std::vector<FaceBox> all;
    processScale(0, 8, 0.5f, all);
    processScale(1, 16, 0.5f, all);
    processScale(2, 32, 0.5f, all);

    auto kept = nms(all, 0.4f);
    if (kept.empty()) return false;

    // 取面积最大的脸
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

                out.push_back({x1, y1, x2, y2, faceScore});
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

    // 有内置归一化 → 传原始 0-255 像素（mean=0/std=1）；黑底=0
    for (int i = 0; i < totalPx * 3; i++) in[i] = 0.0f;

    // centered-scale 裁剪（移植自 MnnLandmarkDetector.prepareInputBitmap）：
    // 正向 dst = inputScale*src + (96 - center*inputScale)
    // 逆向 src = (dst - 96)/inputScale + center
    for (int dy = 0; dy < S; dy++) {
        for (int dx = 0; dx < S; dx++) {
            float srcX = (dx - S / 2.0f) / inputScale + centerX;
            float srcY = (dy - S / 2.0f) / inputScale + centerY;
            float R, G, B;
            sampleRgb(srcX, srcY, w, h, R, G, B);
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
    for (int i = 0; i < n; i++) {
        float ox = d[i * 2];
        float oy = d[i * 2 + 1];
        float imgX = centerX + ox * looseSize / 2.0f;
        float imgY = centerY + oy * looseSize / 2.0f;
        out212[i * 2] = clampf(imgX / (float)w, 0.0f, 1.0f);
        out212[i * 2 + 1] = clampf(imgY / (float)h, 0.0f, 1.0f);
    }
    for (int i = n; i < 106; i++) { out212[i * 2] = 0; out212[i * 2 + 1] = 0; }
    return n > 0;
}

// ───────────────────────── 顶层 detect ─────────────────────────

bool Detector::detect(const uint8_t *bgra, int w, int h, int bpr, float *out212) {
    if (!retina.ok || !landmark.ok) { debugInfo = "not-ready"; return false; }

    bgraToRgb(bgra, w, h, bpr);

    FaceBox box;
    if (!runRetina(w, h, box)) {
        debugInfo = "retina:no-face";
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

    // ROI ×1.2 居中扩展 + clamp（与 MnnRoiDetector 一致）
    float ccx = (mx1 + mx2) / 2.0f;
    float ccy = (my1 + my2) / 2.0f;
    float fw = (mx2 - mx1) * 1.2f;
    float fh = (my2 - my1) * 1.2f;
    float roiL = clampf(ccx - fw / 2.0f, 0.0f, (float)w);
    float roiT = clampf(ccy - fh / 2.0f, 0.0f, (float)h);
    float roiR = clampf(ccx + fw / 2.0f, 0.0f, (float)w);
    float roiB = clampf(ccy + fh / 2.0f, 0.0f, (float)h);

    bool ok = runLandmark(w, h, roiL, roiT, roiR, roiB, out212);
    debugInfo = ok ? "ok" : "landmark:fail";
    return ok;
}

}  // namespace polang_mnn

// ───────────────────────── ObjC 桥接 ─────────────────────────

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

- (NSString *)debugInfo {
    return [NSString stringWithUTF8String:_det->debugInfo.c_str()];
}

@end
