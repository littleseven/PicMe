package com.mamba.picme.domain.person

/**
 * 人物查询解析结果
 *
 * @param personIds 解析出的去重人物 ID 集合（≥2 时调用方应走共现查询）
 * @param descriptions 人可读的来源说明（命中名字 / 称谓 → 人物），供日志与聊天回复引用
 * @param isAmbiguous 存在歧义：某称谓命中多条关系（已取并集）或同名命中多个人物
 */
data class ResolvedPersons(
    val personIds: Set<Long>,
    val descriptions: List<String>,
    val isAmbiguous: Boolean
)

/**
 * 人物查询解析器 —— 原始查询字符串 → 人物 ID 集合
 *
 * 解析全部在端侧确定性完成（理由是确定性与查询性能）：
 * 1. 已命名人物：query 包含某人名 → 命中
 * 2. 亲属称谓：[KinshipLexicon] 命中 → 经 [PersonRepository.resolveByKinship] 解到人物；
 *    一个称谓命中多条关系取并集并标记歧义
 * 3. "我"：仅在出现合拍电影 Pattern（"我和/和我/与我"）且已有其他人物命中时，
 *    才将本人计入（避免"我想看猫"这类第一人称查询误带本人照片）
 */
class PersonQueryResolver(
    private val personRepository: PersonRepository
) {

    suspend fun resolve(query: String): ResolvedPersons {
        if (query.isBlank()) {
            return ResolvedPersons(emptySet(), emptyList(), isAmbiguous = false)
        }

        val personIds = linkedSetOf<Long>()
        val descriptions = mutableListOf<String>()
        var isAmbiguous = false

        // 1. 已命名人物命中（同名多人物取并集并标记歧义）
        val namedPersons = personRepository.getNamedPersons()
        val nameHits = namedPersons.filter { person ->
            val name = person.name
            !name.isNullOrBlank() && query.contains(name)
        }
        nameHits.groupBy { person -> person.name }.forEach { (name, persons) ->
            personIds.addAll(persons.map { person -> person.personId })
            if (persons.size > 1) {
                isAmbiguous = true
                descriptions.add("$name（同名 ${persons.size} 人，已取并集）")
            } else {
                descriptions.add(name.orEmpty())
            }
        }

        // 2. 亲属称谓命中（一词多关系取并集并标记歧义）
        for ((term, _) in KinshipLexicon.scan(query)) {
            val persons = personRepository.resolveByKinship(term)
            if (persons.isEmpty()) continue
            personIds.addAll(persons.map { person -> person.personId })
            val names = persons.map { person -> person.name ?: "#${person.personId}" }
            if (persons.size > 1) {
                isAmbiguous = true
                descriptions.add("$term → ${names.joinToString("、")}（多人，已取并集）")
            } else {
                descriptions.add("$term → ${names.first()}")
            }
        }

        // 3. "我"：仅合拍 Pattern 且已有其他人物命中时计入
        if (personIds.isNotEmpty() && SELF_JOIN_PATTERNS.any { pattern -> query.contains(pattern) }) {
            val self = personRepository.getSelfPerson()
            if (self != null && self.personId !in personIds) {
                personIds.add(self.personId)
                descriptions.add("我")
            }
        }

        return ResolvedPersons(personIds, descriptions, isAmbiguous)
    }

    companion object {
        /** 合拍意图 Pattern："X 和我/我和 X/与我" */
        private val SELF_JOIN_PATTERNS = listOf("我和", "和我", "与我", "我跟", "跟我")
    }
}
