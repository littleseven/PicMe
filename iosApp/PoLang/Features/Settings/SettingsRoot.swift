import SwiftUI

/// 设置页根容器——在 fullScreenCover 内部自建完整环境
/// （fullScreenCover 是独立 UIWindow，不继承根视图的 preferredColorScheme / locale）。
/// 通过 @StateObject 持有 AppSettings，确保主题/语言切换即时生效。
struct SettingsRoot: View {
    @StateObject private var settings = AppSettings.shared

    var body: some View {
        NavigationStack {
            SettingsScreen()
                .environmentObject(settings)
        }
        .preferredColorScheme(settings.colorScheme)
        .environment(\.locale, settings.locale)
    }
}
