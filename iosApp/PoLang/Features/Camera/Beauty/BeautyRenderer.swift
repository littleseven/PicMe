import Foundation
import Metal
import MetalKit
import CoreVideo

/// 美颜渲染宿主（对应 Android BeautyRenderer 的 MVP 版）。
/// 管线：camera YUV →(yuv pass)→ rgbTexture →(smoothing pass, optional)→ (beauty pass)→ MTKView drawable
/// pass 链结构对齐 Android；Task 14 磨皮插入为中间 pass。
///
/// shader 编译：4 个 .metal 文件在 Xcode 编译期产出 metallib（device.makeDefaultLibrary）；
/// fragment function 名：`yuv_fragment` / `smoothing_fragment` / `beauty_fragment`
/// vertex function 名：`quad_vertex`（concat guard 确保全局一份）
final class BeautyRenderer: NSObject {
    /// 对标 shared BeautySettings 的 MVP 子集
    struct Params {
        var whitening: Float = 0
        var smoothing: Float = 0
        var slimFace: Float = 0
        var bigEyes: Float = 0
        var colorFilter: Int32 = 0  // FilterType ordinal（Task 17 LUT）
    }

    private let device: MTLDevice
    private let commandQueue: MTLCommandQueue
    private let yuvPipeline: MTLRenderPipelineState
    private let beautyPipeline: MTLRenderPipelineState
    private let sampler: MTLSamplerState
    private var textureCache: CVMetalTextureCache?
    private var rgbTexture: MTLTexture?
    private var smoothingTexture: MTLTexture?

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
              let beautyPSO = pipeline(vert, bFrag) else { return nil }
        self.yuvPipeline = yuvPSO
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

        // Pass 2: beauty 上屏（warp + whitenSkin）
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
            enc.setFragmentTexture(rgbTex, index: 0)
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
}
