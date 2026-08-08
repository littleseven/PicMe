import SwiftUI

/// 自建顶栏（对齐 Android `AppTopBar.kt` 规格）：
/// 高 48pt、标题 17pt Medium、surface 背景、状态栏避让由父级 safe area 承担；
/// 图标按钮 36pt 触控框 / 20pt 字形 / 间距 8pt / 屏幕边距 8pt。
/// 不用系统 NavigationBar：双端视觉一致（S5），系统大标题风格不可控。
struct AppTopBar<Actions: View>: View {
    let title: String
    var showsBackButton: Bool = false
    var onBack: (() -> Void)? = nil
    @ViewBuilder var actions: Actions

    var body: some View {
        HStack(spacing: 8) {
            if showsBackButton {
                AppTopBarAction(systemName: "chevron.left",
                                accessibilityID: "topbar_back") { onBack?() }
            }
            Text(title)
                .font(.system(size: 17, weight: .medium))
                .lineLimit(1)
            Spacer(minLength: 0)
            HStack(spacing: 8) { actions }
        }
        .padding(.horizontal, 8)
        .frame(height: 48)
        .frame(maxWidth: .infinity)
        .background(Color(.systemBackground))
    }
}

/// 顶栏图标按钮（对齐 Android `AppTopBarIconButton`：36dp 框 / 22dp 字形）。
/// `isEnabled == false` 时灰置且不可点（功能依赖未落地管线时的降级呈现，不假造交互）。
struct AppTopBarAction: View {
    let systemName: String
    var accessibilityID: String? = nil
    var isEnabled: Bool = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 20))
                .frame(width: 36, height: 36)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(isEnabled ? Color.primary : Color.secondary.opacity(0.35))
        .disabled(!isEnabled)
        .accessibilityIdentifier(accessibilityID ?? "topbar_\(systemName)")
    }
}
