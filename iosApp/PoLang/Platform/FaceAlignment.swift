import UIKit
import CoreGraphics

/// Face alignment utilities for ArcFace embedding extraction.
///
/// Port of Android `TagGenerationPipeline.convert106ToLandmarks5` and
/// `FaceClusterEngine.computeAffineTransform` / `alignFaceWithLandmarks`.
///
/// Produces 112×112 aligned face bitmaps using the InsightFace standard
/// 5-point template, matching the Android pipeline pixel-for-pixel.
enum FaceAlignment {

    /// ArcFace / InsightFace standard 112×112 alignment target points.
    /// Order: [leftEye, rightEye, nose, leftMouth, rightM] (x,y interleaved).
    ///
    /// Source: FaceClusterEngine.kt:179-185
    static let arcFaceTemplate: [Float] = [
        38.2946, 51.6963,   // left eye
        73.5318, 51.5014,   // right eye
        56.0252, 71.7366,   // nose tip
        41.5493, 92.3655,   // left mouth corner
        70.7299, 92.2041,   // right mouth corner
    ]

    /// Standard ArcFace aligned input size (112×112).
    ///
    /// Source: FaceClusterEngine.kt:43 (`FACE_INPUT_SIZE`)
    static let faceInputSize: Int = 112

    // MARK: - 106 → 5 point conversion

    /// Convert unified 106-point normalized landmarks to ArcFace 5-point pixel coordinates.
    ///
    /// Output order: [leftEye, rightEye, nose, leftMouthCorner, rightMouthCorner] (x,y interleaved, 10 floats).
    ///
    /// **Key convention**: ArcFace template left/right is relative to the **image** (left side of frame =
    /// ArcFace "left"). The unified 106 points are named after the **subject's real face**, so there is a
    /// cross-mapping:
    /// - Image-left eye (ArcFace left)  = 106 right-eye region (image-left side) = indices 52-57 + 72-73
    /// - Image-right eye (ArcFace right) = 106 left-eye region (image-right side) = indices 58-63 + 75-76
    /// - Image-left mouth corner (ArcFace left) = 106 right mouth corner (image-left) = index 84
    /// - Image-right mouth corner (ArcFace right) = 106 left mouth corner (image-right) = index 94
    ///
    /// - Parameters:
    ///   - landmarks106: Normalized [0,1] coordinates, x/y interleaved (expected ≥ 212 floats).
    ///   - width: Source image width in pixels.
    ///   - height: Source image height in pixels.
    /// - Returns: 10 floats — 5 points × (x, y) in pixel coordinates.
    ///
    /// Source: TagGenerationPipeline.kt:642-687
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

        // Sanity checks (warning only, same as Android).
        // Source: TagGenerationPipeline.kt:676-684
        if landmarks5[0] >= landmarks5[2] {
            #if DEBUG
            print("[FaceAlignment] Left eye x (\(landmarks5[0])) >= right eye x (\(landmarks5[2])), alignment may be mirrored")
            #endif
        }
        if landmarks5[6] >= landmarks5[8] {
            #if DEBUG
            print("[FaceAlignment] Left mouth x (\(landmarks5[6])) >= right mouth x (\(landmarks5[8])), alignment may be mirrored")
            #endif
        }
        if landmarks5[1] >= landmarks5[9] {
            #if DEBUG
            print("[FaceAlignment] Eye y (\(landmarks5[1])) >= mouth y (\(landmarks5[9])), alignment may be flipped")
            #endif
        }

        return landmarks5
    }

    /// Average of x-coordinates at the given landmark indices.
    ///
    /// Source: TagGenerationPipeline.kt:689-693
    @inline(__always)
    private static func averageX(_ landmarks: [Float], _ indices: [Int]) -> Float {
        var sum: Float = 0
        for i in indices { sum += landmarks[i * 2] }
        return sum / Float(indices.count)
    }

    /// Average of y-coordinates at the given landmark indices.
    ///
    /// Source: TagGenerationPipeline.kt:695-699
    @inline(__always)
    private static func averageY(_ landmarks: [Float], _ indices: [Int]) -> Float {
        var sum: Float = 0
        for i in indices { sum += landmarks[i * 2 + 1] }
        return sum / Float(indices.count)
    }

    // MARK: - Affine transform (least-squares)

    /// Least-squares affine transform from 5 source → 5 destination point pairs.
    ///
    /// Affine model: `u = a*x + b*y + c`, `v = d*x + e*y + f`.
    /// Solves normal equations separately for u and v using the 3×3 matrix inverse.
    ///
    /// - Parameters:
    ///   - src5: Source points (5 × x,y interleaved = 10 floats).
    ///   - dst5: Destination points (5 × x,y interleaved = 10 floats).
    /// - Returns: CGAffineTransform mapping source → destination. Identity on degenerate input.
    ///
    /// Source: FaceClusterEngine.kt:220-283
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
    /// Fills the output with black before warping (matching Android `canvas.drawColor(BLACK)`).
    /// Uses the affine transform computed via least-squares from landmarks5 → arcFaceTemplate.
    ///
    /// - Parameters:
    ///   - image: Source face image (any resolution).
    ///   - landmarks5: 5-point pixel coordinates in the source image (10 floats, x/y interleaved).
    /// - Returns: 112×112 aligned UIImage, or nil on failure.
    ///
    /// Source: FaceClusterEngine.kt:177-195
    static func alignFace(image: UIImage, landmarks5: [Float]) -> UIImage? {
        let size = faceInputSize
        let cgSize = CGFloat(size)

        guard let context = CGContext(
            data: nil,
            width: size,
            height: size,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else {
            return nil
        }

        // Black background fill (matching Android canvas.drawColor(BLACK)).
        // Source: FaceClusterEngine.kt:192
        context.setFillColor(red: 0, green: 0, blue: 0, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: size, height: size))

        guard let cgImage = image.cgImage else { return nil }

        let srcW = CGFloat(cgImage.width)
        let srcH = CGFloat(cgImage.height)

        // Compute the affine transform: source landmarks → InsightFace 112×112 template.
        // Source: FaceClusterEngine.kt:187
        let transform = computeAffineTransform(src5: landmarks5, dst5: arcFaceTemplate)

        // Android Canvas uses top-left origin (y-down). CGContext defaults to bottom-left (y-up).
        // Flip the context to match Android's coordinate system so the warp is pixel-identical.
        context.saveGState()

        // Flip vertically: user (0,0) becomes top-left, y increases downward.
        context.translateBy(x: 0, y: cgSize)
        context.scaleBy(x: 1, y: -1)

        // Apply the affine transform (source pixel coords → 112×112 dest pixel coords).
        context.concatenate(transform)

        // Draw the source image at its natural pixel size; the CTM handles the warp.
        // Source: FaceClusterEngine.kt:193
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: srcW, height: srcH))

        context.restoreGState()

        guard let outputCGImage = context.makeImage() else { return nil }
        return UIImage(cgImage: outputCGImage)
    }
}
