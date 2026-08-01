package com.mamba.picme.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mamba.picme.data.local.entity.PhotoEditRecipeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoEditRecipeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PhotoEditRecipeEntity)

    @Query("SELECT * FROM photo_edit_recipes WHERE outputUri = :outputUri LIMIT 1")
    suspend fun getByOutputUri(outputUri: String): PhotoEditRecipeEntity?

    /** 快照导出：全量配方（备份用） */
    @Query("SELECT * FROM photo_edit_recipes")
    suspend fun getAll(): List<PhotoEditRecipeEntity>

    @Query("SELECT * FROM photo_edit_recipes WHERE outputUri = :outputUri LIMIT 1")
    fun observeByOutputUri(outputUri: String): Flow<PhotoEditRecipeEntity?>

    @Query("DELETE FROM photo_edit_recipes WHERE outputUri = :outputUri")
    suspend fun delete(outputUri: String)

    @Query("DELETE FROM photo_edit_recipes")
    suspend fun deleteAll()
}
