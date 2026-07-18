package com.mamba.picme.data.indexing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.mamba.picme.agent.core.inference.local.llm.LocalLlmEngine
import com.mamba.picme.core.common.Logger
import org.json.JSONObject

/**
 * 身份证识别器 —— 三阶段策略
 *
 * ## 为什么正面身份证 ML Kit 无法识别
 * 1. 人像区域占据 ~40% 面积，文字检测网络误将人像当背景
 * 2. 字段密集且字号不同（姓名、性别、民族、出生、住址、身份证号）
 * 3. 防伪底纹/全息图案产生噪声，ML Kit 通用中文 OCR 无此训练数据
 * 4. 反面（签发机关 + 有效期限）布局简单留白多 → ML Kit 轻松识别
 *
 * ## 三阶段策略
 * - **Pass 1（标准 ML Kit OCR）**：MetadataExtractor 先执行，快速过滤正常图片
 * - **Pass 2（Qwen3.5-2B 多模态）**：当标准 OCR 产出极少时，用 Qwen 视觉模型
 *   直接"看"图片提取结构化字段，准确率 >> 通用 OCR
 * - **Pass 3（图像增强 + ML Kit 重试）**：Qwen 不可用时，灰度化 + 对比度增强 +
 *   重跑 ML Kit
 *
 * ## 性能
 * - Qwen 路径：~2-5s/张，仅触发于 OCR 失败的身份证照片（占总量 <1%）
 * - 图像增强路径：~0.5-1s/张，Qwen 未加载时使用
 */
class IdCardRecognizer(
    private val context: Context,
    private val llmEngine: LocalLlmEngine?
) {

    companion object {
        private const val TAG = "PoLang:IdCardRec"

        /** OCR 产出低于此字符数时触发身份证智能识别 */
        private const val MIN_OCR_LENGTH_FOR_TRIGGER = 20

        /** Qwen 识别的 max token 数（字段较多） */
        private const val MAX_TOKENS = 256

        /** 身份证 system prompt */
        private val ID_CARD_PROMPT = buildString {
            appendLine("你是一个身份证信息提取助手。请分析这张身份证正面照片，提取所有可见的文字信息。")
            appendLine()
            appendLine("请以JSON格式返回以下字段（无法识别则填空字符串）：")
            appendLine("{")
            appendLine("  \"is_id_card\": true/false,")
            appendLine("  \"side\": \"front/back/unknown\",")
            appendLine("  \"name\": \"姓名\",")
            appendLine("  \"gender\": \"男/女\",")
            appendLine("  \"ethnicity\": \"民族\",")
            appendLine("  \"birth_date\": \"出生日期\",")
            appendLine("  \"address\": \"住址\",")
            appendLine("  \"id_number\": \"身份证号码\",")
            appendLine("  \"authority\": \"签发机关(反面)\",")
            appendLine("  \"valid_period\": \"有效期限(反面)\",")
            appendLine("  \"raw_text\": \"所有可见文字以空格拼接\"")
            appendLine("}")
            appendLine()
            appendLine("【重要】")
            appendLine("1. 仔细识别每个字段，特别是身份证号码的18位数字")
            appendLine("2. 住址字段通常很长，请完整提取")
            appendLine("3. 如果这不是身份证，设置is_id_card=false并提取普通文字到raw_text")
        }
    }

    /**
     * 判断是否应该触发身份证智能识别
     *
     * 触发条件：标准 OCR 返回文字极少（< 20 字符）→ 可能是身份证正面 OCR 失败
     */
    fun shouldTrigger(ocrText: String?): Boolean {
        return ocrText.isNullOrBlank() || ocrText.length < MIN_OCR_LENGTH_FOR_TRIGGER
    }

    /**
     * 增强 OCR 主入口（MetadataExtractor 调用）
     *
     * 当标准 ML Kit OCR 产出极少时调用此方法，
     * 尝试用 Qwen 多模态或图像增强来提升识别率。
     *
     * @param inputImage 原始图片（ML Kit InputImage 格式）
     * @param standardResult 标准 ML Kit OCR 产出（可能为空或极短）
     * @return 增强后的 OCR 文本，null 表示无法增强（保留原结果）
     */
    suspend fun enhanceOcr(inputImage: InputImage, standardResult: String?): String? {
        // 优先从 bitmap-based InputImage 获取
        var bitmap = inputImage.bitmapInternal?.let { Bitmap.createBitmap(it) }

        // file-based InputImage：从 byteBuffer 解码
        if (bitmap == null) {
            bitmap = decodeFromByteBuffer(inputImage)
        }

        if (bitmap == null) {
            Logger.d(TAG, "Cannot load bitmap from InputImage, skipping ID card recognition")
            return null
        }

        return try {
            // Pass 2: Qwen 多模态识别（优先）
            val qwenResult = recognizeWithQwen(bitmap)
            if (qwenResult != null) {
                Logger.i(TAG, "ID card recognized by Qwen: ${qwenResult.take(100)}...")
                return qwenResult
            }

            // Pass 3: 图像增强 + ML Kit 重试
            Logger.d(TAG, "Qwen unavailable, trying enhanced ML Kit OCR")
            enhanceWithMlKit(bitmap, inputImage.rotationDegrees)
        } finally {
            bitmap.recycle()
        }
    }

    // ── Pass 2: Qwen 多模态识别 ──────────────────────────

    private suspend fun recognizeWithQwen(bitmap: Bitmap): String? {
        if (llmEngine == null || !llmEngine.isLoaded) {
            Logger.d(TAG, "Qwen LLM not loaded, skipping")
            return null
        }

        return try {
            val response = llmEngine.imageInference(
                bitmap = bitmap,
                systemPrompt = ID_CARD_PROMPT,
                userPrompt = "请提取这张身份证上的所有文字信息",
                maxTokens = MAX_TOKENS
            )

            if (response.isBlank()) return null

            val jsonStr = extractJson(response) ?: response
            val obj = try {
                JSONObject(jsonStr)
            } catch (e: Exception) {
                JSONObject().apply {
                    put("is_id_card", false)
                    put("side", "unknown")
                    put("raw_text", response)
                }
            }

            // 确保 raw_text 包含所有结构化字段
            if (!obj.has("raw_text") || obj.optString("raw_text").isBlank()) {
                val allText = mutableListOf<String>()
                for (key in listOf("name", "gender", "ethnicity", "birth_date",
                    "address", "id_number", "authority", "valid_period")) {
                    val v = obj.optString(key, "")
                    if (v.isNotBlank()) allText.add("$key:$v")
                }
                obj.put("raw_text", allText.joinToString(" "))
            }

            obj.toString()
        } catch (e: Exception) {
            Logger.w(TAG, "Qwen ID card recognition failed", e)
            null
        }
    }

    // ── Pass 3: 图像增强 + ML Kit 重试 ───────────────────

    private suspend fun enhanceWithMlKit(bitmap: Bitmap, rotationDegrees: Int): String? {
        return try {
            val enhanced = createEnhancedBitmap(bitmap)
            val enhancedInput = InputImage.fromBitmap(enhanced, rotationDegrees)
            val recognizer = TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build()
            )
            val result = Tasks.await(recognizer.process(enhancedInput))
            enhanced.recycle()

            val text = result.textBlocks.joinToString(" ") { block -> block.text }.trim()
            if (text.isNotBlank()) {
                Logger.d(TAG, "Enhanced OCR success: ${text.take(100)}...")
                text
            } else null
        } catch (e: Exception) {
            Logger.w(TAG, "Enhanced ML Kit OCR failed", e)
            null
        }
    }

    /**
     * 生成增强版 Bitmap：灰度化 + 提高对比度
     */
    private fun createEnhancedBitmap(original: Bitmap): Bitmap {
        val enhanced = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(enhanced)
        val paint = Paint()

        val cm = ColorMatrix().apply {
            setSaturation(0f) // 去色
        }
        val contrast = ColorMatrix().apply {
            val scale = 1.5f
            val translate = (-0.25f * 255).toInt().toFloat()
            set(floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        cm.postConcat(contrast)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(original, 0f, 0f, paint)
        return enhanced
    }

    // ── 工具方法 ─────────────────────────────────────────

    private fun decodeFromByteBuffer(inputImage: InputImage): Bitmap? {
        return try {
            val buffer = inputImage.byteBuffer ?: return null
            buffer.rewind()
            val remaining = buffer.remaining()
            if (remaining <= 0) return null
            val bytes = ByteArray(remaining)
            buffer.get(bytes)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = 2
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to decode bitmap from byteBuffer", e)
            null
        }
    }

    private fun extractJson(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start != -1 && end > start) {
            text.substring(start, end + 1)
        } else null
    }
}
