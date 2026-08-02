package com.mamba.picme.data.download

import com.mamba.picme.domain.model.ModelCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelMarketDataTest {

    private fun model(id: String, tags: List<String>) = ModelConfig(
        id = id,
        name = id,
        description = "",
        type = "",
        size = 1L,
        sources = emptyMap(),
        files = emptyList(),
        tags = tags
    )

    /**
     * 覆盖 5 个分类的代表模型。
     * - florence2_base / face-det-retina500m-mnn：id 命中真实 REQUIRED_MODEL_IDS 白名单 → isRequired
     * - modnet-onnx：id 命中真实 RECOMMENDED_MODEL_IDS 白名单 → isRecommended
     * - qwen3_vl_2b / sherpa-voice：虚构 id，不在任何白名单，纯按 tags 归类
     */
    private val sampleModels = listOf(
        model("florence2_base", listOf("must-have", "photo-tagging", "vision-llm")),
        model("modnet-onnx", listOf("matting", "recommended")),
        model("qwen3_vl_2b", listOf("photo-tagging", "vision", "tagging")),
        model("face-det-retina500m-mnn", listOf("beauty-camera", "face")),
        model("sherpa-voice", listOf("chat", "ASR"))
    )

    @Test
    fun groupByCategory_returnsExpectedOrder_withVoiceLast() {
        val grouped = ModelMarketData(sampleModels, emptyMap()).groupByCategory()

        assertEquals(
            listOf("must-have", "recommended", "photo-tagging", "beauty-camera", "chat"),
            grouped.keys.map { it.tag }
        )
    }

    @Test
    fun groupByCategory_assignsVlmToPhotoTagging_notToChat() {
        val grouped = ModelMarketData(sampleModels, emptyMap()).groupByCategory()

        val photoTagging = grouped.getValue(ModelCategory("photo-tagging"))
        assertTrue(photoTagging.any { it.id == "qwen3_vl_2b" })

        val chat = grouped.getValue(ModelCategory("chat"))
        assertFalse(chat.any { it.id == "qwen3_vl_2b" })
        assertTrue(chat.any { it.id == "sherpa-voice" })
    }
}
