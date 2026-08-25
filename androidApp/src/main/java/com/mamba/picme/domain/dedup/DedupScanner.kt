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
): List<DedupGroup> {
    if (hashed.size < 2) return emptyList()
    val clusters = PerceptualHash.clusterByHamming(
        hashed.map { member -> member.phash ?: 0L },
        threshold,
    )
    val groups = mutableListOf<DedupGroup>()
    for (cluster in clusters) {
        val members = cluster.map { index -> hashed[index] }
        val buckets: List<List<DedupMember>> = if (timeWindowMs != null) {
            splitByTimeWindow(members, timeWindowMs)
        } else {
            listOf(members)
        }
        for (bucket in buckets) {
            if (bucket.size < 2) continue
            groups += buildGroup(level, bucket)
        }
    }
    return groups
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
    return DedupGroup(
        id = DedupGroup.stableId(level, sorted.map { member -> member.uri }),
        level = level,
        members = sorted,
        keepUri = sorted.first().uri,
    )
}

/**
 * 流式去重扫描器：哈希（MD5 / pHash）分批准备 + Room 缓存复用，随后按
 * [DedupScanConfig.levels] 依序执行 EXACT / VISUAL / SCENE 阶段，逐组流出
 * [DedupScanEvent]。全部计算 100% 端侧（[PRIVACY]）。
 *
 * 返回 cold flow；调用方负责 `flowOn(Dispatchers.IO)`，flow 体内直接做阻塞 I/O。
 * 支持 [pauseRequested] / [resume] 暂停恢复；协程取消时经 `onCompletion`
 * 补发 [DedupScanEvent.Cancelled]（onCompletion 是 flow 取消后唯一合法的
 * 补发点，flow 体内 emit 会因 invariant 检查失败）。
 */
class DedupScanner(
    private val context: Context,
    private val hashDao: DedupHashDao,
) {

    /** 扫描输入：媒体元数据 + modifiedAt（缓存失效判定依据）。 */
    data class ScanItem(
        val uri: String,
        val sizeBytes: Long,
        val mime: String,
        val captureDate: Long,
        val modifiedAt: Long,
        val aestheticScore: Float? = null,
    )

    @Volatile
    var pauseRequested: Boolean = false

    fun resume() {
        pauseRequested = false
    }

    fun scan(items: List<ScanItem>, config: DedupScanConfig): Flow<DedupScanEvent> = flow {
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

        // 1. 哈希准备：分批 500，命中缓存（modifiedAt + sizeBytes 一致且所需字段齐备）则复用
        val members = ArrayList<DedupMember>(items.size)
        var scanned = 0
        for (batch in items.chunked(HASH_BATCH_SIZE)) {
            awaitIfPaused()
            val cachedByUri = hashDao.getByUris(batch.map { item -> item.uri })
                .associateBy { entity -> entity.uri }
            val now = System.currentTimeMillis()
            val toUpsert = mutableListOf<DedupHashEntity>()
            for (item in batch) {
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
                )
            }
            if (toUpsert.isNotEmpty()) {
                hashDao.upsertAll(toUpsert)
            }
            scanned += batch.size
            emit(DedupScanEvent.Progress(currentPhase, scanned, items.size))
        }

        // 2. 分阶段成组
        val allGroups = mutableListOf<DedupGroup>()
        phases.forEachIndexed { index, phase ->
            currentPhase = phase
            emit(DedupScanEvent.PhaseChanged(phase, index + 1, phases.size))
            val phaseGroups = when (phase) {
                DedupLevel.EXACT -> exactGroups(members)
                DedupLevel.VISUAL -> clusterVisual(
                    hashed = members.filter { member -> member.phash != null },
                    threshold = config.visualThreshold,
                    timeWindowMs = null,
                    level = DedupLevel.VISUAL,
                ).filterNot { group -> allSameMd5(group) } // 全部字节相同的簇与 EXACT 重复
                DedupLevel.SCENE -> clusterVisual(
                    hashed = members.filter { member -> member.phash != null },
                    threshold = config.sceneThreshold,
                    timeWindowMs = config.sceneTimeWindowMs,
                    level = DedupLevel.SCENE,
                )
            }
            for (group in phaseGroups) {
                allGroups += group
                emit(DedupScanEvent.GroupFound(group))
            }
        }

        emit(DedupScanEvent.Done(allGroups))
    }

    /** 组内成员（有 md5 的）全部同一 md5；md5 缺失（EXACT 未启用）时不判重。 */
    private fun allSameMd5(group: DedupGroup): Boolean {
        val md5s = group.members.mapNotNull { member -> member.md5 }.distinct()
        return md5s.isNotEmpty() && md5s.size < 2
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
        val scaled = if (decoded.width != target || decoded.height != target) {
            Bitmap.createScaledBitmap(decoded, target, target, false)
                .also { created -> if (created !== decoded) decoded.recycle() }
        } else {
            decoded
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
        const val HASH_BATCH_SIZE = 500
        const val PAUSE_POLL_MS = 200L
    }
}
