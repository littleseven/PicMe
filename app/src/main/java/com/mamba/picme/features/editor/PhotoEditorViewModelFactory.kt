package com.mamba.picme.features.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import com.mamba.picme.data.repository.PhotoEditRecipeRepository
import com.mamba.picme.domain.repository.MediaRepository

class PhotoEditorViewModelFactory(
    private val photoProcessor: PhotoProcessor,
    private val faceDetector: FaceDetector,
    private val recipeRepository: PhotoEditRecipeRepository,
    private val mediaRepository: MediaRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PhotoEditorViewModel::class.java)) {
            return PhotoEditorViewModel(
                photoProcessor,
                faceDetector,
                recipeRepository,
                mediaRepository
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
