package com.mamba.picme.agent.core.tool

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.agent.core.runtime.state.SceneManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * 相机工具辅助类。
 *
 * 统一处理相机工具的执行前准备：
 * 1. 检查当前场景，如果不在 CAMERA 场景，先导航到相机页
 * 2. 等待场景切换和 CameraCapability 绑定完成
 * 3. 构建 AgentCommand 并通过 CapabilityRegistry 分发执行
 *
 * 这是解决 ReAct Agent 远程控制时"相机命令在 CHAT 场景找不到 Capability"问题的关键：
 * CameraCapability 是页面级 Capability，只在 CameraScreen 中注册，不在全局 registry 中。
 * 当 App 不在相机页时，CapabilityRegistry.findCapabilityForCommand() 找不到 CameraCapability，
 * 导致命令被入队但永远不被执行（因为没有导航触发）。
 *
 * 此辅助类在工具层主动触发导航，确保相机命令能在正确的场景下执行。
 */
object CameraToolHelper {

    private const val TAG = "CameraToolHelper"
    private const val NAVIGATION_TIMEOUT_MS = 5000L
    private const val SCENE_POLL_INTERVAL_MS = 100L
    private const val CAPABILITY_WAIT_MS = 300L

    /**
     * dispatch 常驻内部 scope（SupervisorJob 隔离单命令失败）。替代 GlobalScope：
     * 等待超时后通过 deferred.cancel() 级联取消底层 dispatch 协程，避免协程裸跑。
     */
    private val dispatchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 根据方法名和参数构建对应的 AgentCommand
     *
     * 直接构建命令对象，不经过 method/params JSON 解析。
     */
    private fun buildAgentCommand(method: String, params: Map<String, Any>): AgentCommand {
        return when (method) {
            "capture" -> AgentCommand.CapturePhoto()
            "flip_camera" -> AgentCommand.FlipCamera()
            "toggle_recording" -> AgentCommand.ToggleRecording()
            "switch_mode" -> {
                val modeName = params["mode"] as? String ?: "PHOTO"
                val mode = runCatching { MediaType.valueOf(modeName) }.getOrDefault(MediaType.PHOTO)
                AgentCommand.SwitchMode(mode = mode)
            }
            "adjust_beauty" -> {
                val smoothing = (params["smoothing"] as? Number)?.toFloat() ?: 0f
                val whitening = (params["whitening"] as? Number)?.toFloat() ?: 0f
                val slimFace = (params["slim_face"] as? Number)?.toFloat() ?: 0f
                val bigEyes = (params["big_eyes"] as? Number)?.toFloat() ?: 0f
                val lipColor = (params["lip_color"] as? Number)?.toFloat() ?: 0f
                val blush = (params["blush"] as? Number)?.toFloat() ?: 0f
                val eyebrow = (params["eyebrow"] as? Number)?.toFloat() ?: 0f
                AgentCommand.AdjustBeauty(
                    settings = com.mamba.picme.beauty.api.BeautySettings(
                        enabled = true,
                        smoothing = smoothing,
                        whitening = whitening,
                        slimFace = slimFace,
                        bigEyes = bigEyes,
                        lipColor = lipColor,
                        blush = blush,
                        eyebrow = eyebrow
                    )
                )
            }
            "adjust_exposure" -> {
                val exposure = (params["exposure"] as? Number)?.toInt() ?: 0
                AgentCommand.AdjustExposure(exposure = exposure.coerceIn(-2, 2))
            }
            "adjust_zoom" -> {
                val zoom = (params["zoom"] as? Number)?.toFloat() ?: 1f
                AgentCommand.AdjustZoom(zoomRatio = zoom.coerceAtLeast(0.5f))
            }
            "switch_filter" -> {
                val filterName = params["filter"] as? String ?: "NONE"
                AgentCommand.SwitchFilter(filterType = resolveFilterType(filterName))
            }
            "switch_style" -> {
                val styleName = params["style"] as? String ?: "NONE"
                AgentCommand.SwitchStyle(styleFilter = resolveStyleFilter(styleName))
            }
            "switch_scene" -> {
                val scene = params["scene"] as? String ?: "none"
                AgentCommand.SwitchScene(sceneName = scene)
            }
            "switch_ratio" -> {
                val ratio = params["ratio"] as? String ?: "full"
                AgentCommand.SwitchRatio(ratio = ratio)
            }
            else -> AgentCommand.TextReply(message = "Unknown camera command: $method")
        }
    }

    private fun resolveFilterType(name: String): com.mamba.picme.beauty.api.FilterType {
        val normalized = name.trim().uppercase().replace(" ", "_").replace("-", "_")
        return when (normalized) {
            "NONE" -> com.mamba.picme.beauty.api.FilterType.NONE
            "LEICA_CLASSIC" -> com.mamba.picme.beauty.api.FilterType.LEICA_CLASSIC
            "LEICA_VIBRANT", "VIBRANT" -> com.mamba.picme.beauty.api.FilterType.LEICA_VIBRANT
            "LEICA_BW", "BW" -> com.mamba.picme.beauty.api.FilterType.LEICA_BW
            "FILM_GOLD" -> com.mamba.picme.beauty.api.FilterType.FILM_GOLD
            "FILM_FUJI" -> com.mamba.picme.beauty.api.FilterType.FILM_FUJI
            "VINTAGE", "RETRO" -> com.mamba.picme.beauty.api.FilterType.VINTAGE
            "COOL", "COLD" -> com.mamba.picme.beauty.api.FilterType.COOL
            "WARM" -> com.mamba.picme.beauty.api.FilterType.WARM
            else -> runCatching { com.mamba.picme.beauty.api.FilterType.valueOf(normalized) }
                .getOrDefault(com.mamba.picme.beauty.api.FilterType.NONE)
        }
    }

    private fun resolveStyleFilter(name: String): com.mamba.picme.beauty.api.StyleFilter {
        val normalized = name.trim().uppercase().replace(" ", "_").replace("-", "_")
        return when (normalized) {
            "NONE" -> com.mamba.picme.beauty.api.StyleFilter.NONE
            "TOON" -> com.mamba.picme.beauty.api.StyleFilter.TOON
            "SKETCH" -> com.mamba.picme.beauty.api.StyleFilter.SKETCH
            "POSTERIZE" -> com.mamba.picme.beauty.api.StyleFilter.POSTERIZE
            "EMBOSS" -> com.mamba.picme.beauty.api.StyleFilter.EMBOSS
            "CROSSHATCH" -> com.mamba.picme.beauty.api.StyleFilter.CROSSHATCH
            else -> runCatching { com.mamba.picme.beauty.api.StyleFilter.valueOf(normalized) }
                .getOrDefault(com.mamba.picme.beauty.api.StyleFilter.NONE)
        }
    }

    /**
     * 执行相机命令，确保在相机场景下执行。
     *
     * @param method 命令方法名（如 "capture", "switch_filter"）
     * @param params 命令参数 Map
     * @param buildCommandJson 构建命令 JSON 的 lambda（已废弃，保留参数避免破坏调用方）
     * @param onSuccess 成功时的结果消息
     * @param onError 失败时的错误消息前缀
     * @return 执行结果字符串（成功或错误信息）
     */
    fun executeCameraCommand(
        method: String,
        params: Map<String, Any>,
        buildCommandJson: () -> String,
        onSuccess: (String) -> String,
        onError: (String) -> String
    ): String {
        return try {
            val registry = CapabilityRegistry.getInstance()
            val sceneManager = SceneManager.getInstance()

            // 1. 如果当前不在 CAMERA 场景，先导航到相机页
            val currentScene = sceneManager.currentScene.value
            if (currentScene != SceneManager.Scene.CAMERA) {
                Logger.i(TAG, "Current scene is $currentScene, navigating to CAMERA first")

                val navCommand = AgentCommand.NavigateTo(destination = "camera")
                val navContext = AgentContext(scene = AgentScene.CAMERA)

                val navDeferred = dispatchScope.future {
                    registry.dispatch(navCommand, navContext, null)
                }
                val navResult = try {
                    navDeferred.get(NAVIGATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    // 导航等待超时：取消底层 dispatch 协程，避免超时后协程裸跑
                    navDeferred.cancel(true)
                    throw e
                }

                if (navResult.isFailure) {
                    return "Error: Navigation to camera failed: ${navResult.exceptionOrNull()?.message}"
                }

                // 2. 等待场景切换为 CAMERA
                val waitStart = System.currentTimeMillis()
                var waitCount = 0
                runBlocking {
                    while (sceneManager.currentScene.value != SceneManager.Scene.CAMERA &&
                        waitCount < (NAVIGATION_TIMEOUT_MS / SCENE_POLL_INTERVAL_MS).toInt()
                    ) {
                        delay(SCENE_POLL_INTERVAL_MS)
                        waitCount++
                    }
                }

                val elapsed = System.currentTimeMillis() - waitStart
                if (sceneManager.currentScene.value != SceneManager.Scene.CAMERA) {
                    return "Error: Timeout waiting for CAMERA scene after ${elapsed}ms"
                }
                Logger.i(TAG, "Scene switched to CAMERA after ${elapsed}ms")

                // 3. 额外等待 CameraCapability 绑定完成（delegate 初始化）
                runBlocking { delay(CAPABILITY_WAIT_MS) }
            }

            // 4. 构建并执行目标命令
            val context = AgentContext(scene = AgentScene.CAMERA)
            val command = buildAgentCommand(method, params)

            val deferred = dispatchScope.future {
                registry.dispatch(command, context, null)
            }
            val result = try {
                deferred.get(5, TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                // 等待 dispatch 5s 超时：取消底层 dispatch 协程，避免超时后协程裸跑
                deferred.cancel(true)
                throw e
            }

            result.fold(
                onSuccess = { action ->
                    when (action) {
                        is AgentAction.Success -> onSuccess(method)
                        is AgentAction.TextReply -> action.message
                        else -> onSuccess(method)
                    }
                },
                onFailure = { error ->
                    "Error: ${onError(error.message ?: "Unknown error")}"
                }
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Camera command execution error", e)
            "Error: Camera command error: ${e.message}"
        }
    }
}
