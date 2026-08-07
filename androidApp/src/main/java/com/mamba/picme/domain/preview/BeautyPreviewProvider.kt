package com.mamba.picme.domain.preview

import com.mamba.picme.beauty.api.BeautyPreviewProvider as BeautyEnginePreviewProvider

/**
 * 美颜预览提供者接口（typealias 桥接）
 *
 * Phase 2 后，此接口的权威定义已迁移至 beauty-engine-rplan 模块的
 * [BeautyEnginePreviewProvider]。
 * 此 typealias 保持 app 层调用方无感知兼容。
 *
 * @see BeautyEnginePreviewProvider
 */
typealias BeautyPreviewProvider = BeautyEnginePreviewProvider
