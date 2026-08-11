import SwiftUI
import UIKit
import CoreImage
import Photos

/// 图片编辑器 ViewModel（对齐 androidApp `PhotoEditorViewModel`，@MainActor ObservableObject 惯例）。
/// 状态机：loading / error / ready。预览重渲染 200ms debounce + 后台线程。
/// editor.yaml §2 状态机 / §12 渲染管线。
///
/// 修复闭环（reviewer 🔴/🟡）：
/// - #1 保存走全分辨率原图（ThumbnailLoader.fullResolution），不再用 ≤2048 预览缩略图。
/// - #2 历史去抖入栈（dirty flag + 渲染完成时 push），滑杆拖拽不再 spam。
/// - #3 渲染复用 RecipeApplier.context（不再每次 new CIContext）。
/// - #6 load/render 可取消 + 代际守卫，防 stale 覆盖。
/// - #7 实时编辑不置 isProcessing，拖拽期间不盖 spinner。
@MainActor
final class PhotoEditorViewModel: ObservableObject {

    enum State {
        case loading
        case error(String)
        case ready(Ready)
    }

    struct Ready {
        var originalUIImage: UIImage
        var previewUIImage: UIImage
        var recipe: EditRecipe
        var selectedTab: EditorTab = .crop
        var isProcessing: Bool = false   // 仅保存等离散重操作置位；实时编辑不再置位（#7）
        var isSaving: Bool = false
    }

    @Published private(set) var state: State = .loading

    /// 保存完成回调（新副本 localIdentifier 或 nil）。
    var onSaved: ((String?) -> Void)?

    private let history = EditHistory()
    private var previewSourceCI: CIImage?   // 预览源（≤2048 降采样）
    private var sourceLocalId: String?      // 保存时重取全分辨率
    private var loadTask: Task<Void, Never>?
    private var renderTask: Task<Void, Never>?
    private var dirty = false               // 待提交历史（去抖入栈，#2）
    private var renderGen = 0               // 渲染代际，防 stale 覆盖（#6）

    var canUndo: Bool {
        if case .ready(let r) = state { return !r.isSaving && history.canUndo }
        return false
    }
    var canRedo: Bool {
        if case .ready(let r) = state { return !r.isSaving && history.canRedo }
        return false
    }

    // MARK: Load（可取消 + 代际守卫，#6）

    func load(localIdentifier: String) {
        loadTask?.cancel()
        renderTask?.cancel()
        dirty = false
        renderGen &+= 1
        state = .loading
        sourceLocalId = localIdentifier
        loadTask = Task {
            let image = await ThumbnailLoader.shared.thumbnail(
                for: localIdentifier,
                size: CGSize(width: 2048, height: 2048),
                highQuality: true)
            guard !Task.isCancelled else { return }
            guard let image, let ci = CIImage(image: image) else {
                if !Task.isCancelled { state = .error(String(localized: "Failed to load image")) }
                return
            }
            self.previewSourceCI = ci
            let recipe = EditRecipe(sourceUri: localIdentifier)
            self.history.reset(recipe)
            self.dirty = false
            let myGen = self.renderGen
            let preview = await self.render(recipe, from: ci)
            guard !Task.isCancelled, myGen == self.renderGen else { return }
            self.state = .ready(Ready(originalUIImage: image,
                                       previewUIImage: preview ?? image,
                                       recipe: recipe))
        }
    }

    // MARK: Recipe mutation（实时变更：不入栈、不 spinner；去抖提交，#2/#7）

    func updateRecipe(_ change: (inout EditRecipe) -> Void) {
        guard case .ready(var r) = state else { return }
        change(&r.recipe)
        state = .ready(r)
        dirty = true
        scheduleRender(r.recipe)
    }

    func setMarkup(_ actions: [MarkupAction]) {
        updateRecipe { $0.markup = actions }
    }

    func addMarkupAction(_ action: MarkupAction) {
        updateRecipe { $0.markup.append(action) }
    }

    func selectTab(_ tab: EditorTab) {
        guard case .ready(var r) = state else { return }
        r.selectedTab = tab
        state = .ready(r)
    }

    func undo() {
        guard let recipe = history.undo() else { return }
        dirty = false
        applyHistoryRecipe(recipe)
    }

    func redo() {
        guard let recipe = history.redo() else { return }
        dirty = false
        applyHistoryRecipe(recipe)
    }

    private func applyHistoryRecipe(_ recipe: EditRecipe) {
        guard case .ready(var r) = state else { return }
        r.recipe = recipe
        state = .ready(r)
        scheduleRender(recipe)
    }

    // MARK: Render（200ms debounce + 代际守卫；复用 RecipeApplier.context，#3/#6；去抖入栈 #2）

    private func scheduleRender(_ recipe: EditRecipe) {
        renderTask?.cancel()
        renderGen &+= 1
        let myGen = renderGen
        renderTask = Task {
            try? await Task.sleep(nanoseconds: 200_000_000)
            if Task.isCancelled { return }
            guard let source = self.previewSourceCI, myGen == self.renderGen else { return }
            let preview = await self.render(recipe, from: source)
            guard !Task.isCancelled, myGen == self.renderGen,
                  let preview, case .ready(var r) = self.state else { return }
            r.previewUIImage = preview
            self.state = .ready(r)
            if self.dirty {
                self.history.push(r.recipe)
                self.dirty = false
            }
        }
    }

    private func render(_ recipe: EditRecipe, from source: CIImage) async -> UIImage? {
        let copy = source
        return await Task.detached(priority: .userInitiated) { () -> UIImage? in
            let output = RecipeApplier.apply(recipe, to: copy)
            guard let cg = RecipeApplier.context.createCGImage(output, from: output.extent) else { return nil }
            return UIImage(cgImage: cg)
        }.value
    }

    // MARK: Save（非破坏性：全分辨率原图渲染新副本 → 相册，#1）

    func save() {
        guard case .ready(var r) = state, let localId = sourceLocalId else { return }
        r.isSaving = true
        state = .ready(r)
        let recipe = r.recipe
        Task {
            // 全分辨率原图（非预览缩略图）
            let full = await ThumbnailLoader.shared.fullResolution(for: localId)
            var savedUri: String? = nil
            if let full, let fullCI = CIImage(image: full) {
                let image = await self.render(recipe, from: fullCI)
                if let image {
                    var status = PHPhotoLibrary.authorizationStatus(for: .addOnly)
                    if status == .notDetermined {
                        status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
                    }
                    if status == .authorized || status == .limited {
                        try? await PhotoSaver.saveToLibrary(image)
                        savedUri = localId   // lite 版不追踪新副本 id
                    }
                }
            }
            await MainActor.run {
                if case .ready(var rr) = self.state {
                    rr.isSaving = false
                    self.state = .ready(rr)
                }
                self.onSaved?(savedUri)
            }
        }
    }
}
