package com.mamba.picme.domain.agent.capability.optimize.preset

import android.content.Context
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * 从 assets 加载的本地预设仓库实现
 */
class AssetPresetRepository(
    private val context: Context
) : PresetRepository {

    companion object {
        private const val TAG = "PoLang:AssetPresetRepository"
        private const val PRESETS_ASSET_PATH = "presets/optimize_presets.json"
    }

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val presets by lazy { loadPresets() }

    override fun getPreset(scene: Scene): OptimizePreset {
        return presets[scene] ?: presets[Scene.GENERAL]
        ?: OptimizePreset(
            scene = Scene.GENERAL.name,
            beauty = BeautyPreset(),
            filter = FilterPreset(),
            adjustment = AdjustmentPreset()
        )
    }

    override fun getAllPresets(): Map<Scene, OptimizePreset> {
        return presets.toMap()
    }

    private fun loadPresets(): Map<Scene, OptimizePreset> {
        return try {
            val jsonString = context.assets.open(PRESETS_ASSET_PATH)
                .bufferedReader()
                .use { it.readText() }

            val listType = Types.newParameterizedType(List::class.java, OptimizePreset::class.java)
            val adapter = moshi.adapter<List<OptimizePreset>>(listType)
            val presetList = adapter.fromJson(jsonString) ?: emptyList()

            presetList.associateBy { preset ->
                Scene.entries.find { it.name.equals(preset.scene, ignoreCase = true) }
                    ?: Scene.GENERAL
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load presets from $PRESETS_ASSET_PATH", e)
            emptyMap()
        }
    }
}
