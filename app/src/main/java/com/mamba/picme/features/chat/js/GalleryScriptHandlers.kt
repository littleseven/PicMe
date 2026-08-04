package com.mamba.picme.features.chat.js

import com.mamba.picme.agent.core.js.JsRuntime
import com.mamba.picme.agent.core.js.JsValue
import com.mamba.picme.agent.core.js.asyncHandler
import com.mamba.picme.agent.core.js.toJsValue
import com.mamba.picme.data.local.dao.PersonDao
import com.mamba.picme.domain.tag.ControlledVocab
import com.mamba.picme.domain.tag.scan.TagScanSessionProgress
import com.mamba.picme.domain.usecase.GetGallerySummaryUseCase
import com.mamba.picme.domain.usecase.QueryGalleryMediaUseCase

/**
 * gallery/media 只读 JS handler 的**唯一注册点**。
 *
 * chat 链路（ChatViewModel 持久 JsRuntime）与 Debug 页演示（JsBridgeDemo 临时 JsRuntime）
 * 共用本函数，保证两条链路暴露的 handler 集合一致。新增/修改 handler 只改这里。
 *
 * 全部 handler 为 async：JS 侧只能通过 `await bridge.callAsync(name, args)` 调用
 * （`bridge.call` 会对 async handler 抛 HANDLER_NOT_ASYNC_CALLABLE）。
 * JsBridge.dispatchAsync 已在注入的 scope 内 launch，handler 内直接 suspend 调 UseCase/DAO。
 *
 * JS ↔ 模型的字段转换见 [GalleryJs.kt]（parseQueryFilter / toResultJsValue 等）。
 *
 * @param scanProgressProvider TAG 扫描会话进度快照来源（只读，不触发扫描）。
 *   由调用点注入（handler 层不直接依赖 service 层）；生产实现读
 *   `TagGenerationService.sessionProgress`，测试可注入假快照。
 */
fun registerGalleryHandlers(
    runtime: JsRuntime,
    getGallerySummaryUseCase: GetGallerySummaryUseCase,
    queryGalleryMediaUseCase: QueryGalleryMediaUseCase,
    personDao: PersonDao,
    controlledVocab: ControlledVocab,
    scanProgressProvider: () -> TagScanSessionProgress?,
) {
    // gallery.summary
    runtime.register(asyncHandler("gallery.summary") {
        getGallerySummaryUseCase(includeDetails = true)?.toJsValue() ?: JsValue.Null
    })
    // gallery.query
    runtime.register(asyncHandler("gallery.query") { args ->
        val filter = parseQueryFilter(args)
        queryGalleryMediaUseCase(filter).toResultJsValue()
    })
    // gallery.tags
    runtime.register(asyncHandler("gallery.tags") {
        queryGalleryMediaUseCase.tags().toTagsJsValue()
    })
    // media.meta
    runtime.register(asyncHandler("media.meta") { args ->
        val id = (args as? JsValue.Num)?.value?.toLong()
            ?: (((args as? JsValue.Arr)?.items?.firstOrNull() as? JsValue.Num)?.value?.toLong())
        if (id == null) {
            JsValue.Null
        } else {
            queryGalleryMediaUseCase.meta(id)?.toMetaJsValue() ?: JsValue.Null
        }
    })
    // gallery.timeline
    runtime.register(asyncHandler("gallery.timeline") { args ->
        val (fromMs, toMs, bucketMs) = parseTimelineArgs(args)
        queryGalleryMediaUseCase.timeline(fromMs, toMs, bucketMs).toTimelineJsValue()
    })
    // gallery.intersect
    runtime.register(asyncHandler("gallery.intersect") { args ->
        val req = parseIntersectArgs(args)
        intersectResult(computeIntersect(req))
    })
    // media.batch_meta
    runtime.register(asyncHandler("media.batch_meta") { args ->
        val ids = when (args) {
            is JsValue.Arr -> args.items.mapNotNull { (it as? JsValue.Num)?.value?.toLong() }
            is JsValue.Obj -> {
                (args.entries["ids"] as? JsValue.Arr)?.items
                    ?.mapNotNull { (it as? JsValue.Num)?.value?.toLong() } ?: emptyList()
            }
            else -> emptyList()
        }
        if (ids.isEmpty()) {
            JsValue.Arr(emptyList())
        } else {
            queryGalleryMediaUseCase.batchMeta(ids).toBatchMetaJsValue()
        }
    })
    // gallery.stats_by_tag
    runtime.register(asyncHandler("gallery.stats_by_tag") { args ->
        val filter = parseQueryFilter(args)
        queryGalleryMediaUseCase.tagsByFilter(filter).toTagsJsValue()
    })
    // gallery.stats_by_city：按城市分组的媒体计数分布（只读，DB 层聚合）
    runtime.register(asyncHandler("gallery.stats_by_city") { args ->
        val topN = parseTopN(args)
        queryGalleryMediaUseCase.statsByCity(topN).toTagsJsValue()
    })
    // face.cluster：人脸聚类盘点（只读，不回 embedding 原始数据）
    runtime.register(asyncHandler("face.cluster") { args ->
        val topN = parseTopN(args)
        val topPersons = personDao.getAllPersons().take(topN)
        JsValue.Obj(
            linkedMapOf(
                "clusterCount" to JsValue.Num(personDao.getPersonCount().toDouble()),
                "namedCount" to JsValue.Num(personDao.getNamedPersonCount().toDouble()),
                "totalEmbeddings" to JsValue.Num(personDao.getAllEmbeddingCount().toDouble()),
                "unassignedEmbeddings" to JsValue.Num(personDao.getUnassignedEmbeddingCount().toDouble()),
                "topPersons" to JsValue.Arr(topPersons.map { it.toPersonJsValue() }),
            )
        )
    })
    // tag.audit：打标覆盖与词表外标签审计（只读）
    runtime.register(asyncHandler("tag.audit") { args ->
        val topN = parseTopN(args)
        val stats = queryGalleryMediaUseCase.tagScanAudit()
        val tagDistribution = queryGalleryMediaUseCase.tags(limit = Int.MAX_VALUE)
        val vocabTags = controlledVocab.allCategories + controlledVocab.allCategoriesEn
        JsValue.Obj(
            linkedMapOf(
                "totalMedia" to JsValue.Num(stats.totalMedia.toDouble()),
                "unlabeledCount" to JsValue.Num(stats.unlabeledCount.toDouble()),
                "neverScannedCount" to JsValue.Num(stats.neverScannedCount.toDouble()),
                "lastScanAt" to (stats.lastScanAt?.let { JsValue.Num(it.toDouble()) } ?: JsValue.Null),
                "outOfVocabTags" to outOfVocabTags(tagDistribution, vocabTags, topN).toTagsJsValue(),
            )
        )
    })
    // tag.scan_status：扫描会话状态查询（只读快照，绝不触发/控制扫描）
    runtime.register(asyncHandler("tag.scan_status") {
        scanProgressProvider().toScanStatusJsValue()
    })
}

/** 解析 `{topN?:number}`（默认 [DEFAULT_TOP_N]，上限 [MAX_TOP_N]）。 */
private fun parseTopN(args: JsValue): Int {
    val raw = ((args as? JsValue.Obj)?.entries?.get("topN") as? JsValue.Num)?.value?.toInt()
    return (raw ?: DEFAULT_TOP_N).coerceIn(1, MAX_TOP_N)
}

private const val DEFAULT_TOP_N = 10
private const val MAX_TOP_N = 50
