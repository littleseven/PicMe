import SwiftUI

@main
struct PoLangApp: App {
    @StateObject private var container = AppContainer.shared
    @AppStorage("theme_mode") private var themeMode: String = "system"

    private var colorScheme: ColorScheme? {
        switch themeMode {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }

    var body: some Scene {
        WindowGroup {
            MainTabView()
                .environmentObject(container)
                .preferredColorScheme(colorScheme)
        }
    }
}
