import Foundation
import CoreImage
import UIKit
import Accelerate

// MARK: - FaceAlignment（对标 Android FaceClusterEngine 对齐 + warp）

/// Port of Android `TagGenerationPipeline.convert106ToLandmarks5` and
/// `FaceClusterEngine.alignFaceWithLandmarks` / `computeAffineTransform`.
///
/// Produces 112×112 aligned face bitmaps using the InsightFace standard
/// ArcFace alignment target points.
struct FaceAlignment {

    /// ArcFace / InsightFace standard 112×112 alignment target points.
    /// Order: left_eye, right_eye, nose, left_mouth, right_mouth (x,y interleaved).
    /// Source: FaceClusterEngine.kt:179-185
    static let arcFaceTemplate: [Float] = [
        38.2946, 51.6963,   // left eye
        73.5318, 51.5014,   // right eye
        56.0252, 71.7366,   // nose
        41.5493, 92.3655,   // left mouth
        70.7299, 92.2041    // right mouth
    ]

    /// Standard ArcFace aligned input size (112×112).
    static let faceInputSize: Int = 112

    // MARK: - 106→5 转换

    /// 统一 106 点 → ArcFace 5 点(扁平 x,y 交错,归一化 [0,1])。
    ///
    /// 对标 `TagGenerationPipeline.convert106ToLandmarks5`。
    /// ⚠️ 输入必须是经 `MnnLandmarkAdapter.adapt` 重排后的**统一 106 序**,
    ///    而非 2d106det 模型直出的 InsightFace 原生序——索引(52-57 等)按统一序定义,
    ///    喂原生序会取到错误解剖位置(如眼取到嘴)。
    /// ArcFace 顺序:left_eye, right_eye, nose, left_mouth, right_mouth。
    static func convert106ToLandmarks5(landmarks106: [Float], width: Int, height: Int) -> [Float] {
        let w = Float(width)
        let h = Float(height)

        // ArcFace left eye = image-left = 106 right-eye region (52-57 outer contour + 72-73 inner canthus)
        let leftEyeX = averageX(landmarks106, [52, 53, 54, 55, 56, 57, 72, 73])
        let leftEyeY = averageY(landmarks106, [52, 53, 54, 55, 56, 57, 72, 73])

        // ArcFace right eye = image-right = 106 left-eye region (58-63 outer contour + 75-76 inner canthus)
        let rightEyeX = averageX(landmarks106, [58, 59, 60, 61, 62, 63, 75, 76])
        let rightEyeY = averageY(landmarks106, [58, 59, 60, 61, 62, 63, 75, 76])

        // Nose tip center: index 49
        let noseX = landmarks106[49 * 2]
        let noseY = landmarks106[49 * 2 + 1]

        // ArcFace left mouth corner = image-left = 106 right mouth corner (84)
        let leftMouthX = landmarks106[84 * 2]
        let leftMouthY = landmarks106[84 * 2 + 1]

        // ArcFace right mouth corner = image-right = 106 left mouth corner (94)
        let rightMouthX = landmarks106[94 * 2]
        let rightMouthY = landmarks106[94 * 2 + 1]

        let landmarks5: [Float] = [
            leftEyeX * w, leftEyeY * h,
            rightEyeX * w, rightEyeY * h,
            noseX * w, noseY * h,
            leftMouthX * w, leftMouthY * h,
            rightMouthX * w, rightMouthY * h,
        ]
        return landmarks5
    }

    private static func averageX(_ landmarks: [Float], _ indices: [Int]) -> Float {
        var sum: Float = 0
        for i in indices { sum += landmarks[i * 2] }
        return sum / Float(indices.count)
    }

    private static func averageY(_ landmarks: [Float], _ indices: [Int]) -> Float {
        var sum: Float = 0
        for i in indices { sum += landmarks[i * 2 + 1] }
        return sum / Float(indices.count)
    }

    // MARK: - Affine computation

    /// Least-squares affine transform: src5 → dst5.
    /// Returns CGAffineTransform that maps source points to destination.
    /// Port of FaceClusterEngine.kt:220-283 `computeAffineTransform`.
    ///
    /// - Parameters:
    ///   - src5: Source points (5 × x,y interleaved = 10 floats).
    ///   - dst5: Destination points (5 × x,y interleaved = 10 floats).
    /// - Returns: CGAffineTransform mapping source → destination. Identity on degenerate input.
    static func computeAffineTransform(src5: [Float], dst5: [Float]) -> CGAffineTransform {
        let n = Float(src5.count / 2)

        var sx: Float = 0; var sy: Float = 0
        var sxx: Float = 0; var syy: Float = 0; var sxy: Float = 0
        var su: Float = 0; var sv: Float = 0
        var sxu: Float = 0; var syu: Float = 0
        var sxv: Float = 0; var syv: Float = 0

        let count = src5.count / 2
        for i in 0..<count {
            let x = src5[i * 2]
            let y = src5[i * 2 + 1]
            let u = dst5[i * 2]
            let v = dst5[i * 2 + 1]

            sx += x; sy += y
            sxx += x * x; syy += y * y; sxy += x * y
            su += u; sv += v
            sxu += x * u; syu += y * u
            sxv += x * v; syv += y * v
        }

        // Normal equation matrix M = | sxx sxy sx |
        //                            | sxy syy sy |
        //                            | sx  sy  n  |
        // Source: FaceClusterEngine.kt:245-247
        let m00 = sxx; let m01 = sxy; let m02 = sx
        let m10 = sxy; let m11 = syy; let m12 = sy
        let m20 = sx;  let m21 = sy;  let m22 = n

        // Determinant (cofactor expansion along first row).
        // Source: FaceClusterEngine.kt:249-251
        let det = m00 * (m11 * m22 - m12 * m21)
                - m01 * (m10 * m22 - m12 * m20)
                + m02 * (m10 * m21 - m11 * m20)

        // Degenerate: fall back to identity (matches Android behavior).
        // Source: FaceClusterEngine.kt:254-257
        if det == 0 {
            #if DEBUG
            print("[FaceAlignment] Degenerate landmarks, falling back to identity transform")
            #endif
            return .identity
        }

        let invDet = 1 / det

        // Adjugate / det (inverse of 3×3 matrix).
        // Source: FaceClusterEngine.kt:262-270
        let i00 = (m11 * m22 - m12 * m21) * invDet
        let i01 = -(m01 * m22 - m02 * m21) * invDet
        let i02 = (m01 * m12 - m02 * m11) * invDet
        let i10 = -(m10 * m22 - m12 * m20) * invDet
        let i11 = (m00 * m22 - m02 * m20) * invDet
        let i12 = -(m00 * m12 - m02 * m10) * invDet
        let i20 = (m10 * m21 - m11 * m20) * invDet
        let i21 = -(m00 * m21 - m01 * m20) * invDet
        let i22 = (m00 * m11 - m01 * m10) * invDet

        // Solve for u-coefficients [a, b, c].
        // Source: FaceClusterEngine.kt:272-274
        let a = i00 * sxu + i01 * syu + i02 * su
        let b = i10 * sxu + i11 * syu + i12 * su
        let c = i20 * sxu + i21 * syu + i22 * su

        // Solve for v-coefficients [d, e, f].
        // Source: FaceClusterEngine.kt:276-278
        let d = i00 * sxv + i01 * syv + i02 * sv
        let e = i10 * sxv + i11 * syv + i12 * sv
        let f = i20 * sxv + i21 * syv + i22 * sv

        // Android Matrix.setValues([a, b, c, d, e, f, 0, 0, 1]):
        //   | a b c |
        //   | d e f |     →   u = a*x + b*y + c,  v = d*x + e*y + f
        //   | 0 0 1 |
        //
        // CGAffineTransform(a: A, b: B, c: C, d: D, tx: TX, ty: TY):
        //   u = A*x + C*y + TX
        //   v = B*x + D*y + TY
        //
        // Matching: A=a, B=d, C=b, D=e, TX=c, TY=f
        return CGAffineTransform(
            a:  CGFloat(a),
            b:  CGFloat(d),
            c:  CGFloat(b),
            d:  CGFloat(e),
            tx: CGFloat(c),
            ty: CGFloat(f)
        )
    }

    // MARK: - Face warping

    /// Warp a face image to 112×112 using 5-point landmarks and the InsightFace template.
    ///
    /// Uses CIImage affine transform (y-down coordinate system, matching Android Canvas).
    /// This avoids the CGContext y-up complexity that caused 88% black pixels in previous versions.
    ///
    /// - Parameters:
    ///   - image: Source face image (any resolution).
    ///   - landmarks5: 5-point pixel coordinates in the source image (10 floats, x/y interleaved).
    /// - Returns: 112×112 aligned UIImage, or nil on failure.
    static func alignFace(image: UIImage, landmarks5: [Float]) -> UIImage? {
        let size = faceInputSize

        guard let cgImage = image.cgImage else { return nil }
        let srcW = cgImage.width
        let srcH = cgImage.height

        // CIImage 坐标系是 y-up（原点左下角），但 landmarks 和 template 都是 y-down（原点左上角）。
        // 必须翻转两者的 y 坐标：y_ci = H - y_down。
        // 此前只翻 template → 源 landmark 坐标系不匹配 → warp 落空（88% 黑色像素）。
        var srcYUp: [Float] = []
        for i in stride(from: 0, to: landmarks5.count, by: 2) {
            srcYUp.append(landmarks5[i])
            srcYUp.append(Float(srcH) - landmarks5[i + 1])
        }
        var dstYUp: [Float] = []
        for i in stride(from: 0, to: arcFaceTemplate.count, by: 2) {
            dstYUp.append(arcFaceTemplate[i])
            dstYUp.append(Float(size) - arcFaceTemplate[i + 1])
        }

        let transform = computeAffineTransform(src5: srcYUp, dst5: dstYUp)

        let ciImage = CIImage(cgImage: cgImage)
        let warped = ciImage.transformed(by: transform)
        let ciContext = CIContext(options: nil)
        guard let outputCGImage = ciContext.createCGImage(
            warped,
            from: CGRect(x: 0, y: 0, width: size, height: size)
        ) else { return nil }

        return UIImage(cgImage: outputCGImage)
    }
}
