package com.mamba.picme.features.camera

import android.content.Context
import android.util.Log
import android.util.Rational
import android.view.Surface
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.beauty.api.facedetect.EngineType
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import com.mamba.picme.beauty.api.facedetect.FaceWarpParams
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.model.BeautyStrategy
import com.mamba.picme.domain.model.FaceDetectIntervalProfile
import java.util.concurrent.Executor

private const val TAG = "Camera"

@ExperimentalGetImage
internal fun bindCameraUseCases(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    cameraProviderFuture: ListenableFuture<ProcessCameraProvider>,
    lensFacing: Int,
    captureMode: MediaType,
    aspectRatio: Int,
    previewView: PreviewView,
    bindPreviewSurfaceProvider: (Preview) -> Unit,
    cameraExecutor: Executor,
    isBeautyEnabled: () -> Boolean,
    beautyStrategy: BeautyStrategy,
    detectionEngineMode: EngineType,
    adaptiveFaceDetectionIntervalEnabled: Boolean,
    faceDetectIntervalProfile: FaceDetectIntervalProfile,
    videoCapture: VideoCapture<Recorder>,
    faceDetector: FaceDetector,
    onImageCaptureChanged: (ImageCapture) -> Unit,
    onCameraControlChanged: (CameraControl) -> Unit,
    onZoomRatioChanged: (Float) -> Unit,
    onZoomRangeChanged: (minZoom: Float, maxZoom: Float) -> Unit,
    onActualLensFacingChanged: (Int) -> Unit,
    onFacePointChanged: (Offset) -> Unit,
    onFaceWarpParamsChanged: (FaceWarpParams) -> Unit,
    onShowFocusIndicatorChanged: (Boolean) -> Unit
) {
    Log.d(TAG, "bindCameraUseCases START: aspectRatio=$aspectRatio, captureMode=$captureMode")
    val cameraProvider = cameraProviderFuture.get()
    Logger.d("Camera", "Binding camera with aspectRatio=$aspectRatio")

    val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

    val imageAnalysis = ImageAnalysis.Builder()
        .setTargetAspectRatio(toCameraAspectRatio(aspectRatio))
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        .build()

    val imageCapture = ImageCapture.Builder()
        .setTargetAspectRatio(toCameraAspectRatio(aspectRatio))
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()
    onImageCaptureChanged(imageCapture)

    val useCaseGroup = if (aspectRatio == AspectRatio.RATIO_FULL) {
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val preview = Preview.Builder()
            .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9)
            .setTargetRotation(rotation)
            .build()
            .also { previewUseCase -> bindPreviewSurfaceProvider(previewUseCase) }

        imageCapture.targetRotation = rotation

        val displayMetrics = context.resources.displayMetrics
        val viewport = ViewPort.Builder(
            Rational(displayMetrics.widthPixels, displayMetrics.heightPixels),
            rotation
        ).build()

        val builder = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(imageAnalysis)
            .setViewPort(viewport)

        if (captureMode == MediaType.VIDEO) {
            builder.addUseCase(videoCapture)
        } else {
            builder.addUseCase(imageCapture)
        }

        builder.build()
    } else {
        null
    }

    val preview = if (useCaseGroup == null) {
        Preview.Builder()
            .setTargetAspectRatio(toCameraAspectRatio(aspectRatio))
            .build()
            .also { previewUseCase -> bindPreviewSurfaceProvider(previewUseCase) }
    } else {
        null
    }

    var frameCount = 0
    var lastFrameLogMs = 0L
    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
        frameCount++
        // 限流：帧计数日志 1 秒最多打一次
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastFrameLogMs >= 1_000L) {
            Logger.dThrottled("Camera", "Camera:frameCount", "ImageAnalysis frame #$frameCount (~${frameCount}fps since bind) strategy=$beautyStrategy")
            lastFrameLogMs = nowMs
        }

        // [GC 优化] 美颜关闭时直接关闭 imageProxy，避免函数调用和 try/finally 开销
        if (!isBeautyEnabled()) {
            imageProxy.close()
        } else {
            handleImageAnalysisFrameMediaPipe(
                imageProxy = imageProxy,
                previewView = previewView,
                faceDetector = faceDetector,
                lensFacing = lensFacing,
                detectionEngineMode = detectionEngineMode,
                adaptiveFaceDetectionIntervalEnabled = adaptiveFaceDetectionIntervalEnabled,
                faceDetectIntervalProfile = faceDetectIntervalProfile,
                onFacePointChanged = onFacePointChanged,
                onFaceWarpParamsChanged = onFaceWarpParamsChanged,
                onShowFocusIndicatorChanged = onShowFocusIndicatorChanged,
                beautyEnabled = true
            )
        }
    }

    try {
        cameraProvider.unbindAll()

        val camera = when {
            useCaseGroup != null -> {
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, useCaseGroup)
            }
            else -> {
                when (captureMode) {
                    MediaType.PHOTO, MediaType.DOCUMENT -> {
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview!!,
                            imageCapture,
                            imageAnalysis
                        )
                    }
                    MediaType.VIDEO -> {
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview!!,
                            videoCapture,
                            imageAnalysis
                        )
                    }
                }
            }
        }

        onCameraControlChanged(camera.cameraControl)
        camera.cameraInfo.zoomState.observe(lifecycleOwner) { state ->
            onZoomRatioChanged(state.zoomRatio)
            onZoomRangeChanged(state.minZoomRatio, state.maxZoomRatio)
        }
        onActualLensFacingChanged(camera.cameraInfo.lensFacing)
        Logger.d(
            TAG,
            "Camera bound: lensFacing=${camera.cameraInfo.lensFacing}, selector=$lensFacing, " +
                "useCaseGroup=${useCaseGroup != null}, aspectRatio=$aspectRatio"
        )
    } catch (error: IllegalStateException) {
        Logger.e("Camera", "Camera binding failed (IllegalState), attempting recovery", error)
        // 相机绑定失败时尝试清理并重新绑定
        try {
            cameraProvider.unbindAll()
            Logger.d("Camera", "Unbound all use cases after failure, retry may be triggered by recomposition")
        } catch (cleanupError: IllegalStateException) {
            Logger.e("Camera", "Cleanup after binding failure also failed", cleanupError)
        }
    }
}

