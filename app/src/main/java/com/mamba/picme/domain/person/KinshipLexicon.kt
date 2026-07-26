package com.mamba.picme.domain.person

/**
 * 亲属称谓词表 —— 中文称谓 → 关系谓词映射
 *
 * 查询侧（自然语言搜索"我女儿的照片"）与聊天声明工具（"记住 X 是我 Y"）共用的唯一词表，
 * 新增称谓只改这里。
 */
object KinshipLexicon {

    private val TERM_TO_PREDICATE: Map<String, RelationPredicate> = mapOf(
        // 子女
        "女儿" to RelationPredicate.CHILD,
        "闺女" to RelationPredicate.CHILD,
        "儿子" to RelationPredicate.CHILD,
        "孩子" to RelationPredicate.CHILD,
        // 配偶
        "老婆" to RelationPredicate.SPOUSE,
        "妻子" to RelationPredicate.SPOUSE,
        "老公" to RelationPredicate.SPOUSE,
        "丈夫" to RelationPredicate.SPOUSE,
        "爱人" to RelationPredicate.SPOUSE,
        // 父母
        "爸爸" to RelationPredicate.PARENT,
        "妈妈" to RelationPredicate.PARENT,
        // 兄弟姐妹
        "哥哥" to RelationPredicate.SIBLING,
        "姐姐" to RelationPredicate.SIBLING,
        "弟弟" to RelationPredicate.SIBLING,
        "妹妹" to RelationPredicate.SIBLING,
        // 祖辈
        "爷爷" to RelationPredicate.GRANDPARENT,
        "奶奶" to RelationPredicate.GRANDPARENT,
        "外公" to RelationPredicate.GRANDPARENT,
        "外婆" to RelationPredicate.GRANDPARENT,
        // 孙辈
        "孙子" to RelationPredicate.GRANDCHILD,
        "孙女" to RelationPredicate.GRANDCHILD
    )

    /** 全部受控称谓（供查询分词扫描命中） */
    val terms: Set<String> = TERM_TO_PREDICATE.keys

    /** 称谓 → 谓词；非受控称谓返回 null */
    fun predicateFor(term: String): RelationPredicate? = TERM_TO_PREDICATE[term]

    /** 在一段文本中扫描命中的（称谓, 谓词）对，按称谓长度降序（优先长匹配） */
    fun scan(text: String): List<Pair<String, RelationPredicate>> =
        TERM_TO_PREDICATE.entries
            .filter { entry -> text.contains(entry.key) }
            .sortedByDescending { entry -> entry.key.length }
            .map { entry -> entry.key to entry.value }
}
