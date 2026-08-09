//
//  MnnFaceDetectorBridge.h
//  PoLang
//
//  ObjC 桥接层：封装两阶段 MNN 人脸检测（RetinaFace det_500m → 2D106 关键点），
//  对标 Android `engines/beauty-engine/src/main/cpp/mnn_face_detector.cpp`
//  + Kotlin `MnnRoiDetector.kt` / `MnnLandmarkDetector.kt` 的生产路径。
//
//  iOS 关键差异（见 mnn-ios-integration skill 补验 A）：
//   1. 后端固定 CPU（MNN_FORWARD_CPU）—— Metal 在本机精度异常，CPU 为正确性基准。
//   2. 必须显式 BackendConfig + Precision_High（默认 Normal/fp16 数值错误，nullptr 解引用 SIGSEGV）。
//
//  线程模型：单线程串行调用（Swift 侧 MnnFaceLandmarkService 的 busy 标志保证）。
//

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// MNN 人脸检测桥接（RetinaFace box + 2D106 关键点两阶段）
@interface PLMnnFaceDetector : NSObject

/// 加载两个模型。retinaPath → det_500m.mnn；landmarkPath → 2d106det.mnn。
/// 返回 YES 仅当两个模型均成功创建 session。
- (BOOL)loadRetinaModel:(NSString *)retinaPath landmarkModel:(NSString *)landmarkPath;

/// 是否已就绪（两模型均已加载）。
@property (nonatomic, readonly) BOOL ready;

/// 两阶段检测。
/// @param bgra      源 BGRA 像素（CVPixelBuffer kCVPixelFormatType_32BGRA，B/G/R/A 字节序）
/// @param width     像素宽
/// @param height    像素高
/// @param bytesPerRow 行字节数（可能 > width*4）
/// @param outPoints 调用方分配的 212 float 缓冲区（106 点 × (x,y)）。
///                  坐标为归一化 [0,1]、Y-down、InsightFace 原始点序（**未**做 FULL_REMAP/镜像）。
///                  Swift 侧 MnnLandmarkAdapter 负责 FULL_REMAP + 前置镜像，与 MediaPipe 输出空间对齐。
/// @return YES 检测到人脸且填充了 outPoints；NO 未检测到人脸（outPoints 内容未定义）。
- (BOOL)detect:(const uint8_t *)bgra
        width:(int)width
       height:(int)height
   bytesPerRow:(int)bytesPerRow
     outPoints:(float *)outPoints;

/// 最近一次 detect 的调试信息（后端/耗时/box），供 DebugOverlay 展示。
@property (nonatomic, readonly, copy) NSString *debugInfo;

@end

NS_ASSUME_NONNULL_END
