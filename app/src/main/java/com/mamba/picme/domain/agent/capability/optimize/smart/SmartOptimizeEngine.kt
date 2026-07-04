package com.mamba.picme.domain.agent.capability.optimize.smart

import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset

/**
 * 云端智能优化引擎接口
 *
 * 通过视觉大模型分析图片并返回推荐配方。
 */
interface SmartOptimizeEngine {

    /**
     * 云端智能优化
     *
     * @param imageUri 图片本地 URI
     * @return 推荐优化预设
     */
    suspend fun optimize(imageUri: String): OptimizePreset
}
