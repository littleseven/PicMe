package com.mamba.picme.features.camera

import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.mamba.picme.beauty.api.BeautyPreviewEngine
import com.mamba.picme.domain.model.BeautyStrategy
import com.mamba.picme.features.camera.preview.core.PreviewStrategyBundle
import com.mamba.picme.features.camera.preview.gl.GlBeautyPreviewStrategy

@Composable
internal fun rememberPreviewStrategyBundle(
    beautyStrategy: BeautyStrategy,
    previewView: PreviewView,
    glPreviewProvider: BeautyPreviewEngine?,
    onGlWarmUpFallback: (String) -> Unit
): PreviewStrategyBundle {
    val activeStrategy = remember(beautyStrategy, previewView, glPreviewProvider) {
        GlBeautyPreviewStrategy(
            previewView = previewView,
            glBeautyPreviewProvider = requireNotNull(glPreviewProvider) {
                "GL beauty strategy requires GlBeautyPreviewProvider"
            },
            onWarmUpFallback = onGlWarmUpFallback
        )
    }

    return PreviewStrategyBundle(activeStrategy = activeStrategy)
}
