package com.mamba.picme.domain.person

/**
 * 亲属称谓词表 —— 中文称谓 → 关系谓词映射
 *
 * 查询侧（自然语言搜索"我女儿的照片"）与聊天声明工具（"记住 X 是我 Y"）共用的唯一词表，
 * 新增称谓只改这里。
 *
 * 声明侧：称谓归一到**具体谓词**（"女儿"→DAUGHTER 存库，不写族）。
 * 查询侧：[queryPredicatesFor] 按谓词族扩展——
 * 具体称谓命中"具体值 + 同族未指定桶"（女儿→{DAUGHTER, CHILD}）；
 * 泛化称谓命中整族（孩子→{SON, DAUGHTER, CHILD}）。
 */
object KinshipLexicon {

    private val TERM_TO_PREDICATE: Map<String, RelationPredicate> = mapOf(
        // 子女（具体）
        "女儿" to RelationPredicate.DAUGHTER,
        "闺女" to RelationPredicate.DAUGHTER,
        "儿子" to RelationPredicate.SON,
        // 子女（泛化 → 未指定桶，查询时扩展整族）
        "孩子" to RelationPredicate.CHILD,
        "小孩" to RelationPredicate.CHILD,
        // 配偶
        "老婆" to RelationPredicate.SPOUSE,
        "妻子" to RelationPredicate.SPOUSE,
        "老公" to RelationPredicate.SPOUSE,
        "丈夫" to RelationPredicate.SPOUSE,
        "爱人" to RelationPredicate.SPOUSE,
        // 恋人（未婚，与配偶区分）
        "男朋友" to RelationPredicate.PARTNER,
        "女朋友" to RelationPredicate.PARTNER,
        "对象" to RelationPredicate.PARTNER,
        "恋人" to RelationPredicate.PARTNER,
        // 父母（具体）
        "爸爸" to RelationPredicate.FATHER,
        "父亲" to RelationPredicate.FATHER,
        "爸" to RelationPredicate.FATHER,
        "妈妈" to RelationPredicate.MOTHER,
        "母亲" to RelationPredicate.MOTHER,
        "妈" to RelationPredicate.MOTHER,
        // 父母（泛化）
        "父母" to RelationPredicate.PARENT,
        // 兄弟姐妹（具体）
        "哥哥" to RelationPredicate.ELDER_BROTHER,
        "哥" to RelationPredicate.ELDER_BROTHER,
        "姐姐" to RelationPredicate.ELDER_SISTER,
        "姐" to RelationPredicate.ELDER_SISTER,
        "弟弟" to RelationPredicate.YOUNGER_BROTHER,
        "妹妹" to RelationPredicate.YOUNGER_SISTER,
        // 兄弟姐妹（泛化）
        "兄弟姐妹" to RelationPredicate.SIBLING,
        // 祖辈（具体；内外区分交给 customLabel，不加枚举）
        "爷爷" to RelationPredicate.GRANDFATHER,
        "外公" to RelationPredicate.GRANDFATHER,
        "奶奶" to RelationPredicate.GRANDMOTHER,
        "外婆" to RelationPredicate.GRANDMOTHER,
        // 祖辈（泛化）
        "爷爷奶奶" to RelationPredicate.GRANDPARENT,
        "祖辈" to RelationPredicate.GRANDPARENT,
        // 孙辈
        "孙子" to RelationPredicate.GRANDCHILD,
        "孙女" to RelationPredicate.GRANDCHILD,
        // 同学
        "同学" to RelationPredicate.CLASSMATE,
        "同窗" to RelationPredicate.CLASSMATE
    )

    /**
     * 谓词族：具体谓词 → 同族未指定桶
     */
    private val FAMILY_BUCKET: Map<RelationPredicate, RelationPredicate> = mapOf(
        RelationPredicate.SON to RelationPredicate.CHILD,
        RelationPredicate.DAUGHTER to RelationPredicate.CHILD,
        RelationPredicate.FATHER to RelationPredicate.PARENT,
        RelationPredicate.MOTHER to RelationPredicate.PARENT,
        RelationPredicate.ELDER_BROTHER to RelationPredicate.SIBLING,
        RelationPredicate.ELDER_SISTER to RelationPredicate.SIBLING,
        RelationPredicate.YOUNGER_BROTHER to RelationPredicate.SIBLING,
        RelationPredicate.YOUNGER_SISTER to RelationPredicate.SIBLING,
        RelationPredicate.GRANDFATHER to RelationPredicate.GRANDPARENT,
        RelationPredicate.GRANDMOTHER to RelationPredicate.GRANDPARENT
    )

    /**
     * 谓词族：未指定桶 → 整族（含桶自身）
     */
    private val FAMILY_MEMBERS: Map<RelationPredicate, Set<RelationPredicate>> = mapOf(
        RelationPredicate.CHILD to setOf(
            RelationPredicate.SON, RelationPredicate.DAUGHTER, RelationPredicate.CHILD
        ),
        RelationPredicate.PARENT to setOf(
            RelationPredicate.FATHER, RelationPredicate.MOTHER, RelationPredicate.PARENT
        ),
        RelationPredicate.SIBLING to setOf(
            RelationPredicate.ELDER_BROTHER, RelationPredicate.ELDER_SISTER,
            RelationPredicate.YOUNGER_BROTHER, RelationPredicate.YOUNGER_SISTER,
            RelationPredicate.SIBLING
        ),
        RelationPredicate.GRANDPARENT to setOf(
            RelationPredicate.GRANDFATHER, RelationPredicate.GRANDMOTHER,
            RelationPredicate.GRANDPARENT
        )
    )

    /** 全部受控称谓（供查询分词扫描命中） */
    val terms: Set<String> = TERM_TO_PREDICATE.keys

    /** 称谓 → 声明谓词（具体值，归一存库用）；非受控称谓返回 null */
    fun predicateFor(term: String): RelationPredicate? = TERM_TO_PREDICATE[term]

    /**
     * 称谓 → 查询谓词集合（谓词族扩展）：
     * 具体谓词扩展为 {具体值, 同族未指定桶}；泛化桶扩展为整族；非族谓词为单例。
     */
    fun queryPredicatesFor(term: String): Set<RelationPredicate>? {
        val predicate = TERM_TO_PREDICATE[term] ?: return null
        val bucket = FAMILY_BUCKET[predicate]
        if (bucket != null) return setOf(predicate, bucket)
        return FAMILY_MEMBERS[predicate] ?: setOf(predicate)
    }

    /**
     * 在一段文本中扫描命中的（称谓, 谓词）对，按称谓长度降序（优先长匹配）。
     * 被更长命中称谓包含的短称谓去重（"爸爸"命中后"爸"不再重复命中）。
     */
    fun scan(text: String): List<Pair<String, RelationPredicate>> {
        val hits = TERM_TO_PREDICATE.entries
            .filter { entry -> text.contains(entry.key) }
            .sortedByDescending { entry -> entry.key.length }
        val kept = mutableListOf<Pair<String, RelationPredicate>>()
        for (entry in hits) {
            if (kept.any { (term, _) -> term.contains(entry.key) }) continue
            kept.add(entry.key to entry.value)
        }
        return kept
    }
}
