package com.mamba.picme.features.camera.preview.core

import androidx.camera.core.Preview
import com.mamba.picme.domain.model.BeautyStrategy
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.facedetect.FaceWarpParams

internal interface BeautyPreviewEngineStrategy {
    val strategy: BeautyStrategy

    fun bindPreview(previewUseCase: Preview, aspectRatio: Int): Boolean

    fun applyBeautySettings(settings: BeautySettings)

    fun applyFaceWarpParams(params: FaceWarpParams)

    fun release()
}
