package com.mamba.picme.domain.person

import com.mamba.picme.data.local.dao.PersonDao
import com.mamba.picme.data.local.dao.PersonRelationDao
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.local.entity.PersonRelationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 人物领域仓库 —— 人物命名、"我"标记、人物关系图谱的唯一收口
 *
 * 命名对话框与聊天声明工具都必须走这里，不直调 DAO。
 * 关系声明幂等：同一对人物重复声明即覆盖（纠错通路 = 重新声明）。
 */
class PersonRepository(
    private val personDao: PersonDao,
    private val relationDao: PersonRelationDao
) {

    /** 关系声明结果（枚举所有分支，调用方穷举处理） */
    sealed interface DeclareRelationResult {
        /** 声明成功（含覆盖旧关系），relation 为落库后的实体 */
        data class Declared(val relation: PersonRelationEntity) : DeclareRelationResult

        /** 尚未标记"我"本人，无法建立指向我的关系 */
        data object SelfNotDeclared : DeclareRelationResult

        /** subject 人物不存在 */
        data object SubjectNotFound : DeclareRelationResult
    }

    suspend fun renamePerson(personId: Long, name: String) {
        personDao.updatePersonName(personId, name)
    }

    /** 标记某人物为"我"（全局唯一，自动清除旧标记） */
    suspend fun setSelf(personId: Long) {
        personDao.clearSelfFlags()
        personDao.setSelf(personId, true)
    }

    suspend fun clearSelf() {
        personDao.clearSelfFlags()
    }

    suspend fun getSelfPerson(): PersonEntity? = personDao.getSelfPerson()

    /**
     * 声明"subject 是我的 predicate"（如：小宝 是我的 女儿）。
     *
     * 幂等覆盖：同一对人物已存在任意旧关系时先删除再写入新关系。
     */
    suspend fun declareRelation(
        subjectPersonId: Long,
        predicate: RelationPredicate,
        source: RelationSource
    ): DeclareRelationResult {
        val subject = personDao.getPerson(subjectPersonId)
            ?: return DeclareRelationResult.SubjectNotFound
        val self = personDao.getSelfPerson()
            ?: return DeclareRelationResult.SelfNotDeclared

        relationDao.deleteByPair(subjectPersonId = subject.personId, objectPersonId = self.personId)
        val relation = PersonRelationEntity(
            subjectPersonId = subject.personId,
            objectPersonId = self.personId,
            predicate = predicate.name,
            source = source.name
        )
        val relationId = relationDao.upsert(relation)
        return DeclareRelationResult.Declared(relation.copy(relationId = relationId))
    }

    /**
     * 遗忘"subject 是我的 predicate"关系；返回删除条数（0 = 幂等无操作）。
     */
    suspend fun removeRelation(subjectPersonId: Long, predicate: RelationPredicate): Int {
        val self = personDao.getSelfPerson() ?: return 0
        return relationDao.deleteByPairAndPredicate(
            subjectPersonId = subjectPersonId,
            objectPersonId = self.personId,
            predicate = predicate.name
        )
    }

    /** 遗忘某人物与"我"之间的全部关系；返回删除条数 */
    suspend fun removeAllRelationsOf(subjectPersonId: Long): Int {
        val self = personDao.getSelfPerson() ?: return 0
        return relationDao.deleteByPair(subjectPersonId = subjectPersonId, objectPersonId = self.personId)
    }

    /** 按名字模糊解析人物（"小宝" → 人脸簇） */
    suspend fun resolveByName(name: String): PersonEntity? = personDao.findPersonByName(name)

    /** 全部已命名人物（供 PersonQueryResolver 扫描查询串中的人名命中） */
    suspend fun getNamedPersons(): List<PersonEntity> =
        personDao.getAllPersons().filter { person -> !person.name.isNullOrBlank() }

    /** 查询某人物当前与"我"的关系（对话框回显用） */
    suspend fun getRelationToSelf(subjectPersonId: Long): PersonRelationEntity? {
        val self = personDao.getSelfPerson() ?: return null
        return relationDao.getBySubject(subjectPersonId)
            .firstOrNull { relation -> relation.objectPersonId == self.personId }
    }

    /**
     * 按亲属称谓解析人物集合（"我女儿" → 所有 predicate=CHILD 指向我的人物）。
     * 一个称谓可能命中多条关系（多个孩子），返回并集由调用方决定是否歧义。
     */
    suspend fun resolveByKinship(term: String): List<PersonEntity> {
        val predicate = KinshipLexicon.predicateFor(term) ?: return emptyList()
        val self = personDao.getSelfPerson() ?: return emptyList()
        val relations = relationDao.getByObjectAndPredicate(
            objectPersonId = self.personId,
            predicate = predicate.name
        )
        return relations.mapNotNull { relation -> personDao.getPerson(relation.subjectPersonId) }
    }

    /** 快照导出（Pass 2 重聚前调用，按 personId 原样导出，由调度器翻译成名字） */
    suspend fun exportAllRelations(): List<PersonRelationEntity> = relationDao.getAll()

    /** 快照恢复（重聚后按新 personId 批量写回） */
    suspend fun restoreRelations(relations: List<PersonRelationEntity>) {
        if (relations.isNotEmpty()) {
            relationDao.upsertAll(relations)
        }
    }

    /** 按 relationId 遗忘一条关系（管理界面单条删除）；返回是否命中 */
    suspend fun removeRelationById(relationId: Long): Boolean {
        return relationDao.deleteById(relationId) > 0
    }

    /**
     * 「AI 记忆」页人物关系列表驱动源：指向"我"的关系，联 persons 取名字。
     * 人物/关系表变更时重算（预取 persons map 内存 join，v1 关系量极小）。
     */
    fun observeRelationsToSelf(): Flow<List<RelationDisplayItem>> =
        relationDao.observeAll().map { relations ->
            if (relations.isEmpty()) return@map emptyList()
            val personsById = personDao.getAllPersons().associateBy { person -> person.personId }
            relations.mapNotNull { relation ->
                val subject = personsById[relation.subjectPersonId] ?: return@mapNotNull null
                val obj = personsById[relation.objectPersonId] ?: return@mapNotNull null
                if (!obj.isSelf) return@mapNotNull null
                val predicate = RelationPredicate.fromStored(relation.predicate)
                    ?: return@mapNotNull null
                RelationDisplayItem(
                    relationId = relation.relationId,
                    subjectPersonId = subject.personId,
                    subjectName = subject.name ?: "#${subject.personId}",
                    predicate = predicate
                )
            }
        }
}

/**
 * 人物关系展示项（「AI 记忆」页用）："X 是我的 Y"
 *
 * @property subjectName 人名（subject 端，未命名退化为 "#personId"）
 * @property predicate 关系谓词（本地化标签由 UI 层映射）
 */
data class RelationDisplayItem(
    val relationId: Long,
    val subjectPersonId: Long,
    val subjectName: String,
    val predicate: RelationPredicate
)
