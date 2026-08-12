import Foundation
import Combine
import SharedKit

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

    private var bridge: ChatAgentBridge?
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
    }

    // MARK: - Send (对齐 Android sendMessage 流程)

    func send(_ input: String) {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !isProcessing else { return }
        guard let bridge else { return }

        // 1. user 消息即追加
        messages.append(ChatMessage(role: .user, text: trimmed))
        autoTitleIfNeeded(firstUserText: trimmed)
        touchThread(preview: trimmed)
        persist()

        // 2. assistant 占位：thinking 态（首 token 前显示 3 点动画）
        let placeholderId = UUID()
        messages.append(ChatMessage(
            id: placeholderId, role: .assistant,
            text: "", isStreaming: true, isThinking: true
        ))
        isProcessing = true

        // 3. 启动推理（bridge 已指向当前 sessionId，LLM 记忆按会话隔离）
        bridge.sendMessage(
            input: trimmed,
            onText: { [weak self] snapshot in
                Task { @MainActor in
                    // 首 token 到达：退出 thinking，进入 streaming
                    self?.streamingUpdate(id: placeholderId, text: snapshot)
                }
            },
            onToolCall: { [weak self] in
                Task { @MainActor in
                    // 工具调用：替换为状态文案，清空文本（下一轮从空重新累计）
                    self?.toolCallingUpdate(id: placeholderId)
                }
            },
            onComplete: { [weak self] summary, errorMessage in
                Task { @MainActor in
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

    /// 首 token 到达或后续 token：退出 thinking/toolCalling，显示流式文本
    private func streamingUpdate(id: UUID, text: String) {
        guard let idx = messages.firstIndex(where: { $0.id == id }) else { return }
        messages[idx].isThinking = false
        messages[idx].isToolCalling = false
        messages[idx].text = text
    }

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
            // 媒体结果作为独立消息项追加（不嵌入文本气泡）
            let ids = dto.mediaIds.map { $0.int64Value }
            let header = dto.totalCount > 0
                ? String(localized: "Found \(dto.totalCount) results for「\(dto.query)」")
                : String(localized: "No results found for「\(dto.query)」")
            messages.append(ChatMessage(
                role: .assistant,
                text: header,
                mediaIds: ids
            ))
            touchThread(preview: header)
            persist()
        case "text_reply":
            // 工具产出的文本回复：作为独立 agent 消息（对齐 Android handleAgentAction TextReply）
            guard !dto.message.isEmpty else { return }
            messages.append(ChatMessage(role: .assistant, text: dto.message))
            touchThread(preview: dto.message)
            persist()
        case "error":
            // 工具执行错误：同 agent 正常气泡渲染（spec：错误无特殊色），不再静默丢弃
            let text = dto.message.isEmpty
                ? String(localized: "Operation failed.")
                : dto.message
            messages.append(ChatMessage(role: .assistant, text: text, error: dto.message))
            touchThread(preview: text)
            persist()
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

    // MARK: - Persistence

    private func persist() {
        // 只持久化非流式消息，落当前会话文件
        let persisted = messages.filter { !$0.isStreaming }
        ChatHistoryStore.shared.saveMessages(sessionId: currentSessionId, messages: persisted)
    }
}
