import Foundation

// MARK: - 人物关系谓词（双端契约 SSOT: contracts.md §7.4）
//
// 逐字对齐 shared `RelationPredicate.kt:13-47`：封闭枚举 + 中/英/日标签。
// 数据库存枚举名字符串（rawValue），`fromStored` 按 name 精确还原（无法识别 → nil，
// 调用方决定降级策略）。标签仅供命名 UI 展示，搜索不消费。

/// 人物关系谓词（封闭枚举）——人物关系图谱边的类型。
enum RelationPredicate: String, CaseIterable, Sendable {
    case spouse = "SPOUSE"
    case partner = "PARTNER"
    case child = "CHILD"
    case son = "SON"
    case daughter = "DAUGHTER"
    case parent = "PARENT"
    case father = "FATHER"
    case mother = "MOTHER"
    case sibling = "SIBLING"
    case elderBrother = "ELDER_BROTHER"
    case elderSister = "ELDER_SISTER"
    case youngerBrother = "YOUNGER_BROTHER"
    case youngerSister = "YOUNGER_SISTER"
    case grandparent = "GRANDPARENT"
    case grandfather = "GRANDFATHER"
    case grandmother = "GRANDMOTHER"
    case grandchild = "GRANDCHILD"
    case otherFamily = "OTHER_FAMILY"
    case friend = "FRIEND"
    case classmate = "CLASSMATE"
    case colleague = "COLLEAGUE"
    case idol = "IDOL"
    case other = "OTHER"

    /// 中文标签（RelationPredicate.kt labelZh）
    var labelZh: String {
        switch self {
        case .spouse: return "配偶"
        case .partner: return "恋人"
        case .child: return "孩子"
        case .son: return "儿子"
        case .daughter: return "女儿"
        case .parent: return "父母"
        case .father: return "爸爸"
        case .mother: return "妈妈"
        case .sibling: return "兄弟姐妹"
        case .elderBrother: return "哥哥"
        case .elderSister: return "姐姐"
        case .youngerBrother: return "弟弟"
        case .youngerSister: return "妹妹"
        case .grandparent: return "祖辈"
        case .grandfather: return "爷爷"
        case .grandmother: return "奶奶"
        case .grandchild: return "孙辈"
        case .otherFamily: return "其他亲属"
        case .friend: return "朋友"
        case .classmate: return "同学"
        case .colleague: return "同事"
        case .idol: return "偶像"
        case .other: return "其他"
        }
    }

    /// 英文标签（RelationPredicate.kt labelEn）
    var labelEn: String {
        switch self {
        case .spouse: return "Spouse"
        case .partner: return "Partner"
        case .child: return "Child"
        case .son: return "Son"
        case .daughter: return "Daughter"
        case .parent: return "Parent"
        case .father: return "Father"
        case .mother: return "Mother"
        case .sibling: return "Sibling"
        case .elderBrother: return "Elder brother"
        case .elderSister: return "Elder sister"
        case .youngerBrother: return "Younger brother"
        case .youngerSister: return "Younger sister"
        case .grandparent: return "Grandparent"
        case .grandfather: return "Grandfather"
        case .grandmother: return "Grandmother"
        case .grandchild: return "Grandchild"
        case .otherFamily: return "Other family"
        case .friend: return "Friend"
        case .classmate: return "Classmate"
        case .colleague: return "Colleague"
        case .idol: return "Idol"
        case .other: return "Other"
        }
    }

    /// 日文标签（RelationPredicate.kt labelJa）
    var labelJa: String {
        switch self {
        case .spouse: return "配偶者"
        case .partner: return "恋人"
        case .child: return "子供"
        case .son: return "息子"
        case .daughter: return "娘"
        case .parent: return "親"
        case .father: return "父"
        case .mother: return "母"
        case .sibling: return "兄弟姉妹"
        case .elderBrother: return "兄"
        case .elderSister: return "姉"
        case .youngerBrother: return "弟"
        case .youngerSister: return "妹"
        case .grandparent: return "祖父母"
        case .grandfather: return "祖父"
        case .grandmother: return "祖母"
        case .grandchild: return "孫"
        case .otherFamily: return "その他の親族"
        case .friend: return "友人"
        case .classmate: return "クラスメート"
        case .colleague: return "同僚"
        case .idol: return "アイドル"
        case .other: return "その他"
        }
    }

    /// 数据库字符串 → 枚举；无法识别返回 nil（对齐 `RelationPredicate.fromStored`）。
    static func fromStored(_ stored: String) -> RelationPredicate? {
        RelationPredicate(rawValue: stored)
    }
}
