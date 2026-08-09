import Foundation
import SwiftUI

/// 语言管理器——运行时切换 App 语言（通过 Bundle swizzling）。
///
/// 核心原理：iOS 的 String(localized:) 最终调 Bundle.main.localizedString(forKey:)。
/// 通过 objc method swizzling 替换 Bundle.main 的方法，使所有翻译查找
/// 走用户选定的 lproj，而非系统语言优先级。
///
/// 这样无需改任何 String(localized:) 调用处——切换语言后全 App 即时生效。
final class LanguageManager: ObservableObject {
    static let shared = LanguageManager()

    @Published var currentLanguage: String {
        didSet {
            UserDefaults.standard.set(currentLanguage, forKey: "app_language")
            applyLanguage()
        }
    }

    private init() {
        currentLanguage = UserDefaults.standard.string(forKey: "app_language") ?? "system"
        // 确保 swizzle 只执行一次
        Bundle.activateLanguageSwizzling()
        applyLanguage()
    }

    private func applyLanguage() {
        let langCode: String
        switch currentLanguage {
        case "english": langCode = "en"
        case "chinese_simplified": langCode = "zh-Hans"
        case "chinese_traditional": langCode = "zh-Hant"
        default:
            // 跟随系统：清空覆盖
            UserDefaults.standard.removeObject(forKey: "polang_language_override")
            Bundle.resetLanguageOverride()
            return
        }

        if let path = Bundle.main.path(forResource: langCode, ofType: "lproj") {
            UserDefaults.standard.set(path, forKey: "polang_language_override")
            Bundle.setLanguage(path)
        } else if let fallback = Bundle.main.path(forResource: "zh-Hans", ofType: "lproj") {
            // zh-Hant 不存在时 fallback 到 zh-Hans
            UserDefaults.standard.set(fallback, forKey: "polang_language_override")
            Bundle.setLanguage(fallback)
        }
    }
}

// MARK: - Bundle Swizzling

private var kBundleKey = 0

extension Bundle {
    /// 记录当前覆盖的语言 lproj 路径
    private static var languageOverride: String? {
        get { UserDefaults.standard.string(forKey: "polang_language_override") }
        set {
            if let newValue {
                UserDefaults.standard.set(newValue, forKey: "polang_language_override")
            } else {
                UserDefaults.standard.removeObject(forKey: "polang_language_override")
            }
        }
    }

    /// 执行一次 method swizzling，替换 Bundle.localizedString(forKey:)
    static func activateLanguageSwizzling() {
        // 用 dispatch_once 语义
        struct Static { static var token = false }
        if Static.token { return }
        Static.token = true

        let original = class_getInstanceMethod(Bundle.self, #selector(localizedString(forKey:value:table:)))
        let swizzled = class_getInstanceMethod(Bundle.self, #selector(swizzled_localizedString(forKey:value:table:)))

        if let original = original, let swizzled = swizzled {
            method_exchangeImplementations(original, swizzled)
        }

        // 启动时恢复上次选择的语言
        if let saved = languageOverride {
            setLanguage(saved)
        }
    }

    /// 设置语言覆盖（指定 lproj 路径）
    static func setLanguage(_ lprojPath: String) {
        languageOverride = lprojPath
    }

    /// 重置为系统语言
    static func resetLanguageOverride() {
        languageOverride = nil
    }

    @objc private func swizzled_localizedString(forKey key: String, value: String?, table tableName: String?) -> String {
        if let lprojPath = Bundle.languageOverride,
           let bundle = Bundle(path: lprojPath) {
            // 从选定的 lproj bundle 查找，找不到回退到 main
            let result = bundle.swizzled_localizedString(forKey: key, value: value, table: tableName)
            if result != key {
                return result
            }
        }
        // 回退：原始行为（系统语言）
        return self.swizzled_localizedString(forKey: key, value: value, table: tableName)
    }
}
