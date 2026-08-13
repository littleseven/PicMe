import SwiftUI
import Photos
import SharedKit

/// Chat 主视图（1:1 对标 Android ChatScreen.kt）。
/// spec: specs/screens/chat.yaml
struct ChatView: View {
    /// 返回动作（pager 场景 = 回相册页）；nil 时返回键占位
    var onBack: (() -> Void)? = nil

    @StateObject private var viewModel = ChatViewModel()
    @EnvironmentObject private var container: AppContainer
    @State private var inputText = ""
    @FocusState private var inputFocused: Bool
    @State private var showClearConfirm = false
    @State private var isSidebarOpen = false
    /// 诚实占位：功能未实现时的说明（spec §11 允许差异外的项后续补齐）
    @State private var comingSoonFeature: String? = nil

    var body: some View {
        ZStack(alignment: .leading) {
            VStack(spacing: 0) {
                chatTopBar

                if viewModel.messages.isEmpty {
                    ChatEmptyState { prompt in
                        viewModel.send(prompt)  // 直接发送，不填充输入框
                    }
                } else {
                    messageList
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                // 经 safeAreaInset 钉底：键盘弹起时输入栏自动上浮，空态/消息态一致；
                // 不再作为 greedy 内容（ChatEmptyState）的兄弟节点（会破坏系统键盘避让）。
                inputBar
            }

            // 会话历史侧栏（spec §2.5）：常驻渲染 + offset 抽屉（条件插入在真机上偶发不展开）
            Color.black.opacity(isSidebarOpen ? 0.3 : 0)
                .ignoresSafeArea()
                .allowsHitTesting(isSidebarOpen)
                .onTapGesture { withAnimation { isSidebarOpen = false } }

            ChatThreadSidebarView(
                threads: viewModel.filteredThreads,
                currentSessionId: viewModel.currentSessionId,
                searchQuery: $viewModel.searchQuery,
                onThreadSelected: { sessionId in
                    viewModel.switchSession(sessionId)
                    withAnimation { isSidebarOpen = false }
                },
                onNewChat: {
                    viewModel.newSession()
                    withAnimation { isSidebarOpen = false }
                },
                onRename: { sessionId, newTitle in
                    viewModel.renameSession(sessionId, newTitle: newTitle)
                },
                onDelete: { sessionId in
                    viewModel.deleteSession(sessionId)
                },
                onDismiss: { withAnimation { isSidebarOpen = false } }
            )
            .offset(x: isSidebarOpen ? 0 : -300)
            .allowsHitTesting(isSidebarOpen)
            .accessibilityHidden(!isSidebarOpen)
        }
        .background(Color(.systemBackground).ignoresSafeArea())
        .onAppear {
            if let bridge = container.chatBridge {
                viewModel.configure(bridge: bridge)
            }
        }
        .confirmationDialog(
            String(localized: "Clear conversation?"),
            isPresented: $showClearConfirm,
            titleVisibility: .visible
        ) {
            Button(String(localized: "Clear"), role: .destructive) {
                viewModel.clearHistory()
            }
            Button(String(localized: "Cancel"), role: .cancel) {}
        }
        .alert(
            String(localized: "Coming Soon"),
            isPresented: Binding(
                get: { comingSoonFeature != nil },
                set: { if !$0 { comingSoonFeature = nil } }
            )
        ) {
            Button(String(localized: "OK"), role: .cancel) {}
        } message: {
            Text(comingSoonFeature ?? "")
        }
    }

    // MARK: - Top Bar (48dp, 无标题，spec §2)

    private var chatTopBar: some View {
        HStack(spacing: TopBarTokens.spacing) {
            // 返回（pager 场景回相册页）
            Button { onBack?() } label: {
                MatIcon(name: "mat_arrow_back", size: TopBarTokens.iconSize)
                    .foregroundColor(Color(.label))
            }
            .frame(width: TopBarTokens.buttonSize, height: TopBarTokens.buttonSize)
            .accessibilityIdentifier("chat_back")

            // 菜单（打开会话历史侧栏，spec §2.5）
            Button { withAnimation { isSidebarOpen = true } } label: {
                MatIcon(name: "mat_menu", size: TopBarTokens.iconSize)
                    .foregroundColor(Color(.label))
            }
            .frame(width: TopBarTokens.buttonSize, height: TopBarTokens.buttonSize)
            .accessibilityIdentifier("chat_menu")

            Spacer()

            // 上报问题（Android 走 /v1/report-issue 建 GitHub issue，iOS 通道未接）
            Button { comingSoonFeature = String(localized: "Issue reporting is not available in this version.") } label: {
                MatIcon(name: "mat_bug_report", size: TopBarTokens.iconSize)
                    .foregroundColor(Color(.label))
            }
            .frame(width: TopBarTokens.buttonSize, height: TopBarTokens.buttonSize)
            .accessibilityIdentifier("chat_report")

            // 新对话（= 新建会话并切换，对齐 Android onNewChat；非清空当前会话）
            Button { viewModel.newSession() } label: {
                MatIcon(name: "mat_add_comment", size: TopBarTokens.iconSize)
                    .foregroundColor(Color(.label))
            }
            .frame(width: TopBarTokens.buttonSize, height: TopBarTokens.buttonSize)
            .accessibilityIdentifier("chat_new")

            // 清空对话（仅有消息时显示）
            if !viewModel.messages.isEmpty {
                Button { showClearConfirm = true } label: {
                    MatIcon(name: "mat_delete_sweep", size: TopBarTokens.iconSize)
                        .foregroundColor(Color(.label))
                }
                .frame(width: TopBarTokens.buttonSize, height: TopBarTokens.buttonSize)
                .accessibilityIdentifier("chat_clear")
            }
        }
        .padding(.horizontal, TopBarTokens.horizontalPadding)
        .frame(height: TopBarTokens.height)
        .background(Color(.systemBackground))
    }

    // MARK: - Message List

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 8) {
                    ForEach(viewModel.messages) { msg in
                        MessageBubble(message: msg)
                            .id(msg.id)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.top, 12)
                .padding(.bottom, 8)
            }
            .scrollDismissesKeyboard(.interactively)  // 下滑消息列表收起键盘
            .onChange(of: viewModel.messages.count) { _ in scrollToBottom(proxy) }
            .onChange(of: viewModel.messages.last?.text) { _ in scrollToBottom(proxy) }
        }
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        if let lastId = viewModel.messages.last?.id {
            withAnimation(.easeOut(duration: 0.2)) {
                proxy.scrollTo(lastId, anchor: .bottom)
            }
        }
    }

    // MARK: - Input Bar（spec §7：24dp 大圆角卡片，行1 文本 + 行2 按钮栏）

    private var inputBar: some View {
        VStack(spacing: 0) {
            VStack(spacing: TopBarTokens.spacing) {
                // 行 1：文本输入（通栏；处理中仍可编辑）
                TextField(String(localized: "Ask AI Agent..."), text: $inputText, axis: .vertical)
                    .font(.system(size: 16))
                    .lineSpacing(8)
                    .foregroundColor(Color(.label))
                    .focused($inputFocused)
                    .lineLimit(1...5)
                    .submitLabel(.send)
                    .onSubmit(send)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("chat_input")
                    .toolbar {
                        // 键盘上方「完成」按钮收起键盘（多行 TextField 的 Return=换行，无法收起）
                        ToolbarItemGroup(placement: .keyboard) {
                            Spacer()
                            Button(String(localized: "Done")) { inputFocused = false }
                        }
                    }

                // 行 2：按钮栏（SpaceBetween）
                HStack(spacing: TopBarTokens.spacing) {
                    // 相册胶囊（spec gallery_capsule：Android 打开图片选择器；
                    // iOS chat v1 无图片消息，诚实占位）
                    Button {
                        comingSoonFeature = String(localized: "Attaching photos in chat is not available in this version.")
                    } label: {
                        HStack(spacing: 6) {
                            MatIcon(name: "mat_photo_library", size: 16)
                            Text(String(localized: "Gallery"))
                                .font(.system(size: 12))
                        }
                        .foregroundColor(Color(.label).opacity(0.7))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(Color(.secondarySystemBackground).opacity(ChatBubbleTokens.capsuleInactiveAlpha))
                        .clipShape(RoundedRectangle(cornerRadius: ChatBubbleTokens.capsuleCornerRadius))
                    }
                    .disabled(viewModel.isProcessing)
                    .accessibilityIdentifier("chat_gallery_capsule")

                    Spacer()

                    // 语音切换按钮（常驻，圆形；对齐 Android voice_button。
                    // iOS chat v1 无语音输入模式，诚实占位）
                    Button {
                        comingSoonFeature = String(localized: "Voice input is not available in this version.")
                    } label: {
                        MatIcon(name: "mat_keyboard_voice", size: ChatBubbleTokens.circularButtonIconSize)
                            .foregroundColor(Color(.label).opacity(0.7))
                    }
                    .frame(width: ChatBubbleTokens.circularButtonSize, height: ChatBubbleTokens.circularButtonSize)
                    .accessibilityIdentifier("chat_voice")

                    // 发送按钮（圆形，primary tint；仅有内容 && 非处理中时显示）
                    if canSend {
                        Button(action: send) {
                            MatIcon(name: "mat_send", size: ChatBubbleTokens.circularButtonIconSize)
                                .foregroundColor(.accentColor)
                        }
                        .frame(width: ChatBubbleTokens.circularButtonSize, height: ChatBubbleTokens.circularButtonSize)
                        .accessibilityIdentifier("chat_send")
                    }
                }
            }
            .padding(.horizontal, ChatBubbleTokens.paddingH)
            .padding(.vertical, ChatBubbleTokens.paddingV)
            .background(Color(.systemBackground))
            .clipShape(RoundedRectangle(cornerRadius: ChatBubbleTokens.inputCornerRadius))
            .shadow(color: .black.opacity(0.08), radius: ChatBubbleTokens.inputShadowElevation, y: 2)
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
    }

    /// 对齐 Android：发送按钮仅在 text 非空 && !isProcessing 时出现
    private var canSend: Bool {
        !inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !viewModel.isProcessing
    }

    private func send() {
        guard canSend else { return }
        let text = inputText
        inputText = ""
        viewModel.send(text)
    }
}

// MARK: - Message Bubble

private struct MessageBubble: View {
    let message: ChatMessage

    var body: some View {
        HStack {
            if message.role == .user { Spacer(minLength: 40) }

            VStack(alignment: .leading, spacing: 0) {
                if message.isThinking {
                    // 思考态：3 点动画（首 token 前）
                    ThinkingIndicator()
                } else if message.isToolCalling {
                    // 工具调用态：状态文案
                    Text(message.text)
                        .font(.system(size: ChatBubbleTokens.textSize))
                        .foregroundColor(Color(.secondaryLabel))
                } else if !message.text.isEmpty || !message.mediaIds.isEmpty {
                    // 正常文本
                    if !message.text.isEmpty {
                        Text(message.text)
                            .font(.system(size: ChatBubbleTokens.textSize))
                            .lineSpacing(ChatBubbleTokens.textLineHeight - ChatBubbleTokens.textSize)
                            .foregroundColor(message.role == .user ? .white : Color(.label))
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: ChatBubbleTokens.bubbleMaxWidth, alignment: .leading)
                            .accessibilityIdentifier(message.role == .user ? "chat_user_bubble" : "chat_ai_bubble")
                    }

                    // 流式光标（有文本且仍在流式）
                    if message.isStreaming && !message.text.isEmpty {
                        HStack(spacing: 2) {
                            Text(message.text.isEmpty ? "" : "")
                            BlinkCursor()
                        }
                    }

                    // 媒体卡片（独立消息项）
                    if !message.mediaIds.isEmpty {
                        MediaCardRow(mediaIds: message.mediaIds)
                            .padding(.top, 6)
                    }
                }
            }
            .padding(.horizontal, ChatBubbleTokens.paddingH)
            .padding(.vertical, ChatBubbleTokens.paddingV)
            .background(bubbleBackground)
            .clipShape(bubbleShape)
            .contentShape(Rectangle())
            .onLongPressGesture {
                // 长按复制（对齐 Android）
                UIPasteboard.general.string = message.text
            }

            if message.role == .assistant { Spacer(minLength: 40) }
        }
    }

    private var bubbleShape: UnevenRoundedRectangle {
        let r = ChatBubbleTokens.cornerRadius, sharp = ChatBubbleTokens.tailCornerRadius
        if message.role == .user {
            return UnevenRoundedRectangle(
                topLeadingRadius: r, bottomLeadingRadius: r,
                bottomTrailingRadius: sharp, topTrailingRadius: r
            )
        } else {
            return UnevenRoundedRectangle(
                topLeadingRadius: r, bottomLeadingRadius: sharp,
                bottomTrailingRadius: r, topTrailingRadius: r
            )
        }
    }

    private var bubbleBackground: Color {
        if message.role == .user {
            return Color.accentColor.opacity(0.9)
        } else {
            return Color(.secondarySystemBackground).opacity(0.85)
        }
    }
}

// MARK: - Thinking Indicator (6dp dots, 5dp spacing, 160ms stagger)

private struct ThinkingIndicator: View {
    @State private var animate = false

    var body: some View {
        HStack(spacing: 5) {
            ForEach(0..<3) { i in
                Circle()
                    .fill(Color(.label).opacity(animate ? 1.0 : 0.3))
                    .frame(width: 6, height: 6)
                    .animation(
                        .easeInOut(duration: 0.4)
                        .repeatForever(autoreverses: true)
                        .delay(Double(i) * 0.16),
                        value: animate
                    )
            }
        }
        .padding(.vertical, 4)
        .onAppear { animate = true }
    }
}

// MARK: - Blink Cursor (> 字符闪烁)

private struct BlinkCursor: View {
    @State private var visible = true

    var body: some View {
        Text(">")
            .font(.system(size: 14, weight: .bold))
            .foregroundColor(Color(.label))
            .opacity(visible ? 1.0 : 0.3)
            .onAppear {
                withAnimation(.easeInOut(duration: 0.5).repeatForever(autoreverses: true)) {
                    visible = false
                }
            }
    }
}

// MARK: - Media Card Row (spec §9：120×150 卡片 + 日期标签)

private struct MediaCardRow: View {
    let mediaIds: [Int64]
    @State private var idToIdentifier: [Int64: String] = [:]
    @State private var idToDate: [Int64: Date] = [:]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: ChatCarouselTokens.cardSpacing) {
                ForEach(mediaIds, id: \.self) { id in
                    MediaThumbnail(
                        localIdentifier: idToIdentifier[id],
                        date: idToDate[id]
                    )
                    .frame(width: ChatCarouselTokens.cardWidth, height: ChatCarouselTokens.cardHeight)
                    .clipShape(RoundedRectangle(cornerRadius: ChatCarouselTokens.cardCornerRadius))
                }
            }
            .padding(.vertical, 4)
        }
        .task(id: mediaIds) {
            // mediaIds = localIdentifier.hashCode().toLong()（Kotlin String.hashCode，见
            // IosChatGalleryCapability.toDomain id 口径）。反查须用同一 32-bit Java hash。
            guard !mediaIds.isEmpty else { return }
            var idMap: [Int64: String] = [:]
            var dateMap: [Int64: Date] = [:]
            PHAsset.fetchAssets(with: nil).enumerateObjects { asset, _, _ in
                let key = Self.javaHashCode(asset.localIdentifier)
                idMap[key] = asset.localIdentifier
                dateMap[key] = asset.creationDate
            }
            let resolved = mediaIds.filter { idMap[$0] != nil }.count
            #if DEBUG
            DebugBypass.log("Chat", "carousel mediaIds=[\(mediaIds.map(String.init).joined(separator: ","))] resolved=\(resolved)/\(mediaIds.count)")
            #endif
            idToIdentifier = idMap
            idToDate = dateMap
        }
    }

    /// Kotlin `String.hashCode().toLong()` 等价：**Int32** 溢出运算（31 &* / &+ 在 Int32 上 wrap，
    /// 对齐 Kotlin Int 的 32-bit 回绕），再符号扩展到 Int64。早期用 Int64 累加 → wrap 模 2^63 ≠
    /// Kotlin 的模 2^32 → 永远匹配不上 → 卡片空白。
    static func javaHashCode(_ s: String) -> Int64 {
        var h: Int32 = 0
        for u in s.utf16 {
            h = 31 &* h &+ Int32(u)
        }
        return Int64(h)
    }
}

private struct MediaThumbnail: View {
    let localIdentifier: String?
    let date: Date?
    @State private var image: UIImage?

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Color(.tertiarySystemBackground)
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
            } else {
                Image(matIcon: "photo")
                    .font(.system(size: 20))
                    .foregroundColor(.secondary.opacity(0.3))
            }
            // 日期标签（对齐 spec §9 card_date_label：yyyy-MM-dd，底部渐变 scrim 上白字）
            if let date, let label = Self.dateFormatter.string(for: date) {
                Text(label)
                    .font(.system(size: 11))
                    .foregroundColor(.white)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(
                        LinearGradient(
                            colors: [.clear, .black.opacity(0.5)],
                            startPoint: .top, endPoint: .bottom
                        )
                    )
            }
        }
        .task(id: localIdentifier) {
            guard let localIdentifier, image == nil else { return }
            image = await ThumbnailLoader.shared.thumbnail(
                for: localIdentifier, size: CGSize(width: 360, height: 450))
        }
    }
}

// MARK: - Empty State

struct ChatEmptyState: View {
    let onExampleTap: (String) -> Void

    // 本地化热词（key 已在 Localizable.xcstrings 配齐 zh-Hans/zh-Hant），
    // 显示与点击发送均用本地化串（对齐 Android stringArray 行为）
    private let examples: [String] = [
        String(localized: "Find photos from last summer"),
        String(localized: "Search beach photos"),
        String(localized: "Find photos with people"),
        String(localized: "Search night scenes"),
        String(localized: "Give me an album health report"),
        String(localized: "Analyze my photos by tag and time"),
    ]

    var body: some View {
        VStack(spacing: 0) {
            Spacer().frame(height: 40)

            // Logo（品牌前景图 104pt 溢出盒内居中不裁切，对齐 spec empty_state.logo）
            ZStack {
                RoundedRectangle(cornerRadius: 18)
                    .fill(Color(.tertiarySystemBackground))
                    .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color(.separator), lineWidth: 1))
                    .frame(width: 64, height: 64)
                Image("chat_logo")
                    .resizable()
                    .frame(width: 104, height: 104)
            }

            Spacer().frame(height: 16)

            Text("Hi, I'm Xiaolang, your smart assistant")
                .font(.system(size: 24, weight: .bold))
                .foregroundColor(Color(.label))

            Spacer().frame(height: 6)

            Text("I can search, edit, adjust beauty, find people/scenes—ask anything!")
                .font(.system(size: 14))
                .foregroundColor(Color(.secondaryLabel))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 20)

            Spacer()

            Text("Try these:")
                .font(.system(size: 14))
                .foregroundColor(Color(.secondaryLabel))
                .padding(.bottom, 10)

            FlowLayout(spacing: 8) {
                ForEach(examples, id: \.self) { prompt in
                    Button { onExampleTap(prompt) } label: {
                        Text(prompt)
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(Color(.secondaryLabel))
                            .padding(.horizontal, 16)
                            .padding(.vertical, 10)
                            .background(Color(.tertiarySystemBackground))
                            .clipShape(Capsule())
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 8)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - FlowLayout

struct FlowLayout: Layout {
    let spacing: CGFloat
    init(spacing: CGFloat = 8) { self.spacing = spacing }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var totalHeight: CGFloat = 0, x: CGFloat = 0, rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x + size.width > maxWidth && x > 0 {
                totalHeight += rowHeight + spacing; x = 0; rowHeight = 0
            }
            x += size.width + spacing; rowHeight = max(rowHeight, size.height)
        }
        totalHeight += rowHeight
        return CGSize(width: maxWidth, height: totalHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowHeight: CGFloat = 0
        let maxX = bounds.minX + bounds.width
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x + size.width > maxX && x > bounds.minX {
                x = bounds.minX; y += rowHeight + spacing; rowHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), proposal: .init(size))
            x += size.width + spacing; rowHeight = max(rowHeight, size.height)
        }
    }
}

#Preview {
    ChatView()
        .environmentObject(AppContainer.shared)
}
