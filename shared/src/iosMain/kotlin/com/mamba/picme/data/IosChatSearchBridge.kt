package com.mamba.picme.data

import com.mamba.picme.agent.core.model.context.SearchIntent

/**
 * Swift 搜索引擎（MediaSearchEngine）→ Kotlin 的结果行 DTO：字段全原始类型，K/N → ObjC 导出友好。
 *
 * 双 id 口径（勿混淆）：
 * - [dbId]：iOS TagDatabase `media_assets` 主键。反馈落库（media_feedback.media_id，
 *   对齐 Android `MediaAsset.id.toString()`）与 refine in-set（limitToIds）用此口径；
 * - [localIdentifier]：PHAsset localIdentifier = `MediaAsset.uri`；`MediaAsset.id` 由 Kotlin 侧
 *   用 `localIdentifier.hashCode()` 派生（与 [IosMediaRepository] / Chat MediaCardRow 的
 *   javaHashCode 映射同一 id 空间，仅进程内稳定、用于展示定位）。
 */
data class IosSearchResultItem(
    /** media_assets 主键（feedback / limitToIds 口径）。 */
    val dbId: Long,
    /** PHAsset.localIdentifier，同时作 MediaAsset.uri（对齐 IosMediaRepository 语义）。 */
    val localIdentifier: String,
    /** "PHOTO" | "VIDEO"（字符串而非枚举，避免 K/N 枚举导出名碰撞）。 */
    val mediaType: String,
    val captureDateMs: Long,
    val durationMs: Long? = null,
    val fileName: String,
    val hasFace: Boolean = false,
    /** 标签 JSON（含 `tags` 数组，契约 §4.6）；null = 未打标。 */
    val labels: String? = null,
    val ocrText: String? = null,
    val locationName: String? = null,
    val city: String? = null
)

/**
 * Swift（MediaSearchEngine）→ Kotlin 的 chat 搜索桥协议。由 iosApp `PhSearchBridge`（Swift/NSObject）实现。
 *
 * 与 [IosMediaRepositoryBridge] 同一 SharedBridge 约定（kmp-ios-interop skill）：
 * - Swift 实现侧绝不抛异常跨边界——未声明 @Throws 的异常逃逸会 signal 6（SIGABRT）；
 * - 失败一律用空集合表达；[search] 的 completion **必须**被调用（成功或失败），
 *   否则 Kotlin 侧 suspendCancellableCoroutine 永久挂起。
 *
 * 行为契约 SSOT：`tmp/ios-follow/gallery-search/contracts.md` §9（Chat 搜索链路）。
 */
interface IosChatSearchBridge {

    /**
     * 搜索本地相册（异步：Swift 侧 async 引擎完成后经 [completion] 回传）。
     *
     * - [intent] == null：全文搜索（契约 §2.2 query 管线，Swift `MediaSearchEngine.search(query:lang:)`）；
     * - [intent] != null：结构化过滤（SearchIntent → SearchFilter 字段映射在 Swift 侧完成，
     *   契约 §9.3 含时间词清洗 sanitizeTimeKeywords）；
     * - [limitToDbIds] 非 null：结果限制在该 media_assets 主键集合内（refine 结构化精确交集，契约 §9.2）。
     *
     * 实现侧不得抛异常；失败回传空列表。
     */
    fun search(
        query: String,
        intent: SearchIntent?,
        limitToDbIds: List<Long>?,
        completion: (List<IosSearchResultItem>) -> Unit
    )

    /**
     * 反馈落库（media_feedback 表，契约 §8；同步调用）。
     *
     * @param mediaId media_assets 主键字符串（[IosSearchResultItem.dbId]）
     * @param feedbackType `FeedbackAction.name.lowercase()`（"like"/"dislike"/"more_like_this"）
     * @param query 最近一轮搜索 query（⚠️ query_text 精确等值匹配，契约 §8/R10）
     * @param sessionId 会话 id（AgentContext.memorySessionId）
     */
    fun recordFeedback(mediaId: String, feedbackType: String, query: String, sessionId: String)
}
