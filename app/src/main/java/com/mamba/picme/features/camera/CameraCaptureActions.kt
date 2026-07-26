package com.mamba.picme.features.camera

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.MediaStore

import androidx.camera.core.ImageCapture
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.mamba.picme.beauty.api.BeautyPreviewEngine
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.Face
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.recorder.BeautyVideoRecorder
import com.mamba.picme.core.common.Logger
import com.mamba.picme.core.image.ImageProcessor
import com.mamba.picme.domain.model.BeautyStrategy
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.domain.agent.remote.RemotePhotoTracker
import com.mamba.picme.features.camera.state.CameraStateMachine
import com.mamba.picme.features.camera.state.CameraStateManager
import com.mamba.picme.features.gallery.MediaViewModel
import com.mamba.picme.PoLangApplication
import kotlinx.coroutines.CoroutineScope
import java.io.File
import android.content.ContentValues
import android.net.Uri
import java.io.IOException

// [常量定义] 视频录制分辨率
private const val TARGET_RECORDING_WIDTH = 1920
private const val TARGET_RECORDING_HEIGHT = 1920
private const val MIN_RECORDING_DIMENSION = 720
private const val TAG = "Camera"
private const val TAG_CAPTURE = "CameraCapture"

@SuppressLint("MissingPermission")
internal fun handleCaptureClick(
    context: Context,
    captureMode: MediaType,
    isRecording: Boolean,
    recording: Recording?,
    videoCapture: VideoCapture<Recorder>,
    viewModel: MediaViewModel,
    imageCapture: ImageCapture?,
    imageProcessor: ImageProcessor,
    selectedFilter: FilterType,
    beautySettings: BeautySettings,
    lensFacing: Int,
    cachedFaces: List<Face> = emptyList(),
    beautyStrategy: BeautyStrategy = BeautyStrategy.BIG_BEAUTY,
    glPreviewProvider: BeautyPreviewEngine?,
    beautyVideoRecorder: BeautyVideoRecorder?,
    onRecordingChanged: (Recording?) -> Unit,
    onIsRecordingChanged: (Boolean) -> Unit,
    coroutineScope: CoroutineScope? = null,
    cameraStateManager: CameraStateManager? = null
) {
    if (captureMode != MediaType.VIDEO) {
        cameraStateManager?.let { manager ->
            val currentState = manager.getState()
            when (currentState) {
                is CameraStateMachine.Previewing -> {
                    try {
                        manager.transition(
                            CameraStateMachine.Capturing(lensFacing, captureMode.ordinal)
                        )
                    } catch (e: IllegalStateException) {
                        Logger.w(TAG, "Failed to enter Capturing: ${e.message}")
                        return
                    }
                }
                is CameraStateMachine.Capturing -> Unit
                else -> {
                    Logger.w(TAG, "Capture rejected: state=${currentState.name}")
                    return
                }
            }
        }

        val capture = imageCapture
        if (capture == null) {
            Logger.w(TAG, "Capture skipped: ImageCapture is null")
            // [远程拍照] ImageCapture 未就绪时，经激活通道通知用户拍照失败
            if (RemotePhotoTracker.hasPendingCapture()) {
                val pendingReplyToken = RemotePhotoTracker.consumePendingReplyToken()
                if (pendingReplyToken != null) {
                    Logger.w(TAG, "Remote photo capture failed: ImageCapture not ready, replyToken=$pendingReplyToken")
                    try {
                        val app = context.applicationContext as? PoLangApplication
                        app?.remoteChannelManager?.sendMessage(
                            "抱歉，相机还没准备好，请稍等片刻再试",
                            pendingReplyToken
                        )
                    } catch (e: Exception) {
                        Logger.e(TAG, "Failed to send remote capture error notification", e)
                    }
                }
            }
            cameraStateManager?.forceSetState(
                CameraStateMachine.Previewing(lensFacing, captureMode.ordinal)
            )
            return
        }

        val photoSource = if (RemotePhotoTracker.hasPendingCapture()) {
            (context.applicationContext as? PoLangApplication)?.remoteChannelManager?.activeSourceTag ?: "remote_capture"
        } else {
            null
        }
        Logger.i(TAG, "开始拍照: hasPendingCapture=${RemotePhotoTracker.hasPendingCapture()}, source=$photoSource")
        imageProcessor.takePhoto(
            context = context,
            imageCapture = capture,
            viewModel = viewModel,
            filter = selectedFilter,
            beauty = beautySettings,
            lensFacing = lensFacing,
            mode = captureMode,
            cachedFaces = cachedFaces,
            beautyStrategy = beautyStrategy,
            coroutineScope = coroutineScope,
            source = photoSource,
            onPhotoFinished = { success ->
                cameraStateManager?.let { manager ->
                    if (!success) {
                        Logger.w(TAG, "Photo processing failed, recovering to Previewing")
                        // [远程拍照] 拍照失败时，如有 pending replyToken，经激活通道发送失败通知
                        if (photoSource != null) {
                            val pendingReplyToken = RemotePhotoTracker.consumePendingReplyToken()
                            if (pendingReplyToken != null) {
                                Logger.w(TAG, "Remote photo capture failed, notifying user: replyToken=$pendingReplyToken")
                                try {
                                    val app = context.applicationContext as? PoLangApplication
                                    app?.remoteChannelManager?.sendMessage(
                                        "抱歉，拍照失败了，请再试一次",
                                        pendingReplyToken
                                    )
                                } catch (e: Exception) {
                                    Logger.e(TAG, "Failed to send remote capture error notification", e)
                                }
                            }
                        }
                    }
                    try {
                        manager.transition(
                            CameraStateMachine.Previewing(lensFacing, captureMode.ordinal)
                        )
                    } catch (e: IllegalStateException) {
                        Logger.w(TAG, "State transition failed after photo: ${e.message}")
                        manager.forceSetState(
                            CameraStateMachine.Previewing(lensFacing, captureMode.ordinal)
                        )
                    }
                }
            }
        )
        return
    }

    if (isRecording) {
        // 优先停止美颜录制（如果正在使用）
        if (beautyVideoRecorder != null && glPreviewProvider != null) {
            glPreviewProvider.stopRecording()
            beautyVideoRecorder.stop()
        } else {
            recording?.stop()
        }
        onRecordingChanged(null)
        onIsRecordingChanged(false)
        return
    }

    // 判断是否可以使用美颜录制：BIG_BEAUTY 策略且 GL Provider 已就绪
    val canUseBeautyRecording = beautyStrategy == BeautyStrategy.BIG_BEAUTY
        && glPreviewProvider != null
        && glPreviewProvider.isReady()
        && beautyVideoRecorder != null

    val name = "PoLang_" + System.currentTimeMillis() + ".mp4"

    if (canUseBeautyRecording) {
        // 美颜录制路径：通过 OpenGL 管线直接输出到编码器
        val outputDir = context.getExternalFilesDir(null) ?: context.cacheDir
        val outputFile = File(outputDir, name)

        // [修复] 根据预览视图实际尺寸设置录制分辨率，保持竖屏/横屏比例一致
        val previewView = glPreviewProvider.getView()
        val previewWidth = previewView.width.coerceAtLeast(1)
        val previewHeight = previewView.height.coerceAtLeast(1)

        // 计算目标录制分辨率：短边对齐 1080p，保持原始比例
        val (recordingWidth, recordingHeight) = if (previewHeight > previewWidth) {
            // 竖屏：高度为 1920，宽度按比例计算
            val targetHeight = TARGET_RECORDING_HEIGHT
            val targetWidth = (targetHeight * previewWidth.toFloat() / previewHeight.toFloat()).toInt().coerceAtLeast(MIN_RECORDING_DIMENSION)
            // 确保宽度为偶数（编码器要求）
            Pair(targetWidth - (targetWidth % 2), targetHeight)
        } else {
            // 横屏：宽度为 1920，高度按比例计算
            val targetWidth = TARGET_RECORDING_WIDTH
            val targetHeight = (targetWidth * previewHeight.toFloat() / previewWidth.toFloat()).toInt().coerceAtLeast(MIN_RECORDING_DIMENSION)
            // 确保高度为偶数（编码器要求）
            Pair(targetWidth, targetHeight - (targetHeight % 2))
        }

        Logger.i(TAG_CAPTURE, "Recording resolution: ${recordingWidth}x${recordingHeight} (preview: ${previewWidth}x${previewHeight})")

        onIsRecordingChanged(true)
        beautyVideoRecorder.start(
            outputFile = outputFile,
            width = recordingWidth,
            height = recordingHeight,
            callback = object : BeautyVideoRecorder.Callback {
                override fun onStarted() {
                    glPreviewProvider.startRecording(
                        beautyVideoRecorder.getInputSurface(),
                        recordingWidth,
                        recordingHeight
                    )
                }

                override fun onFinished(outputPath: String) {
                    val uri = insertVideoToMediaStore(context, outputFile, name)
                    if (uri != null) {
                        viewModel.insertMedia(
                            MediaAsset(
                                uri = uri.toString(),
                                type = MediaType.VIDEO,
                                captureDate = System.currentTimeMillis(),
                                fileName = name
                            )
                        )
                    }
                    onRecordingChanged(null)
                    onIsRecordingChanged(false)
                }

                override fun onError(error: Throwable) {
                    onRecordingChanged(null)
                    onIsRecordingChanged(false)
                }
            }
        )
        onRecordingChanged(null)
        return
    }

    //  fallback 路径：CameraX 原生 Recorder（无美颜）
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PoLang")
    }
    val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(
        context.contentResolver,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    )
        .setContentValues(contentValues)
        .build()

    onIsRecordingChanged(true)
    val newRecording = videoCapture.output
        .prepareRecording(context, mediaStoreOutputOptions)
        .withAudioEnabled()
        .start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Finalize -> {
                    if (!event.hasError()) {
                        viewModel.insertMedia(
                            MediaAsset(
                                uri = event.outputResults.outputUri.toString(),
                                type = MediaType.VIDEO,
                                captureDate = System.currentTimeMillis(),
                                fileName = name
                            )
                        )
                    } else {
                        onRecordingChanged(null)
                        onIsRecordingChanged(false)
                    }
                }
            }
        }
    onRecordingChanged(newRecording)
}

private fun insertVideoToMediaStore(context: Context, file: File, displayName: String): Uri? {
    return try {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PoLang")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                file.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
        uri
    } catch (e: SecurityException) {
        Logger.e(TAG, "Permission denied when saving video: ${e.message}")
        null
    } catch (e: IOException) {
        Logger.e(TAG, "IO error when saving video: ${e.message}")
        null
    } catch (e: IllegalArgumentException) {
        Logger.e(TAG, "Invalid arguments when saving video: ${e.message}")
        null
    } catch (e: IllegalStateException) {
        Logger.e(TAG, "Activity state error: ${e.message}")
        null
    }
}

