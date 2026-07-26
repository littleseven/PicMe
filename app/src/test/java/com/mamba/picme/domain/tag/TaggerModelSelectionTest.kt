package com.mamba.picme.domain.tag

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 校验 [TaggerModelSelector]：Florence-2 为默认首选，未下载回退 Qwen3-VL-2B；
 * 手动指定仍可覆盖。SmolVLM/LFM2 等已下线模型视为无偏好。
 */
class TaggerModelSelectionTest {

    @Test
    fun default_key_is_florence2_base() {
        assertEquals("florence2_base", TaggerModelSelector.defaultKey)
    }

    @Test
    fun auto_prefers_florence2_when_available() {
        val allAvailable: (String) -> Boolean = { true }
        assertEquals("florence2_base", TaggerModelSelector.resolve(null, allAvailable))
        assertEquals("florence2_base", TaggerModelSelector.resolve("", allAvailable))
        assertEquals("florence2_base", TaggerModelSelector.resolve(TaggerModelSelector.AUTO, allAvailable))
        assertEquals("florence2_base", TaggerModelSelector.resolve("nonsense", allAvailable))
    }

    @Test
    fun auto_falls_back_to_qwen_when_florence2_unavailable() {
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
        assertEquals("florence2_base", TaggerModelSelector.resolve("florence2_base", allAvailable))
        assertEquals("qwen3_vl_2b", TaggerModelSelector.resolve("qwen3_vl_2b", allAvailable))
    }

    @Test
    fun explicit_override_falls_back_when_unavailable() {
        val onlyQwen: (String) -> Boolean = { it == "qwen3_vl_2b" }
        // 手动指定 florence2 但没下载 -> 回退 qwen
        assertEquals("qwen3_vl_2b", TaggerModelSelector.resolve("florence2_base", onlyQwen))
    }

    @Test
    fun removed_or_unknown_models_treated_as_auto() {
        // 已下线模型（smolvlm_500m / smolvlm_256m / lfm2_*）视为无偏好 -> Florence-2 优先
        assertEquals("florence2_base", TaggerModelSelector.resolve("smolvlm_500m"))
        assertEquals("florence2_base", TaggerModelSelector.resolve("smolvlm_256m"))
        assertEquals("florence2_base", TaggerModelSelector.resolve("lfm2_vl_450m"))
    }
}
