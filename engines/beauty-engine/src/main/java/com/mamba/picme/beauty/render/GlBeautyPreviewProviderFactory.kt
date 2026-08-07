package com.mamba.picme.beauty.render

import android.content.Context
import com.mamba.picme.beauty.api.BeautyPreviewProvider
import com.mamba.picme.beauty.api.BeautyPreviewProviderFactory
import com.mamba.picme.beauty.api.PhotoProcessor

/**
 * GL 美颜预览提供者工厂实现
 *
 * 封装 [GlBeautyPreviewProvider] 的创建逻辑，对外仅暴露 [BeautyPreviewProvider] 接口。
 * 同时提供 [PhotoProcessor] 的创建能力，支持拍照 GPU 化。
 */
class GlBeautyPreviewProviderFactory : BeautyPreviewProviderFactory {

    override fun create(context: Context): BeautyPreviewProvider {
        return GlBeautyPreviewProvider(context)
    }

    /**
     * 创建拍照后处理器（GPU 离屏渲染）
     *
     * @param context Application 或 Activity Context
     * @return PhotoProcessor 实例
     */
    fun createPhotoProcessor(context: Context): PhotoProcessor {
        return PhotoProcessorImpl(context)
    }
}
