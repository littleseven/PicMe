package com.mamba.picme.features.editor

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import com.mamba.picme.data.repository.PhotoEditRecipeRepository
import com.mamba.picme.domain.repository.MediaRepository
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.usecase.AiOptimizeUseCase

class PhotoEditorViewModelFactory(
    private val appContext: Context,
    private val photoProcessorFactory: (Context) -> PhotoProcessor,
    private val faceDetector: FaceDetector,
    private val recipeRepository: PhotoEditRecipeRepository,
    private val mediaRepository: MediaRepository,
    private val userSettingsRepository: UserSettingsRepository? = null,
    private val aiOptimizeUseCase: AiOptimizeUseCase? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PhotoEditorViewModel::class.java)) {
            return PhotoEditorViewModel(
                photoProcessor = photoProcessorFactory(appContext),
                faceDetector = faceDetector,
                recipeRepository = recipeRepository,
                mediaRepository = mediaRepository,
                userSettingsRepository = userSettingsRepository,
                aiOptimizeUseCase = aiOptimizeUseCase
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
