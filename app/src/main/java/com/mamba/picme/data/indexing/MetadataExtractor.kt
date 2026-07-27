package com.mamba.picme.data.indexing

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.indexing.geo.OfflineGeocoder
import com.mamba.picme.data.indexing.geo.ResolvedLocation
import java.io.IOException

/**
 * 媒体元数据提取器
 *
 * 为单张图片提取：OCR 文字、EXIF GPS、逆地理编码地名。
 * 标签生成已迁移到 TagGenerationScheduler 的 Qwen/SmolVLM 管线。
 * 所有提取均为端侧执行，不上传任何数据。
 *
 * @param idCardRecognizer 身份证智能识别器，null 时降级为纯 ML Kit OCR
 */
class MetadataExtractor(
    private val context: Context,
    private val idCardRecognizer: IdCardRecognizer? = null,
    private val offlineGeocoder: OfflineGeocoder = OfflineGeocoder.fromAssets(context)
) {

    private val tag = "PoLang:MetadataExtractor"

    private val textRecognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    /**
     * 提取单张图片的全部元数据
     */
    suspend fun extract(imageUri: Uri, inputImage: InputImage): ExtractionResult {
        val ocrText = extractOcrWithIdCardFallback(inputImage)
        val resolved = extractLocation(imageUri)

        return ExtractionResult(emptyList(), ocrText, resolved)
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
     * EXIF 位置提取 + 逆地理编码（系统 Geocoder 优先，失败走离线兜底）。
     */
    private fun extractLocation(imageUri: Uri): ResolvedLocation? {
        return try {
            context.contentResolver.openInputStream(imageUri)?.use { stream ->
                val exif = ExifInterface(stream)
                val latLong = exif.latLong
                val lat = latLong?.getOrNull(0)
                val lon = latLong?.getOrNull(1)
                if (lat != null && lon != null) reverseGeocode(lat, lon) else null
            }
        } catch (e: IOException) {
            Logger.w(tag, "EXIF location extraction failed", e)
            null
        }
    }

    /**
     * 逆地理编码：经纬度 → [ResolvedLocation]。系统 Geocoder 失败/为空 → 离线质心兜底。
     */
    private fun reverseGeocode(lat: Double, lon: Double): ResolvedLocation? {
        val addresses = try {
            Geocoder(context).getFromLocation(lat, lon, 1)
        } catch (e: IOException) {
            Logger.w(tag, "Geocoder failed, will try offline", e)
            null
        }
        val addr = addresses?.firstOrNull()
        return if (addr != null) addr.toResolvedLocation(lat, lon)
        else offlineGeocoder.lookup(lat, lon)
    }

    fun close() {
        try {
            textRecognizer.close()
        } catch (e: Exception) {
            Logger.w(tag, "Error closing extractors", e)
        }
    }

    data class ExtractionResult(
        val labels: List<String> = emptyList(),
        val ocrText: String? = null,
        val resolved: ResolvedLocation? = null
    ) {
        val latitude: Double? get() = resolved?.latitude
        val longitude: Double? get() = resolved?.longitude
        val locationName: String? get() = resolved?.toDisplayString()

        val labelsJson: String?
            get() = labels.takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "[", postfix = "]") { label ->
                    "\"${label}\""
                }
    }
}

/** 把系统 Geocoder 的 [Address] 映射为 [ResolvedLocation]（internal 便于单测）。 */
internal fun Address.toResolvedLocation(lat: Double, lon: Double): ResolvedLocation = ResolvedLocation(
    country = countryName,
    province = adminArea,
    city = locality,
    district = subLocality,
    poi = featureName,
    latitude = lat,
    longitude = lon
)
