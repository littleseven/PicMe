package com.mamba.picme.agent

import ai.koog.agents.core.tools.ToolRegistry
import com.mamba.picme.agent.core.capability.IosChatGalleryCapability
import com.mamba.picme.agent.core.facade.AgentDependencies
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.inference.local.IosUnavailableImageInferenceEngine
import com.mamba.picme.agent.core.inference.remote.ChatAgentBridge
import com.mamba.picme.agent.core.inference.remote.IosChatPrompt
import com.mamba.picme.agent.core.inference.remote.tool.ChatToolManifest
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.storage.ChatHistoryCleaner
import com.mamba.picme.agent.core.platform.thread.DispatcherProvider
import com.mamba.picme.data.IosMediaRepository
import com.mamba.picme.data.IosMediaRepositoryBridge
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * iOS Agent 组合根（Phase 6.2 T5）—— [AgentOrchestrator] 的 iOS 唯一直构点。
 *
 * 镜像 Android 的 `AndroidAgentComposition`（Application.onCreate 接线），
 * 但用 **手工清单** 替代 JVM 反射（K/N 无 `asToolsByClass()`），并注入 iOS 专属
 * actual 实现（T1-T4 产物）。
 *
 * 接线清单（plan §2 核对表逐项）：
 * - `dispatcherProvider` → iosMain `DispatcherProvider` actual（已有）
 * - `chatMemoryStore` → [IosKoogMessageMemoryStore]（T1，NSUserDefaults）
 * - `chatHistoryCleaner` → no-op lambda（iOS 无旧 `memory_` 键空间需清理）
 * - `imageEngineProvider` → [IosUnavailableImageInferenceEngine]（T1 stub，AgentConfigurator 构造期 eager 调用）
 * - `chatToolDescriptors/Registry` → [ChatToolManifest]（T2，手工 8 工具）
 * - `cameraToolDescriptors/Registry` → 空（相机 AI 指令不在 Chat 范围，plan §1 不进第一版）
 * - `remoteImToolRegistryProvider` → 空 ToolRegistry（飞书 RPA 不在 iOS 范围）
 * - `chatPromptBuilder` → [IosChatPrompt.build]（T3，精简版 prompt）
 *
 * **Capability 注册**：[IosChatGalleryCapability]（T4）在 [AgentOrchestrator.initialize]
 * 完成后注册到 `CapabilityRegistry(scene=CHAT)`，使 ChatToolService.dispatchCommand
 * 能路由到 iOS 相册能力执行端。
 *
 * 调用方式：Swift `AppContainer` 在初始化时经 SharedKit framework 调
 * [initialize]，传入 Swift 实现的 [IosMediaRepositoryBridge]（PhMediaBridge）和
 * `deviceId`（identifierForVendor + UserDefaults 持久化），随后取 [chatBridge] 供
 * `ChatViewModel` 消费。
 *
 * [PRIVACY] 红线：组合根不注入任何 [com.mamba.picme.agent.core.inference.local.ImageInferenceEngine]
 * 的真实实现（VLM stub 的 `imageInference` 返回空串），确保 chat 链路无多模态上传能力。
 */
@OptIn(ExperimentalAtomicApi::class)
object IosAgentComposition {

    private const val TAG = "IosAgentComposition"

    private val initialized = AtomicBoolean(false)

    /**
     * chat 桥（[ChatAgentBridge]），Swift 侧 ChatViewModel 的唯一入口。
     * [initialize] 完成后方可访问。
     */
    var chatBridge: ChatAgentBridge? = null
        private set

    /**
     * iOS Agent 接线入口（幂等：重复调用直接跳过）。
     *
     * @param bridge Swift 侧 Photos framework 桥实现（PhMediaBridge）
     * @param deviceId 设备标识（identifierForVendor + UserDefaults 持久化），作访客 X-Device-Id
     */
    fun initialize(bridge: IosMediaRepositoryBridge, deviceId: String) {
        if (!initialized.compareAndSet(false, true)) {
            Logger.w(TAG, "initialize called twice, skipping")
            return
        }

        val dispatcherProvider = DispatcherProvider()
        val mediaRepository = IosMediaRepository(bridge)

        // T2 手工清单（替代 Android 的 asToolsByClass 反射展开）
        val chatTools = ChatToolManifest.tools

        AgentOrchestrator.initialize(
            AgentDependencies(
                dispatcherProvider = dispatcherProvider,
                chatMemoryStore = com.mamba.picme.agent.core.platform.storage.IosKoogMessageMemoryStore(),
                chatHistoryCleaner = ChatHistoryCleaner { }, // no-op：iOS 无旧 memory_ 键空间
                imageEngineProvider = { IosUnavailableImageInferenceEngine() },
                chatToolDescriptors = ChatToolManifest.buildDescriptors(),
                chatToolRegistry = ToolRegistry { tools(chatTools) },
                cameraToolDescriptors = emptyList(), // 相机 AI 指令不在 Chat v1 范围
                cameraToolRegistry = ToolRegistry { },
                remoteImToolRegistryProvider = { ToolRegistry { } }, // 飞书 RPA 不在 iOS 范围
                chatPromptBuilder = IosChatPrompt::build,
            )
        )

        // 设置访客设备标识（访客模式仅 X-Device-Id，无需 X-App-Token）
        val orchestrator = AgentOrchestrator.getInstance()
        orchestrator.setDeviceId(deviceId)

        // 配置远程推理：访客模式，PICME_SERVER_DEFAULT（baseUrl/apiKey 兜底由 getChatAgent 内处理）
        orchestrator.updateRemoteRuntimeConfig(
            remoteConfig = com.mamba.picme.agent.core.remote.config.RemoteModelConfig.PICME_SERVER_DEFAULT,
            privacyLevel = com.mamba.picme.agent.core.model.config.AiAgentPrivacyLevel.PERMISSIVE,
        )

        // 注册 iOS chat 相册能力（T4），使 ChatToolService.dispatchCommand → CapabilityRegistry(CHAT) 路由可达
        orchestrator.registerCapability(IosChatGalleryCapability(mediaRepository, bridge))

        // 创建 chat 桥
        chatBridge = ChatAgentBridge(orchestrator)

        Logger.i(TAG, "iOS agent composition initialized (deviceId=${deviceId.take(8)}…)")
    }
}
