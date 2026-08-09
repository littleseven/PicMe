import SwiftUI

/// 自建顶栏（对齐 Android `AppTopBar.kt`，量化基准 = dump gallery_grid/gallery_longpress，
/// 密度 1200px/360dp = 3.33）：
/// 标题 22sp Medium、左缘 19.5dp；图标按钮触控框 35dp（字形 22dp）、pitch 43dp、右缘 ~2dp；
/// 栏内容高 48dp + 状态栏避让由父级 safe area 承担。
/// 不用系统 NavigationBar：双端视觉一致（S5），系统大标题风格不可控。
struct AppTopBar<Actions: View>: View {
    let title: String
    var showsBackButton: Bool = false
    var onBack: (() -> Void)? = nil
    @ViewBuilder var actions: Actions

    var body: some View {
        HStack(spacing: 7) {  // dump：按钮 pitch 143px=43dp = 36 框 + 7 间距
            if showsBackButton {
                AppTopBarAction(systemName: "chevron.left",
                                accessibilityID: "topbar_back") { onBack?() }
            }
            Text(title)
                .font(.system(size: 22, weight: .medium))  // dump：标题 22sp
                .lineLimit(1)
            Spacer(minLength: 0)
            HStack(spacing: 7) { actions }
        }
        .padding(.leading, showsBackButton ? 4 : 16)   // dump：标题 x65px=19.5dp（含字形内边距≈16+4）
        .padding(.trailing, 4)                          // dump：末按钮右缘 5px≈1.5dp
        .frame(height: 48)
        .frame(maxWidth: .infinity)
        .background(Color(.systemBackground))
    }
}

/// 顶栏图标按钮（dump：触控框 117px=35dp、字形 72px=22dp）。
/// `isEnabled == false` 时灰置且不可点（功能依赖未落地管线时的降级呈现，不假造交互）。
struct AppTopBarAction: View {
    let systemName: String
    var accessibilityID: String? = nil
    var isEnabled: Bool = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            MatIcon(name: systemName, size: 22)
                .frame(width: 36, height: 36)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(isEnabled ? Color.primary : Color.secondary.opacity(0.35))
        .disabled(!isEnabled)
        .accessibilityIdentifier(accessibilityID ?? "topbar_\(systemName)")
    }
}
