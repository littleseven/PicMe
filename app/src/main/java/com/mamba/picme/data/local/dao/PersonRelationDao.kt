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
