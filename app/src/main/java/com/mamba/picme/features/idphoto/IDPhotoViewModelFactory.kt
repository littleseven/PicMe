package com.mamba.picme.features.idphoto

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mamba.picme.domain.matting.MattingEngine
import com.mamba.picme.domain.repository.MediaRepository

class IDPhotoViewModelFactory(
    private val appContext: Context,
    private val mattingEngineFactory: (Context) -> MattingEngine,
    private val mediaRepository: MediaRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(IDPhotoViewModel::class.java)) {
            return IDPhotoViewModel(
                mattingEngine = mattingEngineFactory(appContext),
                mediaRepository = mediaRepository
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
