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
#import <CoreGraphics/CoreGraphics.h>

NS_ASSUME_NONNULL_BEGIN

/// 单张人脸检测结果（多人脸检测 detectAllFaces 返回）。
/// 对标 Android `FaceBox` (FaceBox.kt) —— RetinaFace 第一阶段输出。
@interface PLDetectedFace : NSObject

/// ROI（原始图像像素坐标，已 clamp 到图像边界内）。
@property (nonatomic, readonly) CGRect roi;

/// 检测置信度 [0,1]。
@property (nonatomic, readonly) float confidence;

/// 获取 5 点关键点（10 float: x0,y0,x1,y1,...,x4,y4），原始图像像素坐标。
/// 关键点顺序：left_eye, right_eye, nose, mouth_left, mouth_right（InsightFace 标准，
/// 对标 Android mnn_face_detector.cpp:1073-1078 processRetinaFaceOutput landmark 解析）。
/// @param outLandmarks 调用方分配的 10 float 缓冲区。
- (void)getLandmarks:(float *)outLandmarks;

@end

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

/// 开启输入朝向探针（静态诊断用）：detect 后额外以 4 种旋转跑 landmark 模型，
/// 结果写入 stage1Dump 的 ORIENT-PROBE 段（裁决宽扁形变是否源于输入 X/Y 转置）。
- (void)setOrientProbe:(BOOL)on;

/// 多人脸检测（仅 RetinaFace 第一阶段，不做 2D106 关键点）。
/// 对标 Android `MnnFaceDetector.detectRetinaFaces()` (MnnFaceDetector.kt:272-295) →
/// JNI `nativeDetectRetinaFaces` (mnn_jni_bridge.cpp:153-221) → C++ `detectRetinaFace`
/// (mnn_face_detector.cpp:244-614) 的多脸路径。
///
/// 每张脸包含：ROI（CGRect 像素坐标）、5 点关键点（10 float 像素坐标）、置信度。
/// 返回数组按置信度降序排列（NMS 已排序）。
///
/// @param bgra        源 BGRA 像素（CVPixelBuffer kCVPixelFormatType_32BGRA）
/// @param width       像素宽
/// @param height      像素高
/// @param bytesPerRow 行字节数（可能 > width*4）
/// @return 所有人脸；空数组 = 未检测到人脸。
- (NSArray<PLDetectedFace *> *)detectAllFaces:(const uint8_t *)bgra
                                        width:(int)width
                                       height:(int)height
                                  bytesPerRow:(int)bytesPerRow;

/// 最近一次 detect 的调试信息（后端/耗时/box），供 DebugOverlay 展示。
@property (nonatomic, readonly, copy) NSString *debugInfo;

/// 最近一次 detect 的 Stage-1 诊断（-galleryFace 用）：定位「人脸框竖向压扁」根因。
/// 含 320 空间 RetinaFace box（aspect 是裁决关键）、逆 letterbox 参数、图像空间 box、
/// ×1.2 扩展后 ROI。无人脸时为字面量 "no-stage1"。
/// 仅坐标/尺寸（无人脸像素），隐私安全。
@property (nonatomic, readonly, copy) NSString *stage1Dump;

@end

NS_ASSUME_NONNULL_END
