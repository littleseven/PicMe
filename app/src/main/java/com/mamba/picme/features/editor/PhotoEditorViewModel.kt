package com.mamba.picme.features.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.R
import com.mamba.picme.beauty.api.FaceData
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.beauty.api.facedetect.DetectionPipelineConfig
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.repository.PhotoEditRecipeRepository
import com.mamba.picme.domain.matting.MattingEngine
import com.mamba.picme.domain.matting.MattingRouter
import com.mamba.picme.domain.repository.MediaRepository
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.usecase.AiOptimizeUseCase
import com.mamba.picme.features.camera.toDevicePreference
import com.mamba.picme.features.camera.toInferenceBackendType
import com.mamba.picme.features.camera.toLandmarkDetectorType
import com.mamba.picme.features.camera.toRoiDetectorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private const val TAG = "PhotoEditorViewModel"
private const val PREVIEW_MAX_DIM = 2048

@Suppress("TooManyFunctions") // 待重构：编辑器 ViewModel，按工具组拆 delegate
@OptIn(FlowPreview::class)
class PhotoEditorViewModel(
    private val photoProcessor: PhotoProcessor,
    private val faceDetector: FaceDetector,
    private val recipeRepository: PhotoEditRecipeRepository,
    private val mediaRepository: MediaRepository,
    private val userSettingsRepository: UserSettingsRepository? = null,
    private val aiOptimizeUseCase: AiOptimizeUseCase? = null,
    private val mattingEngine: MattingEngine? = null
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

    /**
     * 照片处理专用单线程调度器。
     *
     * PhotoProcessor 内部使用 EGL 上下文，必须在同一线程上调用；
     * 协程 [Dispatchers.Default] 线程池可能切换线程，导致 EGL 上下文失效而黑屏。
     */
    private val photoProcessingDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

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

    fun load(context: Context, sourceUri: String, recipeUri: String?, autoOptimize: Boolean = false) {
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
                ensureFaceDetectionPipeline()
                cachedFaceData = detectFace(bitmap)
                history.reset(loadedRecipe)
                _state.value = State.Ready(originalBitmap = bitmap, previewBitmap = bitmap, recipe = loadedRecipe)
                processPreview(loadedRecipe)
                if (autoOptimize) {
                    aiOptimize()
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load photo", e)
                _state.value = State.Error(
                    context.getString(R.string.editor_load_failed_with_reason, e.message ?: "")
                )
            }
        }
    }

    /**
     * 确保人脸检测流水线已初始化。
     *
     * 编辑页从相册直接进入时，可能尚未经过相机页，导致 [FaceDetectorManager] 的 pipelineConfig
     * 为 null，人脸检测被跳过，进而瘦脸/大眼等美型效果不生效。
     * 此处从用户设置读取 ROI/Landmark 阶段配置并下发到 FaceDetector。
     */
    private suspend fun ensureFaceDetectionPipeline() {
        val repository = userSettingsRepository ?: return
        try {
            val roiStageConfig = repository.roiStageConfigFlow.first()
            val landmarkStageConfig = repository.landmarkStageConfigFlow.first()
            val config = DetectionPipelineConfig(
                roiDetector = roiStageConfig.modelType.toRoiDetectorType(),
                landmarkDetector = landmarkStageConfig.modelType.toLandmarkDetectorType(),
                roiEngine = roiStageConfig.engineType.toInferenceBackendType(),
                landmarkEngine = landmarkStageConfig.engineType.toInferenceBackendType(),
                roiDevice = roiStageConfig.devicePreference.toDevicePreference(),
                landmarkDevice = landmarkStageConfig.devicePreference.toDevicePreference()
            )
            faceDetector.updatePipelineConfig(config)
            Logger.d(TAG, "Face detection pipeline initialized for editor")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to initialize face detection pipeline for editor", e)
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

    /** 一键去背景：按是否人像路由写入 cutout 配方（默认透明抠图），可撤销/重做，复用 [updateRecipe] 触发预览。 */
    fun removeBackground() {
        val current = _state.value as? State.Ready ?: return
        val source = MattingRouter.choose(cachedFaceData != null)
        updateRecipe(
            current.recipe.copy(
                cutout = CutoutRecipe(
                    maskSource = source,
                    bgMode = CutoutRecipe.BgMode.TRANSPARENT
                )
            )
        )
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

    /**
     * AI 一键优化：分析当前图片场景并应用推荐配方。
     */
    fun aiOptimize() {
        val useCase = aiOptimizeUseCase ?: run {
            _state.value = (_state.value as? State.Ready)?.copy(
                error = appContext?.getString(R.string.ai_optimize_not_available) ?: "AI 优化不可用"
            ) ?: State.Error("AI 优化不可用")
            return
        }
        val current = _state.value as? State.Ready ?: return
        val sourceUri = current.recipe.sourceUri
        viewModelScope.launch {
            val processingState = current.copy(isProcessing = true, error = null)
            _state.value = processingState
            try {
                val result = useCase.optimize(sourceUri, current.recipe)
                history.push(result.editRecipe)
                _state.value = processingState.copy(
                    recipe = result.editRecipe,
                    isProcessing = false
                )
                _recipeChanges.value = result.editRecipe
            } catch (e: Exception) {
                Logger.e(TAG, "AI optimize failed", e)
                _state.value = processingState.copy(
                    isProcessing = false,
                    error = appContext?.getString(R.string.ai_optimize_failed, e.message ?: "") ?: "AI 优化失败"
                )
            }
        }
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
                val applier = RecipeApplier(photoProcessor, photoProcessingDispatcher, mattingEngine)
                val cropped = withContext(Dispatchers.Default) { applier.applyCrop(base, recipe.crop) }
                val processed = applier.applyGpuEffects(cropped, recipe, cachedFaceData)
                val cutout = withContext(Dispatchers.Default) { applier.applyCutout(processed, recipe.cutout) }
                val marked = withContext(Dispatchers.Default) { applier.applyMarkup(cutout, recipe.markup) }
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
                val applier = RecipeApplier(photoProcessor, photoProcessingDispatcher, mattingEngine)
                val cropped = withContext(Dispatchers.Default) { applier.applyCrop(fullBitmap, recipe.crop) }
                val processed = applier.applyGpuEffects(cropped, recipe, cachedFaceData)
                val afterCutout = withContext(Dispatchers.Default) { applier.applyCutout(processed, recipe.cutout) }
                val finalBitmap = withContext(Dispatchers.Default) { applier.applyMarkup(afterCutout, recipe.markup) }
                val transparent = recipe.cutout?.bgMode == CutoutRecipe.BgMode.TRANSPARENT
                val outputUri = saveBitmapToMediaStore(context, finalBitmap, transparent)
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

    private fun saveBitmapToMediaStore(context: Context, bitmap: Bitmap, transparent: Boolean): String? {
        val ext = if (transparent) "png" else "jpg"
        val mime = if (transparent) "image/png" else "image/jpeg"
        val name = "EDITED_${System.currentTimeMillis()}.$ext"
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PoLang")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        return uri?.also {
            context.contentResolver.openOutputStream(it)?.use { out ->
                if (transparent) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
            }
        }?.toString()
    }

    var onSaveComplete: ((String) -> Unit)? = null

    override fun onCleared() {
        super.onCleared()
        sourceBitmap?.takeIf { !it.isRecycled }?.recycle()
        runCatching {
            photoProcessor.release()
            photoProcessingDispatcher.close()
        }.onFailure { Logger.e(TAG, "Failed to release photo processor", it) }
    }
}
