package com.mamba.picme.domain.agent.capability.optimize.analyzer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import com.mamba.picme.core.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * 本地场景分析器实现
 *
 * 基于 ML Kit 图像标签、人脸检测、EXIF 元数据和亮度统计，
 * 通过规则引擎将多路信号映射为场景类型。
 */
class LocalSceneAnalyzer(
    private val context: Context,
    private val faceDetector: FaceDetector,
    private val confidenceThreshold: Float = 0.5f,
    private val maxLabels: Int = 5
) : SceneAnalyzer {

    companion object {
        private const val TAG = "PicMe:LocalSceneAnalyzer"

        /**
         * 小图分析尺寸：降采样以提升速度
         */
        private const val ANALYSIS_MAX_DIMENSION = 512

        /**
         * 自拍判定：人脸占画面最小比例
         */
        private const val SELFIE_FACE_RATIO_THRESHOLD = 0.15f

        /**
         * 合影判定：人脸最小数量
         */
        private const val GROUP_FACE_COUNT_THRESHOLD = 2

        /**
         * 夜景判定：平均亮度阈值
         */
        private const val LOW_LIGHT_BRIGHTNESS_THRESHOLD = 60f

        private val FOOD_LABELS = setOf(
            "food", "meal", "dish", "cuisine", "dessert", "fruit", "vegetable",
            "meat", "seafood", "drink", "coffee", "pizza", "sushi", "burger"
        )

        private val LANDSCAPE_LABELS = setOf(
            "sky", "mountain", "sea", "ocean", "beach", "tree", "forest",
            "lake", "river", "sunset", "sunrise", "cloud", "nature", "landscape",
            "cityscape", "building", "architecture"
        )

        private val DOCUMENT_LABELS = setOf(
            "document", "text", "receipt", "paper", "business card", "id card",
            "invoice", "letter", "book", "screenshot"
        )
    }

    /**
     * ML Kit 图像标注客户端。
     *
     * 采用 lazy + try-catch 延迟初始化：避免在 Application.onCreate 阶段创建
     * 导致启动崩溃（部分机型/R8 构建下 MultiFlavorDetectorCreator 会抛出 NPE）。
     * 初始化失败时降级为 null，后续标签分析返回空列表。
     */
    private val labeler by lazy {
        try {
            ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to initialize ML Kit image labeler, scene labeling disabled", e)
            null
        }
    }

    override suspend fun analyze(imageUri: String): SceneAnalysis = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val bitmap = loadSampledBitmap(imageUri)

        if (bitmap == null) {
            Logger.w(TAG, "Failed to load bitmap for analysis: $imageUri")
            return@withContext SceneAnalysis(
                scene = Scene.GENERAL,
                confidence = 0.5f,
                signals = emptyList()
            )
        }

        try {
            val faceSignalDeferred = async { analyzeFaces(bitmap) }
            val labelsSignalDeferred = async { analyzeLabels(bitmap) }
            val brightnessSignalDeferred = async { analyzeBrightness(bitmap) }
            val exifSignal = analyzeExif(imageUri)

            val faceSignal = faceSignalDeferred.await()
            val labelsSignal = labelsSignalDeferred.await()
            val brightnessSignal = brightnessSignalDeferred.await()

            val signals = listOfNotNull(
                faceSignal,
                labelsSignal,
                brightnessSignal,
                exifSignal
            )

            val (scene, confidence) = resolveScene(
                faceSignal = faceSignal,
                labelsSignal = labelsSignal,
                brightnessSignal = brightnessSignal
            )

            val elapsed = System.currentTimeMillis() - startTime
            Logger.d(TAG, "Scene analyzed: $scene (confidence=$confidence, ${elapsed}ms)")

            SceneAnalysis(
                scene = scene,
                confidence = confidence,
                signals = signals
            )
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 加载降采样后的 Bitmap
     */
    private fun loadSampledBitmap(imageUri: String): Bitmap? {
        return try {
            val uri = Uri.parse(imageUri)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }

            options.inSampleSize = calculateInSampleSize(
                options.outWidth,
                options.outHeight,
                ANALYSIS_MAX_DIMENSION
            )
            options.inJustDecodeBounds = false

            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to load bitmap from $imageUri: ${e.message}")
            null
        }
    }

    /**
     * 计算 Bitmap 采样率
     */
    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var inSampleSize = 1
        while (width / inSampleSize > maxDimension || height / inSampleSize > maxDimension) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    /**
     * 人脸检测分析
     */
    private fun analyzeFaces(bitmap: Bitmap): SceneSignal.Face {
        return try {
            val faces = faceDetector.detectFacesOnly(bitmap)
            val imageArea = bitmap.width * bitmap.height.toFloat()
            val maxFaceRatio = faces.maxOfOrNull { faceRect ->
                faceRect.area() / imageArea
            } ?: 0f

            SceneSignal.Face(
                count = faces.size,
                faceRatio = maxFaceRatio
            )
        } catch (e: Exception) {
            Logger.w(TAG, "Face detection failed: ${e.message}")
            SceneSignal.Face(count = 0, faceRatio = 0f)
        }
    }

    /**
     * ML Kit 图像标签分析
     */
    private fun analyzeLabels(bitmap: Bitmap): SceneSignal.Labels {
        val currentLabeler = labeler ?: run {
            Logger.w(TAG, "Image labeler unavailable, skipping label analysis")
            return SceneSignal.Labels(labels = emptyList())
        }
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val result = com.google.android.gms.tasks.Tasks.await(currentLabeler.process(inputImage))
            val labels = result
                .filter { it.confidence >= confidenceThreshold }
                .sortedByDescending { it.confidence }
                .take(maxLabels)
                .map { it.text }

            SceneSignal.Labels(labels = labels)
        } catch (e: Exception) {
            Logger.w(TAG, "Image labeling failed: ${e.message}")
            SceneSignal.Labels(labels = emptyList())
        }
    }

    /**
     * 亮度统计
     */
    private fun analyzeBrightness(bitmap: Bitmap): SceneSignal.Brightness {
        return try {
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
            val pixels = IntArray(64 * 64)
            scaledBitmap.getPixels(pixels, 0, 64, 0, 0, 64, 64)

            var totalBrightness = 0f
            for (pixel in pixels) {
                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)
                totalBrightness += (0.299f * r + 0.587f * g + 0.114f * b)
            }
            scaledBitmap.recycle()

            SceneSignal.Brightness(meanBrightness = totalBrightness / pixels.size)
        } catch (e: Exception) {
            Logger.w(TAG, "Brightness analysis failed: ${e.message}")
            SceneSignal.Brightness(meanBrightness = 128f)
        }
    }

    /**
     * EXIF 元数据分析
     */
    private fun analyzeExif(imageUri: String): SceneSignal.Exif {
        return try {
            context.contentResolver.openInputStream(Uri.parse(imageUri))?.use { stream ->
                val exif = ExifInterface(stream)
                val iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
                    .takeIf { it > 0 }
                val focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
                    .takeIf { it > 0 }
                    ?.toFloat()

                SceneSignal.Exif(iso = iso, focalLength = focalLength)
            } ?: SceneSignal.Exif(iso = null, focalLength = null)
        } catch (e: Exception) {
            Logger.w(TAG, "EXIF analysis failed: ${e.message}")
            SceneSignal.Exif(iso = null, focalLength = null)
        }
    }

    /**
     * 规则引擎：根据信号确定场景
     */
    private fun resolveScene(
        faceSignal: SceneSignal.Face,
        labelsSignal: SceneSignal.Labels,
        brightnessSignal: SceneSignal.Brightness
    ): Pair<Scene, Float> {
        val labels = labelsSignal.labels.map { it.lowercase() }.toSet()

        return when {
            faceSignal.count >= GROUP_FACE_COUNT_THRESHOLD -> {
                Scene.GROUP to 0.9f
            }
            faceSignal.count == 1 && faceSignal.faceRatio > SELFIE_FACE_RATIO_THRESHOLD -> {
                Scene.SELFIE to 0.9f
            }
            faceSignal.count == 1 -> {
                Scene.PORTRAIT to 0.85f
            }
            labels.containsAny(FOOD_LABELS) -> {
                Scene.FOOD to 0.85f
            }
            labels.containsAny(LANDSCAPE_LABELS) -> {
                Scene.LANDSCAPE to 0.8f
            }
            labels.containsAny(DOCUMENT_LABELS) -> {
                Scene.DOCUMENT to 0.85f
            }
            brightnessSignal.meanBrightness < LOW_LIGHT_BRIGHTNESS_THRESHOLD -> {
                Scene.LOW_LIGHT to 0.7f
            }
            else -> {
                Scene.GENERAL to 0.6f
            }
        }
    }

    private fun RectF.area(): Float {
        return width() * height()
    }

    private fun Set<String>.containsAny(other: Set<String>): Boolean {
        return this.any { it in other }
    }

    fun close() {
        try {
            labeler?.close()
        } catch (e: Exception) {
            Logger.w(TAG, "Error closing labeler", e)
        }
    }
}
