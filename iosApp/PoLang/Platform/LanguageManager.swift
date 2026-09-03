import Foundation
import SwiftUI

/// 语言管理器——运行时切换 App 语言。
///
/// 核心原理：Swift 5 的 String(localized:) 走 Foundation 新 localizer，
/// 绕过 Bundle.localizedString swizzle。因此直接替换翻译查找路径：
/// 所有 UI 用 L("key") 替代 String(localized: "key")，
/// L() 函数从 LanguageManager 选定的 lproj bundle 查找。
final class LanguageManager: ObservableObject {
    static let shared = LanguageManager()

    @Published var currentLanguage: String {
        didSet {
            UserDefaults.standard.set(currentLanguage, forKey: "app_language")
            updateBundle()
        }
    }

    /// 当前语言对应的 lproj Bundle
    private(set) var bundle: Bundle = .main

    private init() {
        currentLanguage = UserDefaults.standard.string(forKey: "app_language") ?? "system"
        updateBundle()
    }

    private func updateBundle() {
        let langCode: String
        switch currentLanguage {
        case "english": langCode = "en"
        case "chinese_simplified": langCode = "zh-Hans"
        case "chinese_traditional": langCode = "zh-Hant"
        case "spanish": langCode = "es"
        case "french": langCode = "fr"
        default:
            // 跟随系统：用系统首选语言
            bundle = .main
            return
        }

        if let path = Bundle.main.path(forResource: langCode, ofType: "lproj"),
           let lprojBundle = Bundle(path: path) {
            bundle = lprojBundle
        } else if let fallback = Bundle.main.path(forResource: "zh-Hans", ofType: "lproj"),
                  let fallbackBundle = Bundle(path: fallback) {
            bundle = fallbackBundle
        } else {
            bundle = .main
        }
    }

    /// 翻译查找（核心方法）
    func translate(_ key: String) -> String {
        // 从选定的 lproj bundle 查找
        let val = bundle.localizedString(forKey: key, value: key, table: nil)
        // 如果 lproj 返回 key 本身（没找到），且当前是"跟随系统"，尝试系统语言
        if val == key && currentLanguage == "system" {
            return String(localized: String.LocalizationValue(key))
        }
        return val
    }
}

/// 全局翻译函数——替代 String(localized:)。
/// 用法：Text(L("Settings")) 或 Text("Settings".tr)
func L(_ key: String) -> String {
    LanguageManager.shared.translate(key)
}

extension String {
    /// 翻译扩展
    var tr: String { L(self) }
}
