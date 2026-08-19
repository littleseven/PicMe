import SwiftUI

/// 会话历史侧栏（spec chat.yaml §2.5，对标 Android ChatThreadSidebar.kt）。
///
/// 280pt 左侧滑出面板：标题 + 新建/关闭、搜索框（过滤标题与预览）、
/// 会话列表（标题 15 / 预览 12 / 选中 primary 12% 高亮）、行内菜单（重命名/删除二次确认）。
struct ChatThreadSidebarView: View {
    let threads: [ChatThread]
    let currentSessionId: String
    @Binding var searchQuery: String
    let onThreadSelected: (String) -> Void
    let onNewChat: () -> Void
    let onRename: (String, String) -> Void
    let onDelete: (String) -> Void
    let onDismiss: () -> Void

    /// 重命名弹窗目标（会话 ID + 预填标题）
    @State private var renameTarget: (sessionId: String, title: String)? = nil
    @State private var renameText = ""
    /// 删除确认目标
    @State private var deleteTarget: ChatThread? = nil

    var body: some View {
        VStack(spacing: 0) {
            header
            searchField
            threadList
        }
        .frame(width: 280)
        .frame(maxHeight: .infinity)
        .background(Color(.systemBackground).ignoresSafeArea())
        .shadow(radius: 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityIdentifier("chat_sidebar")
        .alert(String(localized: "Rename"), isPresented: renamePresented) {
            TextField("", text: $renameText)
            Button(String(localized: "Save")) {
                if let target = renameTarget {
                    onRename(target.sessionId, renameText)
                }
            }
            .disabled(renameText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            Button(String(localized: "Cancel"), role: .cancel) {}
        }
        .confirmationDialog(
            String(localized: "Delete Chat"),
            isPresented: deletePresented,
            titleVisibility: .visible
        ) {
            Button(String(localized: "Delete"), role: .destructive) {
                if let target = deleteTarget {
                    onDelete(target.sessionId)
                }
            }
            Button(String(localized: "Cancel"), role: .cancel) {}
        } message: {
            if let target = deleteTarget {
                Text(String(localized: "Delete \"\(target.title)\"? This cannot be undone."))
            }
        }
    }

    private var renamePresented: Binding<Bool> {
        Binding(
            get: { renameTarget != nil },
            set: { if !$0 { renameTarget = nil } }
        )
    }

    private var deletePresented: Binding<Bool> {
        Binding(
            get: { deleteTarget != nil },
            set: { if !$0 { deleteTarget = nil } }
        )
    }

    // MARK: - 头部（标题 + 新建/关闭）

    private var header: some View {
        HStack {
            Text("Chat History")
                .font(.system(size: 17, weight: .bold))
                .foregroundColor(Color(.label))
            Spacer()
            Button(action: onNewChat) {
                MatIcon(name: "mat_add", size: 22)
                    .foregroundColor(Color(.label))
            }
            .frame(width: 36, height: 36)
            .accessibilityIdentifier("chat_sidebar_new")
            Button(action: onDismiss) {
                MatIcon(name: "mat_close", size: 22)
                    .foregroundColor(Color(.label))
            }
            .frame(width: 36, height: 36)
            .accessibilityIdentifier("chat_sidebar_close")
        }
        .padding(16)
    }

    // MARK: - 搜索框（过滤标题与预览）

    private var searchField: some View {
        HStack(spacing: 8) {
            MatIcon(name: "mat_o_search", size: 18)
                .foregroundColor(Color(.secondaryLabel))
            TextField(String(localized: "Search history"), text: $searchQuery)
                .font(.system(size: 14))
                .foregroundColor(Color(.label))
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color(.tertiarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
        .accessibilityIdentifier("chat_sidebar_search")
    }

    // MARK: - 会话列表（updatedAt 倒序由 store 保证）

    private var threadList: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(threads) { thread in
                    threadRow(thread)
                }
            }
        }
    }

    private func threadRow(_ thread: ChatThread) -> some View {
        let isSelected = thread.sessionId == currentSessionId
        return HStack(spacing: 0) {
            Button { onThreadSelected(thread.sessionId) } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(thread.title)
                        .font(.system(size: 15, weight: isSelected ? .bold : .regular))
                        .foregroundColor(isSelected ? .accentColor : Color(.label))
                        .lineLimit(1)
                    if !thread.lastMessagePreview.isEmpty {
                        Text(thread.lastMessagePreview)
                            .font(.system(size: 12))
                            .foregroundColor(Color(.label).opacity(0.6))
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            Menu {
                Button(String(localized: "Rename")) {
                    renameText = thread.title
                    renameTarget = (thread.sessionId, thread.title)
                }
                Button(String(localized: "Delete chat"), role: .destructive) {
                    deleteTarget = thread
                }
            } label: {
                MatIcon(name: "mat_more_horiz", size: 20)
                    .foregroundColor(Color(.label).opacity(0.6))
                    .frame(width: 36, height: 36)
            }
            .accessibilityIdentifier("chat_thread_menu")
        }
        .padding(.leading, 16)
        .padding(.trailing, 8)
        .padding(.vertical, 4)
        .background(isSelected ? Color.accentColor.opacity(0.12) : Color.clear)
        .accessibilityIdentifier("chat_thread_item")
    }
}
