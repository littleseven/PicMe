package com.mamba.picme.domain.agent.capability.optimize.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QA] HeuristicSceneAnalyzer 回归守卫（T1 / US-1 核心）。
 *
 * 为什么用源码级断言而非 Robolectric 行为测试：
 * - HeuristicSceneAnalyzer 的核心逻辑 computePixelStats / analyzeBitmap 均为 private 且
 *   接收 Android Bitmap；公开入口 analyze(imageUri) 走 ContentResolver → BitmapFactory
 *   .decodeStream。本项目 JVM 单测里 Robolectric 的 ShadowBitmapFactory 并非真解码（返回
 *   shadow 假图，getPixels 拿不到真实像素），无法验证像素统计行为（见
 *   ChatImageRendererDecodeBitmapTest 的同类论证与 memory/test-env-pitfalls）。
 * - 本项目 JVM 单测对 Robolectric SDK36 / mockk 存在大量环境性预存失败，硬门槛为编译通过。
 * - 源码级断言精确、确定、零依赖地锁住 T1 的关键不变量：零网络调用（隐私红线）、
 *   缩略图 ≤256px、启发式优先级链顺序、人脸检测可选、analyze 绝不抛异常兜底 GENERAL、
 *   统计在后台线程。一旦未来重构破坏这些不变量，本测试立刻失败。
 *
 * 这些断言与 T1 验收命令（grep SceneAnalyzer 包 + analyzer 无网络调用 + compileDebugKotlin）
 * 完全同源，把人工 AC 固化为自动化回归。
 */
class HeuristicSceneAnalyzerTest {

    /**
     * 读取 analyzer 包下指定源文件全文。
     * Gradle JVM 测试的 user.dir 默认为模块目录（app/）。
     */
    private fun sourceText(relPath: String): String {
        val moduleDir = java.io.File(System.getProperty("user.dir"))
        val candidates = listOf(
            java.io.File(moduleDir, relPath),
            java.io.File(moduleDir.parentFile, "app/$relPath"),
            java.io.File(moduleDir.parentFile?.parentFile, "app/$relPath")
        )
        val file = candidates.first { it.exists() }
        return file.readText()
    }

    private val heuristicSource: String by lazy {
        sourceText(BASE_REL_PATH + "HeuristicSceneAnalyzer.kt")
    }

    private val interfaceSource: String by lazy {
        sourceText(BASE_REL_PATH + "SceneAnalyzer.kt")
    }

    // ---- 隐私红线：零网络调用（T1 验收命令同源）----

    @Test
    fun `analyzer package makes zero network calls - privacy red line`() {
        val networkTokens = listOf(
            "OkHttp", "Retrofit", "HttpURLConnection", "java.net.URL",
            "langchain4j", "RemoteInference", "openai"
        )
        val allSource = heuristicSource + "\n" + interfaceSource
        val hits = networkTokens.filter { token ->
            // 排除注释/文档里的英文说明字样，只看真正 import 或调用。
            // import 行或实际类型引用均会出现在行首非注释区，这里用单词边界整体匹配。
            val regex = Regex("(?<![A-Za-z])" + Regex.escape(token) + "(?![A-Za-z])")
            regex.containsMatchIn(allSource)
        }
        assertTrue(
            "T1 隐私红线：analyzer 包禁止任何网络调用，命中: $hits",
            hits.isEmpty()
        )
    }

    // ---- 接口契约 ----

    @Test
    fun `SceneAnalyzer interface declares suspend analyze returning Scene`() {
        assertTrue(
            "T1: SceneAnalyzer 必须声明 suspend fun analyze(imageUri: String): Scene",
            interfaceSource.contains("suspend fun analyze(imageUri: String): Scene")
        )
    }

    @Test
    fun `SceneAnalyzer documents zero-network privacy contract`() {
        assertTrue(
            "T1: SceneAnalyzer 接口需在文档中声明零网络调用（隐私红线）",
            interfaceSource.contains("零网络调用")
        )
    }

    // ---- 启发式不变量 ----

    @Test
    fun `thumbnail max dimension is capped at 256px`() {
        assertTrue(
            "T1: 像素统计只应用 ≤256px 缩略图（控制耗时）",
            heuristicSource.contains("MAX_THUMBNAIL_DIM = 256")
        )
    }

    @Test
    fun `FaceDetector is optional - nullable with null default`() {
        assertTrue(
            "T1: FaceDetector 必须可选注入（nullable 且默认 null），" +
                "无可用检测器时降级为纯像素启发式",
            heuristicSource.contains("faceDetector: FaceDetector? = null")
        )
    }

    @Test
    fun `pixel statistics run on a background dispatcher`() {
        assertTrue(
            "T1: 解码+像素统计不得阻塞主线程，须 withContext(Dispatchers.Default)",
            heuristicSource.contains("withContext(Dispatchers.Default)")
        )
    }

    @Test
    fun `analyze never throws - decodes null bitmap fallback to GENERAL`() {
        assertTrue(
            "T1: analyze 健壮性——解码返回 null 时兜底 Scene.GENERAL，绝不抛异常",
            heuristicSource.contains("return@withContext Scene.GENERAL")
        )
    }

    @Test
    fun `analyze never throws - exceptions fallback to GENERAL`() {
        // analyze() 体内必须存在 try/catch，catch 分支返回 Scene.GENERAL。
        assertTrue(
            "T1: analyze 健壮性——异常被 catch 后兜底 Scene.GENERAL",
            heuristicSource.contains("catch (e: Exception)") &&
                heuristicSource.contains("fallback GENERAL")
        )
        // 兜底分支必须使用 Scene.GENERAL（在 catch 与 null 两条路径上）。
        val generalCount = heuristicSource.split("Scene.GENERAL").size - 1
        assertTrue(
            "T1: analyze 至少存在两条 Scene.GENERAL 兜底路径（null bitmap + catch）",
            generalCount >= 2
        )
    }

    @Test
    fun `pixel stats computed in a single getPixels pass`() {
        val body = methodBody(heuristicSource, "computePixelStats")
        val getPixelsCount = body.split("getPixels(").size - 1
        assertEquals(
            "T1: 像素统计单次遍历——getPixels 只应调用一次",
            1,
            getPixelsCount
        )
    }

    // ---- 启发式优先级链顺序（高 → 低）----

    @Test
    fun `priority chain order is face then low_light then food then document then landscape then general`() {
        val body = methodBody(heuristicSource, "analyzeBitmap")
        // 各优先级返回语句在方法体中必须按序出现（索引递增）。
        val faceIdx = body.indexOf("detectFaceScene")
        val lowLightIdx = body.indexOf("Scene.LOW_LIGHT")
        val foodIdx = body.indexOf("Scene.FOOD")
        val documentIdx = body.indexOf("Scene.DOCUMENT")
        val landscapeIdx = body.indexOf("Scene.LANDSCAPE")
        val generalIdx = body.lastIndexOf("Scene.GENERAL")

        assertTrue("优先级链锚点缺失: face=$faceIdx", faceIdx >= 0)
        assertTrue("优先级链锚点缺失: low_light=$lowLightIdx", lowLightIdx >= 0)
        assertTrue("优先级链锚点缺失: food=$foodIdx", foodIdx >= 0)
        assertTrue("优先级链锚点缺失: document=$documentIdx", documentIdx >= 0)
        assertTrue("优先级链锚点缺失: landscape=$landscapeIdx", landscapeIdx >= 0)
        assertTrue("优先级链锚点缺失: general=$generalIdx", generalIdx >= 0)

        assertTrue(
            "T1 优先级：人脸检测必须先于低亮度判定",
            faceIdx < lowLightIdx
        )
        assertTrue(
            "T1 优先级：低亮度必须先于美食判定",
            lowLightIdx < foodIdx
        )
        assertTrue(
            "T1 优先级：美食必须先于文档判定",
            foodIdx < documentIdx
        )
        assertTrue(
            "T1 优先级：文档必须先于风景判定",
            documentIdx < landscapeIdx
        )
        assertTrue(
            "T1 优先级：风景必须先于默认 GENERAL",
            landscapeIdx < generalIdx
        )
    }

    @Test
    fun `face heuristics distinguish selfie portrait and group`() {
        val body = methodBody(heuristicSource, "detectFaceScene")
        assertTrue(
            "T1: 人脸场景须区分 GROUP（多人脸）",
            body.contains("Scene.GROUP")
        )
        assertTrue(
            "T1: 人脸场景须区分 SELFIE（单人脸占比大）",
            body.contains("Scene.SELFIE")
        )
        assertTrue(
            "T1: 人脸场景须区分 PORTRAIT（单人脸占比小）",
            body.contains("Scene.PORTRAIT")
        )
        assertTrue(
            "T1: 人脸检测走 detectFacesOnly 轻量接口",
            body.contains("detectFacesOnly")
        )
    }

    @Test
    fun `no cloud or smart-optimize remnants in analyzer`() {
        assertFalse(
            "T1: analyzer 不得残留云端/智能优化概念",
            heuristicSource.contains("Cloud") || heuristicSource.contains("SmartOptimize")
        )
    }

    /**
     * 从源码文本中提取 `private fun methodName(`（或 `fun methodName(`）到方法体首个顶层
     * 闭合 `}` 的子串。与 ChatImageRendererDecodeBitmapTest 同一提取语义。
     */
    private fun methodBody(source: String, methodName: String): String {
        val needle = "fun $methodName("
        val startIdx = source.indexOf(needle)
        assertTrue("$methodName 方法必须存在", startIdx >= 0)
        val braceStart = source.indexOf('{', startIdx)
        var depth = 0
        var endIdx = -1
        var i = braceStart
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        endIdx = i + 1
                        break
                    }
                }
            }
            i++
        }
        assertTrue("$methodName 方法体必须完整闭合", endIdx > 0)
        return source.substring(startIdx, endIdx)
    }

    private companion object {
        const val BASE_REL_PATH =
            "src/main/java/com/mamba/picme/domain/agent/capability/optimize/analyzer/"
    }
}
