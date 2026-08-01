@file:Suppress("TooGenericExceptionCaught") // 通用兜底：catch(Exception) 防崩溃，已记录日志
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
import com.mamba.picme.domain.matting.BackgroundComposer
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
private const val DECODE_MAX_DIM = 1024
private const val JPEG_QUALITY = 95
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 4f

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
            val subject: IDPhotoComposer.SubjectBounds? = null,
            val offsetX: Float = 0f,
            val offsetY: Float = 0f,
            val zoom: Float = 1f,
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
        previewBaseCache = null
        viewModelScope.launch {
            _state.value = State.Loading
            try {
                val bitmap = decodePreview(context, Uri.parse(sourceUri))
                    ?: run {
                        _state.value = State.Error(context.getString(R.string.editor_load_failed))
                        return@launch
                    }
                val result = mattingEngine.removeBackground(bitmap, MaskSource.FUSION)
                if (result == null) {
                    _state.value = State.Error(context.getString(R.string.id_photo_matting_failed))
                    return@launch
                }
                // 从 alpha 蒙版提取主体位置，用于「头顶留白」智能构图，避免居中裁剪砍头
                val subject = withContext(Dispatchers.Default) {
                    IDPhotoComposer.subjectBounds(result.alpha, result.width, result.height)
                }
                _state.value = State.Ready(
                    originalBitmap = bitmap,
                    alpha = result.alpha,
                    alphaWidth = result.width,
                    alphaHeight = result.height,
                    subject = subject
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

    /**
     * 拖拽/双指缩放微调构图。[dxFraction]/[dyFraction] 为相对预览尺寸的归一化拖拽量
     * （拖动方向与内容一致：向下拖 = 露出更多顶部）；[zoomChange] 为双指缩放比例增量，
     * 缩放范围 [MIN_ZOOM, MAX_ZOOM]，1.0 = cover 填满。
     * 累加后的偏移经 [IDPhotoComposer.clampFraming] 在状态层收敛，拖过边界不累积死区。
     */
    fun transformBy(dxFraction: Float, dyFraction: Float, zoomChange: Float) {
        val current = _state.value as? State.Ready ?: return
        val size = IDPhotoSpecs.SIZES[current.selectedSizeIndex]
        val clamped = IDPhotoComposer.clampFraming(
            srcW = current.originalBitmap.width,
            srcH = current.originalBitmap.height,
            dstW = size.widthPx,
            dstH = size.heightPx,
            framing = framingOf(current).copy(
                offsetX = current.offsetX - dxFraction,
                offsetY = current.offsetY - dyFraction,
                zoom = (current.zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
            )
        )
        _state.value = current.copy(offsetX = clamped.offsetX, offsetY = clamped.offsetY, zoom = clamped.zoom)
    }

    /** 预览底图缓存：key = selectedColorIndex。手势只改变换参数，不重建底图，保证跟手。 */
    private var previewBaseCache: Pair<Int, Bitmap>? = null

    /** 预览底图（original+alpha 按当前底色合成，原图尺寸）；按底色缓存，跨手势复用。 */
    suspend fun previewBase(): Bitmap? = withContext(Dispatchers.Default) {
        val current = _state.value as? State.Ready ?: return@withContext null
        val colorIndex = current.selectedColorIndex
        previewBaseCache?.takeIf { cached -> cached.first == colorIndex }
            ?.let { cached -> return@withContext cached.second }
        val base = BackgroundComposer.apply(
            current.originalBitmap, current.alpha,
            current.originalBitmap.width, current.originalBitmap.height,
            IDPhotoSpecs.COLORS[colorIndex].argb
        )
        previewBaseCache = colorIndex to base
        base
    }

    private fun framingOf(current: State.Ready) = IDPhotoComposer.CropFraming(
        subject = current.subject,
        offsetX = current.offsetX,
        offsetY = current.offsetY,
        zoom = current.zoom
    )

    /** 当前 framing 下的裁剪窗口（纯计算，UI 变换与保存共用同一定位）。 */
    fun currentCropRect(): IDPhotoComposer.CropRect? {
        val current = _state.value as? State.Ready ?: return null
        val size = IDPhotoSpecs.SIZES[current.selectedSizeIndex]
        return IDPhotoComposer.subjectAwareCropRect(
            srcW = current.originalBitmap.width,
            srcH = current.originalBitmap.height,
            dstW = size.widthPx,
            dstH = size.heightPx,
            framing = framingOf(current)
        )
    }

    /** 合成最终输出图（保存用；底图走缓存，仅裁剪+缩放）。 */
    suspend fun composePreview(): Bitmap? = withContext(Dispatchers.Default) {
        val current = _state.value as? State.Ready ?: return@withContext null
        val base = previewBase() ?: return@withContext null
        val size = IDPhotoSpecs.SIZES[current.selectedSizeIndex]
        IDPhotoComposer.cropAndScale(base, size.widthPx, size.heightPx, framingOf(current))
    }

    fun save(context: Context) {
        val current = _state.value as? State.Ready ?: return
        if (current.isSaving) return
        viewModelScope.launch {
            _state.value = current.copy(isSaving = true)
            try {
                val preview = composePreview()
                val outputUri = preview?.let { saveBitmapToMediaStore(context, it) }
                if (outputUri != null) {
                    mediaRepository.refreshMediaLibrary()
                    finishSaving()
                    onSaveComplete?.invoke(outputUri)
                } else {
                    finishSaving(error = context.getString(R.string.editor_save_failed))
                }
            } catch (e: Exception) {
                Logger.e(TAG, "IDPhoto save failed", e)
                finishSaving(
                    error = context.getString(R.string.editor_save_failed_with_reason, e.message ?: "")
                )
            }
        }
    }

    /** 保存结束恢复状态：基于 flow 当前值更新，不吞掉保存期间的拖拽/换色等操作。 */
    private fun finishSaving(error: String? = null) {
        val latest = _state.value as? State.Ready ?: return
        _state.value = latest.copy(isSaving = false, error = error)
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
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        }?.toString()
    }

    private fun decodePreview(context: Context, uri: Uri): Bitmap? {
        // 限制解码长边到 DECODE_MAX_DIM：证件照输出仅数百像素，无需原图分辨率；
        // 且可避免原图全尺寸 alpha 合成（BackgroundComposer）在大图上 OOM。
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream, null, bounds) }
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        val sample = if (maxDim > DECODE_MAX_DIM) maxDim / DECODE_MAX_DIM else 1
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream, null, opts) }
    }

    var onSaveComplete: ((String) -> Unit)? = null

    override fun onCleared() {
        super.onCleared()
        (mattingEngine as? MattingEngineImpl)?.release()
    }
}
