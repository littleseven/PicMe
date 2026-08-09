import SwiftUI
import Photos
import SharedKit

/// Chat 主视图（1:1 对标 Android ChatScreen.kt）。
///
/// 结构：顶部栏 + 消息列表 + 输入卡片。
/// spec: specs/screens/chat.yaml
struct ChatView: View {
    @StateObject private var viewModel = ChatViewModel()
    @EnvironmentObject private var container: AppContainer
    @State private var inputText = ""
    @FocusState private var inputFocused: Bool
    @State private var showClearConfirm = false

    var body: some View {
        VStack(spacing: 0) {
            chatTopBar

            // 消息列表 或 空状态
            if viewModel.messages.isEmpty {
                ChatEmptyState(onExampleTap: { prompt in
                    inputText = prompt
                    send()
                })
            } else {
                messageList
            }

            // 输入栏
            inputBar
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
    }

    // MARK: - Top Bar (48dp, no title)

    private var chatTopBar: some View {
        HStack(spacing: 8) {
            // 左侧：返回
            Button {} label: {
                Image(matIcon: "chevron.left").font(.system(size: 22))
            }
            .frame(width: 36, height: 36)

            Spacer()

            // 右侧：清空对话
            if !viewModel.messages.isEmpty {
                Button { showClearConfirm = true } label: {
                    Image(matIcon: "delete").font(.system(size: 22))
                }
                .frame(width: 36, height: 36)

                // 停止按钮（推理中）
                if viewModel.isProcessing {
                    Button { viewModel.stop() } label: {
                        Image(matIcon: "close").font(.system(size: 22))
                    }
                    .frame(width: 36, height: 36)
                }
            }
        }
        .padding(.horizontal, 8)
        .frame(height: 48)
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
            .onChange(of: viewModel.messages.count) { _ in
                scrollToBottom(proxy)
            }
            .onChange(of: viewModel.messages.last?.text) { _ in
                scrollToBottom(proxy)
            }
        }
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        if let lastId = viewModel.messages.last?.id {
            withAnimation(.easeOut(duration: 0.2)) {
                proxy.scrollTo(lastId, anchor: .bottom)
            }
        }
    }

    // MARK: - Input Bar (大圆角卡片)

    private var inputBar: some View {
        VStack(spacing: 0) {
            VStack(spacing: 0) {
                HStack(alignment: .bottom, spacing: 8) {
                    // 文本输入
                    TextField(String(localized: "Ask AI Agent..."), text: $inputText, axis: .vertical)
                        .font(.system(size: 16))
                        .lineSpacing(8)
                        .focused($inputFocused)
                        .lineLimit(1...5)
                        .submitLabel(.send)
                        .onSubmit(send)

                    // 发送按钮（仅有内容且非处理中时显示）
                    if canSend {
                        Button(action: send) {
                            Image(matIcon: "arrow_upward")
                                .font(.system(size: 22))
                                .foregroundColor(.accentColor)
                        }
                        .frame(width: 36, height: 36)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(Color(.systemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 24))
            .shadow(color: .black.opacity(0.08), radius: 4, y: 2)
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
    }

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

// MARK: - Message Bubble (1:1 对标 spec §5)

private struct MessageBubble: View {
    let message: ChatMessage

    var body: some View {
        HStack {
            if message.role == .user { Spacer(minLength: 40) }

            // 气泡内容
            VStack(alignment: .leading, spacing: 0) {
                if message.isStreaming && message.text.isEmpty {
                    // 思考态：三个圆点
                    ThinkingIndicator()
                } else if !message.text.isEmpty {
                    // 文本内容
                    Text(message.text)
                        .font(.system(size: 14))
                        .lineSpacing(6)
                        .foregroundColor(message.role == .user ? .white : Color(.label))
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: 360, alignment: .leading)
                }

                // 流式光标（有文本且仍在流式）
                if message.isStreaming && !message.text.isEmpty {
                    Text(">")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(Color(.label))
                        .padding(.leading, 2)
                }

                // 媒体卡片
                if !message.mediaIds.isEmpty {
                    MediaCardRow(mediaIds: message.mediaIds)
                        .padding(.top, 6)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(bubbleBackground)
            .clipShape(bubbleShape)

            if message.role == .assistant { Spacer(minLength: 40) }
        }
    }

    // 圆角：user 底右 4dp 尖角，agent 底左 4dp 尖角
    private var bubbleShape: UnevenRoundedRectangle {
        let r: CGFloat = 20
        let sharp: CGFloat = 4
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

// MARK: - Media Card Row (120×150 cards, spec §9)

private struct MediaCardRow: View {
    let mediaIds: [Int64]
    @State private var idToIdentifier: [Int64: String] = [:]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(mediaIds, id: \.self) { id in
                    MediaThumbnail(localIdentifier: idToIdentifier[id])
                        .frame(width: 120, height: 150)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                }
            }
            .padding(.vertical, 8)
        }
        .task(id: mediaIds) {
            var map: [Int64: String] = [:]
            let result = PHAsset.fetchAssets(with: nil)
            result.enumerateObjects { asset, _, _ in
                map[Self.javaHashCode(asset.localIdentifier)] = asset.localIdentifier
            }
            idToIdentifier = map
        }
    }

    static func javaHashCode(_ s: String) -> Int64 {
        var h: Int64 = 0
        for u in s.utf16 { h = 31 &* h &+ Int64(u) }
        return h
    }
}

private struct MediaThumbnail: View {
    let localIdentifier: String?
    @State private var image: UIImage?

    var body: some View {
        ZStack {
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
        }
        .task(id: localIdentifier) {
            guard let localIdentifier, image == nil else { return }
            let size = CGSize(width: 360, height: 450)  // 120×150 @3x
            image = await ThumbnailLoader.shared.thumbnail(
                for: localIdentifier, size: size)
        }
    }
}

// MARK: - Empty State (spec §4)

struct ChatEmptyState: View {
    let onExampleTap: (String) -> Void

    private let examples = [
        "Find photos from last summer",
        "Search beach photos",
        "Find photos with people",
        "Search night scenes",
        "Give me an album health report",
        "Analyze my photos by tag and time",
    ]

    var body: some View {
        VStack(spacing: 0) {
            Spacer().frame(height: 40)

            // Logo
            ZStack {
                RoundedRectangle(cornerRadius: 18)
                    .fill(Color(.tertiarySystemBackground))
                    .overlay(
                        RoundedRectangle(cornerRadius: 18)
                            .stroke(Color(.separator), lineWidth: 1)
                    )
                    .frame(width: 64, height: 64)
                Image(matIcon: "chat_bubble")
                    .font(.system(size: 32))
                    .foregroundColor(.accentColor)
            }

            Spacer().frame(height: 16)

            // Title
            Text("Hi, I'm Xiaolang, your smart assistant")
                .font(.system(size: 24, weight: .bold))
                .foregroundColor(Color(.label))

            Spacer().frame(height: 6)

            // Subtitle
            Text("I can search, edit, adjust beauty, find people/scenes—ask anything!")
                .font(.system(size: 14))
                .foregroundColor(Color(.secondaryLabel))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 20)

            Spacer()

            // Examples
            Text("Try these:")
                .font(.system(size: 14))
                .foregroundColor(Color(.secondaryLabel))
                .padding(.bottom, 10)

            // Chips
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

// MARK: - FlowLayout (简易流式布局)

struct FlowLayout: Layout {
    let spacing: CGFloat

    init(spacing: CGFloat = 8) { self.spacing = spacing }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var totalHeight: CGFloat = 0
        var x: CGFloat = 0
        var rowHeight: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x + size.width > maxWidth && x > 0 {
                totalHeight += rowHeight + spacing
                x = 0
                rowHeight = 0
            }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        totalHeight += rowHeight
        return CGSize(width: maxWidth, height: totalHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let maxWidth = bounds.width
        var x: CGFloat = bounds.minX
        var y: CGFloat = bounds.minY
        var rowHeight: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x + size.width > bounds.minX + maxWidth && x > bounds.minX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), proposal: .init(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

#Preview {
    ChatView()
        .environmentObject(AppContainer.shared)
}
