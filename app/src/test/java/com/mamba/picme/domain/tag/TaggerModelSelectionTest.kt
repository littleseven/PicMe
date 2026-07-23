package com.mamba.picme.domain.tag

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 校验 [TaggerModelSelector]：默认 qwen3_vl_2b；空白/未识别/已下线模型回退默认；白名单内 key 原样返回。
 *
 * LFM2-VL（450M/1.6B）与 SmolVLM-256M 经测试打标效果不佳或被替代，已下线。
 */
class TaggerModelSelectionTest {

    @Test
    fun default_tagger_is_qwen3_vl_2b() {
        assertEquals("qwen3_vl_2b", TaggerModelSelector.defaultKey)
    }

    @Test
    fun unknown_or_blank_falls_back_to_default() {
        assertEquals("qwen3_vl_2b", TaggerModelSelector.resolve(null))
        assertEquals("qwen3_vl_2b", TaggerModelSelector.resolve(""))
        assertEquals("qwen3_vl_2b", TaggerModelSelector.resolve("   "))
        assertEquals("qwen3_vl_2b", TaggerModelSelector.resolve("nonsense_model"))
    }

    @Test
    fun removed_models_fall_back_to_default() {
        // 已下线：smolvlm_256m、lfm2_vl_450m、lfm2_vl_1_6b
        assertEquals("qwen3_vl_2b", TaggerModelSelector.resolve("smolvlm_256m"))
        assertEquals("qwen3_vl_2b", TaggerModelSelector.resolve("lfm2_vl_450m"))
        assertEquals("qwen3_vl_2b", TaggerModelSelector.resolve("lfm2_vl_1_6b"))
    }

    @Test
    fun known_keys_pass_through() {
        assertEquals("qwen3_vl_2b", TaggerModelSelector.resolve("qwen3_vl_2b"))
        assertEquals("smolvlm_500m", TaggerModelSelector.resolve("smolvlm_500m"))
    }
}
