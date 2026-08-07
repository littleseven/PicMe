package com.mamba.picme.features.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归守卫：US-3 / T4「chat 内美型不生效」修复的结构不变量。
 *
 * 背景（bug）：原先 [ChatImageRenderer.renderRecipe] 直接 `applyGpuEffects(cropped, recipe,
 * faceData = null)` —— faceData 恒为 null，导致瘦脸/大眼/唇色等依赖人脸关键点的美型在 chat
 * 内被静默跳过；且 [ChatImageRenderer.aiOptimize] 误调 `fastOptimize`（旧 API）。
 *
 * 修复（T4）：
 * 1. 构造注入 `faceDetector` + `userSettingsRepository`；
 * 2. 新增 `ensureFacePipeline()`（镜像 ChatEditProcessor，从 repo 读 ROI/landmark 配置调
 *    `updatePipelineConfig`，否则 detectPhoto 静默返回 null）；
 * 3. 新增 `detectFace(bitmap)`（detectPhoto → FaceDataConverter.fromLandmarks106）；
 * 4. `renderRecipe` 在 applyCrop 之后调 `ensureFacePipeline()` 与 `detectFace(cropped)`，
 *    并把结果传给 `applyGpuEffects`（不再 `faceData = null`）；
 * 5. `aiOptimize` 改用 `optimize(`（不再 `fastOptimize`）。
 *
 * 为什么用源码级断言：renderRecipe 内部依赖 `BitmapFactory`（Android 静态）、EGL/GPU、
 * MNN native，纯 JVM 单测无法真正执行到 applyGpuEffects/detectPhoto（见
 * memory/test-env-pitfalls：JVM 单测对 Robolectric SDK36 / mockk 有大量环境性预存失败，
 * 硬门槛为编译通过）。源码级断言精确、确定、零依赖地锁住 T4 的接线契约；一旦未来重构把
 * faceData 改回 null、删除 ensureFacePipeline/detectFace 调用、或退回 fastOptimize，本测试
 * 立刻失败。
 *
 * 这些断言与任务 T4 的验收命令（grep fastOptimize / faceData=null / faceDetector + 编译）
 * 完全同源，把人工 AC 固化为自动化回归。
 */
class ChatImageRendererFaceWiringTest {

    private val sourceFile: java.io.File by lazy {
        // Gradle JVM 测试的 user.dir 默认为模块目录（app/）。
        val moduleDir = java.io.File(System.getProperty("user.dir"))
        val candidates = listOf(
            java.io.File(moduleDir, SRC_REL_PATH),
            java.io.File(moduleDir.parentFile, "app/$SRC_REL_PATH"),
            java.io.File(moduleDir.parentFile?.parentFile, "app/$SRC_REL_PATH")
        )
        candidates.first { it.exists() }
    }

    private fun fullSource(): String = sourceFile.readText()

    /**
     * 从 [startAnchor] 起定位方法体首个 `{`，按花括号配平提取到方法结束 `}`（含）。
     * 与验收命令的 awk 体提取语义一致。
     */
    private fun methodBody(startAnchor: String): String {
        val full = fullSource()
        val startIdx = full.indexOf(startAnchor)
        assertTrue("$startAnchor 方法必须存在", startIdx >= 0)
        val braceStart = full.indexOf('{', startIdx)
        assertTrue("$startAnchor 方法必须有方法体", braceStart >= 0)
        var depth = 0
        var endIdx = -1
        for (i in braceStart until full.length) {
            when (full[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        endIdx = i + 1
                        break
                    }
                }
            }
        }
        assertTrue("$startAnchor 方法体必须完整闭合", endIdx > 0)
        return full.substring(startIdx, endIdx)
    }

    @Test
    fun `aiOptimize calls optimize not fastOptimize`() {
        val body = methodBody("suspend fun aiOptimize(")
        assertTrue(
            "T4: aiOptimize 必须调用 optimizeUseCase.optimize(，实际:\n$body",
            body.contains("optimizeUseCase.optimize(")
        )
        assertFalse(
            "T4: aiOptimize 不得再调用 fastOptimize（已下线）",
            body.contains("fastOptimize")
        )
    }

    @Test
    fun `constructor declares faceDetector and userSettingsRepository params`() {
        val full = fullSource()
        // 构造器参数列表里必须显式声明这两个注入点（T4 修复的核心）。
        assertTrue(
            "T4: 构造器必须注入 faceDetector: FaceDetector",
            full.contains("faceDetector: FaceDetector")
        )
        assertTrue(
            "T4: 构造器必须注入 userSettingsRepository（用于 ensureFacePipeline）",
            full.contains("userSettingsRepository: UserSettingsRepository?")
        )
    }

    @Test
    fun `ensureFacePipeline exists and configures pipeline via repository`() {
        val full = fullSource()
        val anchor = "suspend fun ensureFacePipeline()"
        assertTrue(
            "T4: 必须存在 ensureFacePipeline() 方法",
            full.contains(anchor)
        )
        val body = methodBody(anchor)
        // 镜像 ChatEditProcessor.ensureFacePipeline：从 repo 读两路配置 → updatePipelineConfig。
        assertTrue(
            "T4: ensureFacePipeline 必须读 roiStageConfigFlow",
            body.contains("roiStageConfigFlow.first()")
        )
        assertTrue(
            "T4: ensureFacePipeline 必须读 landmarkStageConfigFlow",
            body.contains("landmarkStageConfigFlow.first()")
        )
        assertTrue(
            "T4: ensureFacePipeline 必须调 faceDetector.updatePipelineConfig",
            body.contains("updatePipelineConfig")
        )
        // 进程级 once：配置后置标志，避免重复初始化。
        assertTrue(
            "T4: ensureFacePipeline 必须有 facePipelineConfigured once 标志",
            full.contains("@Volatile") && full.contains("facePipelineConfigured")
        )
    }

    @Test
    fun `detectFace exists and uses detectPhoto plus FaceDataConverter`() {
        val anchor = "suspend fun detectFace("
        assertTrue(
            "T4: 必须存在 detectFace(bitmap) 方法",
            fullSource().contains(anchor)
        )
        val body = methodBody(anchor)
        assertTrue(
            "T4: detectFace 必须调 faceDetector.detectPhoto(bitmap, lensFacing = 1)",
            body.contains("detectPhoto(bitmap, lensFacing = 1)")
        )
        assertTrue(
            "T4: detectFace 必须用 FaceDataConverter.fromLandmarks106 转换关键点",
            body.contains("FaceDataConverter.fromLandmarks106(")
        )
    }

    @Test
    fun `renderRecipe wires ensureFacePipeline then detectFace then applyGpuEffects with faceData`() {
        val body = methodBody("suspend fun renderRecipe(")

        // 关键时序：applyCrop 之后必须先 ensureFacePipeline 再 detectFace。
        val cropIdx = body.indexOf("applyCrop")
        val ensureIdx = body.indexOf("ensureFacePipeline()")
        val detectIdx = body.indexOf("detectFace(cropped)")
        val gpuIdx = body.indexOf("applyGpuEffects(")

        assertTrue("renderRecipe 必须调用 applyCrop", cropIdx >= 0)
        assertTrue(
            "T4: renderRecipe 必须在 applyCrop 之后调用 ensureFacePipeline()",
            ensureIdx >= 0 && ensureIdx > cropIdx
        )
        assertTrue(
            "T4: renderRecipe 必须在 applyCrop 之后调用 detectFace(cropped)",
            detectIdx >= 0 && detectIdx > cropIdx
        )
        assertTrue(
            "T4: detectFace 必须先于 applyGpuEffects（产物作为 faceData 传入）",
            detectIdx >= 0 && gpuIdx >= 0 && detectIdx < gpuIdx
        )
        // applyGpuEffects 必须接收 detectFace 的产物，而非恒为 null。
        assertFalse(
            "T4: applyGpuEffects 不得再传 faceData = null（美型会被静默跳过）",
            body.contains("faceData = null")
        )
        assertTrue(
            "T4: applyGpuEffects 必须接收 faceData 变量（detectFace 产物）",
            body.contains("applyGpuEffects(cropped, recipe, faceData)")
        )
    }

    @Test
    fun `renderRecipe never hardcodes faceData null anywhere`() {
        // 与验收命令同源：整个文件不得再出现 faceData = null。
        assertFalse(
            "T4: ChatImageRenderer 不得出现 faceData = null",
            fullSource().contains("faceData = null")
        )
    }

    @Test
    fun `detectFace swallows exceptions and returns null on failure`() {
        val body = methodBody("suspend fun detectFace(")
        // T4 要求：检测异常时返回 null + 日志警告，绝不让 chat 渲染链路崩。
        assertTrue(
            "T4: detectFace 必须用 runCatching 兜底异常",
            body.contains("runCatching")
        )
        assertTrue(
            "T4: detectFace 异常时必须记日志警告",
            body.contains("Logger.w(") || body.contains("Logger.w")
        )
        assertTrue(
            "T4: detectFace 必须 getOrNull() 安全返回（异常→null）",
            body.contains(".getOrNull()")
        )
    }

    companion object {
        private const val SRC_REL_PATH = "src/main/java/com/mamba/picme/features/chat/ChatImageRenderer.kt"
    }
}
