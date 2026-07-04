package com.mamba.picme.domain.agent.capability.optimize.preset

import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene

/**
 * AI 优化预设仓库接口
 */
interface PresetRepository {

    /**
     * 获取指定场景的优化预设
     *
     * @param scene 照片场景
     * @return 优化预设，若找不到则返回通用场景预设
     */
    fun getPreset(scene: Scene): OptimizePreset

    /**
     * 获取所有可用预设
     */
    fun getAllPresets(): Map<Scene, OptimizePreset>
}
