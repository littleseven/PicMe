package com.mamba.picme.features.camera.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.model.config.AiAgentPrivacyLevel
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.usecase.AiAgentUseCase
import com.mamba.picme.features.agent.GlobalAgentPanel
import com.mamba.picme.features.agent.rememberGlobalAgentPanelState

private const val TAG = "CameraAgent"

/**
 * CameraScreen 的 Agent 集成
 *
 * 将 AgentOrchestrator 与现有 CameraScreen 集成
 * 提供向后兼容的桥梁
 */
class CameraAgentIntegration(
    val orchestrator: AgentOrchestrator,
    private val useCase: AiAgentUseCase
) {
    /**
     * 进入 Camera 场景
     *
     * 相机页默认不加载 LLM：进入时若模型已加载则立即卸载，释放内存给相机预览/美颜。
     * Scene 切换由 MainActivity 统一管理，但 LLM 生命周期在此显式协调。
     */
    fun enterCameraScene() {
        Logger.i(TAG, "Entering CAMERA scene, unload LLM if loaded")
        if (orchestrator.localModelService.isModelLoaded) {
            orchestrator.localModelService.unloadModel()
        }
    }

    /**
     * 离开 Camera 场景
     *
     * 注意：Scene 切换由 MainActivity 统一管理，此处不再重复设置。
     * LLM 是否在离开相机页后预加载由具体目标页自行决定。
     */
    fun leaveCameraScene() {
        Logger.i(TAG, "Exiting CAMERA scene (scene managed by MainActivity)")
    }
}

/**
 * CameraScreen 的 Agent Panel 组件
 *
 * 使用新的 GlobalAgentPanel，但保持与现有 UI 的兼容性
 */
@Composable
fun CameraAgentPanelV2(
    integration: CameraAgentIntegration,
    agentContext: AgentContext,
    modifier: Modifier = Modifier
) {
    val panelState = rememberGlobalAgentPanelState()

    // 进入/离开场景的生命周期管理
    DisposableEffect(Unit) {
        integration.enterCameraScene()
        onDispose {
            integration.leaveCameraScene()
        }
    }

    GlobalAgentPanel(
        state = panelState,
        orchestrator = integration.orchestrator,
        agentContext = agentContext,
        pageContext = null, // Camera 页面暂无特定上下文
        modifier = modifier
    )
}

/**
 * 创建 CameraAgentIntegration 的 remember 函数
 */
@Composable
fun rememberCameraAgentIntegration(
    useCase: AiAgentUseCase
): CameraAgentIntegration {
    val context = LocalContext.current
    val orchestrator = remember {
        AgentOrchestrator.getInstance().apply {
            // 加载配置（端侧文本 LLM 已移除，相机 AI 走远程 tool_calls 链路）
            configure(
                mode = AiAgentMode.REMOTE,
                modelId = "qwen3_vl_2b", // 端侧 VLM 打标模型（下划线格式，与 ModelManager 注册表一致）
                privacyLevel = AiAgentPrivacyLevel.STRICT
            )
        }
    }

    return remember {
        CameraAgentIntegration(orchestrator, useCase)
    }
}
