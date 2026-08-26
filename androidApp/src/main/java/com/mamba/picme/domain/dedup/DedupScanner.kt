package com.mamba.picme.domain.dedup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.mamba.picme.core.common.Logger
import com.mamba.picme.core.common.PerceptualHash
import com.mamba.picme.data.local.DedupHashDao
import com.mamba.picme.data.local.DedupHashEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive

/**
 * 视觉聚类纯函数（可 JVM 单测）：pHash 并查集聚类 → 可选按拍摄时间窗切桶 →
 * KeepPolicyEngine 分类 + BEST_QUALITY 排序，keepUri = 排序后首张。
 *
 * [timeWindowMs] 非空（SCENE 阶段）时：组内按 captureDate 升序，相邻间隔
 * 超过时间窗则切桶，仅保留 ≥2 张的桶；为空（VISUAL）时整簇一组。
 */
internal fun clusterVisual(
    hashed: List<DedupMember>,
    threshold: Int,
    timeWindowMs: Long?,
    level: DedupLevel,
): List<DedupGroup> =
    clusterVisualBuckets(hashed, threshold, timeWindowMs).map { bucket -> buildGroup(level, bucket) }

/**
 * pHash 并查集聚类 → 可选时间窗切桶，返回 ≥2 张的成员桶（不成组）。
 * 与 [clusterVisual] 分离，供扫描器在调用侧按簇分片处理：每簇之间可
 * 让出协程/检查取消。要求 [hashed] 全部已带 pHash。
 */
internal fun clusterVisualBuckets(
    hashed: List<DedupMember>,
    threshold: Int,
    timeWindowMs: Long?,
): List<List<DedupMember>> {
    require(hashed.all { member -> member.phash != null }) { "phash must be computed for all members" }
    if (hashed.size < 2) return emptyList()
    val clusters = PerceptualHash.clusterByHamming(
        hashed.map { member -> member.phash ?: 0L },
        threshold,
    )
    val buckets = mutableListOf<List<DedupMember>>()
    for (cluster in clusters) {
        val members = cluster.map { index -> hashed[index] }
        if (timeWindowMs != null) {
            for (bucket in splitByTimeWindow(members, timeWindowMs)) {
                if (bucket.size >= 2) buckets += bucket
            }
        } else {
            // clusterByHamming 仅返回 size ≥ 2 的簇，无需再过滤
            buckets += members
        }
    }
    return buckets
}

private fun splitByTimeWindow(
    members: List<DedupMember>,
    timeWindowMs: Long,
): List<List<DedupMember>> {
    val buckets = mutableListOf<List<DedupMember>>()
    var current = mutableListOf<DedupMember>()
    for (member in members.sortedBy { m -> m.captureDate }) {
        val last = current.lastOrNull()
        if (last != null && member.captureDate - last.captureDate > timeWindowMs) {
            buckets += current
            current = mutableListOf()
        }
        current += member
    }
    if (current.isNotEmpty()) buckets += current
    return buckets
}

private fun buildGroup(level: DedupLevel, raw: List<DedupMember>): DedupGroup {
    val classified = KeepPolicyEngine.classify(raw)
    val sorted = KeepPolicyEngine.recommend(KeepPolicy.BEST_QUALITY, classified)
    // 组内容类型取成员众数（VISUAL 已按 contentType 分桶，成员同质；EXACT 跨路径拷贝可能混合）
    val contentType = sorted.groupBy { member -> member.contentType }
        .maxByOrNull { entry -> entry.value.size }
        ?.key ?: DedupContentType.GENERAL
    return DedupGroup(
        id = DedupGroup.stableId(level, sorted.map { member -> member.uri }),
        level = level,
        members = sorted,
        keepUri = sorted.first().uri,
        contentType = contentType,
        autoPreselected = autoPreselectedFor(level, contentType),
    )
}

/** spec §10.3 默认预选口径：EXACT→预选；VISUAL 仅 SCREENSHOT/DOCUMENT 不预选；SCENE 一律逐组确认。 */
internal fun autoPreselectedFor(level: DedupLevel, contentType: DedupContentType): Boolean =
    when (level) {
        DedupLevel.EXACT -> true
        DedupLevel.VISUAL ->
            contentType != DedupContentType.SCREENSHOT && contentType != DedupContentType.DOCUMENT
        DedupLevel.SCENE -> false
    }

/**
 * VISUAL 聚类按 contentType 分桶（spec §10.5：跨桶不成组），SCREENSHOT 桶用收紧阈值
 * [screenshotVisualThreshold]，其余桶用 [visualThreshold]。可 JVM 单测。
 */
internal fun clusterVisualByContentType(
    hashed: List<DedupMember>,
    visualThreshold: Int,
    screenshotVisualThreshold: Int,
): List<DedupGroup> =
    hashed.groupBy { member -> member.contentType }.flatMap { entry ->
        val threshold = if (entry.key == DedupContentType.SCREENSHOT) {
            screenshotVisualThreshold
        } else {
            visualThreshold
        }
        clusterVisual(entry.value, threshold, timeWindowMs = null, level = DedupLevel.VISUAL)
    }

/**
 * 流式去重扫描器：哈希（MD5 / pHash）分批准备 + Room 缓存复用，随后按
 * [DedupScanConfig.levels] 依序执行 EXACT / VISUAL / SCENE 阶段，逐组流出
 * [DedupScanEvent]。全部计算 100% 端侧（[PRIVACY]）。
 *
 * 返回 cold flow；调用方负责 `flowOn(Dispatchers.IO)`，flow 体内直接做阻塞 I/O。
 * 支持 [pauseRequested] / [resume] 暂停恢复（哈希每批、成组每阶段/每簇检查）；
 * 协程取消时经 `onCompletion` 补发 [DedupScanEvent.Cancelled]（onCompletion 是
 * flow 取消后唯一合法的补发点，flow 体内 emit 会因 invariant 检查失败）。
 */
class DedupScanner(
    private val context: Context,
    private val hashDao: DedupHashDao,
) : DedupScanController {

    /** 扫描输入：媒体元数据 + modifiedAt（缓存失效判定依据）+ 内容类型信号（取数阶段顺带判定）。 */
    data class ScanItem(
        val uri: String,
        val sizeBytes: Long,
        val mime: String,
        val captureDate: Long,
        val modifiedAt: Long,
        val aestheticScore: Float? = null,
        val contentType: DedupContentType = DedupContentType.GENERAL,
        val faceQualityScore: Float? = null,
    )

    @Volatile
    override var pauseRequested: Boolean = false

    override fun resume() {
        pauseRequested = false
    }

    override fun scan(items: List<ScanItem>, config: DedupScanConfig): Flow<DedupScanEvent> = flow {
        runScan(items, config)
    }.onCompletion { cause ->
        if (cause is CancellationException) {
            emit(DedupScanEvent.Cancelled)
        }
    }

    private suspend fun FlowCollector<DedupScanEvent>.runScan(
        items: List<ScanItem>,
        config: DedupScanConfig,
    ) {
        val phases = config.levels.sorted()
        if (phases.isEmpty()) {
            emit(DedupScanEvent.Done(emptyList()))
            return
        }
        var currentPhase = phases.first()
        val needMd5 = DedupLevel.EXACT in phases
        val needPhash = DedupLevel.VISUAL in phases || DedupLevel.SCENE in phases

        // 1. 哈希准备：分批，命中缓存（modifiedAt + sizeBytes 一致且所需字段齐备）则复用
        // 首帧进度：让 UI 立即拿到总数，避免首批（500 张/批）完成前显示 0/0
        emit(DedupScanEvent.Progress(currentPhase, 0, items.size))
        val members = ArrayList<DedupMember>(items.size)
        var scanned = 0
        for (batch in items.chunked(HASH_BATCH_SIZE)) {
            awaitIfPaused()
            val cachedByUri = hashDao.getByUris(batch.map { item -> item.uri })
                .associateBy { entity -> entity.uri }
            val now = System.currentTimeMillis()
            val toUpsert = mutableListOf<DedupHashEntity>()
            var sincePauseCheck = 0
            for (item in batch) {
                // 批内 500 张顺序 I/O 期间的取消/暂停检查点：取消每张都查（开销可忽略），
                // 暂停每 PAUSE_CHECKPOINT_ITEMS 张查一次（awaitIfPaused 是 suspend 轮询）
                currentCoroutineContext().ensureActive()
                if (++sincePauseCheck >= PAUSE_CHECKPOINT_ITEMS) {
                    sincePauseCheck = 0
                    awaitIfPaused()
                }
                val cached = cachedByUri[item.uri]
                    ?.takeIf { entity ->
                        entity.modifiedAt == item.modifiedAt && entity.sizeBytes == item.sizeBytes
                    }
                var md5 = cached?.md5
                var phash = cached?.phash
                var pixelArea = cached?.pixelArea ?: 0
                var dirty = cached == null
                if (needMd5 && md5 == null) {
                    md5 = md5FromUri(item.uri)
                    dirty = true
                }
                if (needPhash && phash == null) {
                    val visual = phashFromUri(item.uri)
                    if (visual != null) {
                        phash = visual.first
                        pixelArea = visual.second
                    }
                    dirty = true
                }
                if (dirty) {
                    toUpsert += DedupHashEntity(
                        uri = item.uri,
                        sizeBytes = item.sizeBytes,
                        mime = item.mime,
                        modifiedAt = item.modifiedAt,
                        md5 = md5,
                        phash = phash,
                        pixelArea = pixelArea,
                        hashedAt = now,
                    )
                }
                members += DedupMember(
                    uri = item.uri,
                    sizeBytes = item.sizeBytes,
                    mime = item.mime,
                    captureDate = item.captureDate,
                    modifiedAt = item.modifiedAt,
                    pixelArea = pixelArea,
                    aestheticScore = item.aestheticScore,
                    role = VersionRole.UNKNOWN,
                    md5 = md5,
                    phash = phash,
                    contentType = item.contentType,
                    faceQualityScore = item.faceQualityScore,
                )
            }
            if (toUpsert.isNotEmpty()) {
                hashDao.upsertAll(toUpsert)
            }
            scanned += batch.size
            emit(DedupScanEvent.Progress(currentPhase, scanned, items.size))
        }

        // 2. 分阶段成组（每阶段前检查暂停/取消；视觉阶段按簇分片，每簇让出协程）
        val allGroups = mutableListOf<DedupGroup>()
        phases.forEachIndexed { index, phase ->
            awaitIfPaused()
            currentCoroutineContext().ensureActive()
            currentPhase = phase
            emit(DedupScanEvent.PhaseChanged(phase, index + 1, phases.size))
            when (phase) {
                DedupLevel.EXACT -> {
                    for (group in exactGroups(members)) {
                        allGroups += group
                        emit(DedupScanEvent.GroupFound(group))
                    }
                }
                DedupLevel.VISUAL -> {
                    // spec §10.5：按 contentType 分桶聚类（跨桶不成组），截图桶用收紧阈值；
                    // 逐组流出，每组处理前检查取消
                    val hashedMembers = members.filter { member -> member.phash != null }
                    for (group in clusterVisualByContentType(
                        hashed = hashedMembers,
                        visualThreshold = config.visualThreshold,
                        screenshotVisualThreshold = config.screenshotVisualThreshold,
                    )) {
                        currentCoroutineContext().ensureActive()
                        if (allSameMd5(group)) continue // 与 EXACT 组完全重复
                        allGroups += group
                        emit(DedupScanEvent.GroupFound(group))
                    }
                }
                DedupLevel.SCENE -> streamVisualGroups(
                    hashed = members.filter { member -> member.phash != null },
                    threshold = config.sceneThreshold,
                    timeWindowMs = config.sceneTimeWindowMs,
                    level = DedupLevel.SCENE,
                    skipExactOverlap = false,
                    allGroups = allGroups,
                )
            }
        }

        emit(DedupScanEvent.Done(allGroups))
    }

    /** SCENE 阶段按簇分片成组并逐组流出：每簇处理前检查取消，emit 本身即挂起让出点。 */
    private suspend fun FlowCollector<DedupScanEvent>.streamVisualGroups(
        hashed: List<DedupMember>,
        threshold: Int,
        timeWindowMs: Long?,
        level: DedupLevel,
        skipExactOverlap: Boolean,
        allGroups: MutableList<DedupGroup>,
    ) {
        for (bucket in clusterVisualBuckets(hashed, threshold, timeWindowMs)) {
            currentCoroutineContext().ensureActive()
            val group = buildGroup(level, bucket)
            if (skipExactOverlap && allSameMd5(group)) continue // 与 EXACT 组完全重复
            allGroups += group
            emit(DedupScanEvent.GroupFound(group))
        }
    }

    /** 组内所有成员 md5 均非空且全部相同（与 EXACT 组完全重复）才过滤；任一成员 md5 缺失则保留。 */
    private fun allSameMd5(group: DedupGroup): Boolean {
        val md5s = group.members.map { member -> member.md5 }
        return md5s.all { md5 -> md5 != null } && md5s.distinct().size == 1
    }

    private fun exactGroups(members: List<DedupMember>): List<DedupGroup> {
        val groups = mutableListOf<DedupGroup>()
        members
            .filter { member -> member.md5 != null }
            .groupBy { member -> member.sizeBytes to member.mime }
            .filter { entry -> entry.value.size >= 2 }
            .forEach { entry ->
                entry.value
                    .groupBy { member -> member.md5!! }
                    .filter { md5Entry -> md5Entry.value.size >= 2 }
                    .forEach { md5Entry -> groups += buildGroup(DedupLevel.EXACT, md5Entry.value) }
            }
        return groups
    }

    private suspend fun awaitIfPaused() {
        while (pauseRequested && currentCoroutineContext().isActive) {
            delay(PAUSE_POLL_MS)
        }
    }

    /** 流式 MD5；失败/不可读返回 null。 */
    private fun md5FromUri(uri: String): String? = try {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            PerceptualHash.md5Hex(input)
        }
    } catch (e: Exception) {
        Logger.w(TAG, "md5 failed for $uri", e)
        null
    }

    /** 返回 (pHash, 原始 width*height)；解码失败返回 null。降采样到 32×32 后转灰度。 */
    private fun phashFromUri(uri: String): Pair<Long, Int>? {
        val parsed = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.contentResolver.openInputStream(parsed)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
        } catch (e: Exception) {
            Logger.w(TAG, "phash bounds failed for $uri", e)
            return null
        }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        val target = PerceptualHash.PHASH_SIZE
        val sample = maxOf(1, maxOf(width, height) / target)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded: Bitmap = try {
            context.contentResolver.openInputStream(parsed)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            } ?: return null
        } catch (e: Exception) {
            Logger.w(TAG, "phash decode failed for $uri", e)
            return null
        }
        // 缩放 OOM 时 decoded 尚未回收，catch 里兜底回收（OOM 是 Error，不进下面的 Exception 分支）
        val scaled: Bitmap = try {
            if (decoded.width != target || decoded.height != target) {
                val created = Bitmap.createScaledBitmap(decoded, target, target, false)
                if (created !== decoded) decoded.recycle()
                created
            } else {
                decoded
            }
        } catch (e: OutOfMemoryError) {
            decoded.recycle()
            Logger.w(TAG, "phash scale failed for $uri", e)
            return null
        }
        return try {
            val pixels = IntArray(target * target)
            scaled.getPixels(pixels, 0, target, 0, 0, target, target)
            val gray = DoubleArray(target * target) { index ->
                val pixel = pixels[index]
                0.299 * ((pixel shr 16) and 0xFF) +
                    0.587 * ((pixel shr 8) and 0xFF) +
                    0.114 * (pixel and 0xFF)
            }
            PerceptualHash.phash(gray, target) to (width * height)
        } catch (e: Exception) {
            Logger.w(TAG, "phash compute failed for $uri", e)
            null
        } finally {
            scaled.recycle()
        }
    }

    private companion object {
        const val TAG = "PoLang:Dedup"

        /** 哈希分批大小：必须 < 999（SQLite IN 参数上限，getByUris 走 `uri IN (:uris)` 查询）。 */
        const val HASH_BATCH_SIZE = 500
        const val PAUSE_POLL_MS = 200L

        /** 批内暂停检查间隔（张）：awaitIfPaused 为 suspend 轮询，不宜每张调用。 */
        const val PAUSE_CHECKPOINT_ITEMS = 50
    }
}
