package com.mamba.picme.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mamba.picme.data.local.entity.PersonRelationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonRelationDao {

    /**
     * 幂等写入：命中 (subject, predicate, object) 唯一索引即整行覆盖。
     * 返回行 id（覆盖时为新行 id）。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(relation: PersonRelationEntity): Long

    /** 快照恢复用：批量写回（冲突覆盖） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(relations: List<PersonRelationEntity>): List<Long>

    @Query("SELECT * FROM person_relations WHERE subjectPersonId = :subjectPersonId")
    suspend fun getBySubject(subjectPersonId: Long): List<PersonRelationEntity>

    @Query("SELECT * FROM person_relations WHERE subjectPersonId = :subjectPersonId AND predicate = :predicate")
    suspend fun getBySubjectAndPredicate(subjectPersonId: Long, predicate: String): List<PersonRelationEntity>

    /** 以 object 端反查（如"我"的所有某种关系：objectPersonId = 我, predicate = CHILD） */
    @Query("SELECT * FROM person_relations WHERE objectPersonId = :objectPersonId AND predicate = :predicate")
    suspend fun getByObjectAndPredicate(objectPersonId: Long, predicate: String): List<PersonRelationEntity>

    /** 以 object 端按谓词集合反查（谓词族扩展查询：女儿 → {DAUGHTER, CHILD}） */
    @Query("SELECT * FROM person_relations WHERE objectPersonId = :objectPersonId AND predicate IN (:predicates)")
    suspend fun getByObjectAndPredicates(objectPersonId: Long, predicates: List<String>): List<PersonRelationEntity>

    /** 指向某人物且带自定义称呼的关系（查询解析的自定义称呼匹配源） */
    @Query("SELECT * FROM person_relations WHERE objectPersonId = :objectPersonId AND customLabel IS NOT NULL AND customLabel != ''")
    suspend fun getByObjectWithCustomLabel(objectPersonId: Long): List<PersonRelationEntity>

    /** 管理界面单条编辑：只改谓词与自定义称呼，保留 source / createdAt */
    @Query("UPDATE person_relations SET predicate = :predicate, customLabel = :customLabel, updatedAt = :updatedAt WHERE relationId = :relationId")
    suspend fun updateRelation(relationId: Long, predicate: String, customLabel: String?, updatedAt: Long): Int

    /** 按 subject 人物名字模糊反查关系 */
    @Query(
        """
        SELECT r.* FROM person_relations r
        INNER JOIN persons p ON p.personId = r.subjectPersonId
        WHERE p.name LIKE '%' || :name || '%'
        """
    )
    suspend fun getBySubjectNameLike(name: String): List<PersonRelationEntity>

    @Query("SELECT * FROM person_relations WHERE relationId = :relationId")
    suspend fun getById(relationId: Long): PersonRelationEntity?

    /** 快照导出：全量关系 */
    @Query("SELECT * FROM person_relations")
    suspend fun getAll(): List<PersonRelationEntity>

    /** 管理界面列表驱动源（Room 自动在表变更时重发） */
    @Query("SELECT * FROM person_relations")
    fun observeAll(): Flow<List<PersonRelationEntity>>

    /** 删除某对人物之间的全部关系（重新声明前的覆盖清理） */
    @Query("DELETE FROM person_relations WHERE subjectPersonId = :subjectPersonId AND objectPersonId = :objectPersonId")
    suspend fun deleteByPair(subjectPersonId: Long, objectPersonId: Long): Int

    @Query("DELETE FROM person_relations WHERE subjectPersonId = :subjectPersonId AND objectPersonId = :objectPersonId AND predicate = :predicate")
    suspend fun deleteByPairAndPredicate(subjectPersonId: Long, objectPersonId: Long, predicate: String): Int

    @Query("DELETE FROM person_relations WHERE relationId = :relationId")
    suspend fun deleteById(relationId: Long): Int

    @Query("DELETE FROM person_relations")
    suspend fun clearAll()
}
