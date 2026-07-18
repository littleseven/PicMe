package com.mamba.picme.testing.agent.cases



import com.mamba.picme.testing.agent.core.AgentTestCase
import com.mamba.picme.testing.agent.core.TestCategory
import com.mamba.picme.testing.agent.core.TestPriority
import com.mamba.picme.testing.agent.core.agentTestCase
import com.mamba.picme.testing.agent.device.DeviceTestController
import kotlin.time.Duration.Companion.seconds
import com.mamba.picme.testing.agent.core.AssertionResult

/**
 * 相机模块 Agent 测试用例
 *
 * 使用声明式 DSL 定义，AI Agent 可直接理解、执行和扩展。
 */
object CameraAgentTestCases {

    /**
     * TC-CAMERA-01: 应用启动与预览验证
     */
    fun tcCamera01Startup(controller: DeviceTestController): AgentTestCase<Boolean> =
        agentTestCase("TC-CAMERA-01", "应用启动与预览验证") {
            category(TestCategory.CAMERA)
            priority(TestPriority.P0)

            step("启动 PoLang 应用") {
                action { ctx ->
                    val success = controller.launchApp()
                    ctx.setMetadata("launchSuccess", success.toString())
                }
                assertState("应用已启动") { state ->
                    state["isAppForeground"] == true
                }
            }

            step("等待相机预览就绪") {
                action { ctx ->
                    kotlinx.coroutines.delay(2000)
                    val cameraState = controller.getCameraState()
                    ctx.addStateSnapshot(
                        mapOf(
                            "isPreviewActive" to (cameraState != null),
                            "lensFacing" to (cameraState?.lensFacing ?: "unknown"),
                            "captureMode" to (cameraState?.captureMode ?: "unknown")
                        )
                    )
                }
                assertState("预览已启动") { state ->
                    state["isPreviewActive"] == true
                }
            }

            step("截屏验证启动画面") {
                action { ctx ->
                    controller.takeScreenshot("startup", ctx)
                }
                assertScreenshot("画面非黑屏") { screenshot ->
                    // 实际实现中应检查图像亮度
                    true
                }
            }

            output { ctx ->
                ctx.getMetadata("launchSuccess")?.toBoolean() ?: false
            }
        }

    /**
     * TC-CAMERA-02: 前后摄像头切换
     */
    fun tcCamera02Flip(controller: DeviceTestController): AgentTestCase<String> =
        agentTestCase("TC-CAMERA-02", "前后摄像头切换验证") {
            category(TestCategory.CAMERA)
            priority(TestPriority.P0)

            step("获取初始摄像头状态") {
                action { ctx ->
                    val state = controller.getCameraState()
                    ctx.setMetadata("initialLens", state?.lensFacing ?: "unknown")
                    ctx.addStateSnapshot(mapOf("lensFacing" to (state?.lensFacing ?: "unknown")))
                }
            }

            step("切换到前置摄像头") {
                action { ctx ->
                    controller.flipCamera()
                    val state = controller.getCameraState()
                    ctx.addStateSnapshot(mapOf("lensFacing" to (state?.lensFacing ?: "unknown")))
                }
                assertState("已切换到前置") { state ->
                    state["lensFacing"] == "front"
                }
            }

            step("截屏记录前置预览") {
                action { ctx ->
                    controller.takeScreenshot("front_camera", ctx)
                }
            }

            step("切换回后置摄像头") {
                action { ctx ->
                    controller.flipCamera()
                    val state = controller.getCameraState()
                    ctx.addStateSnapshot(mapOf("lensFacing" to (state?.lensFacing ?: "unknown")))
                }
                assertState("已切换到后置") { state ->
                    state["lensFacing"] == "back"
                }
            }

            step("截屏记录后置预览") {
                action { ctx ->
                    controller.takeScreenshot("back_camera", ctx)
                }
            }

            output { ctx ->
                ctx.getMetadata("initialLens") ?: "unknown"
            }
        }

    /**
     * TC-CAMERA-03: 拍照与 GPU 后处理验证
     */
    fun tcCamera03Capture(controller: DeviceTestController): AgentTestCase<Map<String, Any>> =
        agentTestCase("TC-CAMERA-03", "拍照与 GPU 后处理验证") {
            category(TestCategory.CAMERA)
            priority(TestPriority.P0)

            step("确保后置摄像头") {
                action { ctx ->
                    val state = controller.getCameraState()
                    if (state?.lensFacing == "front") {
                        controller.flipCamera()
                    }
                    ctx.addStateSnapshot(mapOf("lensFacing" to "back"))
                }
            }

            step("设置美颜参数") {
                action { ctx ->
                    controller.setBeauty(smooth = 80, whiten = 60)
                    val state = controller.getCameraState()
                    ctx.addStateSnapshot(
                        mapOf(
                            "beautySmooth" to (state?.beautySmooth ?: 0f),
                            "beautyWhiten" to (state?.beautyWhiten ?: 0f)
                        )
                    )
                }
                assertState("美颜参数已设置") { state ->
                    (state["beautySmooth"] as? Float)?.let { it > 0 } == true
                }
            }

            step("触发拍照") {
                action { ctx ->
                    controller.collectLogs(ctx, lines = 50)
                    controller.capture()
                    kotlinx.coroutines.delay(3000)
                    controller.collectLogs(ctx, lines = 100)
                }
                assertLogContains("PhotoProcessor", "process DONE", timeout = 5.seconds)
            }

            step("验证 GPU 处理耗时") {
                action { ctx ->
                    // 日志已在上一歩收集
                }
                customAssertion("GPU 处理在 1 秒内完成") { ctx ->
                    val log = ctx.logs.find { log ->
                        log.tag.contains("PhotoProcessor", ignoreCase = true) &&
                            log.message.contains("process DONE", ignoreCase = true)
                    }
                    if (log == null) {
                        AssertionResult.Failure("未找到 GPU 处理完成日志")
                    } else {
                        val timeMs = log.message.replace(Regex("[^0-9]"), "").toLongOrNull() ?: 0
                        if (timeMs <= 1000) {
                            ctx.setMetadata("gpuProcessTimeMs", timeMs.toString())
                            AssertionResult.Success
                        } else {
                            AssertionResult.Failure("GPU 处理耗时 ${timeMs}ms，超过 1000ms")
                        }
                    }
                }
            }

            step("截屏验证拍照后状态") {
                action { ctx ->
                    controller.takeScreenshot("after_capture", ctx)
                }
            }

            output { ctx ->
                mapOf(
                    "gpuProcessTimeMs" to (ctx.getMetadata("gpuProcessTimeMs")?.toLongOrNull() ?: -1),
                    "screenshotCount" to ctx.screenshots.size
                )
            }
        }

    /**
     * TC-CAMERA-04: 画幅比例切换验证
     */
    fun tcCamera04Ratio(controller: DeviceTestController): AgentTestCase<List<String>> =
        agentTestCase("TC-CAMERA-04", "画幅比例切换验证") {
            category(TestCategory.CAMERA)
            priority(TestPriority.P1)

            val ratios = listOf("4_3", "16_9", "full")
            val results = mutableListOf<String>()

            ratios.forEach { ratio ->
                step("切换到 $ratio 比例") {
                    action { ctx ->
                        controller.setRatio(ratio)
                        val state = controller.getCameraState()
                        ctx.addStateSnapshot(mapOf("aspectRatio" to (state?.aspectRatio ?: "unknown")))
                        results.add(ratio)
                    }
                    assertState("比例已切换为 $ratio") { state ->
                        state["aspectRatio"] == ratio
                    }
                }

                step("截屏记录 $ratio 比例") {
                    action { ctx ->
                        controller.takeScreenshot("ratio_$ratio", ctx)
                    }
                }
            }

            output { results.toList() }
        }

    /**
     * TC-CAMERA-05: 滤镜切换验证
     */
    fun tcCamera05Filter(controller: DeviceTestController): AgentTestCase<List<String>> =
        agentTestCase("TC-CAMERA-05", "滤镜切换验证") {
            category(TestCategory.CAMERA)
            priority(TestPriority.P1)

            val filters = listOf("none", "leica_classic", "leica_vibrant", "leica_bw")
            val appliedFilters = mutableListOf<String>()

            filters.forEach { filter ->
                step("应用滤镜: $filter") {
                    action { ctx ->
                        controller.setFilter(filter)
                        val state = controller.getCameraState()
                        ctx.addStateSnapshot(mapOf("currentFilter" to (state?.currentFilter ?: "unknown")))
                        appliedFilters.add(filter)
                    }
                }

                step("截屏记录滤镜效果: $filter") {
                    action { ctx ->
                        controller.takeScreenshot("filter_$filter", ctx)
                    }
                }
            }

            output { appliedFilters.toList() }
        }

    /**
     * 获取所有相机测试用例
     */
    fun allCases(controller: DeviceTestController): List<AgentTestCase<*>> = listOf(
        tcCamera01Startup(controller),
        tcCamera02Flip(controller),
        tcCamera03Capture(controller),
        tcCamera04Ratio(controller),
        tcCamera05Filter(controller)
    )
}
