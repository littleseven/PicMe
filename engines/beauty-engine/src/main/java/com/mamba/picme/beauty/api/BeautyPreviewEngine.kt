package com.mamba.picme.beauty.api

import android.view.Surface
import android.view.View

/**
 * 美颜预览引擎组合接口
 *
 * 同时继承 [BeautyPreviewProvider] 与 [BeautyPreviewCapability]，
 * 用于需要完整预览 + 人脸变形能力的场景。
 *
 * @since Phase 3（库化）
 */
interface BeautyPreviewEngine : BeautyPreviewProvider, BeautyPreviewCapability {

    /**
     * 获取引擎内部托管的预览视图实例（供 UI 层嵌入容器）
     */
    fun getView(): View

    /**
     * 开始将美颜后的帧输出到录制编码 Surface
     *
     * @param encoderSurface MediaCodec 编码器输入 Surface
     * @param width 录制视频宽度
     * @param height 录制视频高度
     */
    fun startRecording(encoderSurface: Surface, width: Int, height: Int)

    /**
     * 停止录制输出
     */
    fun stopRecording()
}
