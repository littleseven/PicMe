import SwiftUI
import UIKit

/// 图片编辑屏（editor.yaml §1-§5 + §17 抽卡对比模式）。从 MediaPagerView「编辑」入口经 fullScreenCover 进入。
/// CROP/ADJUST/FILTER(9 色+5 风格)/MARKUP 全功能；BEAUTY 滑杆可调 + 参数存档（渲染 DEFER）；
/// 去背景顶栏按钮置灰 + 敬请期待 toast；AI 优化为抽卡对比模式（gachaRun 非空时底栏整体
/// 替换为 GachaCandidateBar，主预览区照常全尺寸预览候选卡）。
struct PhotoEditorScreen: View {
    let localIdentifier: String
    var onSaved: (String?) -> Void = { _ in }
    /// chat 回链：编辑结果文件路径（与 onSaved 并存；chat EDIT 意图由 MainTabView 接收）
    var onEditResult: (String) -> Void = { _ in }

    @StateObject private var vm = PhotoEditorViewModel()
    @StateObject private var markupTool = MarkupToolState()
    @Environment(\.dismiss) private var dismiss

    // 预览缩放/平移
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero
    @State private var comparing = false

    // 标记输入
    @State private var pendingActions: [MarkupAction] = []
    @State private var textPos: NormPoint? = nil
    @State private var textInput: String = ""
    @State private var viewSize: CGSize = .zero
    @State private var showUnavailableToast = false
    private var ready: PhotoEditorViewModel.Ready? {
        if case .ready(let r) = vm.state { return r } else { return nil }
    }
    private var markupMode: Bool {
        // §11：MARKUP 绘制层仅在普通编辑态接管（对比模式 gachaRun 非空时隐藏）
        ready?.selectedTab == .markup && ready?.gachaRun == nil
    }

    /// 顶栏「AI 优化」可点条件（§3/§17.2）：已加载 + 非处理中 + 非对比模式。
    private var aiOptimizeEnabled: Bool {
        if case .ready(let r) = vm.state { return !r.isProcessing && r.gachaRun == nil }
        return false
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 0) {
                topBar
                previewArea
                bottomArea
            }
            if showUnavailableToast {
                Text(L("editor_feature_unavailable"))
                    .font(.system(size: 14))
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(Color.black.opacity(0.8))
                    .clipShape(Capsule())
                    .transition(.opacity)
            }
            if let errorMessage = vm.error {
                Text(errorMessage)
                    .font(.system(size: 14))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(Color.black.opacity(0.8))
                    .clipShape(Capsule())
                    .transition(.opacity)
            }
        }
        .preferredColorScheme(.dark)
        .animation(.easeInOut(duration: 0.2), value: ready?.selectedTab)
        .animation(.easeInOut(duration: 0.2), value: showUnavailableToast)
        .animation(.easeInOut(duration: 0.2), value: vm.error)
        .animation(.easeInOut(duration: 0.2), value: ready?.gachaRun == nil)   // 底栏 ↔ 抽卡条切换
        .task {
            vm.onSaved = { id in
                onSaved(id)
                dismiss()
            }
            vm.onEditResult = { path in onEditResult(path) }
            if ready == nil { vm.load(localIdentifier: localIdentifier) }
        }
        .onChange(of: ready?.previewUIImage) { _ in
            pendingActions = []   // 新预览到达 = 已提交动作烘焙完成
        }
        .onChange(of: localIdentifier) { _ in
            resetZoom()
            vm.load(localIdentifier: localIdentifier)
        }
        .onChange(of: ready?.selectedTab) { _ in resetZoom() }
        .overlay(alignment: .center) { textInputDialog }
    }

    // MARK: Top bar

    private var topBar: some View {
        AppTopBar(title: String(localized: "Edit"),
                  showsBackButton: true,
                  onBack: { dismiss() }) {
            EditorAction(system: "mat_o_layers_clear", enabled: false) { unavailable() } // 去背景 DEFER
            EditorAction(system: "mat_o_auto_fix_high",
                         enabled: aiOptimizeEnabled,
                         accessibilityLabel: L("ai_optimize")) { vm.aiOptimize() } // AI 优化抽卡（§17）
            EditorAction(system: "mat_o_undo", enabled: vm.canUndo) { vm.undo() }
            EditorAction(system: "mat_o_redo", enabled: vm.canRedo) { vm.redo() }
            EditorAction(system: "mat_o_check", enabled: !(ready?.isSaving ?? false)) { vm.save() }
        }
        .background(Color(.systemBackground))
    }

    // MARK: Preview

    private var previewArea: some View {
        // 编辑器画布锁黑底——语义色固定暗色档（下同）
        let s = AppColorScheme.dark
        return GeometryReader { geo in
            ZStack {
                Color.black
                switch vm.state {
                case .loading:
                    ProgressView().tint(s.primary)
                case .error(let msg):
                    Text(msg).foregroundStyle(.white).padding()
                case .ready(let r):
                    previewImage(r, in: geo.size)
                }
            }
            .onAppear { viewSize = geo.size }
        }
    }

    @ViewBuilder
    private func previewImage(_ r: PhotoEditorViewModel.Ready, in size: CGSize) -> some View {
        let s = AppColorScheme.dark
        let img = comparing ? r.originalUIImage : r.previewUIImage
        let ratio = img.size.width / max(1, img.size.height)
        ZStack {
            if markupMode {
                // MARKUP 模式：图片不带缩放/平移手势，让 MarkupDrawingCanvas 接管绘制
                Image(uiImage: img).resizable().scaledToFit()
                    .scaleEffect(scale).offset(offset)
                MarkupDrawingCanvas(
                    bitmapRatio: ratio,
                    toolState: markupTool,
                    pendingActions: pendingActions,
                    onCommit: { action in
                        pendingActions.append(action)
                        vm.addMarkupAction(action)
                    },
                    onTextTap: { pos in textPos = pos; textInput = "" })
            } else {
                Image(uiImage: img).resizable().scaledToFit()
                    .scaleEffect(scale).offset(offset)
                    .gesture(magnify(in: size))
                    .highPriorityGesture(pan(in: size), isEnabled: scale > 1.02)
                    .onTapGesture(count: 2) { resetZoom() }
                    .onLongPressGesture(minimumDuration: 0.35, perform: {},
                                        onPressingChanged: { pressing in comparing = pressing })
            }

            if r.selectedTab == .crop {
                cropTransformOverlay
            }
            if r.isProcessing {
                ProgressView().tint(s.primary)
            }
        }
        .clipped()
    }

    private var cropTransformOverlay: some View {
        GeometryReader { geo in
            HStack {
                Button {
                    if case .ready(var r) = vm.state {
                        vm.updateRecipe { $0.crop.rotation = (r.recipe.crop.rotation - 90 + 360) % 360 }
                    }
                } label: {
                    Image(systemName: "rotate.left")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: EditorTokens.cropTransformButtonSize,
                               height: EditorTokens.cropTransformButtonSize)
                        .background(Circle().fill(Color.black.opacity(EditorTokens.cropTransformButtonBgAlpha)))
                }
                Spacer()
                Button {
                    vm.updateRecipe { $0.crop.flippedH.toggle() }
                } label: {
                    Image(systemName: "rectangle.righthalf.infilled")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: EditorTokens.cropTransformButtonSize,
                               height: EditorTokens.cropTransformButtonSize)
                        .background(Circle().fill(Color.black.opacity(EditorTokens.cropTransformButtonBgAlpha)))
                }
            }
            .padding(16)
            .frame(height: geo.size.height, alignment: .bottom)
        }
    }

    // MARK: Bottom（面板 + tab 条；抽卡对比模式整体替换为 GachaCandidateBar）

    @ViewBuilder
    private var bottomArea: some View {
        if let r = ready {
            if let run = r.gachaRun {
                // 对比模式（§17.1）：面板 + 底栏隐藏，主预览区照常全尺寸预览（VM 驱动）
                GachaCandidateBar(
                    run: run,
                    isProcessing: r.isProcessing,
                    onPreview: { index in vm.previewGachaCandidate(index) },
                    onApply: { vm.applyGachaCandidate() },
                    onReroll: { vm.rerollGacha() },
                    onDismiss: { vm.dismissGacha() })
                    .background(Color.black)
            } else {
                VStack(spacing: 0) {
                    panelSlot(r)
                    EditorBottomBar(selectedTab: r.selectedTab) { vm.selectTab($0) }
                }
                .background(Color(.systemBackground))
            }
        }
    }

    @ViewBuilder
    private func panelSlot(_ r: PhotoEditorViewModel.Ready) -> some View {
        switch r.selectedTab {
        case .crop:
            CropPanel(crop: Binding(
                get: { r.recipe.crop },
                set: { v in vm.updateRecipe { $0.crop = v } }))
        case .adjust:
            AdjustPanel(adjustments: Binding(
                get: { r.recipe.adjustments },
                set: { v in vm.updateRecipe { $0.adjustments = v } }))
        case .beauty:
            BeautyPanel(beauty: Binding(
                get: { r.recipe.beauty },
                set: { v in vm.updateRecipe { $0.beauty = v } }))
        case .filter:
            FilterPanel(
                colorFilter: Binding(
                    get: { r.recipe.colorFilter },
                    set: { v in vm.updateRecipe { $0.colorFilter = v } }),
                styleFilter: Binding(
                    get: { r.recipe.styleFilter },
                    set: { v in vm.updateRecipe { $0.styleFilter = v } }))
        case .markup:
            MarkupPanel(toolState: markupTool,
                        actions: Binding(
                            get: { r.recipe.markup },
                            set: { v in vm.updateRecipe { $0.markup = v } }))
        }
    }

    // MARK: Text input dialog

    @ViewBuilder
    private var textInputDialog: some View {
        if textPos != nil {
            let s = AppColorScheme.dark
            ZStack {
                Color.black.opacity(0.5).ignoresSafeArea().onTapGesture { textPos = nil }
                VStack(spacing: 12) {
                    Text("Text").font(.headline).foregroundStyle(s.onSurface)
                    TextField("", text: $textInput)
                        .textFieldStyle(.roundedBorder)
                    HStack {
                        Button("Cancel") { textPos = nil }
                            .foregroundStyle(s.primary)
                        Spacer()
                        Button("Add") {
                            guard !textInput.isEmpty, let pos = textPos else { return }
                            let action = MarkupAction.text(id: UUID().uuidString,
                                                           text: textInput,
                                                           position: pos,
                                                           color: markupTool.color,
                                                           size: MarkupConstants.defaultTextSize)
                            pendingActions.append(action)
                            vm.addMarkupAction(action)
                            textPos = nil
                        }
                        .foregroundStyle(s.primary)
                    }
                }
                .padding()
                .background(AppShapes.panel.fill(s.surfaceContainerHigh))
                .padding(40)
            }
        }
    }

    // MARK: Gestures

    private func magnify(in size: CGSize) -> some Gesture {
        MagnificationGesture()
            .onChanged { v in
                scale = min(4, max(1, lastScale * v))
                offset = clamped(offset, in: size)
            }
            .onEnded { _ in
                lastScale = scale
                if scale <= 1.02 { resetZoom() }
                else { offset = clamped(offset, in: size); lastOffset = offset }
            }
    }

    private func pan(in size: CGSize) -> some Gesture {
        DragGesture()
            .onChanged { d in
                offset = clamped(CGSize(width: lastOffset.width + d.translation.width,
                                        height: lastOffset.height + d.translation.height), in: size)
            }
            .onEnded { _ in lastOffset = offset }
    }

    private func clamped(_ v: CGSize, in size: CGSize) -> CGSize {
        let maxX = size.width * (scale - 1) / 2
        let maxY = size.height * (scale - 1) / 2
        return CGSize(width: min(maxX, max(-maxX, v.width)),
                      height: min(maxY, max(-maxY, v.height)))
    }

    private func resetZoom() {
        withAnimation(.easeInOut(duration: 0.2)) { scale = 1; offset = .zero }
        lastScale = 1; lastOffset = .zero; comparing = false
    }

    private func unavailable() {
        showUnavailableToast = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) { showUnavailableToast = false }
    }
}

// MARK: - 子组件

private struct EditorAction: View {
    let system: String
    var enabled: Bool = true
    var accessibilityLabel: String? = nil
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            // mat_o_* outlined 资产经 MatIcon template 渲染（对齐 Android Outlined.*；字形 22=IconSize.md，框 36 不变）
            MatIcon(name: system, size: IconSize.md)
                .frame(width: 36, height: 36).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(enabled ? Color.primary : Color.secondary.opacity(0.35))
        .disabled(!enabled)
        .modifier(EditorActionA11yLabel(label: accessibilityLabel))
    }
}

/// 可选 accessibilityLabel（nil 时不施加 modifier，保持系统默认）。
private struct EditorActionA11yLabel: ViewModifier {
    let label: String?

    func body(content: Content) -> some View {
        if let label {
            content.accessibilityLabel(Text(label))
        } else {
            content
        }
    }
}
