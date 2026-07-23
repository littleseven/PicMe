package com.mamba.picme.data.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校验 [LlmModelDownloadManager.modelFilesForId] 的模型→文件清单映射。
 *
 * 当前打标模型仅保留 Qwen3-VL-2B（默认）与 SmolVLM-500M；LFM2-VL、SmolVLM-256M 已下线。
 */
class ModelFilesMappingTest {

    @Test
    fun smolvlm_500m_maps_to_multimodal_files_with_embedding() {
        val files = LlmModelDownloadManager.modelFilesForId("smolvlm_500m")
        assertTrue("SmolVLM-500M 须含 visual.mnn", files.contains("visual.mnn"))
        assertTrue("SmolVLM-500M 须含 embeddings_bf16.bin", files.contains("embeddings_bf16.bin"))
    }

    @Test
    fun removed_models_fall_back_to_default_llm_files() {
        // 已下线模型不再有专属映射，回退到默认 LLM_MODEL_FILES（不含视觉/嵌入文件，且不崩溃）
        val files = LlmModelDownloadManager.modelFilesForId("lfm2_vl_450m")
        assertFalse("已下线 LFM2 不应再返回 visual.mnn", files.contains("visual.mnn"))
    }
}
