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
    
     @Query("SELECT * FROM persons ORDER BY faceCount DESC")
    suspend fun getAllPersons(): List<PersonEntity>

    @Query("SELECT * FROM persons WHERE personId = :personId")
    suspend fun getPerson(personId: Long): PersonEntity?

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

    @Insert
    suspend fun insertEmbedding(embedding: FaceEmbeddingEntity): Long

    @Query("SELECT * FROM face_embeddings WHERE personId = :personId")
    suspend fun getEmbeddingsByPerson(personId: Long): List<FaceEmbeddingEntity>

    @Query("SELECT * FROM face_embeddings WHERE personId IS NULL")
    suspend fun getUnassignedEmbeddings(): List<FaceEmbeddingEntity>

    @Query("SELECT * FROM face_embeddings")
    suspend fun getAllEmbeddings(): List<FaceEmbeddingEntity>

    @Query("UPDATE face_embeddings SET personId = :personId WHERE embeddingId = :embeddingId")
    suspend fun assignEmbedding(embeddingId: Long, personId: Long)

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
