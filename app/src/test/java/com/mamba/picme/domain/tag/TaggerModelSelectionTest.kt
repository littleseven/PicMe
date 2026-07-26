package com.mamba.picme.domain.tag

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 校验 [TaggerModelSelector]：恒英文打标下，首选 SmolVLM-500M（英文原生 + 省电），
 * 未下载回退 Qwen3-VL-2B；手动指定仍可覆盖。
 *
 * （语言路由方案已废弃——见 spec §6；打标恒英文，模型不再按语言选。）
 */
class TaggerModelSelectionTest {

    @Test
    fun default_key_is_qwen3_vl_2b() {
        assertEquals("qwen3_vl_2b", TaggerModelSelector.defaultKey)
    }

    @Test
    fun auto_prefers_smolvlm_when_available() {
        val allAvailable: (String) -> Boolean = { true }
        assertEquals("smolvlm_500m", TaggerModelSelector.resolve(null, allAvailable))
        assertEquals("smolvlm_500m", TaggerModelSelector.resolve("", allAvailable))
        assertEquals("smolvlm_500m", TaggerModelSelector.resolve(TaggerModelSelector.AUTO, allAvailable))
        assertEquals("smolvlm_500m", TaggerModelSelector.resolve("nonsense", allAvailable))
    }

    @Test
    fun auto_falls_back_to_qwen_when_smolvlm_unavailable() {
        val onlyQwen: (String) -> Boolean = { it == "qwen3_vl_2b" }
        assertEquals("qwen3_vl_2b", TaggerModelSelector.resolve(TaggerModelSelector.AUTO, onlyQwen))
    }

    @Test
    fun auto_returns_default_when_nothing_available() {
        val noneAvailable: (String) -> Boolean = { false }
        assertEquals(
            TaggerModelSelector.defaultKey,
            TaggerModelSelector.resolve(TaggerModelSelector.AUTO, noneAvailable)
        )
    }

    @Test
    fun explicit_manual_override_wins() {
        val allAvailable: (String) -> Boolean = { true }
        assertEquals("qwen3_vl_2b", TaggerModelSelector.resolve("qwen3_vl_2b", allAvailable))
        assertEquals("smolvlm_500m", TaggerModelSelector.resolve("smolvlm_500m", allAvailable))
    }

    @Test
    fun explicit_override_falls_back_when_unavailable() {
        val onlyQwen: (String) -> Boolean = { it == "qwen3_vl_2b" }
        // 手动指定 smolvlm 但没下载 -> 回退 qwen
        assertEquals("qwen3_vl_2b", TaggerModelSelector.resolve("smolvlm_500m", onlyQwen))
    }

    @Test
    fun removed_or_unknown_models_treated_as_auto() {
        // 已下线模型（smolvlm_256m / lfm2_*）视为无偏好 -> SmolVLM 优先（全可用时）
        assertEquals("smolvlm_500m", TaggerModelSelector.resolve("smolvlm_256m"))
        assertEquals("smolvlm_500m", TaggerModelSelector.resolve("lfm2_vl_450m"))
    }
}
