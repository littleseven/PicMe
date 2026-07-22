package com.mamba.picme.domain.tag

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 校验 [TaggerModelSelector]：把用户设置解析为有效的打标模型 key。
 *
 * 默认 smolvlm_256m；空白/未识别回退默认；白名单内 key 原样返回（含新增 lfm2_vl_450m）。
 */
class TaggerModelSelectionTest {

    @Test
    fun default_tagger_is_smolvlm_256m() {
        assertEquals("smolvlm_256m", TaggerModelSelector.defaultKey)
    }

    @Test
    fun unknown_or_blank_setting_falls_back_to_default() {
        assertEquals("smolvlm_256m", TaggerModelSelector.resolve(null))
        assertEquals("smolvlm_256m", TaggerModelSelector.resolve(""))
        assertEquals("smolvlm_256m", TaggerModelSelector.resolve("   "))
        assertEquals("smolvlm_256m", TaggerModelSelector.resolve("nonsense_model"))
    }

    @Test
    fun known_keys_are_passed_through() {
        assertEquals("smolvlm_256m", TaggerModelSelector.resolve("smolvlm_256m"))
        assertEquals("smolvlm_500m", TaggerModelSelector.resolve("smolvlm_500m"))
        assertEquals("qwen3_vl_2b", TaggerModelSelector.resolve("qwen3_vl_2b"))
        assertEquals("lfm2_vl_450m", TaggerModelSelector.resolve("lfm2_vl_450m"))
    }
}
