package com.mamba.picme.domain.agent.capability.optimize.recipe

import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import com.mamba.picme.domain.agent.capability.optimize.OptimizeResultDto
import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import com.mamba.picme.domain.agent.capability.optimize.preset.AdjustmentPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.BeautyPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.FilterPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset
import com.mamba.picme.features.editor.AdjustmentRecipe
import com.mamba.picme.features.editor.EditRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QA] OptimizeRecipeMapper 单元测试
 *
 * 验证预设到 EditRecipe 的映射、滤镜/风格解析、场景文案生成。
 */
class OptimizeRecipeMapperTest {

    @Test
    fun `toEditRecipe maps preset to edit recipe correctly`() {
        val preset = OptimizePreset(
            scene = Scene.PORTRAIT.name,
            beauty = BeautyPreset(
                enabled = true,
                smoothing = 30f,
                whitening = 20f,
                slimFace = 5f,
                bigEyes = 10f
            ),
            filter = FilterPreset(
                colorFilter = "COOL",
                styleFilter = "NONE"
            ),
            adjustment = AdjustmentPreset(
                brightness = 10f,
                contrast = 60f,
                saturation = 110f
            )
        )

        val recipe = OptimizeRecipeMapper.toEditRecipe(
            preset = preset,
            sourceUri = "file:///test.jpg"
        )

        assertEquals("file:///test.jpg", recipe.sourceUri)
        assertTrue(recipe.beauty.enabled)
        assertEquals(30f, recipe.beauty.smoothing, 0.001f)
        assertEquals(20f, recipe.beauty.whitening, 0.001f)
        assertEquals(5f, recipe.beauty.slimFace, 0.001f)
        assertEquals(10f, recipe.beauty.bigEyes, 0.001f)
        assertEquals(FilterType.COOL, recipe.colorFilter)
        assertEquals(StyleFilter.NONE, recipe.styleFilter)
        assertEquals(10f, recipe.adjustments.brightness, 0.001f)
        assertEquals(60f, recipe.adjustments.contrast, 0.001f)
        assertEquals(110f, recipe.adjustments.saturation, 0.001f)
    }

    @Test
    fun `toEditRecipe preserves base recipe crop and markup`() {
        val baseRecipe = com.mamba.picme.features.editor.EditRecipe(
            sourceUri = "file:///original.jpg",
            crop = com.mamba.picme.features.editor.CropRecipe(rotation = 90)
        )

        val preset = OptimizePreset(
            scene = Scene.LANDSCAPE.name,
            beauty = BeautyPreset(),
            filter = FilterPreset(),
            adjustment = AdjustmentPreset()
        )

        val recipe = OptimizeRecipeMapper.toEditRecipe(
            preset = preset,
            sourceUri = "file:///test.jpg",
            baseRecipe = baseRecipe
        )

        assertEquals(90, recipe.crop.rotation)
    }

    @Test
    fun `resolveFilterType handles aliases`() {
        assertEquals(FilterType.COOL, OptimizeRecipeMapper.resolveFilterType("COOL"))
        assertEquals(FilterType.COOL, OptimizeRecipeMapper.resolveFilterType("cold"))
        assertEquals(FilterType.WARM, OptimizeRecipeMapper.resolveFilterType("暖色"))
        assertEquals(FilterType.LEICA_CLASSIC, OptimizeRecipeMapper.resolveFilterType("徕卡经典"))
        assertEquals(FilterType.LEICA_BW, OptimizeRecipeMapper.resolveFilterType("MONOCHROME"))
        assertEquals(FilterType.VINTAGE, OptimizeRecipeMapper.resolveFilterType("复古"))
    }

    @Test
    fun `resolveFilterType returns NONE for unknown`() {
        assertEquals(FilterType.NONE, OptimizeRecipeMapper.resolveFilterType("UNKNOWN_FILTER"))
    }

    @Test
    fun `resolveStyleFilter handles aliases`() {
        assertEquals(StyleFilter.TOON, OptimizeRecipeMapper.resolveStyleFilter("CARTOON"))
        assertEquals(StyleFilter.SKETCH, OptimizeRecipeMapper.resolveStyleFilter("素描"))
        assertEquals(StyleFilter.POSTERIZE, OptimizeRecipeMapper.resolveStyleFilter("海报"))
    }

    @Test
    fun `resolveStyleFilter returns NONE for unknown`() {
        assertEquals(StyleFilter.NONE, OptimizeRecipeMapper.resolveStyleFilter("UNKNOWN_STYLE"))
    }

    @Test
    fun `buildExplanation returns localized text for each scene`() {
        Scene.entries.forEach { scene ->
            val explanation = OptimizeRecipeMapper.buildExplanation(scene)
            assertNotNull(explanation)
            assertTrue(explanation.isNotBlank())
        }
    }

    // ---- T3: reverse mapping (toOptimizePreset / toResultDto) ----

    @Test
    fun `toOptimizePreset reverse maps beauty fields from recipe`() {
        val recipe = EditRecipe(
            sourceUri = "file:///test.jpg",
            beauty = BeautySettings(
                enabled = true,
                smoothing = 35f,
                whitening = 25f,
                slimFace = -10f,
                bigEyes = 15f,
                lipColor = 40f,
                blush = 30f
            )
        )

        val preset = OptimizeRecipeMapper.toOptimizePreset(Scene.SELFIE, recipe)

        assertEquals(Scene.SELFIE.name, preset.scene)
        assertEquals(true, preset.beauty.enabled)
        assertEquals(35f, preset.beauty.smoothing, 0.001f)
        assertEquals(25f, preset.beauty.whitening, 0.001f)
        assertEquals(-10f, preset.beauty.slimFace, 0.001f)
        assertEquals(15f, preset.beauty.bigEyes, 0.001f)
        assertEquals(40f, preset.beauty.lipColor, 0.001f)
        assertEquals(30f, preset.beauty.blush, 0.001f)
    }

    @Test
    fun `toOptimizePreset converts filter and style enums to their names`() {
        val recipe = EditRecipe(
            sourceUri = "file:///test.jpg",
            colorFilter = FilterType.COOL,
            styleFilter = StyleFilter.TOON
        )

        val preset = OptimizeRecipeMapper.toOptimizePreset(Scene.GENERAL, recipe)

        assertEquals(FilterType.COOL.name, preset.filter.colorFilter)
        assertEquals(StyleFilter.TOON.name, preset.filter.styleFilter)
    }

    @Test
    fun `toOptimizePreset reverse maps all adjustment fields`() {
        val recipe = EditRecipe(
            sourceUri = "file:///test.jpg",
            adjustments = AdjustmentRecipe(
                brightness = 12f,
                exposure = -8f,
                contrast = 70f,
                saturation = 120f,
                temperature = 6500f,
                tint = 5f
            )
        )

        val preset = OptimizeRecipeMapper.toOptimizePreset(Scene.LANDSCAPE, recipe)

        assertEquals(12f, preset.adjustment.brightness, 0.001f)
        assertEquals(-8f, preset.adjustment.exposure, 0.001f)
        assertEquals(70f, preset.adjustment.contrast, 0.001f)
        assertEquals(120f, preset.adjustment.saturation, 0.001f)
        assertEquals(6500f, preset.adjustment.temperature, 0.001f)
        assertEquals(5f, preset.adjustment.tint, 0.001f)
    }

    @Test
    fun `toResultDto wraps preset with metadata and delegates to toOptimizePreset`() {
        val recipe = EditRecipe(
            sourceUri = "file:///test.jpg",
            beauty = BeautySettings(
                enabled = true,
                slimFace = 5f,
                bigEyes = 8f,
                lipColor = 30f
            ),
            colorFilter = FilterType.WARM,
            adjustments = AdjustmentRecipe(brightness = 10f)
        )

        val dto: OptimizeResultDto = OptimizeRecipeMapper.toResultDto(
            sourceUri = "file:///orig.jpg",
            scene = Scene.PORTRAIT,
            explanation = "detected portrait",
            recipe = recipe
        )

        // metadata fields
        assertEquals("file:///orig.jpg", dto.sourceUri)
        assertEquals(Scene.PORTRAIT.name, dto.scene)
        assertEquals("detected portrait", dto.explanation)
        // preset is built by toOptimizePreset
        assertEquals(
            OptimizeRecipeMapper.toOptimizePreset(Scene.PORTRAIT, recipe),
            dto.preset
        )
        // spot-check a couple of reverse-mapped fields flow through the DTO
        assertEquals(5f, dto.preset.beauty.slimFace, 0.001f)
        assertEquals(FilterType.WARM.name, dto.preset.filter.colorFilter)
        assertEquals(10f, dto.preset.adjustment.brightness, 0.001f)
    }

    @Test
    fun `forward then reverse round-trips a preset with standard filter names`() {
        val original = OptimizePreset(
            scene = Scene.FOOD.name,
            beauty = BeautyPreset(
                enabled = true,
                smoothing = 40f,
                whitening = 10f,
                slimFace = 3f,
                bigEyes = 6f,
                lipColor = 20f,
                blush = 15f
            ),
            filter = FilterPreset(
                colorFilter = "WARM", // exact enum name -> round-trips
                styleFilter = "NONE"
            ),
            adjustment = AdjustmentPreset(
                brightness = 15f,
                exposure = 5f,
                contrast = 65f,
                saturation = 130f,
                temperature = 6000f,
                tint = 3f
            )
        )

        val recipe = OptimizeRecipeMapper.toEditRecipe(original, "file:///rt.jpg")
        val rebuilt = OptimizeRecipeMapper.toOptimizePreset(Scene.FOOD, recipe)

        assertEquals(original, rebuilt)
    }
}
