import CoreGraphics
import Foundation
import Photos
import UIKit

// MARK: - 证照屏 ViewModel（对标 specs/screens/idphoto.yaml §7 状态机与数据流）
// UI 层契约见 IDPhotoScreen；纯逻辑在 IdPhotoDomain；推理在 MattingEngine。
// 线程契约：状态拷贝在 Main；全图计算经串行 compute 队列（描边快照在进队前取）；
// 模型下载中心交互（路径解析/enqueue）在 Main。

enum IdPhotoState {
    case loading
    case error(String)

    struct Ready {
        var selectedColorIndex: Int = 0
        var selectedSizeIndex: Int = 0
        var activeTab: IdPhotoTab = .bgColor
        var edgeParams: EdgeParams = .defaultValue
        var strokeVersion: Int = 0
        var canUndoStroke: Bool = false
        var canRedoStroke: Bool = false
        var hasStrokes: Bool = false
        var isSaving: Bool = false
    }

    case ready(Ready)
}

@MainActor
final class IDPhotoViewModel: ObservableObject {

    // 常量契约
    private static let decodeMaxDim = 1024
    private static let jpegQuality: CGFloat = 0.95
    private static let minZoom: Float = 1.0
    private static let maxZoom: Float = 4.0

    @Published private(set) var state: IdPhotoState = .loading

    var onSaved: (() -> Void)?
    var onSaveFailed: ((String) -> Void)?

    private let localIdentifier: String
    private let engine = IDPhotoMattingEngine()

    // Ready 数据（非 UI 投影部分）
    private var original: CGImage?
    private var originalPixels: [UInt8] = []
    private var sourceW = 0
    private var sourceH = 0
    private var rawAlpha: [Float] = []
    private var subject: (top: Int, centerX: Float)?
    private var offsetX: Float = 0
    private var offsetY: Float = 0
    private var zoom: Float = 1

    // 描边栈
    private var strokes: [BrushStroke] = []
    private var redoStack: [BrushStroke] = []
    private var activeStroke: BrushStroke?

    // 缓存（key = 内容指纹）
    private struct AdjustKey: Equatable {
        var edgeParams: EdgeParams
        var strokeVersion: Int
    }
    private struct PreviewKey: Equatable {
        var colorIndex: Int
        var edgeParams: EdgeParams
        var strokeVersion: Int
    }
    private var adjustedAlphaCacheKey: AdjustKey?
    private var adjustedAlphaCache: [Float] = []
    private var previewCacheKey: PreviewKey?
    private var previewCache: CGImage?

    /// 串行计算队列：掩码变换/合成/推理全走这里（重放前快照在 Main 已取）
    private let computeQueue = DispatchQueue(label: "com.mamba.picme.idphoto.compute", qos: .userInitiated)

    private func runCompute<T>(_ work: @escaping () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            computeQueue.async {
                do {
                    continuation.resume(returning: try work())
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    init(localIdentifier: String) {
        self.localIdentifier = localIdentifier
        Task { await load() }
    }

    deinit {
        engine.release()
    }

    // MARK: - 加载

    private func load() async {
        guard let uiImage = await ThumbnailLoader.shared.fullResolution(for: localIdentifier),
              let fullCg = uiImage.cgImage else {
            state = .error(L("editor_load_failed"))
            return
        }
        // 长边 ≤1024 降采样（契约 DECODE_MAX_DIM）
        let cg = IdPhotoBitmap.downscale(fullCg, maxDim: Self.decodeMaxDim)
        // Main 侧预解析 modnet 路径（下载中心属 MainActor；缺失 → enqueue 下载，本次报错重进重试）
        let modnetPath = Self.resolveModnetPath()
        do {
            let result = try await runCompute { [engine] in
                try engine.removeBackground(cg, modnetModelPath: modnetPath)
            }
            guard let buffer = IdPhotoBitmap.rgbaBuffer(from: cg) else {
                state = .error(L("editor_load_failed"))
                return
            }
            let bounds = try? await runCompute {
                IDPhotoComposer.subjectBounds(result.alpha, w: result.width, h: result.height)
            }
            original = cg
            originalPixels = buffer.pixels
            sourceW = buffer.width
            sourceH = buffer.height
            rawAlpha = result.alpha
            subject = bounds
            offsetX = 0
            offsetY = 0
            zoom = 1
            adjustedAlphaCacheKey = nil
            previewCacheKey = nil
            state = .ready(IdPhotoState.Ready())
        } catch MattingError.modelMissing(let modelId) {
            // 引擎已 enqueue 下载；本次报错，返回重进即重试（Android 对齐）
            state = .error(String(format: L("editor_load_failed_with_reason"), modelId))
        } catch {
            state = .error(L("id_photo_matting_failed"))
        }
    }

    // MARK: - 面板操作（Main 同步状态拷贝）

    func selectColor(_ index: Int) {
        updateReady { $0.selectedColorIndex = index }
    }

    func selectSize(_ index: Int) {
        updateReady { $0.selectedSizeIndex = index }
    }

    func selectTab(_ tab: IdPhotoTab) {
        updateReady { $0.activeTab = tab }
    }

    func setEdgeParams(_ params: EdgeParams) {
        updateReady { $0.edgeParams = params }
    }

    func resetEdgeParams() {
        updateReady { $0.edgeParams = .defaultValue }
    }

    // MARK: - 构图手势

    /// dxFraction/dyFraction：拖动位移/预览框尺寸（正=手指右/下移）；zoomChange：缩放增量因子。
    /// 语义：内容跟手——offsetX -= dx（右拖 → 取景窗左移）；随后 clampFraming 收敛防死区。
    func transformBy(dxFraction: Float, dyFraction: Float, zoomChange: Float) {
        guard case .ready = state else { return }
        let spec = currentSizeSpec()
        let clamped = IDPhotoComposer.clampFraming(
            subject: subject,
            offsetX: offsetX - dxFraction, offsetY: offsetY - dyFraction,
            zoom: zoom * zoomChange,
            srcW: sourceW, srcH: sourceH,
            dstW: spec.pixelW, dstH: spec.pixelH
        )
        offsetX = clamped.offsetX
        offsetY = clamped.offsetY
        zoom = min(Self.maxZoom, max(Self.minZoom, clamped.zoom))
    }

    /// 当前取景窗（源图像素）；供预览绘制与坐标换算
    func currentCropRect() -> CropRect? {
        guard case .ready(let ready) = state else { return nil }
        let spec = IDPhotoSizeSpec.allCases[ready.selectedSizeIndex]
        return IDPhotoComposer.subjectAwareCropRect(
            subject: subject,
            offsetX: offsetX, offsetY: offsetY, zoom: zoom,
            srcW: sourceW, srcH: sourceH,
            dstW: spec.pixelW, dstH: spec.pixelH
        )
    }

    // MARK: - 描边（快照契约：Main 取快照 → compute 重放）

    func beginStroke(mode: StrokeMode, radiusSourcePx: Float, softness: Float) {
        activeStroke = BrushStroke(mode: mode, radiusPx: max(1, radiusSourcePx),
                                   softness: softness, points: [])
    }

    func appendStrokePoint(xSourcePx: Float, ySourcePx: Float) {
        activeStroke?.points.append(StrokePoint(x: xSourcePx, y: ySourcePx))
    }

    func endStroke() {
        guard var stroke = activeStroke else { return }
        stroke.points = stroke.points.count == 1
            ? [stroke.points[0], stroke.points[0]]
            : stroke.points
        activeStroke = nil
        guard !stroke.points.isEmpty else { return }
        strokes.append(stroke)
        redoStack.removeAll()
        bumpStrokeState()
    }

    var hasActiveStroke: Bool { activeStroke != nil }

    func undoStroke() {
        guard let last = strokes.popLast() else { return }
        redoStack.append(last)
        bumpStrokeState()
    }

    func redoStroke() {
        guard let stroke = redoStack.popLast() else { return }
        strokes.append(stroke)
        bumpStrokeState()
    }

    func clearStrokes() {
        guard !strokes.isEmpty || !redoStack.isEmpty else { return }
        strokes.removeAll()
        redoStack.removeAll()
        bumpStrokeState()
    }

    private func bumpStrokeState() {
        updateReady {
            $0.strokeVersion += 1
            $0.canUndoStroke = !strokes.isEmpty
            $0.canRedoStroke = !redoStack.isEmpty
            $0.hasStrokes = !strokes.isEmpty
        }
    }

    // MARK: - 底图合成（缓存 key = color + edge + strokeVersion）

    /// 合成底图；nil = 构建中/失败（UI 保留上一帧防闪白）
    func previewBase() async -> CGImage? {
        guard case .ready(let ready) = state, original != nil else { return nil }
        let key = PreviewKey(colorIndex: ready.selectedColorIndex,
                             edgeParams: ready.edgeParams,
                             strokeVersion: ready.strokeVersion)
        if let cached = previewCache, previewCacheKey == key {
            return cached
        }
        // Main 取快照（线程契约）
        let colorIndex = ready.selectedColorIndex
        let edgeParams = ready.edgeParams
        let strokeSnapshot = strokes
        let raw = rawAlpha
        let pixels = originalPixels
        let w = sourceW
        let h = sourceH
        let cg = (try? await runCompute { () -> CGImage? in
            var alpha = raw
            alpha = MaskPostProcessor.adjustEdges(alpha, w: w, h: h, params: edgeParams)
            alpha = StrokeLayer.replay(strokes: strokeSnapshot, base: alpha, w: w, h: h)
            guard IDPhotoColorSpec.allCases.indices.contains(colorIndex) else { return nil }
            let color = IDPhotoColorSpec.allCases[colorIndex].rgb
            let composed = BackgroundComposer.composeOnColor(pixels: pixels, alpha: alpha, bgColor: color)
            return IdPhotoBitmap.cgImage(from: composed, width: w, height: h)
        })
        guard let image = cg else { return nil }
        previewCacheKey = key
        previewCache = image
        return image
    }

    // MARK: - 保存（WYSIWYG：与预览同 base 同 cropRect）

    func composePreview() async -> CGImage? {
        guard let base = await previewBase(),
              let crop = currentCropRect(),
              let cropped = base.cropping(to: CGRect(x: crop.left, y: crop.top,
                                                     width: crop.width, height: crop.height)) else {
            return nil
        }
        let spec = currentSizeSpec()
        // 精确拉伸到规格像素尺寸（与预览框渲染同语义：cover 零留边）
        var pixels = [UInt8](repeating: 0, count: spec.pixelW * spec.pixelH * 4)
        return pixels.withUnsafeMutableBytes { ptr -> CGImage? in
            guard let ctx = CGContext(
                data: ptr.baseAddress,
                width: spec.pixelW, height: spec.pixelH,
                bitsPerComponent: 8, bytesPerRow: spec.pixelW * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return nil }
            ctx.interpolationQuality = .high
            ctx.draw(cropped, in: CGRect(x: 0, y: 0, width: spec.pixelW, height: spec.pixelH))
            return ctx.makeImage()
        }
    }

    func save() async {
        guard case .ready(var ready) = state, !ready.isSaving else { return }
        ready.isSaving = true
        state = .ready(ready)
        defer {
            // 读最新 state 复位（不吞保存期间的操作）
            if case .ready(var latest) = state {
                latest.isSaving = false
                state = .ready(latest)
            }
        }
        guard let cg = await composePreview() else {
            onSaveFailed?(L("editor_save_failed"))
            return
        }
        // JPEG 0.95 契约（PHAsset 由系统命名——双端差异已登记 spec §12）
        let image = UIImage(cgImage: cg)
        guard let jpeg = image.jpegData(compressionQuality: Self.jpegQuality),
              let finalImage = UIImage(data: jpeg) else {
            onSaveFailed?(L("editor_save_failed"))
            return
        }
        do {
            try await PhotoSaver.saveToLibrary(finalImage)
            onSaved?()
        } catch {
            onSaveFailed?(L("editor_save_failed"))
        }
    }

    // MARK: - 工具

    /// Main 侧解析 modnet 模型路径；缺失时 enqueue 下载（Android initializeWithDownloadFallback 对齐）
    private static func resolveModnetPath() -> String? {
        let manager = ModelDownloadManager.shared
        let path = manager.modelsDir
            .appendingPathComponent(IDPhotoMattingEngine.modnetModelId)
            .appendingPathComponent("modnet.onnx").path
        guard FileManager.default.fileExists(atPath: path) else {
            if !manager.isModelDownloaded(IDPhotoMattingEngine.modnetModelId) {
                manager.download(IDPhotoMattingEngine.modnetModelId)
            }
            return nil
        }
        return path
    }

    private func currentSizeSpec() -> IDPhotoSizeSpec {
        guard case .ready(let ready) = state,
              IDPhotoSizeSpec.allCases.indices.contains(ready.selectedSizeIndex) else {
            return .in1
        }
        return IDPhotoSizeSpec.allCases[ready.selectedSizeIndex]
    }

    private func updateReady(_ mutate: (inout IdPhotoState.Ready) -> Void) {
        guard case .ready(var ready) = state else { return }
        mutate(&ready)
        state = .ready(ready)
    }
}
