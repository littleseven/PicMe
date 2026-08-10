import SwiftUI

@main
struct PoLangApp: App {
    @StateObject private var container = AppContainer.shared
    @StateObject private var settings = AppSettings.shared

    var body: some Scene {
        WindowGroup {
            MainTabView()
                .environmentObject(container)
                .environmentObject(settings)
                .preferredColorScheme(settings.colorScheme)
                .environment(\.locale, settings.locale)
                #if DEBUG
                // SKIE spike S5 冒烟（spike/skie 分支临时，GO 后移除）
                .onAppear { SkieSmoke.run() }
                #endif
        }
    }
}

/// 全局应用设置（主题 + 语言），ObservableObject 确保变更全 App 即时生效。
/// 所有页面用 @EnvironmentObject AppSettings 读取，用 @AppStorage 持久化。
final class AppSettings: ObservableObject {
    static let shared = AppSettings()

    @Published var themeMode: String {
        didSet { UserDefaults.standard.set(themeMode, forKey: "theme_mode") }
    }
    @Published var appLanguage: String {
        didSet {
            UserDefaults.standard.set(appLanguage, forKey: "app_language")
            LanguageManager.shared.currentLanguage = appLanguage
        }
    }

    var colorScheme: ColorScheme? {
        switch themeMode {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }

    var locale: Locale {
        switch appLanguage {
        case "english": return Locale(identifier: "en")
        case "chinese_simplified": return Locale(identifier: "zh-Hans")
        case "chinese_traditional": return Locale(identifier: "zh-Hant")
        default: return Locale.current
        }
    }

    private init() {
        themeMode = UserDefaults.standard.string(forKey: "theme_mode") ?? "system"
        appLanguage = UserDefaults.standard.string(forKey: "app_language") ?? "system"
        // 初始化语言管理器（触发 Bundle swizzling + 恢复上次选择）
        _ = LanguageManager.shared
        // -galleryFace：自动拉真实相册照片验证人脸检测（裁决 UIImage 朝向假设），无需 UI 导航
        if ProcessInfo.processInfo.arguments.contains("-galleryFace") {
            Task.detached(priority: .utility) { await GalleryFaceAutoCheck.run() }
        }
    }
}
