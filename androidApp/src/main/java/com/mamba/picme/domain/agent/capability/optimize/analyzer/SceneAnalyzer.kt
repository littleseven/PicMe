package com.mamba.picme.domain.agent.capability.optimize.analyzer

/**
 * 端侧场景分析器
 *
 * 对单张图片做端侧启发式分析，输出 [Scene]，供一键优化按场景路由预设。
 *
 * 实现必须保证**零网络调用**（隐私红线），所有媒体处理 100% 本地完成；
 * 不得上传用户图片到远程大模型/推理服务器。
 */
interface SceneAnalyzer {

    /**
     * 分析图片场景
     *
     * @param imageUri 图片本地 URI（content:// 或 file://）
     * @return 识别到的场景；分析过程异常时兜底返回 [Scene.GENERAL]，绝不抛出
     */
    suspend fun analyze(imageUri: String): Scene
}
