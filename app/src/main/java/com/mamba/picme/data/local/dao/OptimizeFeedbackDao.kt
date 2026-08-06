package com.mamba.picme.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mamba.picme.data.local.entity.OptimizeFeedbackEntity

@Dao
interface OptimizeFeedbackDao {

    @Insert
    suspend fun insert(feedback: OptimizeFeedbackEntity)

    @Query("SELECT * FROM optimize_feedback ORDER BY created_at DESC")
    suspend fun getAll(): List<OptimizeFeedbackEntity>

    /** Phase 2 个性化用：取某场景的用户手选记录 */
    @Query("SELECT * FROM optimize_feedback WHERE scene = :scene AND selection_source = 'user'")
    suspend fun getUserPicksForScene(scene: String): List<OptimizeFeedbackEntity>
}
