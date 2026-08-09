import Foundation
import simd

/// MNN 2d106 关键点 → 统一 106 点适配器（对标 Android `MnnLandmarkAdapter.kt`）。
///
/// MNN 模型（2d106det.mnn，InsightFace ONNX 转换）输出点序 = InsightFace 原始点序，
/// **不是**统一 106 格式。需用 FULL_REMAP 重排，再处理前置摄像头水平镜像，
/// 最终输出与 MediaPipe 引擎（MediaPipe468Adapter）完全相同的坐标空间：
/// 归一化 [0,1]、Y-down、已含前置镜像。
enum MnnLandmarkAdapter {

    /// 统一 106 索引 → InsightFace/MNN 原生索引（与 Android FULL_REMAP 逐项一致）
    static let fullRemap: [Int] = [
        // 轮廓 0-32
        1, 9, 10, 11, 12, 13, 14, 15, 16, 2, 3, 4, 5, 6, 7, 8,
        0,
        24, 23, 22, 21, 20, 19, 18, 32, 31, 30, 29, 28, 27, 26, 25, 17,
        // 眉毛上部 33-42
        43, 48, 49, 51, 50,
        101, 105, 104, 103, 102,
        // 眉心 43 / 鼻梁 44-46 / 鼻尖 47-51
        72,
        73, 74, 86,
        78, 79, 80, 85, 84,
        // 右眼外轮廓 52-57 / 左眼外轮廓 58-63
        35, 41, 42, 39, 37, 36,
        89, 95, 96, 93, 91, 90,
        // 眉毛下部 64-71
        44, 45, 47, 46,
        100, 99, 98, 97,
        // 右眼补充 72-74 / 左眼补充 75-77
        40, 33, 34,
        94, 87, 92,
        // 山根 78-79 / 鼻孔 80-83
        75, 81,
        76, 82, 77, 83,
        // 嘴巴外轮廓 84-95
        52, 64, 63, 71, 67, 68, 61, 58, 59, 53, 56, 55,
        // 嘴巴内轮廓 96-103
        65, 66, 62, 70, 69, 57, 60, 54,
        // 瞳孔 104-105
        38, 88
    ]

    /// 将 MNN 原生 106 点（212 floats，归一化 [0,1]，Y-down）转为统一 106 点。
    /// - Parameters:
    ///   - native: 212 元素扁平数组 [x0,y0,x1,y1,...]，InsightFace 原始点序
    ///   - isFrontCamera: 前置摄像头时 x = 1 - x（与 MediaPipe468Adapter 一致）
    /// - Returns: 106 个 SIMD2<Float> 统一关键点，或 nil（输入不足）
    static func adapt(_ native: [Float], isFrontCamera: Bool) -> [SIMD2<Float>]? {
        guard native.count >= 106 * 2 else { return nil }
        var out = [SIMD2<Float>](repeating: .zero, count: 106)
        for unified in 0..<106 {
            let src = fullRemap[unified]
            let sx = native[src * 2]
            let sy = native[src * 2 + 1]
            let ux = isFrontCamera ? 1.0 - sx : sx
            out[unified] = SIMD2<Float>(ux, sy)
        }
        return out
    }
}
