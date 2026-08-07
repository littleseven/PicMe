package com.mamba.picme.features.camera.state

import android.content.Context
import androidx.camera.core.ImageCapture
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.core.image.ImageProcessor
import com.mamba.picme.domain.model.BeautyStrategy
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.features.gallery.MediaViewModel

/**
 * [Day3 消息队列化] 相机动作消息 —— 所有拍照/录制操作通过消息队列投递
 *
 * 主线程只发消息，不直接执行相机操作。
 * CameraHandlerThread 消费消息并执行实际逻辑。
 */
sealed class CameraAction {

    /** 开始预览（绑定 UseCase） */
    data class StartPreview(
        val lensFacing: Int,
        val captureMode: MediaType,
        val aspectRatio: Int
    ) : CameraAction()

    /** 拍照 */
    data class CapturePhoto(
        val context: Context,
        val imageCapture: ImageCapture,
        val viewModel: MediaViewModel,
        val imageProcessor: ImageProcessor,
        val filter: FilterType,
        val beauty: BeautySettings,
        val lensFacing: Int,
        val mode: MediaType,
        val beautyStrategy: BeautyStrategy
    ) : CameraAction()

    /** 开始录制 */
    data class StartRecording(
        val context: Context,
        val lensFacing: Int
    ) : CameraAction()

    /** 停止录制 */
    data class StopRecording(
        val context: Context
    ) : CameraAction()

    /** 切换镜头 */
    data class SwitchLens(
        val newLensFacing: Int
    ) : CameraAction()

    /** 切换比例 */
    data class SwitchAspectRatio(
        val newAspectRatio: Int
    ) : CameraAction()

    /** 切换模式（拍照/视频） */
    data class SwitchMode(
        val newMode: MediaType
    ) : CameraAction()

    /** 重置状态机 */
    data object Reset : CameraAction()
}
