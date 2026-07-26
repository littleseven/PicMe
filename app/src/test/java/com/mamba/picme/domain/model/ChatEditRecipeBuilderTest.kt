package com.mamba.picme.domain.model

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.command.EditParams
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.features.editor.AdjustmentRecipe
import com.mamba.picme.features.editor.EditRecipe
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatEditRecipeBuilderTest {

    private val base = EditRecipe(sourceUri = "file:///test.jpg")

    @Test
    fun `absolute smoothing sets value`() {
        val result = ChatEditRecipeBuilder.build(
            base,
            AgentCommand.EditImage(params = EditParams(smoothing = EditParams.Absolute(35f)))
        )
        assertEquals(35f, result.beauty.smoothing, 0.001f)
    }

    @Test
    fun `delta brightness adds to current`() {
        val current = base.copy(adjustments = AdjustmentRecipe(brightness = 10f))
        val result = ChatEditRecipeBuilder.build(
            current,
            AgentCommand.EditImage(params = EditParams(brightness = EditParams.Delta(15f)))
        )
        assertEquals(25f, result.adjustments.brightness, 0.001f)
    }

    @Test
    fun `filter name maps to FilterType`() {
        val result = ChatEditRecipeBuilder.build(
            base,
            AgentCommand.EditImage(params = EditParams(filterName = EditParams.AbsoluteString("FILM_GOLD")))
        )
        assertEquals(FilterType.FILM_GOLD, result.colorFilter)
        assertEquals(1.0f, result.filterIntensity, 0.001f)
    }

    @Test
    fun `filter intensity halves default`() {
        val result = ChatEditRecipeBuilder.build(
            base,
            AgentCommand.EditImage(
                params = EditParams(
                    filterName = EditParams.AbsoluteString("COOL"),
                    filterIntensity = 0.4f
                )
            )
        )
        assertEquals(FilterType.COOL, result.colorFilter)
        assertEquals(0.4f, result.filterIntensity, 0.001f)
    }

    @Test
    fun `temperature delta converts to Kelvin step`() {
        val current = base.copy(adjustments = AdjustmentRecipe(temperature = 5000f))
        val result = ChatEditRecipeBuilder.build(
            current,
            AgentCommand.EditImage(params = EditParams(temperature = EditParams.Delta(300f)))
        )
        assertEquals(5300f, result.adjustments.temperature, 0.001f)
    }

    @Test
    fun `slim face positive delta is capped to 5 percent of full scale`() {
        val current = base.copy(beauty = base.beauty.copy(slimFace = 0f))
        val result = ChatEditRecipeBuilder.build(
            current,
            AgentCommand.EditImage(params = EditParams(slimFace = EditParams.Delta(20f)))
        )
        assertEquals(5f, result.beauty.slimFace, 0.001f)
    }

    @Test
    fun `slim face negative delta is capped to 5 percent of full scale`() {
        val current = base.copy(beauty = base.beauty.copy(slimFace = 0f))
        val result = ChatEditRecipeBuilder.build(
            current,
            AgentCommand.EditImage(params = EditParams(slimFace = EditParams.Delta(-20f)))
        )
        assertEquals(-5f, result.beauty.slimFace, 0.001f)
    }

    @Test
    fun `slim face small delta is preserved`() {
        val current = base.copy(beauty = base.beauty.copy(slimFace = 10f))
        val result = ChatEditRecipeBuilder.build(
            current,
            AgentCommand.EditImage(params = EditParams(slimFace = EditParams.Delta(3f)))
        )
        assertEquals(13f, result.beauty.slimFace, 0.001f)
    }

    @Test
    fun `slim face absolute value is not capped`() {
        val result = ChatEditRecipeBuilder.build(
            base,
            AgentCommand.EditImage(params = EditParams(slimFace = EditParams.Absolute(30f)))
        )
        assertEquals(30f, result.beauty.slimFace, 0.001f)
    }
}
