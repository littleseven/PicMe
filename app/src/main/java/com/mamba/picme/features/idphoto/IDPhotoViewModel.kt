package com.mamba.picme.features.idphoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.R
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.matting.IDPhotoComposer
import com.mamba.picme.domain.matting.IDPhotoSpecs
import com.mamba.picme.domain.matting.MaskSource
import com.mamba.picme.domain.matting.MattingEngine
import com.mamba.picme.domain.matting.MattingEngineImpl
import com.mamba.picme.domain.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PoLang:IDPhoto"

class IDPhotoViewModel(
    private val mattingEngine: MattingEngine,
    private val mediaRepository: MediaRepository
) : ViewModel() {

    sealed class State {
        object Loading : State()
        data class Ready(
            val originalBitmap: Bitmap,
            val alpha: FloatArray,
            val alphaWidth: Int,
            val alphaHeight: Int,
            val selectedColorIndex: Int = 0,
            val selectedSizeIndex: Int = 0,
            val isSaving: Boolean = false,
            val error: String? = null
        ) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private var appContext: Context? = null

    fun load(context: Context, sourceUri: String) {
        appContext = context.applicationContext
        viewModelScope.launch {
            _state.value = State.Loading
            try {
                val bitmap = decodePreview(context, Uri.parse(sourceUri))
                    ?: run {
                        _state.value = State.Error(context.getString(R.string.editor_load_failed))
                        return@launch
                    }
                val result = mattingEngine.removeBackground(bitmap, MaskSource.SELFIE_SEGMENTATION)
                if (result == null) {
                    _state.value = State.Error(context.getString(R.string.id_photo_matting_failed))
                    return@launch
                }
                _state.value = State.Ready(
                    originalBitmap = bitmap,
                    alpha = result.alpha,
                    alphaWidth = result.width,
                    alphaHeight = result.height
                )
            } catch (e: Exception) {
                Logger.e(TAG, "IDPhoto load failed", e)
                _state.value = State.Error(
                    context.getString(R.string.editor_load_failed_with_reason, e.message ?: "")
                )
            }
        }
    }

    fun selectColor(index: Int) {
        val current = _state.value as? State.Ready ?: return
        _state.value = current.copy(selectedColorIndex = index)
    }

    fun selectSize(index: Int) {
        val current = _state.value as? State.Ready ?: return
        _state.value = current.copy(selectedSizeIndex = index)
    }

    /** 合成预览/最终图（供 UI 与保存共用）。 */
    suspend fun composePreview(): Bitmap? = withContext(Dispatchers.Default) {
        val current = _state.value as? State.Ready ?: return@withContext null
        val color = IDPhotoSpecs.COLORS[current.selectedColorIndex]
        val size = IDPhotoSpecs.SIZES[current.selectedSizeIndex]
        IDPhotoComposer.compose(
            original = current.originalBitmap,
            alpha = current.alpha,
            bgColor = color.argb,
            targetW = size.widthPx,
            targetH = size.heightPx
        )
    }

    fun save(context: Context) {
        val current = _state.value as? State.Ready ?: return
        viewModelScope.launch {
            _state.value = current.copy(isSaving = true)
            try {
                val preview = composePreview() ?: return@launch
                val outputUri = saveBitmapToMediaStore(context, preview)
                if (outputUri != null) {
                    mediaRepository.refreshMediaLibrary()
                    _state.value = current.copy(isSaving = false)
                    onSaveComplete?.invoke(outputUri)
                } else {
                    _state.value = current.copy(
                        isSaving = false,
                        error = context.getString(R.string.editor_save_failed)
                    )
                }
            } catch (e: Exception) {
                Logger.e(TAG, "IDPhoto save failed", e)
                _state.value = current.copy(
                    isSaving = false,
                    error = context.getString(R.string.editor_save_failed_with_reason, e.message ?: "")
                )
            }
        }
    }

    private fun saveBitmapToMediaStore(context: Context, bitmap: Bitmap): String? {
        val size = (_state.value as? State.Ready)?.selectedSizeIndex?.let { IDPhotoSpecs.SIZES[it] }
        val name = "IDPHOTO_${System.currentTimeMillis()}_${size?.widthPx ?: 0}x${size?.heightPx ?: 0}.jpg"
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PoLang")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        return uri?.also {
            context.contentResolver.openOutputStream(it)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
        }?.toString()
    }

    private fun decodePreview(context: Context, uri: Uri): Bitmap? {
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    }

    var onSaveComplete: ((String) -> Unit)? = null

    override fun onCleared() {
        super.onCleared()
        (mattingEngine as? MattingEngineImpl)?.release()
    }
}
