import Foundation

/// MediaPipe 468 点 → 统一 106 点适配器（Swift 移植）
///
/// 移植源: engines/beauty-engine/.../facedetect/adapter/MediaPipe468Adapter.kt
///
/// 映射规则（严格遵循 Kotlin 版实现，零修改）：
/// - 轮廓 33 点（0-32）：基于 MediaPipe FACE_OVAL 路径插值生成
/// - 非轮廓 73 点（33-105）：通过固定映射表 NON_CONTOUR_MAPPING 直接转换
/// - 前置摄像头时水平镜像 x 坐标（x = 1 - x）
///
/// [图像坐标系] 输出 106 点归一化坐标 [0,1]
enum MediaPipe468Adapter {
    static let pointCount = 106
    static let contourPointCount = 33
    static let nonContourPointCount = 73

    // MARK: - 输入类型（与 MediaPipeTasksVision 解耦，便于单测 mock）

    /// MediaPipe 468 点输入的归一化坐标
    struct Landmark {
        let x: Float
        let y: Float
    }

    // MARK: - 非轮廓区域映射表（33-105，共73点）
    // 逐行照抄 Kotlin NON_CONTOUR_MAPPING intArray
    static let nonContourMapping: [Int] = [
        // === 右眉上部 33-37 (5点) - 画面左侧=实际右脸 ===
        70, 63, 105, 66, 107,
        // === 左眉上部 38-42 (5点) - 画面右侧=实际左脸 ===
        336, 296, 334, 293, 300,
        // === 眉心 43 ===
        168,
        // === 鼻梁 44-46 (3点) ===
        197, 5, 4,
        // === 鼻尖 47-51 (5点) ===
        98, 241, 2, 461, 327,
        // === 右眼 52-57 (6点) ===
        226, 30, 56, 133, 26, 110,
        // === 左眼 58-63 (6点) ===
        362, 286, 260, 446, 339, 256,
        // === 右眉下部 64-67 (4点) ===
        53, 52, 65, 55,
        // === 左眉下部 68-71 (4点) ===
        285, 295, 282, 283,
        // === 右眼补充 72-74 (3点) ===
        374, 375, 473,
        // === 左眼补充 75-77 (3点) ===
        44, 45, 468,
        // === 山根 78-79 (2点) ===
        193, 417,
        // === 鼻孔 80-83 (4点) ===
        198, 420, 49, 279,
        // === 嘴巴外轮廓 84-95 (12点) ===
        61, 40, 37, 0, 267, 270, 291, 321, 314, 17, 84, 91,
        // === 嘴巴内轮廓 96-103 (8点) ===
        78, 81, 13, 311, 308, 178, 14, 402,
        // === 瞳孔 104-105 (2点) ===
        473, 468
    ]

    // MARK: - 适配方法

    /// 将 468 个 MediaPipe Landmark 映射为 106 个统一关键点
    /// - Parameters:
    ///   - landmarks: MediaPipe 468 个归一化关键点
    ///   - isFrontCamera: 是否前置摄像头（true 时 x = 1 - x 镜像）
    ///   - rotationDegrees: 图像旋转角度（0/90/180/270）
    /// - Returns: 106 个 SIMD2<Float> 归一化坐标
    static func map(
        _ landmarks: [Landmark],
        isFrontCamera: Bool = false,
        rotationDegrees: Int = 0
    ) -> [SIMD2<Float>]? {
        guard landmarks.count >= 468 else { return nil }

        var result = [SIMD2<Float>](repeating: SIMD2(0, 0), count: pointCount)

        // 辅助：旋转归一化坐标
        func rotateNormalized(_ x: Float, _ y: Float, degrees: Int) -> (Float, Float) {
            switch degrees {
            case 90:  return (1 - y, x)
            case 180: return (1 - x, 1 - y)
            case 270: return (y, 1 - x)
            default:  return (x, y)
            }
        }

        // 辅助：获取 MediaPipe 点坐标（含旋转 + 前置镜像）
        func getMpPoint(_ index: Int) -> (Float, Float)? {
            guard index < landmarks.count else { return nil }
            var x = landmarks[index].x
            let y = landmarks[index].y
            let (rx, ry) = rotateNormalized(x, y, degrees: rotationDegrees)
            x = rx
            let finalY = ry
            if isFrontCamera { x = 1 - x }
            return (x, finalY)
        }

        // 辅助：钳制到 [0,1]
        func clamp01(_ v: Float) -> Float { min(max(v, 0), 1) }

        // 辅助：设置106点坐标
        func setPoint(_ idx: Int, _ point: (Float, Float)?) {
            guard let point else { return }
            result[idx] = SIMD2(clamp01(point.0), clamp01(point.1))
        }

        // === 生成33个轮廓点（0-32）===
        // MediaPipe FACE_OVAL 路径
        let leftContourBasePoints: [(Float, Float)] = [127, 234, 93, 132, 58, 172, 136, 150, 149, 176, 148, 152]
            .compactMap { getMpPoint($0) }
        let rightContourBasePoints: [(Float, Float)] = [152, 377, 400, 378, 379, 365, 397, 288, 361, 323, 454, 356]
            .compactMap { getMpPoint($0) }

        // M0-M16 (17点)：沿 leftContourBasePoints 均匀插值
        for i in 0...16 {
            let t = Float(i) / 16.0
            let pos = t * Float(leftContourBasePoints.count - 1)
            let idx = Int(pos).clamped(to: 0...max(0, leftContourBasePoints.count - 2))
            let frac = pos - Float(idx)
            guard idx + 1 < leftContourBasePoints.count else { continue }
            let p1 = leftContourBasePoints[idx]
            let p2 = leftContourBasePoints[idx + 1]
            let x = p1.0 + (p2.0 - p1.0) * frac
            let y = p1.1 + (p2.1 - p1.1) * frac
            setPoint(i, (x, y))
        }

        // M16-M32 (17点，M16 已设，从 M17 开始)
        for i in 1...16 {
            let t = Float(i) / 16.0
            let pos = t * Float(rightContourBasePoints.count - 1)
            let idx = Int(pos).clamped(to: 0...max(0, rightContourBasePoints.count - 2))
            let frac = pos - Float(idx)
            guard idx + 1 < rightContourBasePoints.count else { continue }
            let p1 = rightContourBasePoints[idx]
            let p2 = rightContourBasePoints[idx + 1]
            let x = p1.0 + (p2.0 - p1.0) * frac
            let y = p1.1 + (p2.1 - p1.1) * frac
            setPoint(16 + i, (x, y))
        }

        // === 生成非轮廓区域点（33-105）===
        for i in 0..<nonContourPointCount {
            let mpIndex = nonContourMapping[i]
            guard mpIndex < landmarks.count else { continue }
            var x = landmarks[mpIndex].x
            var y = landmarks[mpIndex].y
            let (rx, ry) = rotateNormalized(x, y, degrees: rotationDegrees)
            x = rx; y = ry
            if isFrontCamera { x = 1 - x }
            result[33 + i] = SIMD2(clamp01(x), clamp01(y))
        }

        return result
    }
}

// Comparable clamp helper for Int
private extension Int {
    func clamped(to range: ClosedRange<Int>) -> Int {
        Swift.min(Swift.max(self, range.lowerBound), range.upperBound)
    }
}
