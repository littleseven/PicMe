package com.mamba.picme.features.debug.pexels

/**
 * 单张 Pexels 图片保存抽象：隔离 SampleDataGenerator（Android 依赖），
 * 让 PexelsViewModel 可用 fake 做纯 JVM 单测。
 * 实现侧委托 SampleDataGenerator.savePexelsPhoto()。
 */
fun interface PexelsImageSaver {
    /** @return true=已存相册并插库；false=下载失败或被过滤 */
    suspend fun save(photoId: Long, imageUrl: String): Boolean
}
