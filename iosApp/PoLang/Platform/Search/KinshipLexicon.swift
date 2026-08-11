import Foundation

// MARK: - 亲属称谓词表（双端契约 SSOT: contracts.md §7.2）
//
// 逐字对齐 Android `KinshipLexicon.kt:16-137`：37 条称谓 → 谓词映射 + 谓词族扩展
// （具体称谓含同族未指定桶：女儿→{DAUGHTER,CHILD}；泛化称谓含整族：孩子→{SON,DAUGHTER,CHILD}）。
// 查询侧（自然语言搜索"我女儿的照片"）与聊天声明工具共用的唯一词表，新增称谓只改这里。

enum KinshipLexicon {

    /// 称谓 → 声明谓词（具体值，归一存库用）。有序数组保持 Kotlin mapOf 插入序，
    /// 供 scan 的稳定性对齐（Kotlin sortedByDescending 为稳定排序）。
    private static let orderedEntries: [(term: String, predicate: RelationPredicate)] = [
        // 子女（具体）
        ("女儿", .daughter), ("闺女", .daughter), ("儿子", .son),
        // 子女（泛化 → 未指定桶，查询时扩展整族）
        ("孩子", .child), ("小孩", .child),
        // 配偶
        ("老婆", .spouse), ("妻子", .spouse), ("老公", .spouse), ("丈夫", .spouse), ("爱人", .spouse),
        // 恋人（未婚，与配偶区分）
        ("男朋友", .partner), ("女朋友", .partner), ("对象", .partner), ("恋人", .partner),
        // 父母（具体）
        ("爸爸", .father), ("父亲", .father), ("爸", .father),
        ("妈妈", .mother), ("母亲", .mother), ("妈", .mother),
        // 父母（泛化）
        ("父母", .parent),
        // 兄弟姐妹（具体）
        ("哥哥", .elderBrother), ("哥", .elderBrother),
        ("姐姐", .elderSister), ("姐", .elderSister),
        ("弟弟", .youngerBrother), ("妹妹", .youngerSister),
        // 兄弟姐妹（泛化）
        ("兄弟姐妹", .sibling),
        // 祖辈（具体；内外区分交给 customLabel，不加枚举）
        ("爷爷", .grandfather), ("外公", .grandfather),
        ("奶奶", .grandmother), ("外婆", .grandmother),
        // 祖辈（泛化）
        ("爷爷奶奶", .grandparent), ("祖辈", .grandparent),
        // 孙辈
        ("孙子", .grandchild), ("孙女", .grandchild),
        // 同学
        ("同学", .classmate), ("同窗", .classmate),
    ]

    /// 称谓 → 声明谓词查询表（由 orderedEntries 派生）。
    private static let termToPredicate: [String: RelationPredicate] = {
        Dictionary(uniqueKeysWithValues: orderedEntries.map { ($0.term, $0.predicate) })
    }()

    /// 谓词族：具体谓词 → 同族未指定桶（KinshipLexicon.kt FAMILY_BUCKET）。
    private static let familyBucket: [RelationPredicate: RelationPredicate] = [
        .son: .child,
        .daughter: .child,
        .father: .parent,
        .mother: .parent,
        .elderBrother: .sibling,
        .elderSister: .sibling,
        .youngerBrother: .sibling,
        .youngerSister: .sibling,
        .grandfather: .grandparent,
        .grandmother: .grandparent,
    ]

    /// 谓词族：未指定桶 → 整族（含桶自身）（KinshipLexicon.kt FAMILY_MEMBERS）。
    private static let familyMembers: [RelationPredicate: Set<RelationPredicate>] = [
        .child: [.son, .daughter, .child],
        .parent: [.father, .mother, .parent],
        .sibling: [.elderBrother, .elderSister, .youngerBrother, .youngerSister, .sibling],
        .grandparent: [.grandfather, .grandmother, .grandparent],
    ]

    /// 全部受控称谓（供查询分词扫描命中）。
    static var terms: Set<String> { Set(termToPredicate.keys) }

    /// 称谓 → 声明谓词（具体值，归一存库用）；非受控称谓返回 nil。
    static func predicateFor(_ term: String) -> RelationPredicate? {
        termToPredicate[term]
    }

    /// 称谓 → 查询谓词集合（谓词族扩展）：具体谓词扩展为 {具体值, 同族未指定桶}；
    /// 泛化桶扩展为整族；非族谓词（SPOUSE/PARTNER/GRANDCHILD/CLASSMATE）为单例。
    static func queryPredicatesFor(_ term: String) -> Set<RelationPredicate>? {
        guard let predicate = termToPredicate[term] else { return nil }
        if let bucket = familyBucket[predicate] { return [predicate, bucket] }
        return familyMembers[predicate] ?? [predicate]
    }

    /// 在一段文本中扫描命中的（称谓, 谓词）对，按称谓长度降序（优先长匹配）。
    /// 被更长命中称谓包含的短称谓去重（"爸爸"命中后"爸"不再重复命中）。
    /// 同长度按词表插入序（对齐 Kotlin 稳定排序 sortedByDescending）。
    static func scan(_ text: String) -> [(term: String, predicate: RelationPredicate)] {
        let hits = orderedEntries
            .filter { text.contains($0.term) }
            .enumerated()
            .sorted { lhs, rhs in
                let lc = lhs.element.term.count, rc = rhs.element.term.count
                return lc == rc ? lhs.offset < rhs.offset : lc > rc
            }
            .map { $0.element }
        var kept: [(term: String, predicate: RelationPredicate)] = []
        for (term, predicate) in hits {
            if kept.contains(where: { $0.term.contains(term) }) { continue }
            kept.append((term: term, predicate: predicate))
        }
        return kept
    }
}
