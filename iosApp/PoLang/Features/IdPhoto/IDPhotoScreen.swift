import CoreGraphics
import SwiftUI
import UIKit

/// 证照屏（对标 specs/screens/idphoto.yaml §1/§2/§6.4/§7/§8；定稿截图 tmp/ui-reference/idphoto-02..05）。
/// fullScreenCover 全屏强制深色；Loading/Error/Ready 三态（§7.1）。
/// Ready = [弹性预览框 + 构图提示行 + Tab 行 + 面板]（§2，padding/spacing 均 spacing.lg=16）。
/// 🔴 预览渲染契约（§2.1 render_contract）：底图按 currentCropRect 裁切后 Canvas 精确拉伸填满整框，
/// 禁止 Image+scale/translation 组合（历史白边 bug 235281d5b）；重算期间保留上一帧防闪白。
struct IDPhotoScreen: View {
    let localIdentifier: String

    @StateObject private var vm: IDPhotoViewModel
    @Environment(\.dismiss) private var dismiss

    // 合成底图（nil=尚未就绪；重算期间保留上一帧显示——§2.1 rebuild_retention）
    @State private var lastBase: CGImage?

    // 平移/缩放增量结算（§6.4：保存上次值传增量，连续拖动连续生效）
    @State private var lastDragTranslation: CGSize = .zero
    @State private var lastMagnification: CGFloat = 1

    // 修补画笔工具参数（§6；住在 Screen——跨 tab 切换保留，绑定进 RepairPanel）
    @State private var brushMode: StrokeMode = .erase
    @State private var brushSize: Float = RepairConstants.defaultBrushSize
    @State private var softEdge = false
    // 进行中描边 overlay（框内像素坐标）+ 光标（仅拖动中显示）
    @State private var overlayPoints: [CGPoint] = []
    @State private var cursor: CGPoint?

    // 保存失败 toast（§7.5；成功走 vm.onSaved → dismiss）
    @State private var saveError: String?

    private var ready: IdPhotoState.Ready? {
        if case .ready(let r) = vm.state { return r } else { return nil }
    }

    init(localIdentifier: String) {
        self.localIdentifier = localIdentifier
        _vm = StateObject(wrappedValue: IDPhotoViewModel(localIdentifier: localIdentifier))
    }

    var body: some View {
        ZStack {
            IdPhotoTokens.screenBackground.ignoresSafeArea()
            VStack(spacing: 0) {
                topBar
                stateContent
            }
            if let saveError {
                toast(saveError)
            }
        }
        .preferredColorScheme(.dark)   // §8 强制深色（cover 不继承根 scheme，必须显式）
        .task {
            vm.onSaved = { dismiss() }
            vm.onSaveFailed = { message in
                saveError = message
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) {
                    if saveError == message { saveError = nil }
                }
            }
        }
    }

    // MARK: - 顶栏（§1：48pt 通用顶栏；back=退出不保存，check=保存）

    private var topBar: some View {
        AppTopBar(title: L("id_photo_title"),
                  showsBackButton: true,
                  onBack: { dismiss() }) {
            AppTopBarAction(systemName: "mat_o_check",
                            accessibilityID: "idphoto_save",
                            isEnabled: ready.map { readyState in !readyState.isSaving } ?? false) {
                Task { await vm.save() }
            }
            .accessibilityLabel(L("Done"))   // spec §1 actions desc=done
        }
    }

    // MARK: - 内容区（§1：按状态单选）

    @ViewBuilder
    private var stateContent: some View {
        switch vm.state {
        case .loading:
            ProgressView()
                .tint(AppColorScheme.dark.primary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .error(let message):
            Text(message)
                .foregroundStyle(.white)
                .padding(Spacing.lg)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .ready(let ready):
            readyContent(ready)
        }
    }

    // MARK: - Ready 布局（§2：预览区 weight(1f) + 提示行 + Tab 行 + 面板）

    private func readyContent(_ ready: IdPhotoState.Ready) -> some View {
        VStack(spacing: Spacing.lg) {
            previewFrame(ready)
                .frame(maxWidth: .infinity, maxHeight: .infinity)   // 垂直弹性区，内容居中
            Text(L(ready.activeTab == .repair ? "id_photo_repair_hint" : "id_photo_drag_hint"))
                .font(AppTypography.bodySmall.font)
                .foregroundStyle(.white.opacity(AppAlpha.secondary))
            IdPhotoTabRow(selected: ready.activeTab, onSelect: { tab in vm.selectTab(tab) })
            panel(for: ready)
        }
        .padding(Spacing.lg)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        // 底图刷新：task(id:) 必然重跑（onChange 在 TabView 页面有不触发前科）；nil 时保留旧帧
        .task(id: baseKey(ready)) {
            guard let image = await vm.previewBase() else { return }
            lastBase = image
            overlayPoints = []   // 新底图就绪才替换并清空修补 overlay 点（§2.1）
            cursor = nil
        }
    }

    /// 底图内容指纹（colorIndex + edgeParams + strokeVersion，§7 preview 缓存 key 同源）
    private func baseKey(_ ready: IdPhotoState.Ready) -> String {
        "\(ready.selectedColorIndex)-\(ready.edgeParams)-\(ready.strokeVersion)"
    }

    // MARK: - 预览框（§2.1：白边回归契约 + §6.4 手势切换）

    private func previewFrame(_ ready: IdPhotoState.Ready) -> some View {
        let spec = sizeSpec(at: ready.selectedSizeIndex)
        let frameHeight = IdPhotoTokens.frameWidth * CGFloat(spec.pixelH) / CGFloat(spec.pixelW)
        let crop = vm.currentCropRect()
        return previewCanvas(crop: crop)
            .frame(width: IdPhotoTokens.frameWidth, height: frameHeight)
            .background(IdPhotoTokens.frameBackground)
            .clipShape(RoundedRectangle(cornerRadius: IdPhotoTokens.frameRadius))
            .contentShape(Rectangle())
            .gesture(frameGesture(activeTab: ready.activeTab, frameHeight: frameHeight))
            .simultaneousGesture(magnifyGesture(activeTab: ready.activeTab))
    }

    /// Canvas 渲染：合成底图按 cropRect 裁切后**精确拉伸填满整框**（零留边零白条）+ 修补 overlay
    private func previewCanvas(crop: CropRect?) -> some View {
        Canvas { context, size in
            if let base = lastBase, let crop {
                let cropRect = CGRect(x: crop.left, y: crop.top, width: crop.width, height: crop.height)
                var tile = base
                if cropRect.width > 0, cropRect.height > 0, let cropped = base.cropping(to: cropRect) {
                    tile = cropped
                }
                context.draw(Image(uiImage: UIImage(cgImage: tile)), in: CGRect(origin: .zero, size: size))
            }
            drawStrokeOverlay(context: &context)
        }
    }

    /// 修补进行中 overlay（§6 in_progress_overlay）：restore=白/erase=黑 alpha 0.4 圆头折线，
    /// 线宽=brushSize（框内像素）；光标=白 0.8 圆圈描边（宽 2，spec §6 定值），仅拖动中显示
    private func drawStrokeOverlay(context: inout GraphicsContext) {
        guard !overlayPoints.isEmpty else { return }
        let paintColor = (brushMode == .restore ? Color.white : Color.black).opacity(AppAlpha.placeholder)
        let brushWidth = CGFloat(brushSize)
        if overlayPoints.count == 1 {
            let p = overlayPoints[0]
            let r = brushWidth / 2
            context.fill(Path(ellipseIn: CGRect(x: p.x - r, y: p.y - r, width: r * 2, height: r * 2)),
                         with: .color(paintColor))
        } else {
            var path = Path()
            path.move(to: overlayPoints[0])
            for point in overlayPoints.dropFirst() {
                path.addLine(to: point)
            }
            context.stroke(path, with: .color(paintColor),
                           style: StrokeStyle(lineWidth: brushWidth, lineCap: .round, lineJoin: .round))
        }
        if let c = cursor {
            let r = brushWidth / 2
            context.stroke(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)),
                           with: .color(Color.white.opacity(AppAlpha.emphasis)),
                           lineWidth: 2)
        }
    }

    // MARK: - 面板区（§2.4：按 tab 单选显示）

    @ViewBuilder
    private func panel(for ready: IdPhotoState.Ready) -> some View {
        switch ready.activeTab {
        case .bgColor:
            ColorSwatchRow(selectedIndex: ready.selectedColorIndex,
                           onSelect: { index in vm.selectColor(index) })
        case .size:
            SizeChipRow(selectedIndex: ready.selectedSizeIndex,
                        onSelect: { index in vm.selectSize(index) })
        case .edge:
            EdgePanel(edgeParams: ready.edgeParams,
                      onCommit: { params in vm.setEdgeParams(params) },
                      onReset: { vm.resetEdgeParams() })
        case .repair:
            RepairPanel(brushMode: $brushMode,
                        brushSize: $brushSize,
                        softEdge: $softEdge,
                        canUndo: ready.canUndoStroke,
                        canRedo: ready.canRedoStroke,
                        hasStrokes: ready.hasStrokes,
                        onUndo: { vm.undoStroke() },
                        onRedo: { vm.redoStroke() },
                        onClear: { vm.clearStrokes() })
        }
    }

    // MARK: - 手势（§6.4 / §6 painting_contract：REPAIR=单指画笔，其余=拖拽平移+双指缩放）

    private func frameGesture(activeTab: IdPhotoTab, frameHeight: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                if activeTab == .repair {
                    paintChanged(value, frameHeight: frameHeight)
                } else {
                    panChanged(value, frameHeight: frameHeight)
                }
            }
            .onEnded { value in
                if activeTab == .repair {
                    paintEnded()
                } else {
                    lastDragTranslation = .zero
                }
            }
    }

    /// 平移：增量结算（本次 translation − 上次 translation），保证连续拖动连续生效；
    /// 符号语义（右拖=取景窗左移）由 VM 内部处理，UI 原样传。
    /// ⚠️ 视图失效依赖：VM 的 offsetX/zoom 非 @Published——本函数对 lastDragTranslation 的
    /// @State 写入（及 magnifyGesture 对 lastMagnification 的写入）承担了驱动重渲染的职责，
    /// 二者不可「清理」掉，否则拖拽静默失灵（审查 Y4 登记）。
    private func panChanged(_ value: DragGesture.Value, frameHeight: CGFloat) {
        let dx = value.translation.width - lastDragTranslation.width
        let dy = value.translation.height - lastDragTranslation.height
        lastDragTranslation = value.translation
        vm.transformBy(dxFraction: Float(dx / IdPhotoTokens.frameWidth),
                       dyFraction: Float(dy / frameHeight),
                       zoomChange: 1)
    }

    /// 缩放：增量因子（value / 上次值，>1=放大）；双指期间 Drag 同时触发——增量结算下自然兼容
    private func magnifyGesture(activeTab: IdPhotoTab) -> some Gesture {
        MagnificationGesture()
            .onChanged { value in
                guard activeTab != .repair, lastMagnification > 0 else { return }
                vm.transformBy(dxFraction: 0, dyFraction: 0, zoomChange: Float(value / lastMagnification))
                lastMagnification = value
            }
            .onEnded { _ in lastMagnification = 1 }
    }

    /// 画笔：拖动开始 beginStroke（半径仅按宽度轴换算——屏幕上圆形笔刷），
    /// 移动逐点 append（源图坐标，domain 纯函数换算）；crop 为 nil 时不落笔
    private func paintChanged(_ value: DragGesture.Value, frameHeight: CGFloat) {
        let p = value.location
        if overlayPoints.isEmpty, let crop = vm.currentCropRect() {
            let radiusSource = IDPhotoComposer.frameRadiusToSource(
                radiusPx: brushSize / 2,
                frameW: Float(IdPhotoTokens.frameWidth),
                crop: crop)
            vm.beginStroke(mode: brushMode,
                           radiusSourcePx: radiusSource,
                           softness: softEdge ? RepairConstants.softnessOn : RepairConstants.softnessOff)
        }
        overlayPoints.append(p)
        cursor = p
        if let crop = vm.currentCropRect() {
            let source = IDPhotoComposer.frameToSource(
                px: Float(p.x), py: Float(p.y),
                frameW: Float(IdPhotoTokens.frameWidth), frameH: Float(frameHeight),
                crop: crop)
            vm.appendStrokePoint(xSourcePx: source.x, ySourcePx: source.y)
        }
    }

    /// 画笔结束：无条件 endStroke（手势取消也走这里——§6 cancel_safety）
    private func paintEnded() {
        overlayPoints = []
        cursor = nil
        vm.endStroke()
    }

    // MARK: - 工具

    private func sizeSpec(at index: Int) -> IDPhotoSizeSpec {
        let all = IDPhotoSizeSpec.allCases
        return all.indices.contains(index) ? all[index] : all[0]
    }

    /// 保存失败 toast（PhotoEditorScreen 先例样式）
    private func toast(_ message: String) -> some View {
        Text(message)
            .font(AppTypography.bodyMedium.font)
            .foregroundStyle(.white)
            .padding(.horizontal, Spacing.lg)
            .padding(.vertical, Spacing.sm)
            .background(Color.black.opacity(AppAlpha.emphasis))
            .clipShape(Capsule())
            .transition(.opacity)
    }
}
