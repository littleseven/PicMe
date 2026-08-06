package com.mamba.picme.domain.aesthetic

import android.graphics.Bitmap

/**
 * 整图美学评分器抽象。
 *
 * 抽卡链路（optimize/gacha）依赖本接口而非具体实现，便于单测 mock 与未来替换评分模型。
 */
interface AestheticScorer {

    /** 初始化模型；不可用（模型未下载等）返回 false，调用方走降级。 */
    suspend fun initialize(): Boolean

    /** 给整图打分，分数越高越美；推理失败返回 null。 */
    fun score(bitmap: Bitmap): Float?

    /** 释放模型资源。由持有本实例的 DI 容器在销毁时调用；引擎/run 周期内不应调用。 */
    fun release()
}
