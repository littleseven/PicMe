import SwiftUI

/// 悬浮底部 Tab（对标 Android FloatingBottomTab.kt）
/// 底部居中悬浮胶囊，纯图标无文字
/// 对标：RoundedCornerShape(28dp)、surface 底色、12dp/8dp 内边距、24dp 图标、SpaceEvenly
struct FloatingBottomTab: View {
    @Binding var currentPage: Int

    var body: some View {
        HStack(spacing: 0) {
            tabItem(icon: "mat_o_photo_camera", page: 0) // 字形切换 camera_alt→photo_camera（对齐 Android 2278d6f7a）
            tabItem(icon: "mat_o_chat_bubble", page: 2) // Chat 已落地（Phase 6.2）
            tabItem(icon: "mat_o_sell", page: -1, isPlaceholder: true) // 打标无独立页
            tabItem(icon: "mat_o_account_circle", page: 3)
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
                // 占位页 — push 到占位 View（由父处理）；传稳定语义 key（非资产名），
                // 父级 MainTabView 按 "tag" 路由 TagScanScreen，勿回传 mat_o_* 资产名
                onPlaceholderTap?(tabId(for: icon))
            } else {
                withAnimation { currentPage = page }
            }
        } label: {
            MatIcon(name: icon, size: 24)
                .foregroundColor(currentPage == page ? .accentColor : .primary)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
        }
        // UI 自动化锚点：tab_camera / tab_chat / tab_tag / tab_person
        .accessibilityIdentifier("tab_\(tabId(for: icon))")
    }

    private func tabId(for icon: String) -> String {
        switch icon {
        case "mat_o_photo_camera": return "camera"
        case "mat_o_chat_bubble": return "chat"
        case "mat_o_sell": return "tag"
        case "mat_o_account_circle": return "person"
        default: return icon
        }
    }

    var onPlaceholderTap: ((String) -> Void)?
}
