package com.mamba.picme.data.indexing

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.mamba.picme.core.common.Logger
import java.io.IOException

/**
 * 媒体元数据提取器
 *
 * 为单张图片提取：ML Kit 标签、OCR 文字、EXIF GPS、逆地理编码地名。
 * 所有提取均为端侧执行，不上传任何数据。
 *
 * @param idCardRecognizer 身份证智能识别器，null 时降级为纯 ML Kit OCR
 */
class MetadataExtractor(
    private val context: Context,
    private val idCardRecognizer: IdCardRecognizer? = null
) {

    private val tag = "PicMe:MetadataExtractor"

    /**
     * ML Kit 图像标注客户端。
     *
     * 延迟初始化并在失败时降级为 null，避免 Worker 构造阶段因 ML Kit 内部初始化异常
     * （如 R8 构建下 MultiFlavorDetectorCreator NPE）导致崩溃。
     */
    private val labeler by lazy {
        try {
            ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        } catch (e: Exception) {
            Logger.w(tag, "Failed to initialize ML Kit image labeler, label extraction disabled", e)
            null
        }
    }

    private val textRecognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    /**
     * 提取单张图片的全部元数据
     */
    suspend fun extract(imageUri: Uri, inputImage: InputImage): ExtractionResult {
        val labels = extractLabels(inputImage)
        val ocrText = extractOcrWithIdCardFallback(inputImage)
        val (latitude, longitude, locationName) = extractLocation(imageUri)

        return ExtractionResult(labels, ocrText, latitude, longitude, locationName)
    }

    /**
     * ML Kit 图像标注：返回前 5 个置信度最高的标签
     */
    internal fun extractLabels(inputImage: InputImage): List<String> {
        val currentLabeler = labeler ?: run {
            Logger.w(tag, "Image labeler unavailable, skipping label extraction")
            return emptyList()
        }
        return try {
            val result = Tasks.await(currentLabeler.process(inputImage))
            result
                .sortedByDescending { label -> label.confidence }
                .take(5)
                .filter { label -> label.confidence >= 0.5f }
                .map { label -> label.text }
                .also { Logger.d(tag, "Labels extracted: $it") }
        } catch (e: com.google.mlkit.common.MlKitException) {
            // 模型尚未下载，跳过（Play Services 后台下载后下次索引会重试）
            if (e.message?.contains("download") == true || e.message?.contains("optional module") == true) {
                Logger.w(tag, "ML Kit label model not ready yet, skipping (will retry later)")
            } else {
                Logger.e(tag, "ML Kit label error", e)
            }
            emptyList()
        } catch (e: Exception) {
            Logger.e(tag, "Label extraction failed", e)
            emptyList()
        }
    }

    /**
     * OCR 文字提取（含身份证智能识别 fallback）
     *
     * 流程：
     * 1. 标准 ML Kit ChineseTextRecognizer → 正常图片快速产出
     * 2. 若产出极少（< 20 字符，身份证正面 OCR 失败信号）→ IdCardRecognizer 介入
     *    - Pass 2: Qwen3.5-2B 多模态直接"看"图提取结构化字段（优先）
     *    - Pass 3: 图像增强（灰度化+对比度增强）后重试 ML Kit（备用）
     */
    private suspend fun extractOcrWithIdCardFallback(inputImage: InputImage): String? {
        // Pass 1: 标准 ML Kit OCR
        val standardResult = extractOcrWithMlKit(inputImage)

        // 无 IdCardRecognizer 或文字充足 → 直接返回
        if (idCardRecognizer == null || !idCardRecognizer.shouldTrigger(standardResult)) {
            return standardResult
        }

        // 可能为身份证正面 OCR 失败 → 智能识别
        Logger.d(tag, "OCR text too short (${standardResult?.length ?: 0} chars), trying ID card recognition")
        return idCardRecognizer.enhanceOcr(inputImage, standardResult) ?: standardResult
    }

    /**
     * 标准 ML Kit OCR（原有逻辑）
     */
    private fun extractOcrWithMlKit(inputImage: InputImage): String? {
        return try {
            val result = Tasks.await(textRecognizer.process(inputImage))
            val text = result.textBlocks.joinToString(" ") { block -> block.text }.trim()
            if (text.isNotBlank()) {
                Logger.d(tag, "OCR text extracted: ${text.take(100)}...")
                text
            } else null
        } catch (e: Exception) {
            Logger.e(tag, "OCR extraction failed", e)
            null
        }
    }

    /**
     * EXIF 位置提取 + 逆地理编码
     */
    private fun extractLocation(imageUri: Uri): LocationData {
        return try {
            context.contentResolver.openInputStream(imageUri)?.use { stream ->
                val exif = ExifInterface(stream)
                val latLong = exif.latLong
                val lat = latLong?.getOrNull(0)
                val lon = latLong?.getOrNull(1)
                val placeName = if (lat != null && lon != null) {
                    reverseGeocode(lat, lon)
                } else null
                LocationData(lat, lon, placeName)
            } ?: LocationData()
        } catch (e: IOException) {
            Logger.w(tag, "EXIF location extraction failed", e)
            LocationData()
        }
    }

    /**
     * 逆地理编码：经纬度 → 地名（优先城市+区域）
     */
    private fun reverseGeocode(latitude: Double, longitude: Double): String? {
        return try {
            val geocoder = Geocoder(context)
            val addresses: List<Address>? =
                geocoder.getFromLocation(latitude, longitude, 1)
            addresses?.firstOrNull()?.let { addr ->
                listOfNotNull(addr.locality, addr.subLocality)
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" ")
                    ?: addr.getAddressLine(0)
            }
        } catch (e: IOException) {
            Logger.w(tag, "Geocoding failed", e)
            null
        }
    }

    fun close() {
        try {
            labeler?.close()
            textRecognizer.close()
        } catch (e: Exception) {
            Logger.w(tag, "Error closing extractors", e)
        }
    }

    data class ExtractionResult(
        val labels: List<String> = emptyList(),
        val ocrText: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val locationName: String? = null
    ) {
        val labelsJson: String?
            get() = labels.takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "[", postfix = "]") { label ->
                    "\"${label}\""
                }
    }

    private data class LocationData(
        val latitude: Double? = null,
        val longitude: Double? = null,
        val locationName: String? = null
    )
}
