package com.mamba.picme.domain.aesthetic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.domain.tag.FaceRoi
import com.mamba.picme.domain.tag.TagGenerationScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 后台美学/人脸画质打分 + 封面刷新（A1：独立后台，不阻塞扫描）。
 *
 * 当前为 eDifFIQA 单用：解码→复用 [faceDetector] 检测拿 landmarks5→[FaceAligner] 对齐 112×112
 * →[EdiffiqaScorer] 打人脸画质分→回写 media_assets.faceQualityScore；
 * 随后按 [CoverSelector] 重算每个人物封面（缺 NIMA 美学时自动降级为人脸质量单分）。
 *
 * 模型未就绪时只尝试用已有分数刷新封面。人脸检测复用 [scheduler] 的扫描级管线（保证 landmarks5）。
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

    /** 执行一轮打分 + 封面刷新，返回本轮打分照片数。 */
    suspend fun runOnce(): Int = withContext(Dispatchers.IO) {
        val scorer = EdiffiqaScorer(context)
        val modelReady = scorer.initialize()
        if (!modelReady) {
            Log.w(TAG, "eDifFIQA not ready; only refresh covers with existing scores")
            refreshCovers()
            return@withContext 0
        }

        var scored = 0
        try {
            val pending = mediaDao.getMediaWithoutFaceQuality(batch)
            for (entity in pending) {
                val bmp = decodeSampled(entity.uri) ?: continue
                try {
                    val faces: List<FaceRoi> = scheduler.detectFacesForScoring(bmp)
                    // 每个含 5 点 landmarks 的人脸：对齐→打分，取最高者作为该照片的人脸画质
                    val best = faces.mapNotNull { face ->
                        val landmarks5 = face.landmarks5 ?: return@mapNotNull null
                        val aligned = FaceAligner.align(bmp, landmarks5) ?: return@mapNotNull null
                        val s = scorer.score(aligned)
                        aligned.recycle()
                        s
                    }.maxOrNull()
                    if (best != null) {
                        mediaDao.updateFaceQualityScore(entity.id, best)
                        scored++
                    }
                } finally {
                    bmp.recycle()
                }
            }
            refreshCovers()
            Log.i(TAG, "Aesthetic pass done: scored=$scored")
        } catch (e: Exception) {
            Log.e(TAG, "Aesthetic pass failed", e)
        } finally {
            scorer.release()
        }
        scored
    }

    /** 按 CoverSelector 重算每个人物封面（双分加权；缺美学则人脸质量单分）。 */
    private suspend fun refreshCovers() {
        val persons = personDao.getAllPersons()
        for (person in persons) {
            val members = personDao.getMediaByPerson(person.personId)
            val best = CoverSelector.bestCoverMediaId(
                members.map { member ->
                    CoverCandidate(member.id, member.aestheticScore, member.faceQualityScore)
                }
            )
            if (best != null) {
                personDao.updateCoverMedia(person.personId, best)
            }
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
