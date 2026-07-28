package com.mamba.picme.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.mamba.picme.data.local.entity.FaceEmbeddingEntity
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.model.MediaEntity

@Dao
interface PersonDao {

    @Insert
    suspend fun insertPerson(person: PersonEntity): Long

    @Update
    suspend fun updatePerson(person: PersonEntity)

    @Query("UPDATE persons SET name = :name, updatedAt = :now WHERE personId = :personId")
    suspend fun updatePersonName(personId: Long, name: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE persons SET faceCount = :faceCount, coverMediaId = :coverMediaId, updatedAt = :now WHERE personId = :personId")
    suspend fun updatePersonStats(personId: Long, faceCount: Int, coverMediaId: Long?, now: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM persons WHERE name IS NOT NULL AND name != ''")
    suspend fun getNamedPersonCount(): Int

    @Query("SELECT * FROM persons ORDER BY faceCount DESC")
    suspend fun getAllPersons(): List<PersonEntity>

    @Query("SELECT COUNT(*) FROM persons")
    suspend fun getPersonCount(): Int

    @Query("SELECT * FROM persons WHERE personId = :personId")
    suspend fun getPerson(personId: Long): PersonEntity?

    /** 按人物名称模糊匹配（用于自然语言搜索中按人名找照片） */
    @Query("SELECT * FROM persons WHERE name LIKE '%' || :name || '%' LIMIT 1")
    suspend fun findPersonByName(name: String): PersonEntity?

    @Query("UPDATE persons SET faceCount = faceCount + 1, updatedAt = :now WHERE personId = :personId")
    suspend fun incrementFaceCount(personId: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE persons SET faceCount = MAX(faceCount - 1, 0), updatedAt = :now WHERE personId = :personId")
    suspend fun decrementFaceCount(personId: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE persons SET coverMediaId = :coverMediaId, updatedAt = :now WHERE personId = :personId")
    suspend fun updateCoverMedia(personId: Long, coverMediaId: Long, now: Long = System.currentTimeMillis())

    @Query(
        """
        SELECT m.* FROM media_assets m
        INNER JOIN face_embeddings e ON m.id = e.mediaId
        WHERE e.personId = :personId
        ORDER BY m.captureDate DESC
        """
    )
    suspend fun getMediaByPerson(personId: Long): List<MediaEntity>

    /** 按 personId 取媒体 id（轻量，仅供 gallery.query 的 person 过滤做交集）。 */
    @Query("SELECT DISTINCT mediaId FROM face_embeddings WHERE personId = :personId")
    suspend fun getMediaIdsByPerson(personId: Long): List<Long>

    /**
     * 多人物共现查询：返回同时包含所有指定人物的媒体
     * （HAVING COUNT(DISTINCT personId) = 传入 ids 数，确保每人都出现）
     */
    @Query(
        """
        SELECT m.* FROM media_assets m
        WHERE m.id IN (
            SELECT mediaId FROM face_embeddings
            WHERE personId IN (:personIds)
            GROUP BY mediaId
            HAVING COUNT(DISTINCT personId) = :personCount
        )
        ORDER BY m.captureDate DESC
        """
    )
    suspend fun getMediaByPersonsCooccurrence(personIds: List<Long>, personCount: Int): List<MediaEntity>

    /** 标记/取消"我"本人（is_self 列） */
    @Query("UPDATE persons SET is_self = :isSelf, updatedAt = :now WHERE personId = :personId")
    suspend fun setSelf(personId: Long, isSelf: Boolean, now: Long = System.currentTimeMillis())

    /** 全局唯一"我"约束：设置新 self 前清除旧标记 */
    @Query("UPDATE persons SET is_self = 0 WHERE is_self = 1")
    suspend fun clearSelfFlags()

    @Query("SELECT * FROM persons WHERE is_self = 1 LIMIT 1")
    suspend fun getSelfPerson(): PersonEntity?

    @Insert
    suspend fun insertEmbedding(embedding: FaceEmbeddingEntity): Long

    @Insert
    suspend fun insertEmbeddings(embeddings: List<FaceEmbeddingEntity>): List<Long>

    @Query("SELECT * FROM face_embeddings WHERE personId = :personId")
    suspend fun getEmbeddingsByPerson(personId: Long): List<FaceEmbeddingEntity>

    @Query("SELECT * FROM face_embeddings WHERE personId IS NULL")
    suspend fun getUnassignedEmbeddings(): List<FaceEmbeddingEntity>

    @Query("SELECT * FROM face_embeddings")
    suspend fun getAllEmbeddings(): List<FaceEmbeddingEntity>

    @Query("UPDATE face_embeddings SET personId = :personId WHERE embeddingId = :embeddingId")
    suspend fun assignEmbedding(embeddingId: Long, personId: Long)

    /** 未分配人物的 embedding 数量（内存友好的 COUNT，替代 getUnassignedEmbeddings().size） */
    @Query("SELECT COUNT(*) FROM face_embeddings WHERE personId IS NULL")
    suspend fun getUnassignedEmbeddingCount(): Int

    @Query("SELECT COUNT(*) FROM face_embeddings")
    suspend fun getAllEmbeddingCount(): Int

    @Query("SELECT COUNT(*) FROM face_embeddings WHERE personId = :personId")
    suspend fun getEmbeddingCount(personId: Long): Int

    @Query("DELETE FROM persons WHERE personId = :personId")
    suspend fun deletePerson(personId: Long)

    @Query("UPDATE face_embeddings SET personId = NULL WHERE personId = :personId")
    suspend fun unlinkEmbeddings(personId: Long)

    @Query("SELECT * FROM face_embeddings WHERE mediaId = :mediaId")
    suspend fun getEmbeddingsByMedia(mediaId: Long): List<FaceEmbeddingEntity>

    @Query("DELETE FROM face_embeddings WHERE mediaId = :mediaId")
    suspend fun deleteEmbeddingsByMedia(mediaId: Long)

    /** 按 mediaId 批量更新 personId（用于聚类后分配） */
    @Query("UPDATE face_embeddings SET personId = :personId WHERE mediaId = :mediaId")
    suspend fun assignEmbeddingByMediaId(mediaId: Long, personId: Long)

    /** 按 mediaId 列表批量更新 personId（避免逐条更新阻塞主流程） */
    @Query("UPDATE face_embeddings SET personId = :personId WHERE mediaId IN (:mediaIds)")
    suspend fun assignEmbeddingsByMediaIds(mediaIds: List<Long>, personId: Long)

    /** 清空 face_embeddings 和 persons 表（不删除 trigger 依赖的表） */
    @Query("DELETE FROM face_embeddings")
    suspend fun clearAllEmbeddings()

    @Query("DELETE FROM persons")
    suspend fun clearAllPersons()

    /** 重置所有 embedding 的 personId 为 NULL（重聚类前调用） */
    @Query("UPDATE face_embeddings SET personId = NULL")
    suspend fun resetAllEmbeddingAssignments()
}
