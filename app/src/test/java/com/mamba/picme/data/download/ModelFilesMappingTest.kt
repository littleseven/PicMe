package com.mamba.picme.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校验 [LlmModelDownloadManager.modelFilesForId] 的模型→文件清单映射。
 *
 * 重点保护：新增 LFM2-VL-450M-MNN（lfm2_vl_450m）映射正确，且既有 SmolVLM 映射不被破坏。
 */
class ModelFilesMappingTest {

    @Test
    fun lfm2_vl_450m_maps_to_multimodal_files_without_separate_embedding() {
        // LFM2-VL-450M-MNN: Mamba2 骨干 + SigLIP2 视觉塔，tie_word_embeddings → 无 embeddings_bf16.bin
        val files = LlmModelDownloadManager.modelFilesForId("lfm2_vl_450m")
        assertEquals(
            listOf(
                "config.json",
                "llm_config.json",
                "llm.mnn",
                "llm.mnn.json",
                "llm.mnn.weight",
                "tokenizer.txt",
                "visual.mnn",
                "visual.mnn.weight"
            ),
            files
        )
    }

    @Test
    fun smolvlm_mapping_unchanged_regression_guard() {
        // 回归保护：既有 SmolVLM 映射（带 embeddings_bf16.bin）不被破坏
        val files = LlmModelDownloadManager.modelFilesForId("smolvlm_256m")
        assertTrue("SmolVLM 须含 visual.mnn", files.contains("visual.mnn"))
        assertTrue("SmolVLM 须含 embeddings_bf16.bin", files.contains("embeddings_bf16.bin"))
    }
}
