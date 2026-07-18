package com.mamba.picme.features.editor

import android.graphics.RectF
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter

private const val RECIPE_VERSION = 2

data class EditRecipe(
    val sourceUri: String,
    val crop: CropRecipe = CropRecipe(),
    val adjustments: AdjustmentRecipe = AdjustmentRecipe(),
    val beauty: BeautySettings = BeautySettings(enabled = true),
    val colorFilter: FilterType = FilterType.NONE,
    val styleFilter: StyleFilter = StyleFilter.NONE,
    val markup: List<MarkupAction> = emptyList(),
    val cutout: CutoutRecipe? = null,
    val version: Int = RECIPE_VERSION
) {
    companion object
}

data class CropRecipe(
    val rotation: Int = 0,
    val flippedH: Boolean = false,
    val flippedV: Boolean = false,
    val straightenAngle: Float = 0f,
    val cropRect: RectF? = null,
    val aspectRatio: AspectRatio = AspectRatio.FREE
)

data class AdjustmentRecipe(
    val brightness: Float = 0f,      // -100..100
    val exposure: Float = 0f,        // -100..100
    val contrast: Float = 50f,       // 0..200
    val saturation: Float = 100f,    // 0..200
    val temperature: Float = 5000f,  // 2000..8000
    val tint: Float = 0f,            // -100..100
    val vignette: Float = 0f         // 0..100
)
