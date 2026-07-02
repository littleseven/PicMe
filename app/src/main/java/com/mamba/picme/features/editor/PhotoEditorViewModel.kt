package com.mamba.picme.features.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.beauty.api.FaceData
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.repository.PhotoEditRecipeRepository
import com.mamba.picme.domain.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import com.mamba.picme.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PhotoEditorViewModel"
private const val PREVIEW_MAX_DIM = 2048

@OptIn(FlowPreview::class)
class PhotoEditorViewModel(
    private val photoProcessor: PhotoProcessor,
    private val faceDetector: FaceDetector,
    private val recipeRepository: PhotoEditRecipeRepository,
    private val mediaRepository: MediaRepository
) : ViewModel() {

    sealed class State {
        object Loading : State()
        data class Ready(
            val originalBitmap: Bitmap,
            val previewBitmap: Bitmap,
            val recipe: EditRecipe,
            val selectedTab: EditorTab = EditorTab.CROP,
            val isProcessing: Boolean = false,
            val isSaving: Boolean = false,
            val error: String? = null
        ) : State()

        data class Error(val message: String) : State()
    }

    enum class EditorTab { CROP, ADJUST, BEAUTY, FILTER, MARKUP }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private val history = EditHistory()

    private val _recipeChanges = MutableStateFlow<EditRecipe?>(null)

    private var sourceBitmap: Bitmap? = null
    private var cachedFaceData: FaceData? = null
    private var appContext: Context? = null

    init {
        _recipeChanges
            .drop(1)
            .filter { it != null }
            .debounce(200)
            .onEach { recipe ->
                recipe?.let { processPreview(it) }
            }
            .launchIn(viewModelScope)
    }

    fun load(context: Context, sourceUri: String, recipeUri: String?) {
        appContext = context.applicationContext
        viewModelScope.launch {
            try {
                val loadedRecipe = recipeUri?.let { recipeRepository.load(it) }
                    ?: EditRecipe(sourceUri = sourceUri)
                val bitmap = decodePreviewBitmap(context, Uri.parse(sourceUri))
                if (bitmap == null) {
                    _state.value = State.Error(context.getString(R.string.editor_load_failed))
                    return@launch
                }
                sourceBitmap = bitmap
                cachedFaceData = detectFace(bitmap)
                history.reset(loadedRecipe)
                _state.value = State.Ready(originalBitmap = bitmap, previewBitmap = bitmap, recipe = loadedRecipe)
                processPreview(loadedRecipe)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load photo", e)
                _state.value = State.Error(
                    context.getString(R.string.editor_load_failed_with_reason, e.message ?: "")
                )
            }
        }
    }

    private suspend fun detectFace(bitmap: Bitmap): FaceData? = withContext(Dispatchers.Default) {
        runCatching {
            faceDetector.detectPhoto(bitmap, lensFacing = 1)?.landmarks106?.let { landmarks ->
                FaceDataConverter.fromLandmarks106(landmarks, bitmap.width, bitmap.height)
            }
        }.getOrNull()
    }

    private fun decodePreviewBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(stream, null, options)
                val scale = if (maxOf(options.outWidth, options.outHeight) > PREVIEW_MAX_DIM) {
                    maxOf(options.outWidth, options.outHeight) / PREVIEW_MAX_DIM
                } else 1
                options.inJustDecodeBounds = false
                options.inSampleSize = Integer.highestOneBit(scale).coerceAtLeast(1)
                options.inPreferredConfig = Bitmap.Config.ARGB_8888
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Decode preview failed", e)
            null
        }
    }

    fun selectTab(tab: EditorTab) {
        val current = _state.value as? State.Ready ?: return
        _state.value = current.copy(selectedTab = tab)
    }

    fun updateRecipe(recipe: EditRecipe) {
        val current = _state.value as? State.Ready ?: return
        history.push(recipe)
        _state.value = current.copy(recipe = recipe)
        _recipeChanges.value = recipe
    }

    val canUndo: Boolean
        get() = history.canUndo

    val canRedo: Boolean
        get() = history.canRedo

    fun undo() {
        val recipe = history.undo() ?: return
        val current = _state.value as? State.Ready ?: return
        _state.value = current.copy(recipe = recipe)
        _recipeChanges.value = recipe
    }

    fun redo() {
        val recipe = history.redo() ?: return
        val current = _state.value as? State.Ready ?: return
        _state.value = current.copy(recipe = recipe)
        _recipeChanges.value = recipe
    }

    private fun processPreview(recipe: EditRecipe) {
        val base = sourceBitmap ?: return
        viewModelScope.launch {
            val current = _state.value as? State.Ready ?: return@launch
            _state.value = current.copy(isProcessing = true)
            try {
                val applier = RecipeApplier(photoProcessor)
                val cropped = withContext(Dispatchers.Default) { applier.applyCrop(base, recipe.crop) }
                val processed = applier.applyGpuEffects(cropped, recipe, cachedFaceData)
                val marked = withContext(Dispatchers.Default) { applier.applyMarkup(processed, recipe.markup) }
                _state.value = current.copy(
                    previewBitmap = marked,
                    isProcessing = false
                )
            } catch (e: Exception) {
                Logger.e(TAG, "Preview processing failed", e)
                _state.value = current.copy(
                    isProcessing = false,
                    error = appContext?.getString(R.string.editor_preview_failed) ?: "Preview processing failed"
                )
            }
        }
    }

    fun save(context: Context, recipe: EditRecipe) {
        viewModelScope.launch {
            val current = _state.value as? State.Ready ?: return@launch
            _state.value = current.copy(isSaving = true)
            try {
                val fullBitmap = decodeFullBitmap(context, Uri.parse(recipe.sourceUri)) ?: return@launch
                val applier = RecipeApplier(photoProcessor)
                val cropped = withContext(Dispatchers.Default) { applier.applyCrop(fullBitmap, recipe.crop) }
                val processed = applier.applyGpuEffects(cropped, recipe, cachedFaceData)
                val finalBitmap = withContext(Dispatchers.Default) { applier.applyMarkup(processed, recipe.markup) }
                val outputUri = saveBitmapToMediaStore(context, finalBitmap)
                if (outputUri != null) {
                    recipeRepository.save(outputUri, recipe.sourceUri, recipe)
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
                Logger.e(TAG, "Save failed", e)
                _state.value = current.copy(
                    isSaving = false,
                    error = context.getString(R.string.editor_save_failed_with_reason, e.message ?: "")
                )
            }
        }
    }

    private fun decodeFullBitmap(context: Context, uri: Uri): Bitmap? {
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        }
    }

    private fun saveBitmapToMediaStore(context: Context, bitmap: Bitmap): String? {
        val name = "EDITED_${System.currentTimeMillis()}.jpg"
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PicMe")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        return uri?.also {
            context.contentResolver.openOutputStream(it)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
        }?.toString()
    }

    var onSaveComplete: ((String) -> Unit)? = null

    override fun onCleared() {
        super.onCleared()
        sourceBitmap?.takeIf { !it.isRecycled }?.recycle()
    }
}
