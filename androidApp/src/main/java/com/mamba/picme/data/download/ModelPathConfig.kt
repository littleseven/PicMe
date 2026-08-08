package com.mamba.picme.data.download

import android.content.Context
import java.io.File

/**
 * 模型路径配置管理
 *
 * 【设计目标】
 * - 集中管理所有模型的存储路径
 * - 支持新增模型时自动兼容
 * - 避免路径硬编码散落在代码各处
 * - 统一的模型查询和验证接口
 *
 * 【目录结构】
 * /data/data/com.mamba.picme/files/llm_models/
 * ├── qwen3_vl_2b/                            # VLM 打标模型
 * ├── sherpa-onnx-zipformer-zh-en/            # ASR 模型
 * ├── sherpa-onnx-kws-zipformer-wenetspeech/  # KWS 唤醒词模型
 * └── (其他模型...)
 */
object ModelPathConfig {

    /**
     * 获取模型基础目录
     * @param context Android Context
     * @return `/data/data/com.mamba.picme/files/llm_models`
     */
    fun getModelsBaseDir(context: Context): File {
        return File(context.filesDir, "llm_models").also { it.mkdirs() }
    }

    /**
     * 获取指定模型ID的目录
     * @param context Android Context
     * @param modelId 模型ID（e.g., "qwen3_vl_2b", "sherpa-onnx-kws-zipformer-wenetspeech"）
     * @return 模型目录路径
     */
    fun getModelDir(context: Context, modelId: String): File {
        return File(getModelsBaseDir(context), modelId)
    }

    /**
     * 获取 ASR 模型目录
     * @param context Android Context
     * @return ASR 模型目录路径
     */
    fun getAsrModelDir(context: Context): File {
        return getModelDir(context, MODEL_ID_ASR)
    }

    /**
     * 获取 KWS 唤醒词模型目录
     * @param context Android Context
     * @return KWS 模型目录路径
     */
    fun getKwsModelDir(context: Context): File {
        return getModelDir(context, MODEL_ID_KWS)
    }

    /**
     * 获取 MobileCLIP 模型目录
     * @param context Android Context
     * @return MobileCLIP 模型目录路径
     */
    fun getMobileClipModelDir(context: Context): File {
        return getModelDir(context, MODEL_ID_MOBILECLIP)
    }

    /**
     * 获取 OPUS-MT 翻译模型目录
     * @param context Android Context
     * @return OPUS-MT 模型目录路径
     */
    fun getOpusMtModelDir(context: Context): File {
        return getModelDir(context, MODEL_ID_OPUS_MT)
    }

    /**
     * 检查 MobileCLIP 模型是否完整
     *
     * 仅支持 ONNX Runtime 后端（MobileCLIP-S2）：
     * - ONNX fp32: vision_model.onnx / vision_model_fp32.onnx + text_model.onnx / text_model_fp32.onnx + tokenizer.json
     * - ONNX fp16: vision_model_fp16.onnx + text_model_fp16.onnx + tokenizer.json
     */
    fun isMobileClipModelReady(context: Context): Boolean {
        val modelDir = getMobileClipModelDir(context)
        return validateModelFiles(modelDir, MOBILECLIP_ONNX_FP32_MODEL_FILES) ||
            validateModelFiles(modelDir, MOBILECLIP_ONNX_FP32_ALT_MODEL_FILES) ||
            validateModelFiles(modelDir, MOBILECLIP_ONNX_FP16_MODEL_FILES)
    }

    /**
     * 验证模型文件完整性
     * @param modelDir 模型目录
     * @param requiredFiles 必需的文件列表
     * @return 是否所有必需文件都存在
     */
    fun validateModelFiles(modelDir: File, requiredFiles: List<String>): Boolean {
        if (!modelDir.exists() || !modelDir.isDirectory) {
            return false
        }
        return requiredFiles.all { File(modelDir, it).exists() }
    }

    /**
     * 获取缺失的模型文件
     * @param modelDir 模型目录
     * @param requiredFiles 必需的文件列表
     * @return 缺失的文件列表
     */
    fun getMissingFiles(modelDir: File, requiredFiles: List<String>): List<String> {
        if (!modelDir.exists() || !modelDir.isDirectory) {
            return requiredFiles
        }
        return requiredFiles.filter { !File(modelDir, it).exists() }
    }

    // ===== 模型 ID 常量定义 =====

    const val MODEL_ID_ASR = "sherpa-onnx-zipformer-zh-en"
    const val MODEL_ID_KWS = "sherpa-onnx-kws-zipformer-wenetspeech"
    const val MODEL_ID_MOBILECLIP = "mobileclip-onnx"
    const val MODEL_ID_OPUS_MT = "opus-mt-zh-en"
    const val MODEL_ID_OPUS_MT_EN_ZH = "opus-mt-en-zh"
    const val MODEL_ID_FLORENCE2 = "florence2_base"
    const val MODEL_ID_EDIFFIQA = "ediffiqa-face-quality-onnx"
    const val MODEL_ID_NIMA = "nima-aesthetic-onnx"
    const val MODEL_ID_CODEFORMER = "codeformer-onnx"

    // ===== 模型文件列表 =====

    /**
     * ASR 模型文件列表
     */
    val ASR_MODEL_FILES = listOf(
        "encoder-epoch-99-avg-1.int8.onnx",
        "decoder-epoch-99-avg-1.int8.onnx",
        "joiner-epoch-99-avg-1.int8.onnx",
        "tokens.txt"
    )

    /**
     * KWS 唤醒词模型文件列表
     */
    val KWS_MODEL_FILES = listOf(
        "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        "tokens.txt",
        "keywords.txt"
    )

    /**
     * MobileCLIP ONNX fp16 模型文件列表（MobileCLIP-S2）
     *
     * 当前 ModelScope 远程仓库只提供 fp16 版本，优先使用。
     */
    val MOBILECLIP_ONNX_FP16_MODEL_FILES = listOf(
        "vision_model_fp16.onnx",
        "text_model_fp16.onnx",
        "tokenizer.json"
    )

    /**
     * MobileCLIP ONNX fp32 模型文件列表（MobileCLIP-S2）
     */
    val MOBILECLIP_ONNX_FP32_MODEL_FILES = listOf(
        "vision_model.onnx",
        "text_model.onnx",
        "tokenizer.json"
    )

    /**
     * MobileCLIP ONNX fp32 模型文件列表（带 _fp32 后缀的命名）
     */
    val MOBILECLIP_ONNX_FP32_ALT_MODEL_FILES = listOf(
        "vision_model_fp32.onnx",
        "text_model_fp32.onnx",
        "tokenizer.json"
    )

    @Deprecated("Use MOBILECLIP_ONNX_FP16_MODEL_FILES, MOBILECLIP_ONNX_FP32_MODEL_FILES or MOBILECLIP_ONNX_FP32_ALT_MODEL_FILES")
    val MOBILECLIP_ONNX_MODEL_FILES = MOBILECLIP_ONNX_FP32_MODEL_FILES

    @Deprecated("Use MOBILECLIP_ONNX_FP16_MODEL_FILES, MOBILECLIP_ONNX_FP32_MODEL_FILES or MOBILECLIP_ONNX_FP32_ALT_MODEL_FILES")
    val MOBILECLIP_MODEL_FILES = MOBILECLIP_ONNX_FP32_MODEL_FILES

    /**
     * OPUS-MT 翻译模型文件列表（ModelScope: budaoshou/OPUS-MT-Zh-En-ONNX-INT8）
     * decoder_with_past_model_quantized.onnx 为可选优化文件，用于第 2+ 步解码加速
     * source.spm / target.spm 为 SentencePiece 模型文件（DJL tokenizers 优先使用 tokenizer.json）
     */
    val OPUS_MT_MODEL_FILES = listOf(
        "encoder_model_quantized.onnx",
        "decoder_model_quantized.onnx",
        "decoder_with_past_model_quantized.onnx",
        "tokenizer.json",
        "config.json",
        "source.spm",
        "target.spm"
    )

    /** opus-mt-en-zh（英→中，summary 汉化）文件清单，与 zh-en 同构。 */
    val OPUS_MT_EN_ZH_MODEL_FILES = listOf(
        "encoder_model_quantized.onnx",
        "decoder_model_quantized.onnx",
        "decoder_with_past_model_quantized.onnx",
        "tokenizer.json",
        "config.json",
        "source.spm",
        "target.spm"
    )

    /** Florence-2-base（231M VLM，ONNX ORT 打标）量化文件清单。 */
    val FLORENCE2_MODEL_FILES = listOf(
        "vision_encoder_quantized.onnx",
        "encoder_model_quantized.onnx",
        "decoder_model_merged_quantized.onnx",
        "embed_tokens_int8.onnx",
        "config.json",
        "generation_config.json",
        "preprocessor_config.json",
        "tokenizer.json",
        "tokenizer_config.json",
        "vocab.json",
        "merges.txt",
        "added_tokens.json",
        "special_tokens_map.json"
    )

    // ===== 模型验证辅助方法 =====

    /**
     * 检查 ASR 模型是否完整
     */
    fun isAsrModelReady(context: Context): Boolean {
        return validateModelFiles(getAsrModelDir(context), ASR_MODEL_FILES)
    }

    /**
     * 检查 KWS 模型是否完整
     */
    fun isKwsModelReady(context: Context): Boolean {
        return validateModelFiles(getKwsModelDir(context), KWS_MODEL_FILES)
    }

    /**
     * 获取模型状态诊断信息
     */
    fun getDiagnostics(context: Context): String {
        val sb = StringBuilder()
        sb.append("=== Model Path Diagnostics ===\n")
        sb.append("Base Dir: ${getModelsBaseDir(context).absolutePath}\n\n")

        // ASR 诊断
        val asrDir = getAsrModelDir(context)
        sb.append("ASR Model (${MODEL_ID_ASR}):\n")
        sb.append("  Path: ${asrDir.absolutePath}\n")
        sb.append("  Exists: ${asrDir.exists()}\n")
        if (asrDir.exists()) {
            val missing = getMissingFiles(asrDir, ASR_MODEL_FILES)
            sb.append("  Status: ${if (missing.isEmpty()) "✓ Complete" else "✗ Missing ${missing.size} files"}\n")
            if (missing.isNotEmpty()) {
                sb.append("  Missing: ${missing.joinToString(", ")}\n")
            }
        }
        sb.append("\n")

        // KWS 诊断
        val kwsDir = getKwsModelDir(context)
        sb.append("KWS Model (${MODEL_ID_KWS}):\n")
        sb.append("  Path: ${kwsDir.absolutePath}\n")
        sb.append("  Exists: ${kwsDir.exists()}\n")
        if (kwsDir.exists()) {
            val missing = getMissingFiles(kwsDir, KWS_MODEL_FILES)
            sb.append("  Status: ${if (missing.isEmpty()) "✓ Complete" else "✗ Missing ${missing.size} files"}\n")
            if (missing.isNotEmpty()) {
                sb.append("  Missing: ${missing.joinToString(", ")}\n")
            }
        }

        // MobileCLIP 诊断
        val mobileClipDir = getMobileClipModelDir(context)
        sb.append("MobileCLIP Model (${MODEL_ID_MOBILECLIP}):\n")
        sb.append("  Path: ${mobileClipDir.absolutePath}\n")
        sb.append("  Exists: ${mobileClipDir.exists()}\n")
        if (mobileClipDir.exists()) {
            val onnxFp16Ready = validateModelFiles(mobileClipDir, MOBILECLIP_ONNX_FP16_MODEL_FILES)
            val onnxFp32Ready = validateModelFiles(mobileClipDir, MOBILECLIP_ONNX_FP32_MODEL_FILES)
            val onnxFp32AltReady = validateModelFiles(mobileClipDir, MOBILECLIP_ONNX_FP32_ALT_MODEL_FILES)
            sb.append("  Status: ${if (onnxFp16Ready || onnxFp32Ready || onnxFp32AltReady) "✓ Complete" else "✗ Missing files"}\n")
            if (!onnxFp16Ready && !onnxFp32Ready && !onnxFp32AltReady) {
                val missingOnnxFp16 = getMissingFiles(mobileClipDir, MOBILECLIP_ONNX_FP16_MODEL_FILES)
                val missingOnnxFp32 = getMissingFiles(mobileClipDir, MOBILECLIP_ONNX_FP32_MODEL_FILES)
                val missingOnnxFp32Alt = getMissingFiles(mobileClipDir, MOBILECLIP_ONNX_FP32_ALT_MODEL_FILES)
                sb.append("  Missing (ONNX fp16): ${missingOnnxFp16.joinToString(", ")}\n")
                sb.append("  Missing (ONNX fp32): ${missingOnnxFp32.joinToString(", ")}\n")
                sb.append("  Missing (ONNX fp32 alt): ${missingOnnxFp32Alt.joinToString(", ")}\n")
            }
        }

        return sb.toString()
    }
}

