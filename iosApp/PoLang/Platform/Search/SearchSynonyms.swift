import Foundation

// MARK: - 搜索同义词映射（双端契约 SSOT: contracts.md §3.5）
//
// 逐字照抄 Android `app/domain/search/SearchSynonyms.kt`（18 键）。
// 动物/宠物仅扩英文（匹配 Qwen/SmolVLM 英文标签），不扩中文「狗/猫」避免命中「遛狗」。

enum SearchSynonyms {

    static let map: [String: [String]] = [
        "女性": ["女"], "女人": ["女"], "女孩": ["女"], "少女": ["女"], "女生": ["女"],
        "男性": ["男"], "男人": ["男"], "男孩": ["男"], "少年": ["男"], "男生": ["男"],
        "动物": ["cat", "dog", "pet", "bird", "fish", "horse"],
        "宠物": ["cat", "dog", "pet"],
        "夜景": ["夜"], "夜晚": ["夜"],
        "海边": ["海", "沙滩"], "海滩": ["沙滩"],
        "食物": ["面条", "米饭", "菜"], "美食": ["面条", "米饭", "菜"]
    ]

    /// expand(query) = map[query] + query 本身（去重，保序）。
    static func expand(_ query: String) -> [String] {
        var result = map[query] ?? []
        if !result.contains(query) {
            result.append(query)
        }
        return result
    }
}
