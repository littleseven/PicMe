//
//  MnnFaceEmbedderBridge.h
//  PoLang
//
//  ObjC 桥接层：封装 Glint360K-R100 人脸 embedding 提取器（ArcFace 512-dim），
//  对标 Android `engines/beauty-engine/src/main/cpp/mnn_face_embedder.cpp` 的生产路径。
//
//  模型: glintr100.mnn
//  输入: input.1, [1,3,112,112], 归一化 (pixel-127.5)/128.0, RGB（不 swapRB）
//  输出: 512 维 float, L2 归一化
//
//  iOS 关键差异（见 mnn-ios-integration skill 补验 A，已 spike 验证）：
//   1. 后端固定 MNN_FORWARD_CPU + numThread=4（Metal 在本设备精度异常；CPU 为正确性基准）。
//   2. 显式 BackendConfig（成员，生命周期 ≥ session）+ precision=Precision_High
//      （默认 nullptr→SIGSEGV；默认 Normal/fp16→数值错误）。
//
//  线程模型：单线程串行调用（调用方负责同步）。
//

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// MNN 人脸 Embedding 提取桥接（Glint360K-R100 ArcFace 512-dim）。
@interface PLMnnFaceEmbedder : NSObject

/// 加载 MNN 模型。
/// @param modelPath glintr100.mnn 文件路径。
/// @return YES 成功创建 session 并绑定输入输出张量。
- (BOOL)loadModel:(NSString *)modelPath;

/// 是否已就绪（模型已加载）。
@property (nonatomic, readonly) BOOL ready;

/// 提取 112×112 对齐人脸的 512 维 L2 归一化 embedding。
/// @param rgb  112×112 交错 RGB 字节（width×height×3）。
/// @param w    图像宽（应 = 112）。
/// @param h    图像高（应 = 112）。
/// @return 512 × 4 = 2048 字节 NSData（Float32 little-endian, L2 归一化）；失败返回 nil。
- (nullable NSData *)extractEmbedding:(const uint8_t *)rgb
                                width:(int)w
                               height:(int)h;

@end

NS_ASSUME_NONNULL_END
