import Foundation
import Metal
import MetalKit
import CoreVideo

/// 美颜渲染宿主（对应 Android BeautyRenderer 的 MVP 版）。
/// 管线：camera YUV →(yuv pass)→ rgbTexture →(smoothing pass, optional)→ (lut pass, optional)→ (beauty pass)→ drawable
/// pass 链结构对齐 Android。
///
/// shader 编译：5 个 .metal 文件在 Xcode 编译期产出 metallib（device.makeDefaultLibrary）；
/// fragment function 名：`yuv_fragment` / `smoothing_fragment` / `lut_fragment` / `beauty_fragment`
/// vertex function 名：`quad_vertex`（concat guard 确保全局一份）
final class BeautyRenderer: NSObject {
    /// 对标 shared BeautySettings 的 MVP 子集
    struct Params {
        var whitening: Float = 0
        var smoothing: Float = 0
        var slimFace: Float = 0
        var bigEyes: Float = 0
        var colorFilter: FilterType = .none  // Task 17 LUT/ColorMatrix
    }

    private let device: MTLDevice
    private let commandQueue: MTLCommandQueue
    private let yuvPipeline: MTLRenderPipelineState
    private let lutPipeline: MTLRenderPipelineState
    private let beautyPipeline: MTLRenderPipelineState
    private let sampler: MTLSamplerState
    private var textureCache: CVMetalTextureCache?
    private var rgbTexture: MTLTexture?
    private var lutTexture: MTLTexture?

    /// 人脸关键点 buffer（106 点 × 2 floats）
    private var facePointsBuffer: MTLBuffer?

    var params = Params()

    init?(device: MTLDevice) {
        self.device = device
        // spike 踩坑：commandQueue 勿漏初始化 → 黑屏
        guard let queue = device.makeCommandQueue(),
              let lib = device.makeDefaultLibrary(bundle: .main),
              let vert = lib.makeFunction(name: "quad_vertex"),
              let yuvFrag = lib.makeFunction(name: "yuv_fragment"),
              let lutFrag = lib.makeFunction(name: "lut_fragment"),
              let bFrag = lib.makeFunction(name: "beauty_fragment") else { return nil }
        self.commandQueue = queue

        func pipeline(_ v: MTLFunction, _ f: MTLFunction) -> MTLRenderPipelineState? {
            let pd = MTLRenderPipelineDescriptor()
            pd.vertexFunction = v
            pd.fragmentFunction = f
            pd.colorAttachments[0].pixelFormat = .bgra8Unorm
            return try? device.makeRenderPipelineState(descriptor: pd)
        }
        guard let yuvPSO = pipeline(vert, yuvFrag),
              let lutPSO = pipeline(vert, lutFrag),
              let beautyPSO = pipeline(vert, bFrag) else { return nil }
        self.yuvPipeline = yuvPSO
        self.lutPipeline = lutPSO
        self.beautyPipeline = beautyPSO

        let sd = MTLSamplerDescriptor()
        sd.minFilter = .linear; sd.magFilter = .linear
        sd.sAddressMode = .clampToEdge; sd.tAddressMode = .clampToEdge
        guard let s = device.makeSamplerState(descriptor: sd) else { return nil }
        self.sampler = s
        super.init()
        CVMetalTextureCacheCreate(nil, nil, device, nil, &textureCache)
    }

    private func ensureTexture(_ existing: MTLTexture?, w: Int, h: Int) -> MTLTexture? {
        if existing?.width == w && existing?.height == h { return existing }
        let td = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .bgra8Unorm, width: w, height: h, mipmapped: false)
        td.usage = [.renderTarget, .shaderRead]
        return device.makeTexture(descriptor: td)
    }

    /// 更新人脸关键点（由 FaceLandmarkService 调用）
    func updateFacePoints(_ points: [SIMD2<Float>], hasFace: Bool) {
        guard hasFace, points.count >= 106 else {
            facePointsBuffer = nil
            return
        }
        // 扁平化 106×2 floats
        let floats = points.prefix(106).flatMap { [$0.x, $0.y] }
        let buffer = device.makeBuffer(bytes: floats, length: 106 * 2 * MemoryLayout<Float>.stride, options: .storageModeShared)
        facePointsBuffer = buffer
    }

    func draw(pixelBuffer: CVPixelBuffer, in view: MTKView) {
        guard let textureCache,
              let textures = makeTextures(pixelBuffer: pixelBuffer),
              let drawable = view.currentDrawable,
              let cmd = commandQueue.makeCommandBuffer() else { return }
        let (yTex, uvTex) = textures
        let w = CVPixelBufferGetWidth(pixelBuffer), h = CVPixelBufferGetHeight(pixelBuffer)

        // Pass 1: YUV → RGB 离屏
        rgbTexture = ensureTexture(rgbTexture, w: w, h: h)
        guard let rgbTex = rgbTexture else { return }

        let d1 = MTLRenderPassDescriptor()
        d1.colorAttachments[0].texture = rgbTex
        d1.colorAttachments[0].loadAction = .dontCare
        d1.colorAttachments[0].storeAction = .store
        if let enc = cmd.makeRenderCommandEncoder(descriptor: d1) {
            enc.setRenderPipelineState(yuvPipeline)
            enc.setFragmentTexture(yTex, index: 0)
            enc.setFragmentTexture(uvTex, index: 1)
            enc.setFragmentSamplerState(sampler, index: 0)
            enc.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
            enc.endEncoding()
        }

        // Pass 2: LUT / ColorMatrix（仅 colorFilter != .none 时启用）
        var sourceForBeauty = rgbTex
        if params.colorFilter != .none, let lutFragUniforms = makeColorGradeUniforms() {
            lutTexture = ensureTexture(lutTexture, w: w, h: h)
            if let lutTex = lutTexture {
                let dLut = MTLRenderPassDescriptor()
                dLut.colorAttachments[0].texture = lutTex
                dLut.colorAttachments[0].loadAction = .dontCare
                dLut.colorAttachments[0].storeAction = .store
                var cgUni = lutFragUniforms
                if let enc = cmd.makeRenderCommandEncoder(descriptor: dLut) {
                    enc.setRenderPipelineState(lutPipeline)
                    enc.setFragmentTexture(rgbTex, index: 0)
                    enc.setFragmentSamplerState(sampler, index: 0)
                    enc.setFragmentBytes(&cgUni, length: MemoryLayout<ColorGradeUniforms>.stride, index: 0)
                    enc.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
                    enc.endEncoding()
                }
                sourceForBeauty = lutTex
            }
        }

        // Pass 3: beauty 上屏（warp + whitenSkin）
        guard let d2 = view.currentRenderPassDescriptor else { return }
        d2.colorAttachments[0].loadAction = .clear
        d2.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 1)

        var uniforms = BeautyUniforms()
        uniforms.whitening = params.whitening
        uniforms.smoothing = params.smoothing
        uniforms.slimFace = params.slimFace
        uniforms.bigEyes = params.bigEyes
        uniforms.aspectRatio = Float(w) / Float(h)
        uniforms.hasFace = facePointsBuffer != nil ? 1.0 : 0.0
        uniforms.useGpupixelWarp = 1

        if let enc = cmd.makeRenderCommandEncoder(descriptor: d2) {
            enc.setRenderPipelineState(beautyPipeline)
            enc.setFragmentTexture(sourceForBeauty, index: 0)
            enc.setFragmentSamplerState(sampler, index: 0)
            enc.setFragmentBytes(&uniforms, length: MemoryLayout<BeautyUniforms>.stride, index: 0)
            if let fpBuffer = facePointsBuffer {
                enc.setFragmentBuffer(fpBuffer, offset: 0, index: 1)
            }
            enc.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
            enc.endEncoding()
        }

        cmd.present(drawable)
        cmd.commit()
    }

    private func makeTextures(pixelBuffer: CVPixelBuffer) -> (MTLTexture, MTLTexture)? {
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

    // MARK: - LUT / ColorMatrix uniforms（Task 17）

    /// 构造 ColorGradeUniforms（从 FilterType ColorMatrix + 默认调色参数）
    private func makeColorGradeUniforms() -> ColorGradeUniforms? {
        guard let cm = params.colorFilter.colorMatrix else { return nil }
        return ColorGradeUniforms(
            cmRow0: cm.rows.0, cmRow1: cm.rows.1, cmRow2: cm.rows.2, cmRow3: cm.rows.3,
            cmOffset: cm.offset,
            hasColorMatrix: 1.0,
            exposure: 0, contrast: 1, saturation: 1, temperature: 0, tint: 0,
            brightness: 0, warmth: 0,
            redAdj: 1, greenAdj: 1, blueAdj: 1,
            intensity: 1.0
        )
    }

    // MARK: - 离屏渲染（Task 18 拍照链路）

    /// 将美颜全管线渲染到全分辨率离屏纹理，回读为 CGImage。
    /// 复用预览同一 pass 链（yuv→rgb→lut→beauty），不复制 shader 逻辑。
    /// [PERF] 异步调用，不阻塞快门响应。
    func renderToImage(pixelBuffer: CVPixelBuffer) -> CGImage? {
        guard let textures = makeTextures(pixelBuffer: pixelBuffer),
              let cmd = commandQueue.makeCommandBuffer() else { return nil }
        let (yTex, uvTex) = textures
        let w = CVPixelBufferGetWidth(pixelBuffer), h = CVPixelBufferGetHeight(pixelBuffer)

        // Pass 1: YUV → RGB
        let fullRgbTex = ensureTexture(nil, w: w, h: h)
        guard let rgbTex = fullRgbTex else { return nil }
        runPass(cmd: cmd, pipeline: yuvPipeline, dest: rgbTex,
                textures: [(0, yTex), (1, uvTex)], uniforms: nil, facePoints: nil)

        // Pass 2: LUT (optional)
        var sourceForBeauty = rgbTex
        if params.colorFilter != .none, let lutFragUniforms = makeColorGradeUniforms() {
            let lutTex = ensureTexture(nil, w: w, h: h)
            if let lut = lutTex {
                runPass(cmd: cmd, pipeline: lutPipeline, dest: lut,
                        textures: [(0, rgbTex)], uniforms: lutFragUniforms, facePoints: nil,
                        uniformSize: MemoryLayout<ColorGradeUniforms>.stride)
                sourceForBeauty = lut
            }
        }

        // Pass 3: beauty → 最终离屏纹理
        let outputTex = ensureTexture(nil, w: w, h: h)
        guard let outTex = outputTex else { return nil }

        var uniforms = BeautyUniforms()
        uniforms.whitening = params.whitening
        uniforms.smoothing = params.smoothing
        uniforms.slimFace = params.slimFace
        uniforms.bigEyes = params.bigEyes
        uniforms.aspectRatio = Float(w) / Float(h)
        uniforms.hasFace = facePointsBuffer != nil ? 1.0 : 0.0
        uniforms.useGpupixelWarp = 1

        let dOut = MTLRenderPassDescriptor()
        dOut.colorAttachments[0].texture = outTex
        dOut.colorAttachments[0].loadAction = .clear
        dOut.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 1)
        dOut.colorAttachments[0].storeAction = .store
        if let enc = cmd.makeRenderCommandEncoder(descriptor: dOut) {
            enc.setRenderPipelineState(beautyPipeline)
            enc.setFragmentTexture(sourceForBeauty, index: 0)
            enc.setFragmentSamplerState(sampler, index: 0)
            enc.setFragmentBytes(&uniforms, length: MemoryLayout<BeautyUniforms>.stride, index: 0)
            if let fpBuffer = facePointsBuffer {
                enc.setFragmentBuffer(fpBuffer, offset: 0, index: 1)
            }
            enc.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
            enc.endEncoding()
        }

        cmd.commit()
        cmd.waitUntilCompleted()

        // 回读为 CGImage
        return textureToCGImage(outTex)
    }

    // 辅助：运行单个 render pass（YUV 或 LUT 通用封装）
    private func runPass(
        cmd: MTLCommandBuffer,
        pipeline: MTLRenderPipelineState,
        dest: MTLTexture,
        textures: [(index: Int, texture: MTLTexture)],
        uniforms: Any?,
        facePoints: MTLBuffer?,
        uniformSize: Int = MemoryLayout<BeautyUniforms>.stride
    ) {
        let d = MTLRenderPassDescriptor()
        d.colorAttachments[0].texture = dest
        d.colorAttachments[0].loadAction = .dontCare
        d.colorAttachments[0].storeAction = .store
        guard let enc = cmd.makeRenderCommandEncoder(descriptor: d) else { return }
        enc.setRenderPipelineState(pipeline)
        for (idx, tex) in textures {
            enc.setFragmentTexture(tex, index: idx)
        }
        enc.setFragmentSamplerState(sampler, index: 0)
        if var uni = uniforms as? BeautyUniforms {
            enc.setFragmentBytes(&uni, length: MemoryLayout<BeautyUniforms>.stride, index: 0)
        } else if var uni = uniforms as? ColorGradeUniforms {
            enc.setFragmentBytes(&uni, length: MemoryLayout<ColorGradeUniforms>.stride, index: 0)
        }
        enc.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        enc.endEncoding()
    }

    /// MTLTexture → CGImage（通过 bytes 回读）
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
                bitsPerComponent: 8, bitsPerPixel: 32,
                bytesPerRow: bytesPerRow,
                space: colorSpace,
                bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue),
                provider: provider,
                decode: nil, shouldInterpolate: false,
                intent: .defaultIntent) else { return nil }
        return cgImage
    }
}

// MARK: - ColorGradeUniforms（Swift 侧，与 lut.metal ColorGradeUniforms 对齐）

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
