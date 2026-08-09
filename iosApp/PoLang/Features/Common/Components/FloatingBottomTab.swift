import SwiftUI

/// 悬浮底部 Tab（对标 Android FloatingBottomTab.kt）
/// 底部居中悬浮胶囊，纯图标无文字
/// 对标：RoundedCornerShape(28dp)、surface 底色、12dp/8dp 内边距、24dp 图标、SpaceEvenly
struct FloatingBottomTab: View {
    @Binding var currentPage: Int

    var body: some View {
        HStack(spacing: 0) {
            tabItem(icon: "camera", page: 0)
            tabItem(icon: "bubble.left", page: 2, isPlaceholder: true)
            tabItem(icon: "tag", page: -1, isPlaceholder: true) // 打标无独立页
            tabItem(icon: "person.2", page: 3, isPlaceholder: true)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(
            Capsule()
                .fill(.ultraThinMaterial)
                .shadow(color: .black.opacity(0.15), radius: 6, y: 3)
        )
    }

    private func tabItem(icon: String, page: Int, isPlaceholder: Bool = false) -> some View {
        Button {
            if isPlaceholder {
                // 占位页 — push 到占位 View（由父处理）
                onPlaceholderTap?(icon)
            } else {
                withAnimation { currentPage = page }
            }
        } label: {
            MatIcon(name: icon, size: 24)
                .foregroundColor(currentPage == page ? .accentColor : .primary)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
        }
    }

    var onPlaceholderTap: ((String) -> Void)?
}
