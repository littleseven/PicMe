package com.mamba.picme.domain.agent.capability.optimize.preset

import android.content.Context
import android.content.res.AssetManager
import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QA] AssetPresetRepository 单元测试
 *
 * 验证 assets 中 presets/optimize_presets.json 的解析与回退行为。
 */
class AssetPresetRepositoryTest {

    private val validPresetsJson = """
    [
      {
        "scene": "SELFIE",
        "beauty": {
          "enabled": true,
          "smoothing": 30.0,
          "whitening": 20.0
        },
        "filter": {
          "colorFilter": "WARM",
          "styleFilter": "NONE"
        },
        "adjustment": {
          "brightness": 5.0,
          "contrast": 50.0,
          "saturation": 100.0,
          "temperature": 5000.0
        }
      },
      {
        "scene": "GENERAL",
        "beauty": {
          "enabled": false
        },
        "filter": {
          "colorFilter": "NONE"
        },
        "adjustment": {
          "brightness": 0.0
        }
      }
    ]
    """.trimIndent()

    private fun createRepository(json: String): AssetPresetRepository {
        val context = mockk<Context>(relaxed = true)
        val assetManager = mockk<AssetManager>(relaxed = true)
        every { context.assets } returns assetManager
        every { assetManager.open("presets/optimize_presets.json") } returns json.byteInputStream()
        return AssetPresetRepository(context)
    }

    @Test
    fun `getPreset returns matching scene preset`() {
        val repo = createRepository(validPresetsJson)

        val preset = repo.getPreset(Scene.SELFIE)

        assertEquals("SELFIE", preset.scene)
        assertTrue(preset.beauty.enabled)
        assertEquals(30f, preset.beauty.smoothing, 0.001f)
        assertEquals(20f, preset.beauty.whitening, 0.001f)
        assertEquals("WARM", preset.filter.colorFilter)
    }

    @Test
    fun `getPreset falls back to GENERAL for unknown scene`() {
        val repo = createRepository(validPresetsJson)

        val preset = repo.getPreset(Scene.FOOD)

        assertEquals("GENERAL", preset.scene)
        assertFalse(preset.beauty.enabled)
    }

    @Test
    fun `getAllPresets returns parsed map`() {
        val repo = createRepository(validPresetsJson)

        val presets = repo.getAllPresets()

        assertEquals(2, presets.size)
        assertNotNull(presets[Scene.SELFIE])
        assertNotNull(presets[Scene.GENERAL])
    }

    @Test
    fun `getPreset returns default when asset read fails`() {
        val context = mockk<Context>(relaxed = true)
        val assetManager = mockk<AssetManager>(relaxed = true)
        every { context.assets } returns assetManager
        every { assetManager.open("presets/optimize_presets.json") } throws RuntimeException("missing")

        val repo = AssetPresetRepository(context)
        val preset = repo.getPreset(Scene.PORTRAIT)

        assertEquals("GENERAL", preset.scene)
    }
}
