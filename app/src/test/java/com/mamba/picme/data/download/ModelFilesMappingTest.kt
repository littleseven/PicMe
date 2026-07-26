package com.mamba.picme.data.download

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 校验 [LlmModelDownloadManager.modelFilesForId] 的模型→文件清单映射。
 *
 * 当前打标模型仅保留 Florence-2-base（默认）与 Qwen3-VL-2B（备选）；
 * SmolVLM-500M、LFM2-VL、SmolVLM-256M 已下线。
 */
class ModelFilesMappingTest {

    @Test
    fun removed_models_fall_back_to_default_llm_files() {
        // 已下线模型不再有专属映射，回退到默认 LLM_MODEL_FILES（不含视觉/嵌入文件，且不崩溃）
        listOf("smolvlm_500m", "smolvlm_256m", "lfm2_vl_450m").forEach { id ->
            val files = LlmModelDownloadManager.modelFilesForId(id)
            assertFalse("$id 已下线，不应返回 visual.mnn", files.contains("visual.mnn"))
            assertFalse("$id 已下线，不应返回 embeddings_bf16.bin", files.contains("embeddings_bf16.bin"))
        }
    }
}
