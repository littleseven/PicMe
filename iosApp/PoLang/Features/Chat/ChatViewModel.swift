import Foundation
import Combine
import SharedKit
import UIKit

/// Chat ViewModel（MV 模式，对齐 Android ChatViewModel 多会话语义，spec chat.yaml §9.1 session_model）。
///
/// 交互模型（spec chat.yaml §9.1）：
/// - 发送流程：user 消息即追加 → thinking 占位（3 点）→ streaming（文本 + 光标）
///   → toolCalling（「正在调用工具…」）→ complete
/// - 发送按钮仅在有内容 && !isProcessing 时显示（Android 无 stop 按钮）
/// - 媒体结果作为独立消息项（不嵌入文本气泡）
/// - 空状态示例 chips 点击直接发送
///
/// 多会话（spec §2.5/§9.1）：
/// - 会话索引 + 每会话消息文件（ChatHistoryStore）；LLM 记忆按 sessionId 分键隔离
/// - 首条用户消息自动生成标题（仅默认标题时覆盖，重命名后不覆盖）
/// - 当前会话 ID 持久化，冷启校验存在否则回退 default；删除当前会话回退 default
@MainActor
final class ChatViewModel: ObservableObject {
    @Published private(set) var messages: [ChatMessage] = []
    @Published private(set) var isProcessing = false
    @Published private(set) var threads: [ChatThread] = []
    @Published private(set) var currentSessionId: String = ChatHistoryStore.defaultSessionId
    @Published var searchQuery = ""

    // MARK: 上下文附件（B1-feasible）
    /// 选中的图片意图（对齐 Android ChatViewModel.ImageIntent）：理解 / 找相似 / 编辑
    enum ImageIntent: String, CaseIterable {
        case understand, findSimilar, edit
        var label: String {
            switch self {
            case .understand: return String(localized: "Understand")
            case .findSimilar: return String(localized: "Find similar")
            case .edit: return String(localized: "Edit")
            }
        }
    }
    struct StagedImage: Equatable {
        let localIdentifier: String
        var thumbnail: UIImage?
    }
    @Published var stagedImage: StagedImage?
    @Published var stagedIntent: ImageIntent = .understand
    /// 端侧能力不可用的提示（UNDERSTAND/FIND_SIMILAR 引擎本版未实现）
    @Published var unavailableNotice: String? = nil
    /// EDIT 意图回调（ChatView 注入 → PhotoEditorScreen）
    var onEditImage: ((String) -> Void)?

    func stageImage(_ localIdentifier: String) {
        stagedImage = StagedImage(localIdentifier: localIdentifier)
        let lid = localIdentifier
        Task { @MainActor [weak self] in
            let img = await ThumbnailLoader.shared.thumbnail(for: lid, size: CGSize(width: 240, height: 240))
            guard var s = self?.stagedImage, s.localIdentifier == lid else { return }
            s.thumbnail = img
            self?.stagedImage = s
        }
    }
    func unstageImage() { stagedImage = nil }

    private var bridge: ChatAgentBridge?
    /// 流式吐字节奏器（commonMain StreamingPacingController，经 SharedKit 工厂创建）
    private var pacing: StreamingPacingController?
    private var actionWatcher: FlowWatcher?

    /// 侧栏列表：标题或预览模糊过滤（对齐 Android filteredThreads）
    var filteredThreads: [ChatThread] {
        let query = searchQuery.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else { return threads }
        return threads.filter {
            $0.title.range(of: query, options: .caseInsensitive) != nil ||
            $0.lastMessagePreview.range(of: query, options: .caseInsensitive) != nil
        }
    }

    /// 幂等：同一 bridge 重复 configure（tab 切换 onAppear）不重载磁盘，
    /// 避免覆盖流式中的内存消息（Android 侧 Room Flow 天然无此问题）。
    func configure(bridge: ChatAgentBridge) {
        if self.bridge === bridge { return }
        self.bridge = bridge
        currentSessionId = ChatHistoryStore.shared.currentSessionId
        bridge.setSessionId(id: currentSessionId)
        messages = ChatHistoryStore.shared.loadMessages(sessionId: currentSessionId)
        threads = ChatHistoryStore.shared.loadThreads()
        actionWatcher?.cancel()
        actionWatcher = bridge.watchUiActions { [weak self] dto in
            Task { @MainActor in self?.handleUiAction(dto) }
        }
        // 图表渲染回调：LLM draw_chart → IosChartCapability → ChartJsEngine 产 SVG，
        // 经 ChartRendererBridge.onChart 回到本 ViewModel 追加 CHART 消息。
        ChartRendererBridge.onChart = { [weak self] svg, summary in
            Task { @MainActor in self?.appendChartMessage(svg: svg, summary: summary) }
        }
        // 编辑结果回链：chat EDIT → PhotoEditorScreen 保存 → 落盘路径回此追加 AGENT_EDIT_RESULT
        ChatEditResultBridge.onEditResult = { [weak self] path in
            Task { @MainActor in self?.appendEditResultMessage(imagePath: path) }
        }
    }

    // MARK: - Send (对齐 Android sendMessage 流程)

    func send(_ input: String) {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)

        // CHART 触发链 demo：经 CapabilityRegistry 派发 DrawChart（确定性验证 capability→bridge→渲染）
        if trimmed.lowercased() == "/charttool" {
            emitChartViaToolChain()
            return
        }
        // CHART 渲染 demo：直接调 ChartJsEngine（验证渲染层，不经触发链）
        if trimmed.lowercased().hasPrefix("/chart") {
            emitChartDemo()
            return
        }

        // run_gallery_script 触发链 demo：经 CapabilityRegistry 派发 ExecuteScript（确定性验证
        // IosRunScriptCapability → RunScriptBridge → JsRuntime+JsCoreEngine → gallery handler）
        if trimmed.lowercased() == "/runscript" {
            emitRunScriptDemo()
            return
        }

        // AGENT_EDIT_RESULT 渲染 demo：生成图落盘 → 追加编辑结果消息（确定性验证，/chart 同款）
        if trimmed.lowercased() == "/editdemo" {
            emitEditResultDemo()
            return
        }

        // EDIT 意图：有暂存图 → 跳编辑器（对齐 Android EDIT，不发推理）
        if let staged = stagedImage, stagedIntent == .edit {
            onEditImage?(staged.localIdentifier)
            stagedImage = nil
            return
        }
        // 有暂存图 + 无文本 + 非 EDIT：UNDERSTAND/FIND_SIMILAR 端侧引擎本版不可用
        if trimmed.isEmpty, stagedImage != nil {
            unavailableNotice = String(localized: "On-device image understanding and search-by-image are not available in this version.")
            return
        }

        guard !trimmed.isEmpty, !isProcessing else { return }
        guard let bridge else { return }

        // 1. user 消息即追加（带暂存图 → userImageText 上图下文；图引用 localIdentifier，
        //    远程只发文本——图片像素不上传，隐私红线）
        messages.append(ChatMessage(
            role: .user,
            text: trimmed,
            type: stagedImage != nil ? .userImageText : .userText,
            imageUri: stagedImage?.localIdentifier
        ))
        autoTitleIfNeeded(firstUserText: trimmed)
        touchThread(preview: trimmed)
        persist()
        // 带图发文本：远程只收文本（图片像素不上传，隐私红线）；暂存图消费掉
        stagedImage = nil

        // 2. assistant 占位：thinking 态（首 token 前显示 3 点动画）
        let placeholderId = UUID()
        messages.append(ChatMessage(
            id: placeholderId, role: .assistant,
            text: "", isStreaming: true, isThinking: true
        ))
        isProcessing = true

        // 节奏器（豆包风逐字吐）：onPaced 在 main 回调，按 50ms/字推进文本 + 光标可见性
        pacing = createStreamingPacingController(onPaced: { [weak self] text, cursor in
            guard let self else { return }
            guard let idx = self.messages.firstIndex(where: { $0.id == placeholderId }) else { return }
            self.messages[idx].isThinking = false
            self.messages[idx].isToolCalling = false
            self.messages[idx].text = text
            self.messages[idx].showCursor = cursor.boolValue
        })
        pacing?.start()

        // 3. 启动推理（bridge 已指向当前 sessionId，LLM 记忆按会话隔离）
        bridge.sendMessage(
            input: trimmed,
            onText: { [weak self] snapshot in
                Task { @MainActor in
                    // 首 token 到达：喂给节奏器（不直接写 UI，节奏器按字符时间轴推进）
                    self?.pacing?.onTextSnapshot(fullText: snapshot)
                }
            },
            onToolCall: { [weak self] in
                Task { @MainActor in
                    // 工具调用：清节奏器缓冲（避免用旧全文覆盖状态文案）
                    self?.pacing?.reset()
                    self?.toolCallingUpdate(id: placeholderId)
                }
            },
            onComplete: { [weak self] summary, errorMessage in
                Task { @MainActor in
                    self?.pacing?.finish()
                    self?.completeMessage(id: placeholderId, summary: summary, errorMessage: errorMessage)
                    self?.isProcessing = false
                }
            }
        )
    }

    // MARK: - 会话管理（对齐 Android switchSession/newSession/renameSession/deleteSession）

    /// 切换会话：换 UI 消息 + 换 bridge memory ID，持久化当前会话
    func switchSession(_ sessionId: String) {
        guard sessionId != currentSessionId else { return }
        persist()
        currentSessionId = sessionId
        ChatHistoryStore.shared.currentSessionId = sessionId
        bridge?.setSessionId(id: sessionId)
        messages = ChatHistoryStore.shared.loadMessages(sessionId: sessionId)
    }

    /// 新建会话并切换过去（顶栏 + / 侧栏 +）
    func newSession() {
        let sessionId = UUID().uuidString
        ChatHistoryStore.shared.upsertThread(ChatThread(
            sessionId: sessionId,
            title: String(localized: "New Chat")
        ))
        threads = ChatHistoryStore.shared.loadThreads()
        switchSession(sessionId)
    }

    /// 重命名会话（空白忽略；重命名后不再被自动标题覆盖）
    func renameSession(_ sessionId: String, newTitle: String) {
        let trimmed = newTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              var thread = threads.first(where: { $0.sessionId == sessionId }) else { return }
        thread.title = trimmed
        ChatHistoryStore.shared.upsertThread(thread)
        threads = ChatHistoryStore.shared.loadThreads()
    }

    /// 删除会话：删消息 + 会话记录 + 该会话 LLM 记忆；删除当前会话回退 default
    func deleteSession(_ sessionId: String) {
        ChatHistoryStore.shared.deleteThread(sessionId: sessionId)
        bridge?.clearHistory(sessionId: sessionId) { }
        if currentSessionId == sessionId {
            let fallback = ChatHistoryStore.defaultSessionId
            currentSessionId = fallback
            ChatHistoryStore.shared.currentSessionId = fallback
            bridge?.setSessionId(id: fallback)
            messages = ChatHistoryStore.shared.loadMessages(sessionId: fallback)
        }
        threads = ChatHistoryStore.shared.loadThreads()
    }

    /// 清空当前会话（顶栏 delete_sweep）：清当前会话消息 + 当前会话 LLM 记忆
    func clearHistory() {
        guard let bridge else { return }
        let sessionId = currentSessionId
        // UI 消息与预览同步清（文件操作不依赖异步回调，避免清记忆途中切会话漏清）
        messages = []
        ChatHistoryStore.shared.clearMessages(sessionId: sessionId)
        touchThread(preview: "")
        bridge.clearCurrentHistory { }
    }

    deinit {
        actionWatcher?.cancel()
    }

    // MARK: - Streaming State Updates

    // streamingUpdate 已由节奏器 onPaced 内联替代（见 send() 中 pacing 创建）

    /// 工具调用开始：显示状态文案
    private func toolCallingUpdate(id: UUID) {
        guard let idx = messages.firstIndex(where: { $0.id == id }) else { return }
        messages[idx].isThinking = false
        messages[idx].isToolCalling = true
        messages[idx].text = String(localized: "Calling tools…")  // 正在调用工具…
    }

    /// 推理完成
    private func completeMessage(id: UUID, summary: String, errorMessage: String?) {
        guard let idx = messages.firstIndex(where: { $0.id == id }) else { return }
        messages[idx].isStreaming = false
        messages[idx].isThinking = false
        messages[idx].isToolCalling = false
        if let errorMessage, !errorMessage.isEmpty {
            messages[idx].text = errorMessage
            messages[idx].error = errorMessage
        } else {
            messages[idx].text = summary.isEmpty ? String(localized: "(No response)") : summary
        }
        touchThread(preview: messages[idx].text)
        persist()
    }

    // MARK: - UI Actions（媒体结果 = 独立消息）

    private func handleUiAction(_ dto: ChatUiActionDto) {
        switch dto.kind {
        case "media_results":
            // 空结果不出卡片：Android uiActions 收集器以 assets.isNotEmpty() 为门，
            // 空结果由 LLM 在最终回复里说明（避免多轮空搜索刷出多条「未找到」气泡）
            guard dto.totalCount > 0, !dto.mediaIds.isEmpty else { return }
            // ReAct 多轮搜索只保留最后一张卡片：替换本次发送（最后一条 user 消息之后）
            // 的上一张媒体卡（对齐 Android dropLast 替换语义）
            if let lastUser = messages.lastIndex(where: { $0.role == .user }),
               let prevCard = messages[lastUser...].lastIndex(where: { $0.type == .mediaResults }) {
                messages.remove(at: prevCard)
            }
            let ids = dto.mediaIds.map { $0.int64Value }
            let header = String(localized: "Found \(dto.totalCount) results for「\(dto.query)」")
            messages.append(ChatMessage(
                role: .assistant,
                text: header,
                type: .mediaResults,
                mediaIds: ids,
                mediaQuery: dto.query,
                mediaTotalCount: Int(truncatingIfNeeded: dto.totalCount)
            ))
            touchThread(preview: header)
            persist()
        case "text_reply":
            // 工具文本已作为 observation 回传 LLM（ChatToolService.dispatchCommand），
            // 最终答复经 onComplete 写入同一气泡；不追加独立气泡
            //（对齐 Android uiActions 收集器 TextReply → else{} 忽略，修复回复被切成多条）
            break
        case "error":
            // 同 text_reply：错误经 "Error: ..." observation 回传，由 LLM 总结呈现
            break
        case "success":
            // 命令成功：无用户可见文本载荷，静默（对齐 Android Success 不追加气泡）
            break
        default:
            break
        }
    }

    // MARK: - 会话标题与索引

    /// 首条用户消息自动生成标题：仅当标题仍为默认值时覆盖（对齐 Android updateSessionTitleIfDefault）
    private func autoTitleIfNeeded(firstUserText: String) {
        guard var thread = threads.first(where: { $0.sessionId == currentSessionId }) else {
            // default 会话首次发消息时补建会话记录（对齐 Android ensureSessionExists）
            let thread = ChatThread(
                sessionId: currentSessionId,
                title: Self.sanitizeTitle(firstUserText)
            )
            ChatHistoryStore.shared.upsertThread(thread)
            threads = ChatHistoryStore.shared.loadThreads()
            return
        }
        guard Self.isDefaultTitle(thread.title) else { return }
        thread.title = Self.sanitizeTitle(firstUserText)
        ChatHistoryStore.shared.upsertThread(thread)
        threads = ChatHistoryStore.shared.loadThreads()
    }

    /// 更新会话预览与更新时间（对齐 Android touchSession）
    private func touchThread(preview: String) {
        guard var thread = threads.first(where: { $0.sessionId == currentSessionId }) else { return }
        thread.updatedAt = Date()
        thread.lastMessagePreview = String(preview.prefix(50))
        ChatHistoryStore.shared.upsertThread(thread)
        threads = ChatHistoryStore.shared.loadThreads()
    }

    /// 默认标题判定（对齐 Android isDefaultTitle：空 / "New Chat" / "Chat" 及其本地化形式）
    private static func isDefaultTitle(_ title: String) -> Bool {
        if title.isEmpty { return true }
        // 英文常量 fallback（历史数据）+ 当前语言本地化形式，避免非英文下漏判
        let newChatEn = "New Chat"
        let chatEn = "Chat"
        if title == newChatEn || title == chatEn { return true }
        return title == String(localized: "New Chat") || title == String(localized: "Chat")
    }

    /// 标题清洗（对齐 Android ChatTitleGenerator.sanitizeTitle：
    /// 去首尾标点、换行/连续空白折叠、>20 字符截断加 …）
    static func sanitizeTitle(_ content: String) -> String {
        let fallback = String(localized: "New Chat")
        let trimmed = content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return fallback }

        let trimChars = CharacterSet(charactersIn: ".,!?;:。，！？；：\"'「」『』()（）[]【】{}")
        let collapsed = trimmed
            .components(separatedBy: .newlines).joined(separator: " ")
            .components(separatedBy: .whitespaces).filter { !$0.isEmpty }.joined(separator: " ")
        var cleaned = collapsed.trimmingCharacters(in: trimChars)

        if cleaned.count > 20 {
            cleaned = String(cleaned.prefix(20)).trimmingCharacters(in: trimChars) + "…"
        }
        return cleaned.isEmpty ? fallback : cleaned
    }

    // MARK: - CHART（LLM draw_chart → ChartJsEngine 渲染 + 手动 demo）

    /// 追加一条 CHART 消息（图卡）。LLM draw_chart（经 IosChartCapability → ChartRendererBridge.onChart）
    /// 与 /chart 手动 demo 共用此落点。
    private func appendChartMessage(svg: String, summary: String) {
        var msg = ChatMessage(role: .assistant, text: summary, type: .chart)
        msg.chartSvg = svg
        messages.append(msg)
        touchThread(preview: summary)
        persist()
    }

    /// 手动触发一张示例图，验证 ChartJsEngine(JSCore+chart_bootstrap.js) → ChartSvgCard 端到端。
    private func emitChartDemo() {
        guard let r = ChartJsEngine.render(
            type: "bar", title: "Chart Demo",
            labels: ["A", "B", "C", "D"], values: [3, 7, 2, 5], unit: nil
        ) else { return }
        appendChartMessage(svg: r.svg, summary: r.summary)
    }

    /// /charttool：经 CapabilityRegistry 派发 DrawChart，跑通完整触发链（不依赖 LLM）。
    /// 图卡经 ChartRendererBridge.onChart 追加；此处仅兜底 dispatch 失败。
    private func emitChartViaToolChain() {
        guard let bridge else { return }
        bridge.dispatchDrawChart(
            type: "bar", title: "Chart via draw_chart",
            labels: ["一月", "二月", "三月"], valuesCsv: "10,15,8", unit: "张"
        ) { [weak self] _, errorMessage in
            Task { @MainActor in
                guard let self, let errorMessage, !errorMessage.isEmpty else { return }
                self.messages.append(
                    ChatMessage(role: .assistant, text: "图表生成失败：\(errorMessage)", error: errorMessage)
                )
                self.persist()
            }
        }
    }

    // MARK: - run_gallery_script（LLM run_gallery_script → JsRuntime 端侧沙箱 + 确定性 demo）

    /// /runscript：经 CapabilityRegistry 派发 ExecuteScript，跑通 run_gallery_script 完整触发链（不依赖 LLM）。
    /// 脚本 `await bridge.callAsync('gallery.summary')` 取相册盘点并组合成可读文案，return 后作为 agent 文本消息追加。
    private func emitRunScriptDemo() {
        guard let bridge else { return }
        // JS：${...} 是 JS 模板插值（非 Swift \( )，原样透传给 JsCoreEngine。
        let script = """
        const s = await bridge.callAsync('gallery.summary', {});
        return `相册共 ${s.totalMedia} 个媒体（照片 ${s.totalPhotos}、视频 ${s.totalVideos}）；` +
          `已打标 ${s.labeledCount}，未打标 ${s.unlabeledCount}；人物聚类 ${s.personClusterCount}（已命名 ${s.namedPersonCount}）。`;
        """
        bridge.dispatchRunScript(code: script) { [weak self] result, errorMessage in
            Task { @MainActor in
                guard let self else { return }
                if let errorMessage, !errorMessage.isEmpty {
                    self.messages.append(
                        ChatMessage(role: .assistant, text: "脚本执行失败：\(errorMessage)", error: errorMessage)
                    )
                } else {
                    self.messages.append(ChatMessage(role: .assistant, text: result))
                }
                self.persist()
            }
        }
    }

    // MARK: - AGENT_EDIT_RESULT（chat EDIT → 编辑器 → 结果图回链）

    /// 追加编辑结果消息（图=Documents/chat_edits 文件路径；文案标注已存相册——iOS 编辑器
    /// 保存时已入库，与 Android「chat 内保存按钮」为有意分歧，见 plan 范围裁决）。
    private func appendEditResultMessage(imagePath: String) {
        let caption = String(localized: "Edit complete. Result saved to Photos.")
        messages.append(ChatMessage(role: .assistant, text: caption, type: .agentEditResult, imageUri: imagePath))
        touchThread(preview: caption)
        persist()
    }

    /// /editdemo：生成一张渐变图落盘并追加编辑结果消息（确定性验证渲染链，不经编辑器）。
    private func emitEditResultDemo() {
        let size = CGSize(width: 600, height: 400)
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { ctx in
            let colors = [UIColor.systemBlue.cgColor, UIColor.systemTeal.cgColor]
            let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
                                      colors: colors as CFArray, locations: [0, 1])!
            ctx.cgContext.drawLinearGradient(
                gradient, start: .zero,
                end: CGPoint(x: size.width, y: size.height), options: []
            )
        }
        guard let data = image.jpegData(compressionQuality: 0.9),
              let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let dir = docs.appendingPathComponent("chat_edits", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("demo-\(UUID().uuidString).jpg")
        do {
            try data.write(to: url)
            appendEditResultMessage(imagePath: url.path)
        } catch {
            messages.append(ChatMessage(role: .assistant, text: "demo 图片写入失败：\(error.localizedDescription)", error: error.localizedDescription))
            persist()
        }
    }

    // MARK: - Persistence

    private func persist() {
        // 只持久化非流式消息，落当前会话文件
        let persisted = messages.filter { !$0.isStreaming }
        ChatHistoryStore.shared.saveMessages(sessionId: currentSessionId, messages: persisted)
    }
}
