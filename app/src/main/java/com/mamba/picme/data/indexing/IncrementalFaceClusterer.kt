package com.mamba.picme.data.indexing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.download.ModelPathConfig
import com.mamba.picme.data.local.dao.PersonDao
import java.io.File
import kotlin.math.sqrt

/**
 * 增量人脸聚类器
 *
 * 在现有 [FaceClusteringWorker] 的全量 DBSCAN 基础上增加增量聚类能力：
 * - 新人脸 embedding 与现有 person 质心做余弦距离匹配（eps=0.55）
 * - 匹配成功 → 归入对应 person
 * - 无匹配 → 创建新 person
 * - 每 [FULL_RECLUSTER_THRESHOLD] 个增量触发一次全量 DBSCAN 重聚以保证聚类质量
 *
 * 质心计算：person 下所有 embedding 的均值向量。
 */
class IncrementalFaceClusterer(
    private val context: Context,
    private val personDao: PersonDao
) {
    companion object {
        private const val TAG = "PoLang:FaceClusterInc"
        private const val FACE_INPUT_SIZE = 112
        private const val EPS = 0.55f
        /** 每 N 个增量 embedding 触发全量重聚 */
        const val FULL_RECLUSTER_THRESHOLD = 50
    }

    private var incrementalCount = 0

    /**
     * 对指定媒体列表进行增量人脸聚类。
     *
     * @param mediaIds 需要聚类的媒体 ID 列表
     * @param embedder Glint360K R100 embedding 提取器（已初始化）
     * @return 是否需要触发全量重聚
     */
    suspend fun processIncremental(
        mediaIds: List<Long>,
        embedder: MnnEmbeddingExtractor
    ): Boolean {
        if (!embedder.isModelReady) {
            Logger.w(TAG, "Embedder not ready")
            return false
        }

        val faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .build()
        )

        try {
            val existingPersons = personDao.getAllPersons()
            // 预计算所有已有 person 的质心
            val centroids = mutableMapOf<Long, FloatArray>()
            for (person in existingPersons) {
                val centroid = computeCentroid(person.personId)
                if (centroid != null) {
                    centroids[person.personId] = centroid
                }
            }

            for (mediaId in mediaIds) {
                val existingEmbeddings = personDao.getEmbeddingsByMedia(mediaId)
                if (existingEmbeddings.isNotEmpty()) continue // 已处理过

                val uri = getMediaUri(mediaId) ?: continue
                val original = loadBitmap(uri) ?: continue

                try {
                    val inputImage = InputImage.fromBitmap(original, 0)
                    val faces = com.google.android.gms.tasks.Tasks.await(
                        faceDetector.process(inputImage)
                    )
                    if (faces.isEmpty()) continue

                    val face = faces[0]
                    val crop = safeCropFace(original, face.boundingBox) ?: continue
                    val embedding = embedder.extractEmbedding(crop)
                    crop.recycle()

                    if (embedding == null) continue

                    // 存储 embedding
                    val embeddingId = personDao.insertEmbedding(
                        com.mamba.picme.data.local.entity.FaceEmbeddingEntity(
                            mediaId = mediaId,
                            embedding = embedding.toByteArray()
                        )
                    )

                    // 与已有 person 质心匹配
                    val matchedPersonId = findBestMatch(embedding, centroids)
                    if (matchedPersonId != null) {
                        personDao.assignEmbedding(embeddingId, matchedPersonId)
                        personDao.incrementFaceCount(matchedPersonId)
                        Logger.d(TAG, "Media $mediaId matched to person $matchedPersonId")
                    } else {
                        // 创建新 person
                        val newPerson = com.mamba.picme.data.local.entity.PersonEntity(
                            coverMediaId = mediaId,
                            faceCount = 1
                        )
                        val newPersonId = personDao.insertPerson(newPerson)
                        personDao.assignEmbedding(embeddingId, newPersonId)
                        // 新 person 的质心就是这单个 embedding
                        centroids[newPersonId] = embedding
                        Logger.d(TAG, "Media $mediaId created new person $newPersonId")
                    }

                    incrementalCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to process face for media $mediaId: ${e.message}")
                } finally {
                    original.recycle()
                }
            }
        } finally {
            faceDetector.close()
        }

        val needsRecluster = incrementalCount >= FULL_RECLUSTER_THRESHOLD
        if (needsRecluster) {
            incrementalCount = 0
            Logger.i(TAG, "Threshold reached ($FULL_RECLUSTER_THRESHOLD), full recluster recommended")
        }
        return needsRecluster
    }

    /**
     * 在已有 person 质心中寻找最佳匹配。
     * 返回余弦距离 <= EPS 的最佳匹配 personId，无匹配返回 null。
     */
    private fun findBestMatch(
        embedding: FloatArray,
        centroids: Map<Long, FloatArray>
    ): Long? {
        var bestPersonId: Long? = null
        var bestDistance = EPS + 1f

        for ((personId, centroid) in centroids) {
            val dist = cosineDistance(embedding, centroid)
            if (dist < bestDistance && dist <= EPS) {
                bestDistance = dist
                bestPersonId = personId
            }
        }
        return bestPersonId
    }

    /**
     * 计算 person 的质心（所有 embedding 的均值）
     */
    private suspend fun computeCentroid(personId: Long): FloatArray? {
        val embeddings = personDao.getEmbeddingsByPerson(personId)
        if (embeddings.isEmpty()) return null

        val count = embeddings.size
        val dim = 512
        val sum = FloatArray(dim)

        for (entity in embeddings) {
            val emb = entity.embedding.toFloatArray()
            for (i in 0 until dim.coerceAtMost(emb.size)) {
                sum[i] += emb[i]
            }
        }

        for (i in sum.indices) {
            sum[i] /= count.toFloat()
        }
        // L2 归一化
        val norm = sqrt(sum.fold(0f) { acc, v -> acc + v * v })
        if (norm > 0f) {
            for (i in sum.indices) {
                sum[i] /= norm
            }
        }
        return sum
    }

    private fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        val len = a.size.coerceAtMost(b.size)
        for (i in 0 until len) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val similarity = dot / (sqrt(normA) * sqrt(normB))
        return (1f - similarity).coerceAtLeast(0f)
    }

    private fun safeCropFace(bitmap: Bitmap, rect: android.graphics.Rect): Bitmap? {
        val marginW = (rect.width() * 0.2f).toInt().coerceAtLeast(10)
        val marginH = (rect.height() * 0.2f).toInt().coerceAtLeast(10)
        val x = (rect.left - marginW).coerceIn(0, bitmap.width)
        val y = (rect.top - marginH).coerceIn(0, bitmap.height)
        val w = (rect.width() + marginW * 2).coerceAtMost(bitmap.width - x)
        val h = (rect.height() + marginH * 2).coerceAtMost(bitmap.height - y)
        if (w <= 0 || h <= 0) return null
        val cropped = Bitmap.createBitmap(bitmap, x, y, w, h)
        return Bitmap.createScaledBitmap(cropped, FACE_INPUT_SIZE, FACE_INPUT_SIZE, true).also {
            if (it !== cropped) cropped.recycle()
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = 4
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeStream(stream, null, opts)
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getMediaUri(mediaId: Long): Uri? {
        // 通过 AppDatabase 获取 URI
        val db = com.mamba.picme.data.local.AppDatabase.getDatabase(context)
        val entity = db.mediaDao().getMediaById(mediaId) ?: return null
        return try {
            Uri.parse(entity.uri)
        } catch (e: Exception) {
            null
        }
    }

    /** 将 FloatArray 转为 ByteArray（小端序） */
    private fun FloatArray.toByteArray(): ByteArray {
        val bytes = ByteArray(this.size * 4)
        for (i in this.indices) {
            val bits = this[i].toBits()
            bytes[i * 4] = (bits and 0xFF).toByte()
            bytes[i * 4 + 1] = ((bits shr 8) and 0xFF).toByte()
            bytes[i * 4 + 2] = ((bits shr 16) and 0xFF).toByte()
            bytes[i * 4 + 3] = ((bits shr 24) and 0xFF).toByte()
        }
        return bytes
    }

    /** 将 ByteArray 转回 FloatArray */
    private fun ByteArray.toFloatArray(): FloatArray {
        val floats = FloatArray(this.size / 4)
        for (i in floats.indices) {
            val bits = (this[i * 4].toInt() and 0xFF) or
                ((this[i * 4 + 1].toInt() and 0xFF) shl 8) or
                ((this[i * 4 + 2].toInt() and 0xFF) shl 16) or
                ((this[i * 4 + 3].toInt() and 0xFF) shl 24)
            floats[i] = Float.fromBits(bits)
        }
        return floats
    }
}
