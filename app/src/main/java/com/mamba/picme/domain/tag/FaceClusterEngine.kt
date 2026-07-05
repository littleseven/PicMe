package com.mamba.picme.domain.tag

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import com.mamba.picme.data.download.ModelPathConfig
import com.mamba.picme.data.indexing.MnnEmbeddingExtractor
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.entity.FaceEmbeddingEntity
import com.mamba.picme.data.local.entity.PersonEntity
import java.io.File
import kotlin.math.sqrt

/**
 * 人脸聚类引擎
 *
 * 负责 MobileFaceNet 特征提取（Stage 2a/2b）和增量化余弦距离聚类（Stage 2c）。
 *
 * ## 实现状态 (2026-06-24)
 * - **MobileFaceNet 特征提取**：已集成 [MnnEmbeddingExtractor]，
 *   使用 MNN 加载 w600k_mbf.mnn 模型提取 512 维 embedding。
 *   模型缺失时降级为零向量（聚类不生效）。
 * - **聚类算法**：增量式余弦距离匹配已实现。
 *
 * @param context Android Context（用于 Room 数据库访问和模型目录）
 */
class FaceClusterEngine(private val context: Context) {

    companion object {
        private const val TAG = "FaceClusterEngine"

        /** MobileFaceNet 标准输入尺寸 */
        const val FACE_INPUT_SIZE = 112

        /** 特征向量维度 */
        const val EMBEDDING_DIM = 512

        /** 未分配人脸的 personId 标记 */
        const val UNASSIGNED_ID: Long = -1
    }

    private val personDao = AppDatabase.getDatabase(context).personDao()

    /** MobileFaceNet 嵌入提取器（懒加载，模型缺失时为 null） */
    private val embeddingExtractor: MnnEmbeddingExtractor? by lazy {
        val modelDir = ModelPathConfig.getModelDir(context, "picme-face-embedding-mnn")
        val modelFile = File(modelDir, "w600k_mbf.mnn")
        val extractor = MnnEmbeddingExtractor(modelFile)
        if (extractor.isModelReady && extractor.initialize()) {
            Log.i(TAG, "MobileFaceNet model loaded: ${modelFile.absolutePath}")
            extractor
        } else {
            Log.w(TAG, "MobileFaceNet model NOT found at ${modelFile.absolutePath}, face clustering will NOT work. Download w600k_mbf.mnn to enable.")
            null
        }
    }

    /** 嵌入提取器是否可用 */
    val isEmbeddingAvailable: Boolean
        get() = embeddingExtractor != null

    /**
     * 提取人脸特征向量
     *
     * 优先使用 5 点 landmarks 做仿射对齐后再输入 MobileFaceNet；
     * 无 landmarks 时回退到 ROI 裁剪+缩放。
     *
     * @param bitmap 原始图片
     * @param roi 人脸 ROI 区域（像素坐标）
     * @param landmarks5 RetinaFace 5 点原图像素坐标（长度 10），null 则回退旧路径
     * @return 512 维特征向量（L2 归一化后的真实 embedding，或零向量）
     */
    suspend fun extractFeature(
        bitmap: Bitmap,
        roi: RectF,
        landmarks5: FloatArray? = null
    ): FloatArray {
        val extractor = embeddingExtractor
        if (extractor == null) {
            Log.d(TAG, "extractFeature: no model, returning zero vector. roi=$roi")
            return FloatArray(EMBEDDING_DIM) { 0f }
        }

        return try {
            val faceBitmap = if (landmarks5 != null && landmarks5.size >= 10) {
                // 5 点仿射对齐路径
                alignFaceWithLandmarks(bitmap, landmarks5)
            } else {
                // 回退：ROI 裁剪 + 直接缩放
                cropAndScaleFace(bitmap, roi)
            }

            // MNN 推理提取 embedding
            val embedding = extractor.extractEmbedding(faceBitmap)
            faceBitmap.recycle()

            if (embedding != null) {
                Log.d(TAG, "extractFeature: extracted ${embedding.size}-dim embedding, norm=${sqrt(embedding.map { it*it }.sum().toDouble())}")
                embedding
            } else {
                Log.w(TAG, "extractFeature: MNN inference returned null, falling back to zero vector")
                FloatArray(EMBEDDING_DIM) { 0f }
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractFeature: failed with exception, falling back to zero vector", e)
            FloatArray(EMBEDDING_DIM) { 0f }
        }
    }

    /**
     * 使用 5 点 landmarks 做最小二乘仿射对齐，输出 112×112 人脸图
     *
     * 5 点顺序：[左眼，右眼，鼻尖，左嘴角，右嘴角]
     * 目标模板为 ArcFace/MobileFaceNet 标准 112×112 对齐坐标。
     */
    private fun alignFaceWithLandmarks(bitmap: Bitmap, landmarks5: FloatArray): Bitmap {
        // MobileFaceNet 标准 112×112 对齐目标点
        val dstPoints = floatArrayOf(
            38.2946f, 51.6963f,   // 左眼
            73.5318f, 51.5014f,   // 右眼
            56.0252f, 71.7366f,   // 鼻尖
            41.5493f, 92.3655f,   // 左嘴角
            70.7299f, 92.2041f    // 右嘴角
        )

        val transform = computeAffineTransform(landmarks5, dstPoints)

        val aligned = Bitmap.createBitmap(FACE_INPUT_SIZE, FACE_INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(aligned)
        // 黑色背景填充，防止 warp 区域外为透明
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(bitmap, transform, null)
        return aligned
    }

    /**
     * 最小二乘求解 5 点 → 5 点的仿射变换矩阵
     *
     * 仿射模型：u = a*x + b*y + c, v = d*x + e*y + f
     * 分别对 u/v 用正规方程求解。
     */
    private fun computeAffineTransform(src: FloatArray, dst: FloatArray): Matrix {
        val n = src.size / 2

        var sx = 0f; var sy = 0f
        var sxx = 0f; var syy = 0f; var sxy = 0f
        var su = 0f; var sv = 0f
        var sxu = 0f; var syu = 0f
        var sxv = 0f; var syv = 0f

        for (i in 0 until n) {
            val x = src[i * 2]
            val y = src[i * 2 + 1]
            val u = dst[i * 2]
            val v = dst[i * 2 + 1]

            sx += x; sy += y
            sxx += x * x; syy += y * y; sxy += x * y
            su += u; sv += v
            sxu += x * u; syu += y * u
            sxv += x * v; syv += y * v
        }

        // 正规矩阵 M = | sxx sxy sx |
        //              | sxy syy sy |
        //              | sx  sy  n  |
        val m00 = sxx; val m01 = sxy; val m02 = sx
        val m10 = sxy; val m11 = syy; val m12 = sy
        val m20 = sx;  val m21 = sy;  val m22 = n.toFloat()

        val det = m00 * (m11 * m22 - m12 * m21) -
            m01 * (m10 * m22 - m12 * m20) +
            m02 * (m10 * m21 - m11 * m20)

        // 退化时回退到单位变换
        if (det == 0f) {
            Log.w(TAG, "alignFaceWithLandmarks: degenerate landmarks, fallback to identity transform")
            return Matrix()
        }

        val invDet = 1f / det

        // 伴随矩阵 / det
        val i00 = (m11 * m22 - m12 * m21) * invDet
        val i01 = -(m01 * m22 - m02 * m21) * invDet
        val i02 = (m01 * m12 - m02 * m11) * invDet
        val i10 = -(m10 * m22 - m12 * m20) * invDet
        val i11 = (m00 * m22 - m02 * m20) * invDet
        val i12 = -(m00 * m12 - m02 * m10) * invDet
        val i20 = (m10 * m21 - m11 * m20) * invDet
        val i21 = -(m00 * m21 - m01 * m20) * invDet
        val i22 = (m00 * m11 - m01 * m10) * invDet

        val a = i00 * sxu + i01 * syu + i02 * su
        val b = i10 * sxu + i11 * syu + i12 * su
        val c = i20 * sxu + i21 * syu + i22 * su

        val d = i00 * sxv + i01 * syv + i02 * sv
        val e = i10 * sxv + i11 * syv + i12 * sv
        val f = i20 * sxv + i21 * syv + i22 * sv

        val matrix = Matrix()
        matrix.setValues(floatArrayOf(a, b, c, d, e, f, 0f, 0f, 1f))
        return matrix
    }

    /**
     * 回退路径：ROI 裁剪 + 直接缩放到 112×112
     */
    private fun cropAndScaleFace(bitmap: Bitmap, roi: RectF): Bitmap {
        val marginW = (roi.width() * 0.2f).toInt().coerceAtLeast(10)
        val marginH = (roi.height() * 0.2f).toInt().coerceAtLeast(10)
        val cropX = roi.left.toInt().minus(marginW).coerceIn(0, bitmap.width)
        val cropY = roi.top.toInt().minus(marginH).coerceIn(0, bitmap.height)
        val cropW = (roi.width().toInt() + marginW * 2).coerceAtMost(bitmap.width - cropX)
        val cropH = (roi.height().toInt() + marginH * 2).coerceAtMost(bitmap.height - cropY)

        if (cropW <= 0 || cropH <= 0) {
            throw IllegalArgumentException("Invalid crop region: x=$cropX, y=$cropY, w=$cropW, h=$cropH")
        }

        val cropped = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
        val scaled = Bitmap.createScaledBitmap(cropped, FACE_INPUT_SIZE, FACE_INPUT_SIZE, true)
        if (scaled !== cropped) cropped.recycle()
        return scaled
    }

    /**
     * 匹配已有聚类簇
     *
     * 算法：计算新特征向量与所有已有簇质心的余弦相似度，
     * 若最高相似度 > COSINE_THRESHOLD，则归入该簇；否则返回 null 表示需要新建簇。
     *
     * @param feature 512 维特征向量
     * @return 匹配到的 personId，null 表示需要新建簇
     */
    suspend fun matchCluster(feature: FloatArray): Long? {
        val persons = personDao.getAllPersons()
        if (persons.isEmpty()) return null

        var bestPersonId: Long? = null
        var bestSimilarity = ClusteringConfig.COSINE_THRESHOLD

        for (person in persons) {
            val centroid = getPersonCentroid(person.personId) ?: continue
            val similarity = cosineSimilarity(feature, centroid)
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity
                bestPersonId = person.personId
            }
        }

        return bestPersonId
    }

    /**
     * 创建新聚类簇
     *
     * @param feature 512 维特征向量（作为新簇的初始质心）
     * @param mediaId 媒体文件 ID
     * @return 新创建的 personId
     */
    suspend fun createCluster(feature: FloatArray, mediaId: Long): Long {
        val person = PersonEntity(
            faceCount = 1,
            coverMediaId = mediaId
        )
        val personId = personDao.insertPerson(person)
        Log.d(TAG, "Created new cluster: personId=$personId")

        // 写入首个 embedding
        val embeddingEntity = FaceEmbeddingEntity(
            mediaId = mediaId,
            personId = personId,
            embedding = floatArrayToByteArray(feature)
        )
        personDao.insertEmbedding(embeddingEntity)

        return personId
    }

    /**
     * 将新特征归入已有簇
     */
    suspend fun addToCluster(personId: Long, feature: FloatArray, mediaId: Long) {
        // 写入 embedding
        val embeddingEntity = FaceEmbeddingEntity(
            mediaId = mediaId,
            personId = personId,
            embedding = floatArrayToByteArray(feature)
        )
        personDao.insertEmbedding(embeddingEntity)

        // 更新人脸计数
        personDao.incrementFaceCount(personId)
        Log.d(TAG, "Added to cluster: personId=$personId, mediaId=$mediaId")
    }

    /**
     * 合并两个簇（将 personB 的所有 embedding 转移到 personA，删除 personB）
     */
    suspend fun mergeClusters(personA: Long, personB: Long) {
        val embeddingsB = personDao.getEmbeddingsByPerson(personB)
        for (embedding in embeddingsB) {
            personDao.assignEmbedding(embedding.embeddingId, personA)
        }

        val countB = embeddingsB.size
        // 更新 personA faceCount
        repeat(countB) { personDao.incrementFaceCount(personA) }

        // 删除 personB
        personDao.unlinkEmbeddings(personB)
        personDao.deletePerson(personB)

        Log.d(TAG, "Merged clusters: $personB -> $personA, ${countB} embeddings moved")
    }

    /**
     * 获取某个簇的质心特征向量（所有 embedding 的算术平均值）
     */
    private suspend fun getPersonCentroid(personId: Long): FloatArray? {
        val embeddings = personDao.getEmbeddingsByPerson(personId)
        if (embeddings.isEmpty()) return null

        val centroid = FloatArray(EMBEDDING_DIM)
        for (entity in embeddings) {
            val feature = byteArrayToFloatArray(entity.embedding)
            for (i in 0 until EMBEDDING_DIM) {
                centroid[i] += feature[i]
            }
        }
        for (i in 0 until EMBEDDING_DIM) {
            centroid[i] /= embeddings.size.toFloat()
        }
        return centroid
    }

    /**
     * 计算两个向量的余弦相似度 [0, 1]
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denominator = sqrt(normA.toDouble()) * sqrt(normB.toDouble())
        return if (denominator == 0.0) 0f else (dot / denominator).toFloat()
    }

    private fun floatArrayToByteArray(array: FloatArray): ByteArray {
        val bytes = ByteArray(array.size * 4)
        for (i in array.indices) {
            val bits = java.lang.Float.floatToRawIntBits(array[i])
            bytes[i * 4] = (bits shr 24).toByte()
            bytes[i * 4 + 1] = (bits shr 16).toByte()
            bytes[i * 4 + 2] = (bits shr 8).toByte()
            bytes[i * 4 + 3] = bits.toByte()
        }
        return bytes
    }

    private fun byteArrayToFloatArray(bytes: ByteArray): FloatArray {
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) {
            val bits = ((bytes[i * 4].toInt() and 0xFF) shl 24) or
                    ((bytes[i * 4 + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[i * 4 + 2].toInt() and 0xFF) shl 8) or
                    (bytes[i * 4 + 3].toInt() and 0xFF)
            floats[i] = java.lang.Float.intBitsToFloat(bits)
        }
        return floats
    }
}