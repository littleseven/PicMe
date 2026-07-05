#ifndef PICME_MNN_FACE_EMBEDDER_H
#define PICME_MNN_FACE_EMBEDDER_H

#include <vector>
#include <string>
#include <memory>
#include <map>

#include <MNN/Interpreter.hpp>
#include <MNN/MNNDefine.h>
#include <MNN/Tensor.hpp>

namespace picme {

/**
 * 通用 MNN 人脸 Embedding 提取器
 *
 * 不依赖 MnnFaceDetector，直接使用 MNN Interpreter 加载人脸 embedding 模型，
 * 输入 112x112 RGB，输出 512 维 L2 归一化 embedding。
 *
 * 当前默认模型: glintr100.mnn (Glint360K-R100-MNN)
 * 输入: input.1, [1, 3, 112, 112]
 * 输出: 512-dim embedding (自动从多输出中定位)
 */
class MnnFaceEmbedder {
public:
    MnnFaceEmbedder();
    ~MnnFaceEmbedder();

    /**
     * 加载 MNN 模型
     * @param modelPath 模型文件路径
     * @param inputSize 输入尺寸（正方形，默认 112）
     * @param embeddingDim 输出维度（默认 512）
     * @param inputName 输入层名称（默认 "input.1"）
     * @param preferredOutputName 优先使用的输出层名称（空则自动查找）
     * @param useGpu 是否请求 OpenCL GPU 后端（失败可回退 CPU）
     */
    bool load(const std::string &modelPath,
              int inputSize = 112,
              int embeddingDim = 512,
              const std::string &inputName = "input.1",
              const std::string &preferredOutputName = "",
              bool useGpu = false);

    /**
     * 提取人脸 embedding
     * @param imageData RGB 像素数据，尺寸为 inputSize x inputSize x 3
     * @param width 图像宽度（应等于 inputSize）
     * @param height 图像高度（应等于 inputSize）
     * @param channels 通道数（应等于 3）
     * @return 512 维 L2 归一化 embedding；失败返回空 vector
     */
    std::vector<float> extract(const unsigned char *imageData,
                               int width,
                               int height,
                               int channels);

    bool isLoaded() const { return loaded_; }
    int inputSize() const { return inputSize_; }
    int embeddingDim() const { return embeddingDim_; }

    void release();

private:
    std::shared_ptr<MNN::Interpreter> interpreter_;
    MNN::Session *session_;
    MNN::Tensor *inputTensor_;
    MNN::Tensor *outputTensor_;

    int inputSize_;
    int embeddingDim_;
    std::string inputName_;
    std::string outputName_;
    std::string preferredOutputName_;
    bool loaded_;
    bool useGpu_;

    // 复用结果缓冲区
    std::vector<float> resultBuffer_;

    bool createSession();
    bool bindInputOutput();
    MNN::Tensor *findEmbeddingOutput(const std::map<std::string, MNN::Tensor *> &outputs);
    void l2Normalize(float *data, int size);
};

} // namespace picme

#endif // PICME_MNN_FACE_EMBEDDER_H
