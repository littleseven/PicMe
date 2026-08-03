package com.mamba.picme.domain.aesthetic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.tag.FaceRoi
import com.mamba.picme.domain.tag.TagGenerationScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 后台美学/人脸画质打分 + 封面刷新（A1：独立后台，不阻塞扫描）。
 *
 * 一图两分（单次解码）：解码→
 *   ① [NimaScorer] 整图美学分→回写 media_assets.aestheticScore（无需人脸）；
 *   ② 复用 [scheduler] 检测拿 landmarks5→[FaceAligner] 对齐 112×112→[EdiffiqaScorer] 人脸画质分
 *      →回写 media_assets.faceQualityScore（无脸则跳过）。
 * 任一模型未就绪则只跑另一个；两者都未就绪只用已有分数刷新封面。
 * 随后按 [CoverSelector] 重算封面（双分加权；缺任一则单分降级）。
 *
 * **性能**：[nima]/[ediffiqa] 为长生命周期实例，会话跨调用复用（NNAPI 编译昂贵），
 * [ensureScorers] 幂等——首次建会话、模型未到则每次重试直到就绪。
 *
 * 两类入口：[runOnce] 全库批处理；[runOnceForPerson] 仅给某个人脸簇的成员打分 + 刷该簇封面。
 *
 * @param batch 单次最多打分照片数（避免单次耗时过长）
 */
class AestheticScoreWorker(
    private val context: Context,
    private val scheduler: TagGenerationScheduler,
    private val db: AppDatabase,
    private val batch: Int = 50
) {
    companion object {
        private const val TAG = "PoLang:Aesthetic"
        private const val MAX_DIM = 640
    }

    private val mediaDao = db.mediaDao()
    private val personDao = db.personDao()
    /** 串行化打分（自动触发与手动触发可能并发，避免同批重复处理） */
    private val mutex = Mutex()

    private val nima = NimaScorer(context)
    private val ediffiqa = EdiffiqaScorer(context)
    private var nimaReady = false
    private var ediffiqaReady = false

    /** 幂等初始化两个 scorer：已就绪则跳过；模型未下载则每次重试（下载后即就绪）。 */
    private suspend fun ensureScorers() {
        if (!nimaReady) nimaReady = nima.initialize()
        if (!ediffiqaReady) ediffiqaReady = ediffiqa.initialize()
    }

    /** 执行一轮全库打分 + 封面刷新，返回本轮打分照片数。串行化（自动/手动触发并发安全）。
     *  [batchLimit] 单次最多处理照片数（默认构造值；手动触发可传更大值）。 */
    suspend fun runOnce(batchLimit: Int = batch): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureScorers()
            if (!ediffiqaReady && !nimaReady) {
                Log.w(TAG, "no scorer ready; only refresh covers with existing scores")
                refreshCovers()
                return@withLock 0
            }

            val pending = mediaDao.getMediaWithoutEitherScore(batchLimit)
            val total = pending.size
            var scored = 0
            val start = SystemClock.elapsedRealtime()
            try {
                for (entity in pending) {
                    val bmp = decodeSampled(entity.uri) ?: continue
                    try {
                        if (scoreEntity(entity, bmp)) scored++
                    } finally {
                        bmp.recycle()
                    }
                }
                refreshCovers()
                val dt = SystemClock.elapsedRealtime() - start
                val avg = if (total > 0) dt / total else 0L
                Log.i(TAG, "Aesthetic pass done: scored=$scored/$total in ${dt}ms (avg ${avg}ms/img, nima=$nimaReady ediffiqa=$ediffiqaReady)")
            } catch (e: Exception) {
                Log.e(TAG, "Aesthetic pass failed", e)
            }
            scored
        }
    }

    /**
     * 仅给某个人脸簇的成员打分 + 刷新该簇封面，返回本轮打分照片数。
     * 供「个人信息编辑页」按聚类触发（不全库扫）。串行化与 [runOnce] 共用同一锁。
     * [personId] 目标人物；[batchLimit] 单次上限（默认 300，簇内通常不大）。 */
    suspend fun runOnceForPerson(personId: Long, batchLimit: Int = 300): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureScorers()
            if (!ediffiqaReady && !nimaReady) {
                Log.w(TAG, "no scorer ready; only refresh cover for person $personId")
                refreshCoverForPerson(personId)
                return@withLock 0
            }

            val members = personDao.getMediaByPerson(personId)
                .filter { it.aestheticScore == null || it.faceQualityScore == null }
                .take(batchLimit)
            val total = members.size
            var scored = 0
            val start = SystemClock.elapsedRealtime()
            try {
                for (entity in members) {
                    val bmp = decodeSampled(entity.uri) ?: continue
                    try {
                        if (scoreEntity(entity, bmp)) scored++
                    } finally {
                        bmp.recycle()
                    }
                }
                refreshCoverForPerson(personId)
                val dt = SystemClock.elapsedRealtime() - start
                val avg = if (total > 0) dt / total else 0L
                Log.i(TAG, "Aesthetic pass person=$personId done: scored=$scored/$total in ${dt}ms (avg ${avg}ms/img, nima=$nimaReady ediffiqa=$ediffiqaReady)")
            } catch (e: Exception) {
                Log.e(TAG, "Aesthetic pass for person $personId failed", e)
            }
            scored
        }
    }

    /**
     * 给单张已解码 [bmp] 计算 NIMA 美学（缺时）+ eDifFIQA 人脸画质（缺时），回写，返回是否写了任一分。
     */
    private suspend fun scoreEntity(entity: MediaEntity, bmp: Bitmap): Boolean {
        var wroteAny = false
        // ① NIMA 整图美学（仅在缺美学分时；无需人脸）
        if (nimaReady && entity.aestheticScore == null) {
            nima.score(bmp)?.let { a ->
                mediaDao.updateAestheticScore(entity.id, a)
                wroteAny = true
            }
        }
        // ② eDifFIQA 人脸画质（仅在缺人脸分时；需检测到含 5 点的人脸）
        if (ediffiqaReady && entity.faceQualityScore == null) {
            val faces: List<FaceRoi> = scheduler.detectFacesForScoring(bmp)
            val best = faces.mapNotNull { face ->
                val landmarks5 = face.landmarks5 ?: return@mapNotNull null
                val aligned = FaceAligner.align(bmp, landmarks5) ?: return@mapNotNull null
                val s = ediffiqa.score(aligned)
                aligned.recycle()
                s
            }.maxOrNull()
            if (best != null) {
                mediaDao.updateFaceQualityScore(entity.id, best)
                wroteAny = true
            }
        }
        return wroteAny
    }

    /** 按 CoverSelector 重算所有人物封面（双分加权；缺美学则人脸质量单分）。 */
    private suspend fun refreshCovers() {
        for (person in personDao.getAllPersons()) {
            refreshCoverForPerson(person.personId)
        }
    }

    /** 仅重算单个簇的封面（按聚类触发时用，避免全量刷新）。 */
    private suspend fun refreshCoverForPerson(personId: Long) {
        val members = personDao.getMediaByPerson(personId)
        val best = CoverSelector.bestCoverMediaId(
            members.map { member -> CoverCandidate(member.id, member.aestheticScore, member.faceQualityScore) }
        )
        if (best != null) {
            personDao.updateCoverMedia(personId, best)
        }
    }

    private fun decodeSampled(uriString: String): Bitmap? = try {
        val uri = Uri.parse(uriString)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        var sample = 1
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxSide / sample / 2 >= MAX_DIM) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, opts)
        }
    } catch (e: Exception) {
        Log.w(TAG, "decode failed: $uriString", e)
        null
    }
}
