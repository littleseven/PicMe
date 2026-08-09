import Foundation
import Metal
import MetalKit
import CoreVideo

/// 美颜渲染宿主（对应 Android BeautyRenderer 的 MVP 版）。
/// 管线（预览 NV12）：camera YUV →(yuv pass)→ rgbTexture →(smoothing pass)→ (lut pass)→ (beauty pass)→ drawable
/// 管线（拍照 BGRA）：photo BGRA →(copy pass)→ rgbTexture →(smoothing pass)→ (lut pass)→ (beauty pass)→ 离屏 → CGImage
///
/// shader fragment function 名：`yuv_fragment` / `smoothing_fragment` / `lut_fragment` / `beauty_fragment`
/// vertex function 名：`quad_vertex`（唯一定义在 yuv.metal，linker 解析）
final class BeautyRenderer: NSObject {
    /// [S5] 滑杆范围与 Android BeautyPanel.kt 一致
    struct Params: Equatable {
        var whitening: Float = 0       // 0..100 (Android)
        var smoothing: Float = 0       // 0..100 (Android)
        var slimFace: Float = 0        // -50..50 (Android)
        var bigEyes: Float = 0         // 0..100 (Android)
        var colorFilter: FilterType = .none

        /// shader 侧归一化值（对应 Android BeautyParamsConverter.kt + BeautyRenderer.kt 的链路）
        /// slimFace: Android BeautyParamsConverter.kt:65 → -(slimFace/50*1.35).coerceIn(-1,1)
        ///           then BeautyRenderer.kt:230 → *0.2 coerceIn(-0.2,0.2)
        var shaderWhitening: Float { whitening / 100.0 }
        var shaderSmoothing: Float { smoothing / 100.0 }
        var shaderSlimFace: Float {
            let raw = -(slimFace / 50.0 * 1.35).clamped(-1.0...1.0)
            return (raw * 0.2).clamped(-0.2...0.2)
        }
        var shaderBigEyes: Float { bigEyes / 100.0 }
    }

    private let device: MTLDevice
    private let commandQueue: MTLCommandQueue
    private let yuvPipeline: MTLRenderPipelineState
    private let smoothingPipeline: MTLRenderPipelineState
    private let lutPipeline: MTLRenderPipelineState
    private let beautyPipeline: MTLRenderPipelineState
    private let sampler: MTLSamplerState
    private var textureCache: CVMetalTextureCache?
    private var rgbTexture: MTLTexture?
    private var smoothingTexture: MTLTexture?
    private var lutTexture: MTLTexture?

    /// 4 张美白 LUT（Task 14 pass_smoothing）
    private var lutGray: MTLTexture?
    private var lutOrigin: MTLTexture?
    private var lutSkin: MTLTexture?
    private var lutLight: MTLTexture?

    /// 人脸关键点 buffer（106 点 × 2 floats）
    private var facePointsBuffer: MTLBuffer?
    /// 零填充 buffer（hasFace=0 时 Metal validation 要求 buffer(1) 仍绑定）
    private let zeroFacePointsBuffer: MTLBuffer

    var params = Params()

    init?(device: MTLDevice) {
        self.device = device
        guard let queue = device.makeCommandQueue(),
              let lib = try? device.makeDefaultLibrary(bundle: .main),
              let vert = lib.makeFunction(name: "quad_vertex"),
              let yuvFrag = lib.makeFunction(name: "yuv_fragment"),
              let smoothFrag = lib.makeFunction(name: "smoothing_fragment"),
              let lutFrag = lib.makeFunction(name: "lut_fragment"),
              let bFrag = lib.makeFunction(name: "beauty_fragment") else { return nil }
        self.commandQueue = queue

        // 零填充 buffer（106×2 floats 全 0）
        guard let zeroBuf = device.makeBuffer(
            length: 106 * 2 * MemoryLayout<Float>.stride, options: .storageModeShared) else { return nil }
        memset(zeroBuf.contents(), 0, zeroBuf.length)
        self.zeroFacePointsBuffer = zeroBuf

        func pipeline(_ v: MTLFunction, _ f: MTLFunction) -> MTLRenderPipelineState? {
            let pd = MTLRenderPipelineDescriptor()
            pd.vertexFunction = v; pd.fragmentFunction = f
            pd.colorAttachments[0].pixelFormat = .bgra8Unorm
            return try? device.makeRenderPipelineState(descriptor: pd)
        }
        guard let yuvPSO = pipeline(vert, yuvFrag),
              let smoothPSO = pipeline(vert, smoothFrag),
              let lutPSO = pipeline(vert, lutFrag),
              let beautyPSO = pipeline(vert, bFrag) else { return nil }
        self.yuvPipeline = yuvPSO
        self.smoothingPipeline = smoothPSO
        self.lutPipeline = lutPSO
        self.beautyPipeline = beautyPSO

        let sd = MTLSamplerDescriptor()
        sd.minFilter = .linear; sd.magFilter = .linear
        sd.sAddressMode = .clampToEdge; sd.tAddressMode = .clampToEdge
        guard let s = device.makeSamplerState(descriptor: sd) else { return nil }
        self.sampler = s
        super.init()

        var cache: CVMetalTextureCache?
        let cvRet = CVMetalTextureCacheCreate(nil, nil, device, nil, &cache)
        if cvRet == kCVReturnSuccess { self.textureCache = cache }

        // 加载 4 张美白 LUT
        lutGray = loadLut(named: "lookup_gray")
        lutOrigin = loadLut(named: "lookup_origin")
        lutSkin = loadLut(named: "lookup_skin")
        lutLight = loadLut(named: "lookup_light")

        let lutStatus = "\(lutGray != nil)" + "\(lutOrigin != nil)" + "\(lutSkin != nil)" + "\(lutLight != nil)"
        print("[PoLang] beauty.lut gray/origin/skin/light = \(lutStatus)")
        print("[PoLang] beauty.pipelines: yuv=\(yuvPipeline != nil) smooth=\(smoothingPipeline != nil) lut=\(lutPipeline != nil) beauty=\(beautyPipeline != nil)")
        DispatchQueue.main.async {
            DebugOverlayState.shared.set("beauty.lut", lutStatus)
        }
    }

    private func ensureTexture(_ existing: MTLTexture?, w: Int, h: Int) -> MTLTexture? {
        if existing?.width == w && existing?.height == h { return existing }
        let td = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .bgra8Unorm, width: w, height: h, mipmapped: false)
        td.usage = [.renderTarget, .shaderRead]
        return device.makeTexture(descriptor: td)
    }

    // MARK: - LUT 加载

    private func loadLut(named name: String) -> MTLTexture? {
        guard let url = Bundle.main.url(forResource: name, withExtension: "png", subdirectory: "Assets"),
              let data = try? Data(contentsOf: url),
              let cgImage = UIImage(data: data)?.cgImage else {
            // fallback without subdirectory
            guard let url2 = Bundle.main.url(forResource: name, withExtension: "png"),
                  let data2 = try? Data(contentsOf: url2),
                  let cgImage2 = UIImage(data: data2)?.cgImage else { return nil }
            let loader = MTKTextureLoader(device: device)
            return try? loader.newTexture(cgImage: cgImage2, options: [
                .SRGB: false, .textureUsage: MTLTextureUsage.shaderRead.rawValue
            ])
        }
        let loader = MTKTextureLoader(device: device)
        return try? loader.newTexture(cgImage: cgImage, options: [
            .SRGB: false, .textureUsage: MTLTextureUsage.shaderRead.rawValue
        ])
    }

    // MARK: - 人脸关键点

    private var drawFrameCount = 0
    private var lastFacePointsLogTime: Date = .distantPast

    /// 更新人脸关键点（由 CaptureSessionController 帧回调接线调用）
    func updateFacePoints(_ points: [SIMD2<Float>], hasFace: Bool) {
        guard hasFace, points.count >= 106 else {
            facePointsBuffer = nil
            return
        }
        let floats = points.prefix(106).flatMap { [$0.x, $0.y] }
        if let buf = device.makeBuffer(bytes: floats, length: 106 * 2 * MemoryLayout<Float>.stride, options: .storageModeShared) {
            facePointsBuffer = buf
            // 日志：每 2s 打首点坐标（验证非零 + 方向）
            let now = Date()
            if now.timeIntervalSince(lastFacePointsLogTime) > 2.0 {
                let p0 = points[0]
                print("[PoLang] face.updatePoints: count=\(points.count) p0=(\(String(format: "%.3f", p0.x)),\(String(format: "%.3f", p0.y)))")
                lastFacePointsLogTime = now
            }
        }
    }

    // MARK: - 预览渲染（NV12 YUV 输入）

    func draw(pixelBuffer: CVPixelBuffer, in view: MTKView) {
        guard let textureCache,
              let textures = makeYuvTextures(pixelBuffer: pixelBuffer),
              let drawable = view.currentDrawable,
              let cmd = commandQueue.makeCommandBuffer() else { return }
        let (yTex, uvTex) = textures
        let w = CVPixelBufferGetWidth(pixelBuffer), h = CVPixelBufferGetHeight(pixelBuffer)
        drawFrameCount += 1

        // Pass 1: YUV → RGB
        rgbTexture = ensureTexture(rgbTexture, w: w, h: h)
        guard let rgbTex = rgbTexture else { return }
        encodePass(cmd: cmd, pipeline: yuvPipeline, dest: rgbTex,
                   textures: [(0, yTex), (1, uvTex)], sampler: true)

        // Pass 2: smoothing（磨皮，smoothing > 0 时启用）
        var sourceForNext = rgbTex
        if params.shaderSmoothing > 0.001 {
            smoothingTexture = ensureTexture(smoothingTexture, w: w, h: h)
            if let smoothTex = smoothingTexture {
                var sUni = SmoothingUniforms(
                    blurAlpha: params.shaderSmoothing,
                    sharpen: 0,
                    whiten: params.shaderWhitening,
                    widthOffset: 1.0 / Float(w),
                    heightOffset: 1.0 / Float(h)
                )
                encodePass(cmd: cmd, pipeline: smoothingPipeline, dest: smoothTex,
                           textures: [(0, rgbTex), (1, lutGray), (2, lutOrigin), (3, lutSkin), (4, lutLight)],
                           sampler: true, fragmentBytes: &sUni,
                           fragmentBytesLength: MemoryLayout<SmoothingUniforms>.stride, bufferIndex: 0)
                sourceForNext = smoothTex
            }
        }

        // Pass 3: LUT / ColorMatrix（仅 colorFilter != .none 时启用）
        if params.colorFilter != .none, let lutFragUniforms = makeColorGradeUniforms() {
            lutTexture = ensureTexture(lutTexture, w: w, h: h)
            if let lutTex = lutTexture {
                var cgUni = lutFragUniforms
                encodePass(cmd: cmd, pipeline: lutPipeline, dest: lutTex,
                           textures: [(0, sourceForNext)], sampler: true,
                           fragmentBytes: &cgUni,
                           fragmentBytesLength: MemoryLayout<ColorGradeUniforms>.stride, bufferIndex: 0)
                sourceForNext = lutTex
            }
        }

        // Pass 4: beauty 上屏（warp + whitenSkin）
        guard let d2 = view.currentRenderPassDescriptor else { return }
        d2.colorAttachments[0].loadAction = .clear
        d2.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 1)

        var uniforms = BeautyUniforms()
        uniforms.whitening = params.shaderWhitening
        uniforms.smoothing = params.shaderSmoothing
        uniforms.slimFace = params.shaderSlimFace
        uniforms.bigEyes = params.shaderBigEyes
        uniforms.aspectRatio = Float(w) / Float(h)
        uniforms.hasFace = facePointsBuffer != nil ? 1.0 : 0.0
        uniforms.useGpupixelWarp = 1

        if let enc = cmd.makeRenderCommandEncoder(descriptor: d2) {
            enc.setRenderPipelineState(beautyPipeline)
            enc.setFragmentTexture(sourceForNext, index: 0)
            enc.setFragmentSamplerState(sampler, index: 0)
            enc.setFragmentBytes(&uniforms, length: MemoryLayout<BeautyUniforms>.stride, index: 0)
            // 🔴10: hasFace=0 时仍绑定 zeroFacePointsBuffer 避免 Metal validation 报错
            enc.setFragmentBuffer(facePointsBuffer ?? zeroFacePointsBuffer, offset: 0, index: 1)
            enc.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
            enc.endEncoding()
        }
        cmd.present(drawable)
        cmd.commit()

        // 周期性结构化日志（每 60 帧 ≈ 2s @30fps）
        if drawFrameCount % 60 == 0 {
            let activePasses = "yuv" +
                (params.shaderSmoothing > 0.001 ? "+smooth" : "") +
                (params.colorFilter != .none ? "+lut(\(params.colorFilter.rawValue))" : "") +
                "+beauty"
            print("[PoLang] draw.frame=\(drawFrameCount) \(activePasses) w=\(w) h=\(h) face=\(facePointsBuffer != nil) " +
                  "whiten=\(String(format: "%.2f", params.shaderWhitening)) smooth=\(String(format: "%.2f", params.shaderSmoothing)) slim=\(String(format: "%.2f", params.shaderSlimFace)) eye=\(String(format: "%.2f", params.shaderBigEyes))")
        }
    }

    /// [🔴4] 拍照 pixelBuffer 是 32BGRA 单平面，不是 NV12 双平面
    /// 直接做 BGRA → 美颜全管线（跳过 yuv pass）
    func renderToImage(pixelBuffer: CVPixelBuffer) -> CGImage? {
        let w = CVPixelBufferGetWidth(pixelBuffer), h = CVPixelBufferGetHeight(pixelBuffer)
        guard let cmd = commandQueue.makeCommandBuffer() else { return nil }

        // BGRA 单平面 → 单张纹理（不做 YUV 解码）
        guard let bgraTex = makeBgraTexture(pixelBuffer: pixelBuffer) else { return nil }

        // 值快照（🟡2: 拍照入口做值快照避免并发写）
        let snapParams = params
        let snapFacePoints = facePointsBuffer

        // Pass 1: copy BGRA → rgbTexture（作为后续 pass 输入）
        let rgbTex = ensureTexture(nil, w: w, h: h)
        guard let rgbTex else { return nil }
        encodePass(cmd: cmd, pipeline: beautyPipeline, dest: rgbTex,
                   textures: [(0, bgraTex)], sampler: true,
                   fragmentBytes: nil, fragmentBytesLength: 0, bufferIndex: 0,
                   useIdentityBeauty: true)

        // Pass 2: smoothing
        var sourceForNext = rgbTex
        if snapParams.shaderSmoothing > 0.001 {
            let smoothTex = ensureTexture(nil, w: w, h: h)
            if let smoothTex {
                var sUni = SmoothingUniforms(
                    blurAlpha: snapParams.shaderSmoothing, sharpen: 0,
                    whiten: snapParams.shaderWhitening,
                    widthOffset: 1.0 / Float(w), heightOffset: 1.0 / Float(h)
                )
                encodePass(cmd: cmd, pipeline: smoothingPipeline, dest: smoothTex,
                           textures: [(0, rgbTex), (1, lutGray), (2, lutOrigin), (3, lutSkin), (4, lutLight)],
                           sampler: true, fragmentBytes: &sUni,
                           fragmentBytesLength: MemoryLayout<SmoothingUniforms>.stride, bufferIndex: 0)
                sourceForNext = smoothTex
            }
        }

        // Pass 3: LUT
        if snapParams.colorFilter != .none, let cgUni = makeColorGradeUniforms(snap: snapParams) {
            let lutTex = ensureTexture(nil, w: w, h: h)
            if let lutTex {
                var uni = cgUni
                encodePass(cmd: cmd, pipeline: lutPipeline, dest: lutTex,
                           textures: [(0, sourceForNext)], sampler: true,
                           fragmentBytes: &uni,
                           fragmentBytesLength: MemoryLayout<ColorGradeUniforms>.stride, bufferIndex: 0)
                sourceForNext = lutTex
            }
        }

        // Pass 4: beauty → 最终离屏
        let outTex = ensureTexture(nil, w: w, h: h)
        guard let outTex else { return nil }

        var uniforms = BeautyUniforms()
        uniforms.whitening = snapParams.shaderWhitening
        uniforms.smoothing = snapParams.shaderSmoothing
        uniforms.slimFace = snapParams.shaderSlimFace
        uniforms.bigEyes = snapParams.shaderBigEyes
        uniforms.aspectRatio = Float(w) / Float(h)
        uniforms.hasFace = snapFacePoints != nil ? 1.0 : 0.0
        uniforms.useGpupixelWarp = 1

        let dOut = MTLRenderPassDescriptor()
        dOut.colorAttachments[0].texture = outTex
        dOut.colorAttachments[0].loadAction = .clear
        dOut.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 1)
        dOut.colorAttachments[0].storeAction = .store
        if let enc = cmd.makeRenderCommandEncoder(descriptor: dOut) {
            enc.setRenderPipelineState(beautyPipeline)
            enc.setFragmentTexture(sourceForNext, index: 0)
            enc.setFragmentSamplerState(sampler, index: 0)
            enc.setFragmentBytes(&uniforms, length: MemoryLayout<BeautyUniforms>.stride, index: 0)
            enc.setFragmentBuffer(snapFacePoints ?? zeroFacePointsBuffer, offset: 0, index: 1)
            enc.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
            enc.endEncoding()
        }

        cmd.commit()
        cmd.waitUntilCompleted()
        return textureToCGImage(outTex)
    }

    // MARK: - Texture helpers

    private func makeYuvTextures(pixelBuffer: CVPixelBuffer) -> (MTLTexture, MTLTexture)? {
        guard let textureCache else { return nil }
        let w = CVPixelBufferGetWidth(pixelBuffer), h = CVPixelBufferGetHeight(pixelBuffer)
        var yRef: CVMetalTexture?
        var uvRef: CVMetalTexture?
        guard CVMetalTextureCacheCreateTextureFromImage(
                nil, textureCache, pixelBuffer, nil, .r8Unorm, w, h, 0, &yRef) == kCVReturnSuccess,
              CVMetalTextureCacheCreateTextureFromImage(
                nil, textureCache, pixelBuffer, nil, .rg8Unorm, w / 2, h / 2, 1, &uvRef) == kCVReturnSuccess,
              let yRef, let uvRef,
              let y = CVMetalTextureGetTexture(yRef),
              let uv = CVMetalTextureGetTexture(uvRef) else { return nil }
        return (y, uv)
    }

    /// [🔴4] BGRA 单平面 → 单张纹理
    private func makeBgraTexture(pixelBuffer: CVPixelBuffer) -> MTLTexture? {
        guard let textureCache else { return nil }
        let w = CVPixelBufferGetWidth(pixelBuffer), h = CVPixelBufferGetHeight(pixelBuffer)
        var ref: CVMetalTexture?
        guard CVMetalTextureCacheCreateTextureFromImage(
                nil, textureCache, pixelBuffer, nil, .bgra8Unorm, w, h, 0, &ref) == kCVReturnSuccess,
              let ref, let tex = CVMetalTextureGetTexture(ref) else { return nil }
        return tex
    }

    // MARK: - Pass 编码辅助

    /// 通用 render pass 编码（uniforms=nil 时不绑定 buffer(0)）
    /// useIdentityBeauty=true 时用 beautyPipeline 做直通（zeroed uniforms）
    private func encodePass(
        cmd: MTLCommandBuffer,
        pipeline: MTLRenderPipelineState,
        dest: MTLTexture,
        textures: [(index: Int, texture: MTLTexture?)],
        sampler: Bool,
        fragmentBytes: UnsafeMutableRawPointer? = nil,
        fragmentBytesLength: Int = 0,
        bufferIndex: Int = 0,
        useIdentityBeauty: Bool = false
    ) {
        let d = MTLRenderPassDescriptor()
        d.colorAttachments[0].texture = dest
        d.colorAttachments[0].loadAction = .dontCare
        d.colorAttachments[0].storeAction = .store
        guard let enc = cmd.makeRenderCommandEncoder(descriptor: d) else { return }
        enc.setRenderPipelineState(pipeline)
        for (idx, tex) in textures {
            if let tex { enc.setFragmentTexture(tex, index: idx) }
        }
        if sampler { enc.setFragmentSamplerState(self.sampler, index: 0) }
        if let bytes = fragmentBytes, fragmentBytesLength > 0 {
            enc.setFragmentBytes(bytes, length: fragmentBytesLength, index: bufferIndex)
        }
        enc.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        enc.endEncoding()
    }

    // MARK: - ColorGrade uniforms

    private func makeColorGradeUniforms(snap: Params? = nil) -> ColorGradeUniforms? {
        let p = snap ?? params
        guard let cm = p.colorFilter.colorMatrix else { return nil }
        // 🔴7: Android offset 语义 0–255，上传 /255f（BeautyRenderer.kt:751）
        return ColorGradeUniforms(
            cmRow0: cm.rows.0, cmRow1: cm.rows.1, cmRow2: cm.rows.2, cmRow3: cm.rows.3,
            cmOffset: SIMD4(cm.offset.x / 255.0, cm.offset.y / 255.0, cm.offset.z / 255.0, cm.offset.w / 255.0),
            hasColorMatrix: 1.0,
            exposure: 0, contrast: 1, saturation: 1, temperature: 0, tint: 0,
            brightness: 0, warmth: 0,
            redAdj: 1, greenAdj: 1, blueAdj: 1,
            intensity: 1.0
        )
    }

    // MARK: - Texture → CGImage

    private func textureToCGImage(_ texture: MTLTexture) -> CGImage? {
        let w = texture.width, h = texture.height
        let bytesPerRow = w * 4
        var pixelData = [UInt8](repeating: 0, count: bytesPerRow * h)
        pixelData.withUnsafeMutableBytes { ptr in
            texture.getBytes(ptr.baseAddress!,
                             bytesPerRow: bytesPerRow,
                             from: MTLRegionMake2D(0, 0, w, h),
                             mipmapLevel: 0)
        }
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        guard let provider = CGDataProvider(data: Data(pixelData) as CFData),
              let cgImage = CGImage(
                width: w, height: h,
                bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: bytesPerRow,
                space: colorSpace,
                bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue),
                provider: provider, decode: nil, shouldInterpolate: false,
                intent: .defaultIntent) else { return nil }
        return cgImage
    }
}

// MARK: - Uniforms structs
// BeautyUniforms 定义在 BeautyUniforms.swift（唯一定义）

/// smoothing.metal 的 SmoothingUniforms
struct SmoothingUniforms {
    var blurAlpha: Float
    var sharpen: Float
    var whiten: Float
    var widthOffset: Float
    var heightOffset: Float
}

struct ColorGradeUniforms {
    var cmRow0: SIMD4<Float>
    var cmRow1: SIMD4<Float>
    var cmRow2: SIMD4<Float>
    var cmRow3: SIMD4<Float>
    var cmOffset: SIMD4<Float>
    var hasColorMatrix: Float
    var exposure: Float
    var contrast: Float
    var saturation: Float
    var temperature: Float
    var tint: Float
    var brightness: Float
    var warmth: Float
    var redAdj: Float
    var greenAdj: Float
    var blueAdj: Float
    var intensity: Float
}

// MARK: - Float clamp helper
extension Float {
    func clamped(_ range: ClosedRange<Float>) -> Float {
        Swift.min(Swift.max(self, range.lowerBound), range.upperBound)
    }
}
