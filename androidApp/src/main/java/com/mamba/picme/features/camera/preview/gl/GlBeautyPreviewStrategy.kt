package com.mamba.picme.features.camera.preview.gl

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.mamba.picme.core.common.Logger
import com.mamba.picme.beauty.api.BeautyPreviewEngine
import com.mamba.picme.beauty.api.toBeautyParams
import com.mamba.picme.domain.model.BeautyStrategy
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.features.camera.AspectRatio
import com.mamba.picme.features.camera.stopFaceDetectionWorker
import com.mamba.picme.features.camera.preview.core.BeautyPreviewEngineStrategy
import com.mamba.picme.beauty.api.facedetect.FaceWarpParams

internal class GlBeautyPreviewStrategy(
    private val previewView: PreviewView,
    private val glBeautyPreviewProvider: BeautyPreviewEngine,
    private val onWarmUpFallback: (String) -> Unit,
    private val lensFacing: Int = CameraSelector.LENS_FACING_BACK
) : BeautyPreviewEngineStrategy {
    override val strategy: BeautyStrategy = BeautyStrategy.BIG_BEAUTY

    /**
     * 当前活跃的生产者 Surface，用于延迟释放避免 "Surface was abandoned" 崩溃。
     * 当 CameraX 调用 unbindAll() → 重新 bindToLifecycle() 时，旧的 SurfaceRequest
     * 可能在 CaptureSession 创建 OutputConfiguration 的异步窗口期内被 release()，
     * 导致 IllegalArgumentException: Surface was abandoned。
     * 解决方案：新 SurfaceProvider 设置完成后再释放旧 Surface。
     */
    private var activePreviewSurface: android.view.Surface? = null

    override fun bindPreview(previewUseCase: Preview, aspectRatio: Int): Boolean {
        return try {
            glBeautyPreviewProvider.initialize()
            glBeautyPreviewProvider.setIsFrontCamera(lensFacing == CameraSelector.LENS_FACING_FRONT)
            glBeautyPreviewProvider.setScaleMode(isFillCenter = aspectRatio == AspectRatio.RATIO_FULL)

            val mainExecutor = ContextCompat.getMainExecutor(previewView.context)
            previewUseCase.setSurfaceProvider { request ->
                // [Surface 生命周期] 释放旧 Surface，避免 BufferQueue 生产者端堆积
                val oldSurface = activePreviewSurface
                activePreviewSurface = null

                val resolution = request.resolution
                glBeautyPreviewProvider.setCameraInputBufferSize(
                    width = resolution.width,
                    height = resolution.height
                )
                val previewSurface = glBeautyPreviewProvider.createPreviewSurface()
                activePreviewSurface = previewSurface

                request.provideSurface(previewSurface, mainExecutor) { result ->
                    Logger.d("Camera", "GL beauty surface request completed: $result")
                    // [关键修复] 延迟释放：仅当此 Surface 已不是当前活跃 Surface 时才释放。
                    // 避免在 unbindAll() → 重新 bind 的异步窗口期内释放正在被 CameraX 使用的 Surface。
                    if (activePreviewSurface != previewSurface) {
                        previewSurface.release()
                        Logger.d("Camera", "Old previewSurface released (not active anymore)")
                    }
                }

                // 新 Surface 已建立，安全释放旧 Surface
                oldSurface?.let { surface ->
                    if (surface != previewSurface) {
                        surface.release()
                        Logger.d("Camera", "Previous previewSurface released after new one established")
                    }
                }
            }

            Logger.i("Camera", "Preview connected via GL beauty provider surface, aspectRatio=$aspectRatio")
            true
        } catch (error: Throwable) {
            Logger.w("Camera", "GL beauty warm-up failed, fallback to PreviewView", error)
            previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
            onWarmUpFallback(error.message ?: "warm-up error")
            false
        }
    }

    override fun applyBeautySettings(settings: BeautySettings) {
        try {
            glBeautyPreviewProvider.initialize()
            glBeautyPreviewProvider.updateFilters(settings.toBeautyParams())
        } catch (error: Throwable) {
            Logger.w("Camera", "GL beauty update failed", error)
        }
    }

    override fun applyFaceWarpParams(params: FaceWarpParams) {
        runCatching {
            glBeautyPreviewProvider.updateFaceWarpParams(
                faceCenterX = params.faceCenterX,
                faceCenterY = params.faceCenterY,
                leftEyeX = params.leftEyeX,
                leftEyeY = params.leftEyeY,
                rightEyeX = params.rightEyeX,
                rightEyeY = params.rightEyeY,
                mouthCenterX = params.mouthCenterX,
                mouthCenterY = params.mouthCenterY,
                mouthLeftX = params.mouthLeftX,
                mouthLeftY = params.mouthLeftY,
                mouthRightX = params.mouthRightX,
                mouthRightY = params.mouthRightY,
                upperLipCenterX = params.upperLipCenterX,
                upperLipCenterY = params.upperLipCenterY,
                lowerLipCenterX = params.lowerLipCenterX,
                lowerLipCenterY = params.lowerLipCenterY,
                faceRadius = params.faceRadius,
                hasFace = params.hasFace
            )
            glBeautyPreviewProvider.updateLipMaskPoints(
                outerPoints = params.lipOuterContourPoints.map { contourPoint ->
                    Pair(contourPoint.x, contourPoint.y)
                },
                innerPoints = params.lipInnerContourPoints.map { contourPoint ->
                    Pair(contourPoint.x, contourPoint.y)
                }
            )
            glBeautyPreviewProvider.updateCheekContourPoints(
                leftCheekPoints = params.leftCheekContourPoints.map { contourPoint ->
                    Pair(contourPoint.x, contourPoint.y)
                },
                rightCheekPoints = params.rightCheekContourPoints.map { contourPoint ->
                    Pair(contourPoint.x, contourPoint.y)
                }
            )
            // 传递106点关键点给GPUPixel风格瘦脸/大眼
            val bigBeautyLandmarks = params.bigBeautyLandmarks
            // [GC 优化] 使用 rawPoints 直接传递 FloatArray，避免 points 迭代重建
            if (bigBeautyLandmarks.hasFace && bigBeautyLandmarks.rawPoints.isNotEmpty()) {
                glBeautyPreviewProvider.updateFacePoints106(bigBeautyLandmarks.rawPoints)
            }
        }.onFailure { error ->
            Logger.w("Camera", "GL beauty face params update failed", error)
        }
    }

    override fun release() {
        // [帧同步] 停止全局 FaceDetectionWorker（若残留），再释放 GL 资源
        stopFaceDetectionWorker()
        // [Surface 生命周期] 释放残留的生产者 Surface，避免内存泄漏
        activePreviewSurface?.release()
        activePreviewSurface = null
        glBeautyPreviewProvider.release()
    }
}

