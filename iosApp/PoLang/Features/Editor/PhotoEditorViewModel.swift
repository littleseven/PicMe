import SwiftUI
import UIKit
import CoreImage
import Photos

/// 图片编辑器 ViewModel（对齐 androidApp `PhotoEditorViewModel`，@MainActor ObservableObject 惯例）。
/// 状态机：loading / error / ready。预览重渲染 200ms debounce + 后台线程。
/// editor.yaml §2 状态机 / §12 渲染管线 / §17.2 AI 优化抽卡状态机。
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
        var gachaRun: GachaRunUiState? = nil   // AI 优化抽卡对比模式（§17.2）；nil = 普通编辑态
    }

    /// 抽卡对比模式 UI 状态（editor.yaml §17.2）。
    struct GachaRunUiState {
        /// 候选卡组（含 NIMA 分 / 护栏淘汰标记 / 512px 内存缩略图）
        var candidates: [ScoredCandidate]
        /// 推荐卡 index（KeepOriginal 无推荐 → -1，角标不显示）
        var recommendedIndex: Int
        /// 主预览正在展示的卡 index；-1 = 基准配方渲染（原图语义）
        var previewedIndex: Int
        /// 已出现参数指纹集合（「换一组」跨抽累积去重）
        var exclude: Set<String>
        /// 抽卡基准配方（进入对比时的编辑器配方；预览回退与手选映射的基底）
        var baseRecipe: EditRecipe
        /// 识别场景（落库反馈 / 日志）
        var scene: OptimizeScene
        /// 是否 KeepOriginal（全部候选未显著优于原图，入场保持原图预览）
        var keepOriginal: Bool
        /// 第几抽（首轮 0，「换一组」+1）
        var drawIndex: Int = 0
    }

    @Published private(set) var state: State = .loading

    /// 瞬态错误（toast 呈现，自动清除）。抽卡失败等可恢复错误走此通道，
    /// 不进 State.error（那是加载失败整屏态，会丢编辑现场）。
    @Published private(set) var error: String? = nil

    /// 保存完成回调（新副本 localIdentifier 或 nil）。
    var onSaved: ((String?) -> Void)?
    /// chat 回链：编辑结果落盘路径（Documents/chat_edits/xxx.jpg）。
    /// 与 onSaved 并存——onSaved 语义不变（新副本 localId，lite 版=原图 id），本回调专供 chat 渲染。
    var onEditResult: ((String) -> Void)?

    private let history = EditHistory()
    private var previewSourceCI: CIImage?   // 预览源（≤2048 降采样）
    private var sourceLocalId: String?      // 保存时重取全分辨率
    private var loadTask: Task<Void, Never>?
    private var renderTask: Task<Void, Never>?
    private var errorClearTask: Task<Void, Never>?
    private var dirty = false               // 待提交历史（去抖入栈，#2）
    private var renderGen = 0               // 渲染代际，防 stale 覆盖（#6）

    // 抽卡（§17.2）：引擎需要本地文件 URL，编辑器源是 PHAsset → 落临时文件复用整轮
    private lazy var gachaFeedbackLogger = OptimizeFeedbackLogger()
    private var gachaSourceURL: URL?

    var canUndo: Bool {
        // §2：gachaRun 非空（对比模式）时撤销禁用
        if case .ready(let r) = state { return !r.isSaving && r.gachaRun == nil && history.canUndo }
        return false
    }
    var canRedo: Bool {
        if case .ready(let r) = state { return !r.isSaving && r.gachaRun == nil && history.canRedo }
        return false
    }

    // MARK: Load（可取消 + 代际守卫，#6）

    func load(localIdentifier: String) {
        loadTask?.cancel()
        renderTask?.cancel()
        errorClearTask?.cancel()
        error = nil
        cleanupGachaSourceFile()
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
        // 对比模式（gachaRun 非空）下撤销不可用（§2；顶栏按钮已禁用，此处双保险）
        guard case .ready(let r) = state, r.gachaRun == nil else { return }
        guard let recipe = history.undo() else { return }
        dirty = false
        applyHistoryRecipe(recipe)
    }

    func redo() {
        guard case .ready(let r) = state, r.gachaRun == nil else { return }
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

    /// 抽卡对比模式的预览渲染（§17.2）：仅更新 previewUIImage，不动 recipe、不入撤销历史。
    /// 与 scheduleRender 共用 renderTask / renderGen（两态互斥，代际互相作废）。
    private func scheduleGachaRender(_ recipe: EditRecipe) {
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
            var editResultPath: String? = nil
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
                    // chat 回链：编辑结果写文件（Documents/chat_edits）。lite 版库保存不追踪新副本
                    // PHAsset id（savedUri=原图 id，显示会错图），文件路径才可靠——对齐 Android outputUri 模型。
                    if let data = image.jpegData(compressionQuality: 0.92) {
                        editResultPath = Self.writeChatEditFile(data)
                    }
                }
            }
            await MainActor.run {
                if case .ready(var rr) = self.state {
                    rr.isSaving = false
                    self.state = .ready(rr)
                }
                self.onSaved?(savedUri)
                if let path = editResultPath {
                    self.onEditResult?(path)
                }
            }
        }
    }

    // MARK: AI 优化抽卡（§17.2：抽卡 → 对比模式 → 手选应用 / 关闭回退）

    /// 顶栏「AI 优化」入口：抽卡闭环（场景识别 → 4 候选 → NIMA 选优 + 退化守卫）。
    /// - selected：进入对比模式，推荐卡进主预览（不入撤销历史）
    /// - keepOriginal：进入对比模式但 previewedIndex=-1（保持基准配方预览）
    /// - unavailable：兜底固定预设直接应用并入撤销历史（旧行为，不进对比模式）
    func aiOptimize() {
        guard case .ready(let r) = state else { return }
        guard !r.isProcessing, r.gachaRun == nil else { return }   // 防重入
        // 提交在途的去抖编辑（#2 残留），保证抽卡基准 = 完整当前配方
        if dirty {
            history.push(r.recipe)
            dirty = false
        }
        renderTask?.cancel()   // 丢弃在途预览渲染（入栈已手动补齐；代际由后续渲染重置）
        var rr = r
        rr.isProcessing = true
        state = .ready(rr)
        let baseRecipe = r.recipe
        let original = r.originalUIImage
        Task {
            // 引擎需要本地文件 URL（场景分析 + 512px 候选渲染）：落临时文件，整轮复用。
            // 全链路端侧（[PRIVACY]），临时文件退出对比模式时清理。
            let url = await Self.writeGachaSourceFile(original)
            guard let url else {
                self.endGachaProcessing()
                self.showError(String(localized: "ai_optimize_not_available"))
                return
            }
            self.gachaSourceURL = url
            let outcome = await AiOptimizeService.shared.optimizeWithGacha(
                imageFile: url, savedTo: nil, baseRecipe: baseRecipe,
                imageKey: baseRecipe.sourceUri)   // 稳定键=编辑器源 URI（临时文件路径含 UUID 不落库）
            self.handleFirstDrawOutcome(outcome, baseRecipe: baseRecipe)
        }
    }

    /// 首抽结果分流（§17.2）。unavailable → 兜底配方直接应用 + 入历史（旧行为）。
    private func handleFirstDrawOutcome(_ outcome: AiOptimizeService.GachaOutcome,
                                        baseRecipe: EditRecipe) {
        guard case .ready(var r) = state, r.gachaRun == nil else { endGachaProcessing(); return }
        // enterGachaCompare 返回 false 的分支不改动 state（下方局部 r 仍有效）
        if enterGachaCompare(outcome, baseRecipe: baseRecipe, previousRun: nil) { return }
        r.isProcessing = false
        if var fallback = outcome.editRecipe {
            fallback.sourceUri = baseRecipe.sourceUri   // 引擎以临时文件路径回填，还原编辑器源
            r.recipe = fallback
            state = .ready(r)
            dirty = true
            scheduleRender(fallback)   // 渲染完成后入撤销历史（#2 去抖入栈，同普通编辑）
        } else {
            state = .ready(r)
            showError(String(localized: "ai_optimize_not_available"))
        }
        cleanupGachaSourceFile()
    }

    /// 「换一组」：以本轮基准配方 + 累积指纹排除集重抽，覆写 gachaRun（drawIndex+1）。
    /// 不可用时保留旧卡组继续手选，仅提示。
    func rerollGacha() {
        guard case .ready(let r) = state else { return }
        guard !r.isProcessing, let run = r.gachaRun, let url = gachaSourceURL else { return }
        var rr = r
        rr.isProcessing = true
        state = .ready(rr)
        let baseRecipe = run.baseRecipe
        let exclude = run.exclude
        Task {
            let outcome = await AiOptimizeService.shared.optimizeWithGacha(
                imageFile: url, savedTo: nil, baseRecipe: baseRecipe, exclude: exclude,
                imageKey: baseRecipe.sourceUri)
            self.handleRerollOutcome(outcome, previousRun: run)
        }
    }

    private func handleRerollOutcome(_ outcome: AiOptimizeService.GachaOutcome,
                                     previousRun: GachaRunUiState) {
        guard case .ready(var r) = state, r.gachaRun != nil else { endGachaProcessing(); return }
        if enterGachaCompare(outcome, baseRecipe: previousRun.baseRecipe, previousRun: previousRun) {
            return
        }
        // 重抽不可用：保留旧卡组可继续手选
        r.isProcessing = false
        state = .ready(r)
        showError(String(localized: "ai_optimize_not_available"))
    }

    /// selected / keepOriginal 共用：覆写 gachaRun 进入（或刷新）对比模式，并渲染首帧预览。
    /// 返回 false = unavailable（调用方各自兜底）。返回 false 的路径不改动 state。
    private func enterGachaCompare(_ outcome: AiOptimizeService.GachaOutcome,
                                   baseRecipe: EditRecipe,
                                   previousRun: GachaRunUiState?) -> Bool {
        guard case .ready(var r) = state else { return false }
        switch outcome.result {
        case .selected(let best, let all, _):
            guard var recipe = outcome.editRecipe else { return false }
            recipe.sourceUri = baseRecipe.sourceUri
            r.isProcessing = false
            r.gachaRun = GachaRunUiState(
                candidates: all,
                recommendedIndex: best.candidate.index,
                previewedIndex: best.candidate.index,
                exclude: outcome.usedFingerprints,
                baseRecipe: baseRecipe,
                scene: outcome.scene,
                keepOriginal: false,
                drawIndex: (previousRun?.drawIndex ?? -1) + 1)
            state = .ready(r)
            scheduleGachaRender(recipe)   // 推荐卡进主预览（不入撤销历史）
            return true
        case .keepOriginal(let all, _):
            r.isProcessing = false
            r.gachaRun = GachaRunUiState(
                candidates: all,
                recommendedIndex: -1,   // 引擎未选优，无推荐角标
                previewedIndex: -1,     // 保持基准配方（原图语义）预览
                exclude: outcome.usedFingerprints,
                baseRecipe: baseRecipe,
                scene: outcome.scene,
                keepOriginal: true,
                drawIndex: (previousRun?.drawIndex ?? -1) + 1)
            state = .ready(r)
            scheduleGachaRender(baseRecipe)   // 回到基准配方渲染（覆盖可能被取消的旧渲染）
            return true
        case .unavailable:
            return false
        }
    }

    /// 点卡预览：该卡配方（baseRecipe 起，不叠加）进主预览，仅预览不落历史不落库。
    func previewGachaCandidate(_ index: Int) {
        guard case .ready(var r) = state, var run = r.gachaRun else { return }
        guard let scored = Self.candidate(run, at: index), !scored.rejected else { return }
        run.previewedIndex = index
        r.gachaRun = run
        state = .ready(r)
        let recipe = OptimizeRecipeMapper.toEditRecipe(
            preset: scored.candidate.preset,
            sourceUri: run.baseRecipe.sourceUri,
            baseRecipe: run.baseRecipe)
        scheduleGachaRender(recipe)
    }

    /// 「应用」：当前预览卡配方入撤销历史 + 落库 source=user，退出对比模式。
    func applyGachaCandidate() {
        guard case .ready(var r) = state, let run = r.gachaRun else { return }
        guard let scored = Self.candidate(run, at: run.previewedIndex), !scored.rejected else { return }
        let recipe = OptimizeRecipeMapper.toEditRecipe(
            preset: scored.candidate.preset,
            sourceUri: run.baseRecipe.sourceUri,
            baseRecipe: run.baseRecipe)
        // imageUri 与引擎 auto 落库同源（稳定键=baseRecipe.sourceUri；库内只存 SHA-256 摘要）
        gachaFeedbackLogger.log(imageUri: run.baseRecipe.sourceUri,
                                scene: run.scene,
                                all: run.candidates,
                                selectedIndex: run.previewedIndex,
                                source: OptimizeFeedbackLogger.sourceUser)
        r.gachaRun = nil
        r.recipe = recipe
        state = .ready(r)
        dirty = true
        scheduleRender(recipe)   // 渲染完成后入撤销历史（#2；预览帧与该配方一致）
        cleanupGachaSourceFile()
    }

    /// 「关闭」：预览回退基准配方 + 落库 source=dismiss，退出对比模式（不入历史）。
    func dismissGacha() {
        guard case .ready(var r) = state, let run = r.gachaRun else { return }
        // dismiss 语义=未选择放弃（selectedIndex 恒 -1，对齐 Android/Chat 侧口径）
        gachaFeedbackLogger.log(imageUri: run.baseRecipe.sourceUri,
                                scene: run.scene,
                                all: run.candidates,
                                selectedIndex: -1,
                                source: OptimizeFeedbackLogger.sourceDismiss)
        r.gachaRun = nil
        state = .ready(r)
        scheduleGachaRender(run.baseRecipe)
        cleanupGachaSourceFile()
    }

    /// 按 candidate.index 查卡（index 与数组位一致，first 查找防御潜在乱序）。
    private static func candidate(_ run: GachaRunUiState, at index: Int) -> ScoredCandidate? {
        guard index >= 0 else { return nil }
        return run.candidates.first { scored in scored.candidate.index == index }
    }

    /// 仅复位 isProcessing（异常兜底路径用）。
    private func endGachaProcessing() {
        guard case .ready(var r) = state else { return }
        r.isProcessing = false
        state = .ready(r)
    }

    /// 瞬态错误：toast 呈现约 2s 后自动清除（后一条顶掉前一条）。
    private func showError(_ message: String) {
        error = message
        errorClearTask?.cancel()
        errorClearTask = Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            if !Task.isCancelled { self.error = nil }
        }
    }

    /// 抽卡引擎源文件：≤2048 预览原图落临时目录 JPEG（后台编码，避免主线程卡顿）。
    private static func writeGachaSourceFile(_ image: UIImage) async -> URL? {
        await Task.detached(priority: .userInitiated) { () -> URL? in
            guard let data = image.jpegData(compressionQuality: 0.95) else { return nil }
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent("gacha_editor_\(UUID().uuidString).jpg")
            do {
                try data.write(to: url)
                return url
            } catch {
                return nil
            }
        }.value
    }

    /// 清理抽卡临时源文件（退出对比模式 / 重载图片时）。
    private func cleanupGachaSourceFile() {
        if let url = gachaSourceURL {
            try? FileManager.default.removeItem(at: url)
        }
        gachaSourceURL = nil
    }

    deinit {
        // 编辑页直接返回（对比模式中退出）时兜底清理临时文件（仅访问存储属性，Swift 5 安全）
        if let url = gachaSourceURL {
            try? FileManager.default.removeItem(at: url)
        }
    }

    /// 编辑结果落盘 Documents/chat_edits/<uuid>.jpg（目录懒创建）；失败返回 nil。
    private static func writeChatEditFile(_ data: Data) -> String? {
        let fm = FileManager.default
        guard let docs = fm.urls(for: .documentDirectory, in: .userDomainMask).first else { return nil }
        let dir = docs.appendingPathComponent("chat_edits", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("\(UUID().uuidString).jpg")
        do {
            try data.write(to: url)
            return url.path
        } catch {
            return nil
        }
    }
}
