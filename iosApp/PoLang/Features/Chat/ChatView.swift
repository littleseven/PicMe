import SwiftUI
import Photos
import SharedKit

/// Chat 主视图（气泡列表 + 输入栏 + 流式光标 + 媒体卡片 + 错误气泡）。
struct ChatView: View {
    @StateObject private var viewModel = ChatViewModel()
    @EnvironmentObject private var container: AppContainer
    @State private var inputText = ""
    @FocusState private var inputFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            // 消息列表
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(viewModel.messages) { msg in
                            MessageBubble(message: msg)
                                .id(msg.id)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 12)
                    .padding(.bottom, 8)
                }
                .onChange(of: viewModel.messages.count) { _ in
                    if let lastId = viewModel.messages.last?.id {
                        withAnimation(.easeOut(duration: 0.2)) {
                            proxy.scrollTo(lastId, anchor: .bottom)
                        }
                    }
                }
                // 流式 token 只改最后一条 text，count 不变，需单独监听以跟随滚动
                .onChange(of: viewModel.messages.last?.text.count) { _ in
                    if let lastId = viewModel.messages.last?.id {
                        proxy.scrollTo(lastId, anchor: .bottom)
                    }
                }
            }

            Divider().background(Color.white.opacity(0.1))

            // 输入栏
            inputBar
        }
        .background(Color.black.ignoresSafeArea())
        .onAppear {
            if let bridge = container.chatBridge {
                viewModel.configure(bridge: bridge)
            }
        }
    }

    private var inputBar: some View {
        HStack(spacing: 8) {
            // 清除按钮
            if !viewModel.messages.isEmpty && !viewModel.isProcessing {
                Button {
                    viewModel.clearHistory()
                } label: {
                    MatIcon(name: "trash", size: 20)
                        .foregroundColor(.white.opacity(0.4))
                }
                .accessibilityIdentifier("chat_clear")
            }

            // 停止按钮（推理中显示）
            if viewModel.isProcessing {
                Button {
                    viewModel.stop()
                } label: {
                    Image(systemName: "stop.fill")
                        .font(.system(size: 18))
                        .foregroundColor(.white.opacity(0.6))
                }
                .accessibilityIdentifier("chat_stop")
            }

            // 输入框
            TextField(String(localized: "Message"), text: $inputText, axis: .vertical)
                .textFieldStyle(.plain)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Color.white.opacity(0.08))
                .clipShape(RoundedRectangle(cornerRadius: 20))
                .focused($inputFocused)
                .lineLimit(1...4)
                .submitLabel(.send)
                .onSubmit(send)
                .accessibilityIdentifier("chat_input")

            // 发送按钮
            Button(action: send) {
                MatIcon(name: "arrow.up", size: 20)
                    .foregroundColor(canSend ? .white : .white.opacity(0.3))
            }
            .disabled(!canSend)
            .accessibilityIdentifier("chat_send")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    private var canSend: Bool {
        !inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !viewModel.isProcessing
    }

    private func send() {
        // 先守卫再清空：onSubmit 不受 Button.disabled 约束，处理中回车不能丢文本
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
            if message.role == .user { Spacer(minLength: 60) }

            VStack(alignment: message.role == .user ? .trailing : .leading, spacing: 6) {
                // 文本气泡
                if !message.text.isEmpty {
                    Text(message.text)
                        .font(.system(size: 15))
                        .foregroundColor(.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(bubbleColor)
                        .clipShape(RoundedRectangle(cornerRadius: 16))
                        .accessibilityIdentifier(message.role == .user ? "chat_user_bubble" : "chat_ai_bubble")
                }

                // 流式光标
                if message.isStreaming && message.text.isEmpty {
                    HStack(spacing: 4) {
                        ForEach(0..<3) { i in
                            Circle()
                                .fill(Color.white.opacity(0.4))
                                .frame(width: 6, height: 6)
                                .scaleEffect(message.isStreaming ? 1 : 0.5)
                                .animation(
                                    .easeInOut(duration: 0.6)
                                    .repeatForever()
                                    .delay(Double(i) * 0.2),
                                    value: message.isStreaming
                                )
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                    .background(Color.white.opacity(0.06))
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                }

                // 媒体卡片
                if !message.mediaIds.isEmpty {
                    MediaCardRow(mediaIds: message.mediaIds)
                }

                // 错误标记
                if message.error != nil {
                    HStack(spacing: 4) {
                        MatIcon(name: "error", size: 12)
                        Text(String(localized: "Guest quota exhausted. Future versions support account registration."))
                            .font(.system(size: 12))
                    }
                    .foregroundColor(.orange.opacity(0.8))
                }
            }

            if message.role == .assistant { Spacer(minLength: 60) }
        }
    }

    private var bubbleColor: Color {
        message.role == .user
            ? Color.blue.opacity(0.6)
            : Color.white.opacity(0.08)
    }
}

// MARK: - Media Card Row

private struct MediaCardRow: View {
    let mediaIds: [Int64]
    @State private var idToIdentifier: [Int64: String] = [:]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(mediaIds, id: \.self) { id in
                    MediaThumbnail(localIdentifier: idToIdentifier[id])
                        .frame(width: 72, height: 72)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                }
            }
        }
        .accessibilityIdentifier("chat_media_row")
        .task(id: mediaIds) {
            // mediaId 是 Kotlin 侧 localIdentifier.hashCode()（Java 确定性 31 多项式），
            // Swift String.hashValue 是 SipHash 每进程随机，绝不匹配——必须用等价实现反查。
            // 每次搜索结果一次扫描建映射，避免逐卡片 O(n) 全表扫
            var map: [Int64: String] = [:]
            let result = PHAsset.fetchAssets(with: nil)
            result.enumerateObjects { asset, _, _ in
                map[Self.javaHashCode(asset.localIdentifier)] = asset.localIdentifier
            }
            idToIdentifier = map
        }
    }

    /// Java String.hashCode() 等价（UTF-16 码元 31 多项式；localIdentifier 为 ASCII，无代理对问题）
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
            Color.white.opacity(0.05)
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
            } else {
                MatIcon(name: "photo", size: 20)
                    .foregroundColor(.white.opacity(0.2))
            }
        }
        .task(id: localIdentifier) {
            guard let localIdentifier, image == nil else { return }
            let size = CGSize(width: 216, height: 216) // 72pt @3x
            image = await ThumbnailLoader.shared.thumbnail(
                for: localIdentifier, size: size)
        }
    }
}

#Preview {
    ChatView()
        .environmentObject(AppContainer.shared)
        .preferredColorScheme(.dark)
}
