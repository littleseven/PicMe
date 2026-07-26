package com.mamba.picme.domain.person

/**
 * 人物关系谓词（封闭枚举）—— 人物关系图谱边的类型
 *
 * 每个取值带中/英/日三语标签，供命名对话框下拉与聊天工具展示使用；
 * 数据库存储枚举名（[name]），通过 [fromStored] 还原。
 */
enum class RelationPredicate(
    val labelZh: String,
    val labelEn: String,
    val labelJa: String
) {
    SPOUSE(labelZh = "配偶", labelEn = "Spouse", labelJa = "配偶者"),
    PARTNER(labelZh = "恋人", labelEn = "Partner", labelJa = "恋人"),
    CHILD(labelZh = "孩子", labelEn = "Child", labelJa = "子供"),
    SON(labelZh = "儿子", labelEn = "Son", labelJa = "息子"),
    DAUGHTER(labelZh = "女儿", labelEn = "Daughter", labelJa = "娘"),
    PARENT(labelZh = "父母", labelEn = "Parent", labelJa = "親"),
    FATHER(labelZh = "爸爸", labelEn = "Father", labelJa = "父"),
    MOTHER(labelZh = "妈妈", labelEn = "Mother", labelJa = "母"),
    SIBLING(labelZh = "兄弟姐妹", labelEn = "Sibling", labelJa = "兄弟姉妹"),
    ELDER_BROTHER(labelZh = "哥哥", labelEn = "Elder brother", labelJa = "兄"),
    ELDER_SISTER(labelZh = "姐姐", labelEn = "Elder sister", labelJa = "姉"),
    YOUNGER_BROTHER(labelZh = "弟弟", labelEn = "Younger brother", labelJa = "弟"),
    YOUNGER_SISTER(labelZh = "妹妹", labelEn = "Younger sister", labelJa = "妹"),
    GRANDPARENT(labelZh = "祖辈", labelEn = "Grandparent", labelJa = "祖父母"),
    GRANDFATHER(labelZh = "爷爷", labelEn = "Grandfather", labelJa = "祖父"),
    GRANDMOTHER(labelZh = "奶奶", labelEn = "Grandmother", labelJa = "祖母"),
    GRANDCHILD(labelZh = "孙辈", labelEn = "Grandchild", labelJa = "孫"),
    OTHER_FAMILY(labelZh = "其他亲属", labelEn = "Other family", labelJa = "その他の親族"),
    FRIEND(labelZh = "朋友", labelEn = "Friend", labelJa = "友人"),
    CLASSMATE(labelZh = "同学", labelEn = "Classmate", labelJa = "クラスメート"),
    COLLEAGUE(labelZh = "同事", labelEn = "Colleague", labelJa = "同僚"),
    OTHER(labelZh = "其他", labelEn = "Other", labelJa = "その他");

    companion object {
        /** 数据库字符串 → 枚举；无法识别返回 null（调用方决定降级策略） */
        fun fromStored(stored: String): RelationPredicate? =
            values().firstOrNull { predicate -> predicate.name == stored }
    }
}

/**
 * 关系声明来源（封闭枚举）—— 数据库存储枚举名
 */
enum class RelationSource {
    /** 相册人物重命名对话框 */
    RENAME_DIALOG,

    /** 聊天声明工具（"记住 X 是我 Y"） */
    CHAT_DECLARATION;

    companion object {
        fun fromStored(stored: String): RelationSource? =
            values().firstOrNull { source -> source.name == stored }
    }
}
