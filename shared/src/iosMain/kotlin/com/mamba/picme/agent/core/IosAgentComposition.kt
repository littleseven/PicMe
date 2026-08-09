package com.mamba.picme.agent.core

import ai.koog.agents.core.tools.ToolRegistry
import com.mamba.picme.agent.core.capability.IosChatGalleryCapability
import com.mamba.picme.agent.core.facade.AgentDependencies
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.inference.local.IosUnavailableImageInferenceEngine
import com.mamba.picme.agent.core.inference.remote.IosChatPrompt
import com.mamba.picme.agent.core.inference.remote.tool.ChatToolManifest
import com.mamba.picme.agent.core.platform.storage.ChatHistoryCleaner
import com.mamba.picme.agent.core.platform.storage.IosKoogMessageMemoryStore
import com.mamba.picme.agent.core.platform.thread.DispatcherProvider
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.data.IosMediaRepository
import com.mamba.picme.data.IosMediaRepositoryBridge

/**
 * iOS 组合根（Phase 6.2 T5）：shared 平台实现的唯一直构点，对齐 Android 的
 * `androidApp/agent/AndroidAgentComposition`。
 *
 * Swift 侧在 App 启动（AppContainer init）调用一次 [initialize]：
 * - 注册 chat 相册能力（[IosChatGalleryCapability]）到 [CapabilityRegistry]；
 * - 经 [AgentOrchestrator.initialize] 注入全部 iOS 平台实现：
 *   NSUserDefaults 记忆持久化、no-op 旧键空间清理器、占位 VLM 引擎（iOS v1 无端侧模型）、
 *   [ChatToolManifest] 无反射工具清单（K/N 无 `asToolsByClass`）、[IosChatPrompt] 精简 prompt。
 *
 * 相机/飞书链路 iOS v1 不启用：cameraToolDescriptors/Registry 与 remoteImToolRegistryProvider
 * 均为空注册表（AgentOrchestrator 构造期只组装 prompt 字符串，空清单 = 无工具段）。
 */
object IosAgentComposition {

    /**
     * @param bridge Swift `PhMediaBridge` 实例（Photos framework 取数/写桥）。
     * 重复调用安全：[AgentOrchestrator.initialize] 单例语义返回既有实例；
     * [CapabilityRegistry.register] 同名 capability 重复注册走既有替换语义。
     */
    fun initialize(bridge: IosMediaRepositoryBridge): AgentOrchestrator {
        val repository = IosMediaRepository(bridge)
        CapabilityRegistry.getInstance().register(IosChatGalleryCapability(repository, bridge))
        return AgentOrchestrator.initialize(
            AgentDependencies(
                dispatcherProvider = DispatcherProvider(),
                chatMemoryStore = IosKoogMessageMemoryStore(),
                // iOS 无旧 langchain4j 键空间（memory_）历史，清理器为 no-op
                chatHistoryCleaner = ChatHistoryCleaner { },
                imageEngineProvider = { IosUnavailableImageInferenceEngine() },
                chatToolDescriptors = ChatToolManifest.buildDescriptors(),
                chatToolRegistry = ToolRegistry { tools(ChatToolManifest.tools) },
                cameraToolDescriptors = emptyList(),
                cameraToolRegistry = ToolRegistry {},
                remoteImToolRegistryProvider = { ToolRegistry {} },
                chatPromptBuilder = IosChatPrompt::build,
            )
        )
    }
}
