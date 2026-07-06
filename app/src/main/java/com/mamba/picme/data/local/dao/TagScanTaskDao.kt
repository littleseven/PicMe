package com.mamba.picme.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mamba.picme.data.local.entity.TagScanPass
import com.mamba.picme.data.local.entity.TagScanTaskEntity
import com.mamba.picme.data.local.entity.TagScanTaskStatus

@Dao
interface TagScanTaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TagScanTaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<TagScanTaskEntity>): List<Long>

    @Query("SELECT * FROM tag_scan_tasks WHERE id = :id")
    suspend fun getById(id: Long): TagScanTaskEntity?

    /**
     * 获取下一个待执行任务，按优先级、计划时间排序
     */
    @Query(
        """
        SELECT * FROM tag_scan_tasks
        WHERE status = 'PENDING' AND (scheduledAt IS NULL OR scheduledAt <= :now)
        ORDER BY priority ASC, scheduledAt ASC, createdAt ASC
        LIMIT 1
        """
    )
    suspend fun pollNextPending(now: Long = System.currentTimeMillis()): TagScanTaskEntity?

    /**
     * 获取指定会话的下一个待执行任务
     */
    @Query(
        """
        SELECT * FROM tag_scan_tasks
        WHERE sessionId = :sessionId
          AND status = 'PENDING'
          AND (scheduledAt IS NULL OR scheduledAt <= :now)
        ORDER BY priority ASC, scheduledAt ASC, createdAt ASC
        LIMIT 1
        """
    )
    suspend fun pollNextPendingBySession(
        sessionId: String,
        now: Long = System.currentTimeMillis()
    ): TagScanTaskEntity?

    /**
     * 按会话获取待执行任务数
     */
    @Query("SELECT COUNT(*) FROM tag_scan_tasks WHERE sessionId = :sessionId AND status = 'PENDING'")
    suspend fun countPendingBySession(sessionId: String): Int

    /**
     * 按会话获取各状态统计
     */
    @Query(
        """
        SELECT status, COUNT(*) as cnt FROM tag_scan_tasks
        WHERE sessionId = :sessionId
        GROUP BY status
        """
    )
    suspend fun countByStatus(sessionId: String): List<StatusCount>

    /**
     * 按会话获取各状态 × Pass 统计（用于 ETA 估算）
     */
    @Query(
        """
        SELECT status, pass, COUNT(*) as cnt FROM tag_scan_tasks
        WHERE sessionId = :sessionId
        GROUP BY status, pass
        """
    )
    suspend fun countByStatusAndPass(sessionId: String): List<StatusPassCount>

    /**
     * 更新任务为运行中
     */
    @Query(
        """
        UPDATE tag_scan_tasks
        SET status = 'RUNNING', startedAt = :startedAt, attemptCount = attemptCount + 1
        WHERE id = :id
        """
    )
    suspend fun markRunning(id: Long, startedAt: Long = System.currentTimeMillis())

    /**
     * 更新任务为完成
     */
    @Query(
        """
        UPDATE tag_scan_tasks
        SET status = 'COMPLETED', completedAt = :completedAt, errorMessage = NULL
        WHERE id = :id
        """
    )
    suspend fun markCompleted(id: Long, completedAt: Long = System.currentTimeMillis())

    /**
     * 更新任务为失败，并设置重试计划时间
     */
    @Query(
        """
        UPDATE tag_scan_tasks
        SET status = 'FAILED', errorMessage = :errorMessage, scheduledAt = :scheduledAt
        WHERE id = :id
        """
    )
    suspend fun markFailed(id: Long, errorMessage: String?, scheduledAt: Long?)

    /**
     * 批量重置运行中任务为待处理（用于 Service 重建恢复）
     */
    @Query(
        """
        UPDATE tag_scan_tasks
        SET status = 'PENDING', startedAt = NULL
        WHERE status = 'RUNNING'
        """
    )
    suspend fun resetRunningToPending()

    /**
     * 暂停所有指定会话的待处理/运行中任务
     */
    @Query(
        """
        UPDATE tag_scan_tasks
        SET status = 'PAUSED'
        WHERE sessionId = :sessionId AND status IN ('PENDING', 'RUNNING')
        """
    )
    suspend fun pauseSession(sessionId: String)

    /**
     * 恢复指定会话的暂停任务为待处理
     */
    @Query(
        """
        UPDATE tag_scan_tasks
        SET status = 'PENDING'
        WHERE sessionId = :sessionId AND status = 'PAUSED'
        """
    )
    suspend fun resumeSession(sessionId: String)

    /**
     * 取消指定会话的待处理/运行中/暂停任务
     */
    @Query(
        """
        UPDATE tag_scan_tasks
        SET status = 'CANCELLED'
        WHERE sessionId = :sessionId AND status IN ('PENDING', 'RUNNING', 'PAUSED')
        """
    )
    suspend fun cancelSession(sessionId: String)

    /**
     * 取消所有活跃任务
     */
    @Query(
        """
        UPDATE tag_scan_tasks
        SET status = 'CANCELLED'
        WHERE status IN ('PENDING', 'RUNNING', 'PAUSED')
        """
    )
    suspend fun cancelAllActive()

    /**
     * 清理已完成/已取消的旧任务
     */
    @Query(
        """
        DELETE FROM tag_scan_tasks
        WHERE status IN ('COMPLETED', 'CANCELLED') AND completedAt < :before
        """
    )
    suspend fun cleanupOldCompleted(before: Long)

    /**
     * 获取指定会话的所有任务
     */
    @Query("SELECT * FROM tag_scan_tasks WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    suspend fun getTasksBySession(sessionId: String): List<TagScanTaskEntity>

    /**
     * 是否存在活跃任务
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM tag_scan_tasks
            WHERE status IN ('PENDING', 'RUNNING', 'PAUSED')
        )
        """
    )
    suspend fun hasActiveTasks(): Boolean

    /**
     * 查询包含指定状态任务的会话 ID 列表（按最近创建时间倒序）
     */
    @Query(
        """
        SELECT sessionId FROM tag_scan_tasks
        WHERE status = :status
        GROUP BY sessionId
        ORDER BY MAX(createdAt) DESC
        """
    )
    suspend fun findSessionsByStatus(status: TagScanTaskStatus): List<String>
}

data class StatusCount(
    val status: TagScanTaskStatus,
    val cnt: Int
)

data class StatusPassCount(
    val status: TagScanTaskStatus,
    val pass: TagScanPass,
    val cnt: Int
)
