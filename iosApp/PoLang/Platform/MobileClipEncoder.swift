import Foundation
import UIKit
import CoreGraphics

// Module name note: the `onnxruntime-objc` pod (podspec has no explicit module_name)
// exposes its ObjC classes under the module `onnxruntime_objc` (CocoaPods converts
// hyphens to underscores in module names). With `use_frameworks!` the pod is built
// as a static framework whose module map uses the umbrella header
// `objectivec/include/onnxruntime.h`.
//
// If a future podspec revision sets an explicit `module_name`, update this import.
import onnxruntime_objc

// MARK: - MobileClipEncoder

/// MobileCLIP-S2 vision encoder (ONNX Runtime iOS).
///
/// Wraps the `onnxruntime-objc` pod to produce 512-dim L2-normalized semantic
/// image embeddings using the MobileCLIP-S2 vision model (`vision_model.onnx`).
///
/// Port of Android `MobileClipOnnxBackend` — image-encoding path only (text
/// encoding is not needed on iOS for Pass 1/2/3 tag generation; can be added
/// later if cross-modal search is required).
///
/// **Preprocessing** (matches MobileCLIP-S2 official + Android pipeline):
/// 1. Resize so the short side = 256 (maintain aspect ratio, high-quality
///    `CGContext` interpolation).
/// 2. Center-crop to 256×256.
/// 3. Convert to NCHW float32 `[1, 3, 256, 256]`, divide by 255.0.
/// 4. RGB channel order (not BGR).
///
/// **Postprocessing**: L2-normalize the 512-dim output, reject if norm ≤ 0.8
/// or any value is NaN/Inf.
///
/// 参考源文件：
/// - `androidApp/.../domain/tag/MobileClipOnnxBackend.kt` — Android ONNX 后端
/// - `androidApp/.../domain/tag/MobileClipBackend.kt`      — 后端接口 + 校验常量
final class MobileClipEncoder {

    // MARK: - Constants

    /// Expected embedding dimension (MobileCLIP-S2).
    /// Source: `MobileClipBackend.kt:81` (`EMBEDDING_DIM = 512`)
    static let embeddingDim: Int = 512

    /// Vision input spatial size (MobileCLIP-S2 uses 256×256).
    /// Source: `MobileClipBackend.kt:82` (`VISION_INPUT_SIZE = 256`)
    static let inputSize: Int = 256

    /// Minimum acceptable L2 norm of the raw (pre-normalization) embedding.
    /// The Android code validates `norm > 0`; we add a stricter 0.8 floor to
    /// reject degenerate outputs (blank/irrelevant images) earlier.
    private static let minEmbeddingNorm: Float = 0.8

    /// ONNX input / output tensor names.
    /// Source: `MobileClipOnnxBackend.kt:28-29`
    private static let inputName = "pixel_values"
    private static let outputName = "image_embeds"

    // MARK: - Properties

    /// ORT inference session for `vision_model.onnx`.
    ///
    /// The ORT environment is shared process-wide via `ORTSharedEnv.env`
    /// (creating multiple ORTEnv instances per process causes ORT-internal
    /// conflicts — see `ORTSharedEnv` docs).
    private var session: ORTSession?

    /// Whether the model has been loaded successfully.
    var isLoaded: Bool {
        session != nil
    }

    // MARK: - Initialization

    /// Loads the MobileCLIP-S2 vision model from the given file path.
    ///
    /// - Parameter modelPath: Absolute path to `vision_model.onnx`.
    /// - Returns: `true` on success, `false` on failure (error logged in DEBUG).
    @discardableResult
    func load(modelPath: String) -> Bool {
        guard FileManager.default.fileExists(atPath: modelPath) else {
            #if DEBUG
            print("[MobileClipEncoder] Model file not found: \(modelPath)")
            #endif
            return false
        }

        do {
            // Reuse the process-wide shared ORTEnv (ORTSharedEnv).
            // Source: Android uses `OrtEnvironment.getEnvironment()` which
            // is also a singleton.

            // Configure session: 2 intra-op threads (matches Android
            // `setIntraOpNumThreads(2)` / `setInterOpNumThreads(2)`).
            let options = try ORTSessionOptions()
            try options.setIntraOpNumThreads(2)
            try options.setGraphOptimizationLevel(.all)

            // No CoreML EP for now — MobileCLIP-S2 vision ops may not all be
            // supported by CoreML, and CPU fp32 is the safe baseline.
            // TODO: evaluate CoreML EP for performance once correctness is
            // verified on device:
            //   try options.appendExecutionProvider("coreml",
            //                                        providerOptions: [:])

            session = try ORTSession(env: ORTSharedEnv.env,
                                     modelPath: modelPath,
                                     sessionOptions: options)

            #if DEBUG
            print("[MobileClipEncoder] Vision model loaded: \(modelPath)")
            #endif
            return true
        } catch {
            #if DEBUG
            print("[MobileClipEncoder] Failed to load model: \(error)")
            #endif
            session = nil
            return false
        }
    }

    /// Releases the ORT session. The shared ORTEnv (ORTSharedEnv) is a
    /// process-global singleton and is intentionally never released.
    func release() {
        session = nil
        #if DEBUG
        print("[MobileClipEncoder] Session released (shared ORTEnv kept alive)")
        #endif
    }

    // MARK: - Image Encoding

    /// Encodes a `UIImage` into a 512-dim L2-normalized semantic embedding.
    ///
    /// - Parameter image: Input image (any resolution / orientation).
    /// - Returns: 512-element `[Float]` L2-normalized embedding, or `nil` if
    ///   the model is not loaded or the output is invalid.
    func encode(_ image: UIImage) -> [Float]? {
        guard let session = session else {
            #if DEBUG
            print("[MobileClipEncoder] Session not loaded")
            #endif
            return nil
        }

        // 1. Preprocess UIImage → NCHW float32 [1, 3, 256, 256].
        guard let preprocessed = preprocessImage(image) else {
            #if DEBUG
            print("[MobileClipEncoder] Image preprocessing failed")
            #endif
            return nil
        }

        // 2. Create input ORTValue (float tensor [1, 3, 256, 256]).
        //    ORTValue does NOT copy user-provided data — it holds a raw pointer
        //    into the NSMutableData buffer. The buffer must stay alive for the
        //    duration of session.run(). We use `withExtendedLifetime` to
        //    guarantee this under ARC (even with -O optimizations).
        let tensorData = NSMutableData(bytes: preprocessed,
                                       length: preprocessed.count * MemoryLayout<Float>.size)
        let shape: [NSNumber] = [1, 3, NSNumber(value: MobileClipEncoder.inputSize), NSNumber(value: MobileClipEncoder.inputSize)]

        do {
            let inputValue = try ORTValue(tensorData: tensorData,
                                          elementType: .float,
                                          shape: shape)

            // Keep tensorData alive until the run completes.
            return try withExtendedLifetime(tensorData) {
                try self.runInference(session: session, inputValue: inputValue)
            }
        } catch {
            #if DEBUG
            print("[MobileClipEncoder] Inference error: \(error)")
            #endif
            return nil
        }
    }

    // MARK: - Inference (private)

    /// Runs the ORT session and extracts + validates the output embedding.
    ///
    /// Called within `withExtendedLifetime(tensorData)` so the input buffer
    /// is guaranteed alive for the duration of `session.run()`.
    ///
    /// - Parameters:
    ///   - session: The ORT inference session.
    ///   - inputValue: Pre-built input tensor (`pixel_values`).
    /// - Returns: 512-dim L2-normalized embedding, or `nil` on failure.
    private func runInference(session: ORTSession, inputValue: ORTValue) throws -> [Float]? {
        // Run inference — use the outputNames overload so ORT allocates
        // the output tensor internally.
        let inputs: [String: ORTValue] = [Self.inputName: inputValue]
        let outputNames: Set<String> = [Self.outputName]

        let outputs = try session.run(withInputs: inputs,
                                      outputNames: outputNames,
                                      runOptions: nil)

        guard let outputValue = outputs[Self.outputName] else {
            #if DEBUG
            print("[MobileClipEncoder] Output '\(Self.outputName)' not found in results")
            #endif
            return nil
        }

        // Read output tensor data.
        guard let outputData = try? outputValue.tensorData() else {
            #if DEBUG
            print("[MobileClipEncoder] Failed to read output tensor data")
            #endif
            return nil
        }

        // Expected: 512 floats = 2048 bytes.
        let expectedBytes = Self.embeddingDim * MemoryLayout<Float>.size
        guard outputData.length == expectedBytes else {
            #if DEBUG
            print("[MobileClipEncoder] Output size mismatch: \(outputData.length) bytes, expected \(expectedBytes)")
            #endif
            return nil
        }

        // Copy raw bytes into [Float].
        var embedding = [Float](repeating: 0, count: Self.embeddingDim)
        outputData.getBytes(&embedding, length: Self.embeddingDim * MemoryLayout<Float>.size)

        // Validate + L2 normalize.
        return validateAndNormalize(&embedding)
    }

    // MARK: - Preprocessing

    /// Converts a `UIImage` into a flat NCHW float32 array `[1, 3, 256, 256]`.
    ///
    /// Two-step approach (simpler + more reliable than the Android two-pass
    /// `createScaledBitmap` + `createBitmap` crop):
    ///
    /// 1. Compute the center-crop source rectangle in the original image's
    ///    pixel space — a square whose side = `min(srcW, srcH)`, centered.
    /// 2. Draw that crop rect into a 256×256 CGContext with high-quality
    ///    interpolation. CGContext handles resize + crop in one pass.
    /// 3. Read RGBA8 pixels, reorder to planar NCHW, divide by 255.0.
    ///
    /// **Coordinate convention**: CGContext uses bottom-left origin (y-up).
    /// CGImage uses top-left origin (y-down). We flip the context vertically
    /// so the rendered output matches natural image orientation (top = top).
    ///
    /// Source: `MobileClipOnnxBackend.kt:164-216`
    /// (`preprocessImage` + `createCenterCroppedBitmap`)
    private func preprocessImage(_ image: UIImage) -> [Float]? {
        let size = Self.inputSize

        // Get an upright CGImage. If the UIImage has a non-up orientation,
        // `image.cgImage` returns the raw backing without orientation fixup.
        // We normalize by drawing through a UIGraphics context first.
        guard let sourceCG = normalizedCGImage(from: image) else {
            #if DEBUG
            print("[MobileClipEncoder] UIImage has no CGImage backing")
            #endif
            return nil
        }

        let srcW = CGFloat(sourceCG.width)
        let srcH = CGFloat(sourceCG.height)
        let shortSide = min(srcW, srcH)

        guard shortSide > 0 else { return nil }

        // Center-crop source rect: square with side = shortSide, centered.
        // Source: `MobileClipOnnxBackend.kt:209-211` (center crop logic)
        let cropX = (srcW - shortSide) / 2
        let cropY = (srcH - shortSide) / 2
        let cropRect = CGRect(x: cropX, y: cropY, width: shortSide, height: shortSide)
            .intersection(CGRect(x: 0, y: 0, width: srcW, height: srcH))

        guard cropRect.width > 0, cropRect.height > 0 else { return nil }

        // Create a 256×256 RGBA8 context.
        let bytesPerPixel = 4
        let bytesPerRow = size * bytesPerPixel
        var pixelData = [UInt8](repeating: 0, count: size * size * bytesPerPixel)

        guard let context = CGContext(
            data: &pixelData,
            width: size,
            height: size,
            bitsPerComponent: 8,
            bytesPerRow: bytesPerRow,
            space: CGColorSpaceCreateDeviceRGB(),
            // noneSkipLast = RGBX (4 bytes/pixel, alpha ignored), no premultiplication.
            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ) else {
            #if DEBUG
            print("[MobileClipEncoder] Failed to create CGContext")
            #endif
            return nil
        }

        // CGContext origin is bottom-left (y-up); CGImage is top-left (y-down).
        // Flip vertically so the output matches natural image orientation.
        context.translateBy(x: 0, y: CGFloat(size))
        context.scaleBy(x: 1, y: -1)
        context.interpolationQuality = .high

        // Draw the source crop into the full 256×256 destination.
        // We create a cropped CGImage from the source crop rect, then draw
        // it scaled to fill the 256×256 context.
        guard let croppedCG = sourceCG.cropping(to: cropRect) else {
            #if DEBUG
            print("[MobileClipEncoder] Failed to crop CGImage to rect \(cropRect)")
            #endif
            return nil
        }

        context.draw(croppedCG,
                     in: CGRect(x: 0, y: 0, width: CGFloat(size), height: CGFloat(size)))

        // RGBA8 → NCHW float32 [1, 3, 256, 256], divided by 255.0.
        // Source: `MobileClipOnnxBackend.kt:171-186`
        let channelStride = size * size
        var result = [Float](repeating: 0, count: 3 * channelStride)

        for y in 0..<size {
            for x in 0..<size {
                let pixelIndex = (y * size + x) * bytesPerPixel
                let r = Float(pixelData[pixelIndex + 0]) / 255.0
                let g = Float(pixelData[pixelIndex + 1]) / 255.0
                let b = Float(pixelData[pixelIndex + 2]) / 255.0
                let offset = y * size + x
                result[0 * channelStride + offset] = r
                result[1 * channelStride + offset] = g
                result[2 * channelStride + offset] = b
            }
        }

        return result
    }

    /// Returns a CGImage with the UIImage's orientation normalized to `.up`.
    ///
    /// `UIImage.cgImage` returns the raw pixel backing without applying the
    /// `imageOrientation` transform. For photos from `PHAsset` / camera, the
    /// orientation is typically `.up`, but for images loaded from other
    /// sources it may differ. We render through `UIGraphicsImageRenderer` to
    /// bake in the orientation.
    ///
    /// Falls back to `image.cgImage` if `UIGraphicsImageRenderer` fails.
    private func normalizedCGImage(from image: UIImage) -> CGImage? {
        // Fast path: already upright.
        if image.imageOrientation == .up, let cg = image.cgImage {
            return cg
        }

        // Slow path: render through a bitmap context with orientation applied.
        let renderer = UIGraphicsImageRenderer(size: image.size)
        let rendered = renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: image.size))
        }
        return rendered.cgImage
    }

    // MARK: - Validation & Normalization

    /// Validates embedding dimension + finiteness, then L2-normalizes.
    ///
    /// Rejects (returns `nil`) if:
    /// - dimension ≠ 512
    /// - any value is NaN or Inf
    /// - L2 norm ≤ 0.8 (degenerate / blank output)
    ///
    /// Source: `MobileClipBackend.kt:51-78` (`validateAndNormalize`)
    private func validateAndNormalize(_ embedding: inout [Float]) -> [Float]? {
        guard embedding.count == Self.embeddingDim else {
            #if DEBUG
            print("[MobileClipEncoder] Invalid dimension: \(embedding.count), expected \(Self.embeddingDim)")
            #endif
            return nil
        }

        var norm: Float = 0
        for v in embedding {
            if v.isNaN || v.isInfinite {
                #if DEBUG
                print("[MobileClipEncoder] Embedding contains NaN/Inf")
                #endif
                return nil
            }
            norm += v * v
        }

        let rawNorm = sqrt(norm)

        guard rawNorm > Self.minEmbeddingNorm else {
            #if DEBUG
            print("[MobileClipEncoder] Embedding norm too low: \(rawNorm) (threshold \(Self.minEmbeddingNorm))")
            #endif
            return nil
        }

        // L2 normalize.
        let invNorm = 1.0 / rawNorm
        for i in 0..<embedding.count {
            embedding[i] *= invNorm
        }

        return embedding
    }

    // MARK: - Utility

    /// Computes the cosine similarity between two embeddings.
    ///
    /// Source: `MobileClipBackend.kt:33-46` (`cosineSimilarity`)
    static func cosineSimilarity(_ a: [Float], _ b: [Float]) -> Float {
        guard a.count == b.count else { return 0 }
        var dot: Float = 0
        var normA: Float = 0
        var normB: Float = 0
        for i in 0..<a.count {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        guard normA > 0, normB > 0 else { return 0 }
        return dot / (sqrt(normA) * sqrt(normB))
    }
}
