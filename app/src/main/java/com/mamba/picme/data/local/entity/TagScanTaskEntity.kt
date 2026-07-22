package com.mamba.picme.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * TAG 生成扫描任务实体
 *
 * 用于持久化 3-Pass 混合管道中的原子任务，支持：
 * - 按 Pass 阶段拆分任务
 * - 按 TAG 类别精细控制
 * - 暂停/恢复/取消生命周期
 * - 失败重试与避重
 *
 * 说明：MobileCLIP 语义编码已内联合并到 Pass 1（FACE_DETECTION）。
 * [TagScanPass.MOBILE_CLIP_ENCODING] 保留用于历史任务兼容以及单独重编码场景。
 */
@Entity(
    tableName = "tag_scan_tasks",
    indices = [
        Index(value = ["status", "priority", "scheduledAt"]),
        Index(value = ["mediaId", "pass", "status"]),
        Index(value = ["sessionId", "status"])
    ]
)
data class TagScanTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 扫描会话 ID，用于分组管理 */
    val sessionId: String,

    /** 关联媒体 ID */
    val mediaId: Long,

    /** Pass 阶段 */
    val pass: TagScanPass,

    /** 目标 TAG 类别 JSON 数组，null 表示全部 */
    val tagCategories: String? = null,

    /** 任务状态 */
    val status: TagScanTaskStatus = TagScanTaskStatus.PENDING,

    /** 优先级，数值越小越优先 */
    val priority: Int = 0,

    /** 已尝试次数 */
    val attemptCount: Int = 0,

    /** 任务创建时间 */
    val createdAt: Long = System.currentTimeMillis(),

    /** 计划执行时间（用于失败重试的退避） */
    val scheduledAt: Long? = null,

    /** 开始执行时间 */
    val startedAt: Long? = null,

    /** 完成时间 */
    val completedAt: Long? = null,

    /** 失败原因 */
    val errorMessage: String? = null
)

enum class TagScanPass {
    /** Pass 1: 人脸检测 + 人脸 Embedding + MobileCLIP 语义编码（语义编码已内联合并） */
    FACE_DETECTION,
    /** Pass 2: 全局 DBSCAN 聚类 */
    DBSCAN,
    /** Pass 3: Qwen 图像理解标签生成 */
    QWEN_TAGGING,
    /**
     * MobileCLIP 语义编码（保留以兼容历史任务/单独重编码场景）。
     * 常规扫描已将该阶段内联合并到 [FACE_DETECTION]。
     */
    MOBILE_CLIP_ENCODING
}

enum class TagScanTaskStatus {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}
