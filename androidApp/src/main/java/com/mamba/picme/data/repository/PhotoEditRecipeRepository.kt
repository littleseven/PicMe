package com.mamba.picme.data.repository

import android.graphics.RectF
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import com.mamba.picme.data.local.dao.PhotoEditRecipeDao
import com.mamba.picme.data.local.entity.PhotoEditRecipeEntity
import com.mamba.picme.domain.matting.MaskSource
import com.mamba.picme.features.editor.AdjustmentRecipe
import com.mamba.picme.features.editor.AspectRatio
import com.mamba.picme.features.editor.CropRecipe
import com.mamba.picme.features.editor.CutoutRecipe
import com.mamba.picme.features.editor.EditRecipe
import com.mamba.picme.features.editor.MarkupAction
import com.mamba.picme.features.editor.MosaicMode
import com.mamba.picme.features.editor.NormPoint
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class PhotoEditRecipeRepository(
    private val dao: PhotoEditRecipeDao
) {
    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
    private val beautySettingsAdapter = moshi.adapter(BeautySettings::class.java)

    suspend fun save(outputUri: String, sourceUri: String, recipe: EditRecipe) {
        val entity = PhotoEditRecipeEntity(
            outputUri = outputUri,
            sourceUri = sourceUri,
            recipeJson = recipe.toJson()
        )
        dao.insert(entity)
    }

    suspend fun load(outputUri: String): EditRecipe? = withContext(Dispatchers.IO) {
        dao.getByOutputUri(outputUri)?.let { entity ->
            runCatching { EditRecipe.fromJson(entity.recipeJson, entity.sourceUri) }.getOrNull()
        }
    }

    fun observe(outputUri: String): Flow<EditRecipe?> {
        return dao.observeByOutputUri(outputUri).map { entity ->
            entity?.let {
                runCatching { EditRecipe.fromJson(it.recipeJson, it.sourceUri) }.getOrNull()
            }
        }
    }

    suspend fun delete(outputUri: String) {
        dao.delete(outputUri)
    }

    private fun EditRecipe.toJson(): String {
        return JSONObject().apply {
            put("version", version)
            put("sourceUri", sourceUri)
            put("crop", JSONObject().apply {
                put("rotation", crop.rotation)
                put("flippedH", crop.flippedH)
                put("flippedV", crop.flippedV)
                put("straightenAngle", crop.straightenAngle)
                put("aspectRatio", crop.aspectRatio.name)
                crop.cropRect?.let {
                    put("cropRectLeft", it.left)
                    put("cropRectTop", it.top)
                    put("cropRectRight", it.right)
                    put("cropRectBottom", it.bottom)
                }
            })
            put("adjustments", JSONObject().apply {
                put("brightness", adjustments.brightness)
                put("exposure", adjustments.exposure)
                put("contrast", adjustments.contrast)
                put("saturation", adjustments.saturation)
                put("temperature", adjustments.temperature)
                put("tint", adjustments.tint)
                put("vignette", adjustments.vignette)
            })
            put("beauty", beautySettingsAdapter.toJson(beauty))
            put("colorFilter", colorFilter.name)
            put("styleFilter", styleFilter.name)
            put("markup", markupToJson(markup))
            cutout?.let {
                put("cutout", JSONObject().apply {
                    put("maskSource", it.maskSource.name)
                    put("threshold", it.threshold)
                    put("bgMode", it.bgMode.name)
                    if (it.bgColor != null) put("bgColor", it.bgColor)
                    put("feather", it.feather)
                })
            }
        }.toString()
    }

    /** 测试可见的序列化入口。 */
    internal fun toJsonForTest(recipe: EditRecipe): String = recipe.toJson()

    companion object {
        private const val DEFAULT_CUTOUT_THRESHOLD = 0.5
        fun EditRecipe.Companion.fromJson(json: String, fallbackSourceUri: String): EditRecipe {
            val moshi = Moshi.Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()
            val beautyAdapter = moshi.adapter(BeautySettings::class.java)
            val root = JSONObject(json)
            val cropObj = root.getJSONObject("crop")
            val cropRect = if (cropObj.has("cropRectLeft")) {
                RectF(
                    cropObj.getDouble("cropRectLeft").toFloat(),
                    cropObj.getDouble("cropRectTop").toFloat(),
                    cropObj.getDouble("cropRectRight").toFloat(),
                    cropObj.getDouble("cropRectBottom").toFloat()
                )
            } else null

            val cutout = if (root.has("cutout")) {
                val c = root.getJSONObject("cutout")
                CutoutRecipe(
                    maskSource = try {
                        MaskSource.valueOf(c.optString("maskSource", "U2NETP"))
                    } catch (_: IllegalArgumentException) {
                        MaskSource.U2NETP
                    },
                    threshold = c.optDouble("threshold", DEFAULT_CUTOUT_THRESHOLD).toFloat(),
                    bgMode = try {
                        CutoutRecipe.BgMode.valueOf(c.optString("bgMode", "TRANSPARENT"))
                    } catch (_: IllegalArgumentException) {
                        CutoutRecipe.BgMode.TRANSPARENT
                    },
                    bgColor = if (c.has("bgColor")) c.optInt("bgColor") else null,
                    feather = c.optInt("feather", 0)
                )
            } else null

            return EditRecipe(
                sourceUri = root.optString("sourceUri", fallbackSourceUri),
                crop = CropRecipe(
                    rotation = cropObj.optInt("rotation", 0),
                    flippedH = cropObj.optBoolean("flippedH", false),
                    flippedV = cropObj.optBoolean("flippedV", false),
                    straightenAngle = cropObj.optDouble("straightenAngle", 0.0).toFloat(),
                    aspectRatio = try {
                        AspectRatio.valueOf(cropObj.optString("aspectRatio", "FREE"))
                    } catch (_: IllegalArgumentException) {
                        AspectRatio.FREE
                    },
                    cropRect = cropRect
                ),
                adjustments = root.getJSONObject("adjustments").let {
                    AdjustmentRecipe(
                        brightness = it.optDouble("brightness", 0.0).toFloat(),
                        exposure = it.optDouble("exposure", 0.0).toFloat(),
                        contrast = it.optDouble("contrast", 50.0).toFloat(),
                        saturation = it.optDouble("saturation", 100.0).toFloat(),
                        temperature = it.optDouble("temperature", 5000.0).toFloat(),
                        tint = it.optDouble("tint", 0.0).toFloat(),
                        vignette = it.optDouble("vignette", 0.0).toFloat()
                    )
                },
                beauty = runCatching {
                    beautyAdapter.fromJson(root.getString("beauty"))
                }.getOrNull() ?: BeautySettings(),
                colorFilter = try {
                    FilterType.valueOf(root.optString("colorFilter", "NONE"))
                } catch (_: IllegalArgumentException) {
                    FilterType.NONE
                },
                styleFilter = try {
                    StyleFilter.valueOf(root.optString("styleFilter", "NONE"))
                } catch (_: IllegalArgumentException) {
                    StyleFilter.NONE
                },
                markup = markupFromJson(root.optJSONArray("markup")),
                cutout = cutout,
                version = root.optInt("version", 1)
            )
        }

        internal fun fromJsonForTest(json: String, fallbackSourceUri: String): EditRecipe =
            EditRecipe.fromJson(json, fallbackSourceUri)

        private fun markupToJson(markup: List<MarkupAction>): JSONArray {
            return JSONArray().apply {
                markup.forEach { action ->
                    put(JSONObject().apply {
                        put("id", action.id)
                        when (action) {
                            is MarkupAction.Doodle -> {
                                put("type", "doodle")
                                put("points", pointsToJson(action.points))
                                put("color", action.color)
                                put("strokeWidth", action.strokeWidth.toDouble())
                            }
                            is MarkupAction.Mosaic -> {
                                put("type", "mosaic")
                                put("points", pointsToJson(action.points))
                                put("strokeWidth", action.strokeWidth.toDouble())
                                put("mode", action.mode.name)
                            }
                            is MarkupAction.Text -> {
                                put("type", "text")
                                put("text", action.text)
                                put("posX", action.position.x.toDouble())
                                put("posY", action.position.y.toDouble())
                                put("color", action.color)
                                put("size", action.size.toDouble())
                            }
                        }
                    })
                }
            }
        }

        private fun markupFromJson(array: JSONArray?): List<MarkupAction> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                runCatching {
                    val obj = array.getJSONObject(i)
                    val id = obj.optString("id", "markup-$i")
                    when (obj.optString("type")) {
                        "doodle" -> MarkupAction.Doodle(
                            id = id,
                            points = pointsFromJson(obj.getJSONArray("points")),
                            color = obj.getInt("color"),
                            strokeWidth = obj.getDouble("strokeWidth").toFloat()
                        )
                        "mosaic" -> MarkupAction.Mosaic(
                            id = id,
                            points = pointsFromJson(obj.getJSONArray("points")),
                            strokeWidth = obj.getDouble("strokeWidth").toFloat(),
                            mode = try {
                                MosaicMode.valueOf(obj.optString("mode", "PIXEL"))
                            } catch (_: IllegalArgumentException) {
                                MosaicMode.PIXEL
                            }
                        )
                        "text" -> MarkupAction.Text(
                            id = id,
                            text = obj.getString("text"),
                            position = NormPoint(
                                obj.getDouble("posX").toFloat(),
                                obj.getDouble("posY").toFloat()
                            ),
                            color = obj.getInt("color"),
                            size = obj.getDouble("size").toFloat()
                        )
                        else -> null
                    }
                }.getOrNull()
            }
        }

        private fun pointsToJson(points: List<NormPoint>): JSONArray {
            return JSONArray().apply {
                points.forEach { p ->
                    put(JSONArray().apply {
                        put(p.x.toDouble())
                        put(p.y.toDouble())
                    })
                }
            }
        }

        private fun pointsFromJson(array: JSONArray): List<NormPoint> {
            return (0 until array.length()).map { i ->
                val p = array.getJSONArray(i)
                NormPoint(p.getDouble(0).toFloat(), p.getDouble(1).toFloat())
            }
        }
    }
}
