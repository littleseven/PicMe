import Foundation
import simd

/// 对应 Android uniforms_2d.glsl 的 MVP 子集（spec §5.3 翻译纪律：标量/向量→struct 字段）。
/// 内存布局与 .metal 侧 BeautyUniforms 必须一致（Swift/simd 对齐规则相同）。
struct BeautyUniforms {
    var smoothing: Float = 0
    var whitening: Float = 0
    var sharpen: Float = 0
    var bigEyes: Float = 0
    var slimFace: Float = 0
    var hasFace: Float = 0
    var aspectRatio: Float = 1
    var useGpupixelWarp: Int32 = 1
    // uFacePoints[212] 走独立 MTLBuffer（数组不可进 setFragmentBytes 内联结构），Task 16 接
}
