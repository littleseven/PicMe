#ifndef PICME_MNN_FACE_DETECTOR_H
#define PICME_MNN_FACE_DETECTOR_H

#include <vector>
#include <string>
#include <memory>

// MNN headers
#include <MNN/Interpreter.hpp>
#include <MNN/MNNDefine.h>
#include <MNN/Tensor.hpp>
#include <MNN/ImageProcess.hpp>

namespace picme {

struct FaceBox {
    float x1, y1, x2, y2;
    float confidence;
    float landmarks[10]; // 5 points * 2

    float width() const { return x2 - x1; }
    float height() const { return y2 - y1; }
    float area() const { return width() * height(); }
};

/**
 * MNN 人脸检测器封装
 * 支持 ROI 检测（RetinaFace）和关键点检测（2D106）
 * 兼容骁龙 765G + Adreno 620（OpenCL）
 */
class MnnFaceDetector {
public:
    MnnFaceDetector();
    ~MnnFaceDetector();

    /**
     * 加载 MNN 模型
     * @param modelPath MNN 模型文件路径
     * @param inputSize 模型输入尺寸（正方形）
     * @param useGpu 是否使用 OpenCL GPU
     * @param inputName 输入层名称
     * @param outputNames 输出层名称列表（RetinaFace 多输出）
     */
    bool load(const std::string &modelPath,
              int inputSize,
              bool useGpu,
              const std::string &inputName = "input",
              const std::vector<std::string> &outputNames = {});

    /**
     * 单输出检测（2D106 关键点）
     */
    std::vector<float> detect(const unsigned char *imageData,
                              int width,
                              int height,
                              int channels);

    /**
     * [性能优化] 获取最后一次 detect() 的结果缓冲区引用
     * 避免 std::vector 返回值拷贝，在 JNI 层直接读取
     */
    const std::vector<float>& getLastDetectResult() const { return resultBuffer_; }

    /**
     * RetinaFace 多输出检测（score + bbox + landmark）
     */
    std::vector<FaceBox> detectRetinaFace(const unsigned char *imageData,
                                          int width,
                                          int height,
                                          int channels,
                                          float confidenceThreshold = 0.5f,
                                          float nmsThreshold = 0.4f);

    /**
     * [Zero-Copy] RetinaFace 检测——直接从 YUV NV21 输入
     * 使用 MNN ImageProcess::convert 做 NV21→RGB + resize + 归一化，
     * 替换手动 pixel loop，消除 Kotlin 层 YUV→ARGB→RGB 的多重 CPU 拷贝
     *
     * @param nv21Data 紧凑 NV21 数据 (Y 平面 + 交错 VU 平面)
     * @param width 原始图像宽度（未旋转）
     * @param height 原始图像高度（未旋转）
     * @param rotationDegrees 旋转角度 (0/90/180/270)，在 MNN Matrix 中完成旋转，零额外拷贝
     */
    std::vector<FaceBox> detectRetinaFaceFromNv21(const unsigned char *nv21Data,
                                                   int width,
                                                   int height,
                                                   int rotationDegrees,
                                                   float confidenceThreshold = 0.5f,
                                                   float nmsThreshold = 0.4f);

    /**
     * [Zero-Copy] 单输出检测（2D106 关键点）——直接从 YUV NV21 输入
     * @param rotationDegrees 旋转角度 (0/90/180/270)
     */
    std::vector<float> detectFromNv21(const unsigned char *nv21Data,
                                      int width,
                                      int height,
                                      int rotationDegrees);

    /**
     * [Zero-Copy] 单输出检测（2D106 关键点）——YUV NV21 + ROI 裁剪
     * 跳过 Bitmap 创建，直接通过 MNN ImageProcess 在 GPU 上完成
     * NV21→RGB + 旋转 + ROI 裁剪 + 缩放到 INPUT_SIZE 的一体化预处理。
     *
     * @param nv21Data 紧凑 NV21 数据
     * @param nv21Width NV21 图像宽度（未旋转）
     * @param nv21Height NV21 图像高度（未旋转）
     * @param rotationDegrees 旋转角度 (0/90/180/270)
     * @param roiLeft ROI 左边界（旋转后像素坐标）
     * @param roiTop ROI 上边界（旋转后像素坐标）
     * @param roiRight ROI 右边界（旋转后像素坐标）
     * @param roiBottom ROI 下边界（旋转后像素坐标）
     */
    std::vector<float> detectFromNv21(const unsigned char *nv21Data,
                                      int nv21Width, int nv21Height,
                                      int rotationDegrees,
                                      int roiLeft, int roiTop,
                                      int roiRight, int roiBottom);

    enum ReleaseFlags {
        RELEASE_TENSORS = 1 << 0,
        RELEASE_SESSION = 1 << 1,
        RELEASE_MODEL = 1 << 2,
        RELEASE_INTERPRETER = 1 << 3,
        RELEASE_ALL = RELEASE_TENSORS | RELEASE_SESSION | RELEASE_MODEL | RELEASE_INTERPRETER
    };

    void release(int flags = RELEASE_ALL);
    bool rebuildSession();

    bool isLoaded() const { return loaded_; }

    /**
     * 设置日志开关状态（从 Kotlin 层传递）
     */
    static void setLogEnabled(bool enabled) { logEnabled_ = enabled; }
    static bool isLogEnabled() { return logEnabled_; }

private:
    static bool logEnabled_;
    std::shared_ptr<MNN::Interpreter> interpreter_;
    MNN::Session *session_;
    MNN::Tensor *inputTensor_;
    std::vector<MNN::Tensor *> outputTensors_;

    int inputSize_;
    bool useGpu_;
    bool loaded_;
    bool hasBuiltInNormalization_;  // [关键] 模型是否包含内置归一化节点
    bool modelBufferReleased_ = false;
    std::string modelPath_;
    std::string inputName_;
    std::vector<std::string> outputNames_;

    // 预处理配置 (RGB 输入，保留兼容旧路径)
    std::unique_ptr<MNN::CV::ImageProcess> pretreat_;

    // [Zero-Copy] NV21 YUV 预处理配置
    std::unique_ptr<MNN::CV::ImageProcess> pretreatNv21_;

    /**
     * [Zero-Copy] 构建 NV21→InputTensor 的 MNN Matrix
     *
     * MNN Matrix 是「目标→源」的仿射变换矩阵，将目标像素 (inputSize×inputSize 空间)
     * 映射回源 NV21 像素坐标。旋转、letterbox、ROI 裁剪全部在此矩阵中完成，
     * 由 MNN ImageProcess::convert 内部在 GPU 上一次性执行，零额外拷贝。
     *
     * @param srcWidth NV21 原始宽度（未旋转）
     * @param srcHeight NV21 原始高度（未旋转）
     * @param rotationDegrees 旋转角度 (0/90/180/270)
     * @param roiLeft ROI 左边界（旋转后坐标），-1 表示不裁剪
     * @param roiTop ROI 上边界（旋转后坐标），-1 表示不裁剪
     * @param roiRight ROI 右边界（旋转后坐标），-1 表示不裁剪
     * @param roiBottom ROI 下边界（旋转后坐标），-1 表示不裁剪
     */
    MNN::CV::Matrix buildNv21TransformMatrix(int srcWidth, int srcHeight,
                                              int rotationDegrees,
                                              int roiLeft = -1, int roiTop = -1,
                                              int roiRight = -1, int roiBottom = -1);

    // [性能优化] 复用结果缓冲区，避免每帧 std::vector 分配
    std::vector<float> resultBuffer_;
    std::vector<float> retinaResultBuffer_;  // RetinaFace 单个人脸结果 [15 floats]

    // RetinaFace 后处理
    void processRetinaFaceOutput(const float *score,
                                 const float *bbox,
                                 const float *landmark,
                                 int featureSize,
                                 int stride,
                                 int scoreChannels,
                                 std::vector<FaceBox> &faces,
                                 float threshold);
    float calculateIoU(const FaceBox &a, const FaceBox &b);
    std::vector<FaceBox> applyNMS(std::vector<FaceBox> &faces, float threshold);

    // 辅助函数
    bool bindSessionTensors();
    bool createSession();
};

} // namespace picme

#endif // PICME_MNN_FACE_DETECTOR_H
