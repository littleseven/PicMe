package com.mamba.picme.features.common.chat

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.automirrored.rounded.Help
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.PlaylistAddCheck
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.mamba.picme.agent.core.remote.config.RemoteModelConfigs
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.config.AiAgentInferencePreference
import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.platform.voice.AsrEngine
import com.mamba.picme.agent.core.platform.voice.SherpaOnnxAsrEngine
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.preferences.UserPreferencesRepository
import com.mamba.picme.domain.model.AiAgentCommand
import com.mamba.picme.domain.usecase.AiAgentUseCase
import com.mamba.picme.features.camera.voice.SystemAsrEngine
import com.mamba.picme.features.camera.voice.VoiceCommandCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ─────────────────────────────────────────────────────────────────────────────
// 1. ASR 引擎初始化（公共逻辑）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 根据用户设置初始化 ASR 引擎，支持三级降级策略：
 * Sherpa-MNN ASR → MNN ASR → System ASR
 *
 * @param context Android Context
 * @param localAsrModel 本地 ASR 模型 ID（从 UserPreferencesRepository 读取）
 * @param logTag 日志标签前缀
 */
fun createAsrEngine(
    context: Context,
    localAsrModel: String,
    logTag: String
): AsrEngine {
    if (localAsrModel.isBlank()) {
        Logger.d(logTag, "No local ASR model configured, using system ASR")
        return SystemAsrEngine(context)
    }

    val modelDir = context.filesDir.resolve("llm_models/$localAsrModel")
    val modelDirPath = modelDir.absolutePath

    val isModelReady = if (localAsrModel.contains("zipformer", ignoreCase = true)) {
        // 修复 P0-3：检查 .onnx 文件而非 .mnn（已迁移到 Sherpa-ONNX）
        modelDir.exists() && modelDir.isDirectory &&
            modelDir.walkTopDown().any { it.name.endsWith(".onnx") } &&
            File(modelDir, "tokens.txt").exists()
    } else {
        modelDir.exists() && modelDir.isDirectory
    }

    if (!isModelReady) {
        Logger.w(logTag, "ASR model not ready: $localAsrModel")
        return SystemAsrEngine(context)
    }

    return if (localAsrModel.contains("zipformer", ignoreCase = true)) {
        val sherpaOnnxAsr = SherpaOnnxAsrEngine(context, modelDirPath)
        if (sherpaOnnxAsr.isAvailable()) {
            Logger.i(logTag, "Using Sherpa-ONNX ASR engine")
            sherpaOnnxAsr
        } else {
            Logger.w(logTag, "Sherpa-ONNX ASR init failed, fallback to system ASR")
            SystemAsrEngine(context)
        }
    } else {
        Logger.w(logTag, "Unsupported ASR model: $localAsrModel, fallback to system ASR")
        SystemAsrEngine(context)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. VoiceCommandCoordinator 初始化（公共逻辑）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 创建并配置 VoiceCommandCoordinator
 *
 * @param asrEngine ASR 引擎实例
 * @param aiAgentUseCase AI Agent 用例
 * @param scope 协程作用域
 * @param onCommand 命令回调
 * @param onTranscript 识别文本回调（可选）
 * @param onAgentResponse Agent 响应回调（可选）
 */
fun createVoiceCommandCoordinator(
    asrEngine: AsrEngine,
    aiAgentUseCase: AiAgentUseCase,
    scope: CoroutineScope,
    onCommand: (AiAgentCommand) -> Unit,
    onTranscript: ((String) -> Unit)? = null,
    onAgentResponse: ((Result<AiAgentCommand>) -> Unit)? = null,
    context: Context? = null
): VoiceCommandCoordinator {
    return VoiceCommandCoordinator(
        asrEngine = asrEngine,
        aiAgentUseCase = aiAgentUseCase,
        onCommand = onCommand,
        scope = scope,
        onTranscript = onTranscript,
        onAgentResponse = onAgentResponse,
        context = context
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. Agent Chat Panel（浮动按钮 + AiChatScreen + AgentOrchestrator）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 统一的 Agent Chat Panel 组件
 *
 * 封装以下重复逻辑：
 * - 右下角浮动按钮（KeyboardVoice 图标）
 * - AiChatScreen 对话框
 * - AgentOrchestrator 消息处理
 * - 消息状态管理
 *
 * @param pageContext 页面上下文
 * @param agentScene Agent 场景枚举
 * @param memorySessionId 记忆会话 ID
 * @param voiceCoordinator 语音协调器（可选）
 * @param modifier 修饰符（通常用于定位，如 Modifier.align(Alignment.BottomEnd)）
 */
private fun mapAgentActionToAiAgentCommand(action: AgentAction.Success): AiAgentCommand? {
    return when (val cmd = action.command) {
        is AgentCommand.AdjustBeauty ->
            AiAgentCommand.AdjustBeauty(cmd.settings)
        is AgentCommand.SwitchFilter ->
            AiAgentCommand.SwitchFilter(cmd.filterType)
        is AgentCommand.SwitchStyle ->
            AiAgentCommand.SwitchStyle(cmd.styleFilter)
        is AgentCommand.SwitchScene ->
            AiAgentCommand.SwitchScene(cmd.sceneName)
        is AgentCommand.SwitchRatio ->
            AiAgentCommand.SwitchRatio(cmd.ratio)
        is AgentCommand.AdjustExposure ->
            AiAgentCommand.AdjustExposure(cmd.exposure)
        is AgentCommand.AdjustZoom ->
            AiAgentCommand.AdjustZoom(cmd.zoomRatio)
        is AgentCommand.FlipCamera ->
            AiAgentCommand.FlipCamera
        is AgentCommand.CapturePhoto ->
            AiAgentCommand.CapturePhoto
        is AgentCommand.ToggleRecording ->
            AiAgentCommand.ToggleRecording
        is AgentCommand.SwitchMode ->
            AiAgentCommand.SwitchMode(cmd.mode)
        is AgentCommand.Delay ->
            AiAgentCommand.Delay(cmd.delayMs)
        is AgentCommand.NavigateTo ->
            AiAgentCommand.NavigateTo(cmd.destination)
        is AgentCommand.GoBack ->
            AiAgentCommand.GoBack
        is AgentCommand.AiOptimize ->
            cmd.resultRecipe?.let { AiAgentCommand.ApplyEditRecipe(it) }
        else -> null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// AgentAction → CommandExecution 消息转换
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 将 AgentAction.Success 转换为 CommandExecution 消息列表
 *
 * 支持 BatchExecute 展开为多条消息，单命令转换为单条消息。
 */
private fun agentActionToExecutionMessages(action: AgentAction.Success): List<AgentMessage> {
    val cmd = action.command
    return when (cmd) {
        is AgentCommand.BatchExecute -> {
            val total = cmd.commands.size
            cmd.commands.mapIndexed { index, subCmd ->
                AgentMessage.CommandExecution(
                    commandName = getAgentCommandDisplayName(subCmd),
                    commandIcon = resolveCommandIcon(subCmd),
                    status = AgentMessage.CommandExecution.Status.SUCCESS,
                    detail = getAgentCommandDetail(subCmd),
                    index = index + 1,
                    total = total
                )
            }
        }
        else -> listOf(
            AgentMessage.CommandExecution(
                commandName = getAgentCommandDisplayName(cmd),
                commandIcon = resolveCommandIcon(cmd),
                status = AgentMessage.CommandExecution.Status.SUCCESS,
                detail = getAgentCommandDetail(cmd),
                index = 0,
                total = 1
            )
        )
    }
}

private fun getAgentCommandDisplayName(command: AgentCommand): String =
    when (command) {
        is AgentCommand.AdjustBeauty -> "调整美颜"
        is AgentCommand.SwitchFilter -> "切换滤镜"
        is AgentCommand.SwitchStyle -> "切换风格"
        is AgentCommand.SwitchScene -> "切换场景"
        is AgentCommand.SwitchRatio -> "切换画幅"
        is AgentCommand.AdjustExposure -> "调整曝光"
        is AgentCommand.AdjustZoom -> "调整变焦"
        is AgentCommand.FlipCamera -> "翻转摄像头"
        is AgentCommand.CapturePhoto -> "拍照"
        is AgentCommand.Delay -> "等待"
        is AgentCommand.ToggleRecording -> "切换录像"
        is AgentCommand.SwitchMode -> "切换模式"
        is AgentCommand.NavigateTo -> "页面跳转"
        is AgentCommand.GoBack -> "返回"
        is AgentCommand.BatchExecute -> "批量执行"
        is AgentCommand.TextReply -> "文本回复"
        is AgentCommand.ExecutePlan -> "执行计划"
        is AgentCommand.ChangeTheme -> "切换主题"
        is AgentCommand.ChangeLanguage -> "切换语言"
        is AgentCommand.DownloadModel -> "下载模型"
        is AgentCommand.SwitchFaceEngine -> "切换引擎"
        is AgentCommand.ToggleSetting -> "切换设置"
        is AgentCommand.ViewMedia -> "查看照片"
        is AgentCommand.DeleteMedia -> "删除照片"
        is AgentCommand.ShareMedia -> "分享照片"
        is AgentCommand.SelectMedia -> "选择照片"
        is AgentCommand.SearchMedia -> "搜索照片"
        is AgentCommand.RefineMediaSearch -> "细化搜索"
        is AgentCommand.SwitchViewMode -> "切换视图"
        is AgentCommand.FavoriteMedia -> "收藏照片"
        is AgentCommand.GetGallerySummary -> "相册摘要"
        is AgentCommand.StartTagScan -> "TAG 扫描"
        is AgentCommand.AiOptimize -> "AI 一键优化"
        is AgentCommand.LaunchApp -> "打开应用"
        is AgentCommand.OpenSystemSettings -> "打开设置"
        is AgentCommand.Unknown -> "未知命令"
        is AgentCommand.Error -> "执行错误"
        is AgentCommand.RecordMediaFeedback -> "媒体反馈"
        is AgentCommand.MoreLikeThis -> "更多相似"
        is AgentCommand.ExcludeConstraint -> "排除约束"
    }

/**
 * 将 AgentCommand 映射为可视化图标，减少 chat 中 command 文字标识的冗余。
 */
private fun resolveCommandIcon(command: AgentCommand): ImageVector = when (command) {
    is AgentCommand.AdjustBeauty -> Icons.Rounded.Face
    is AgentCommand.SwitchFilter -> Icons.Rounded.PhotoFilter
    is AgentCommand.SwitchStyle -> Icons.Rounded.Style
    is AgentCommand.SwitchScene -> Icons.Rounded.Videocam
    is AgentCommand.SwitchRatio -> Icons.Rounded.AspectRatio
    is AgentCommand.AdjustExposure -> Icons.Rounded.Exposure
    is AgentCommand.AdjustZoom -> Icons.Rounded.ZoomIn
    is AgentCommand.FlipCamera -> Icons.Rounded.FlipCameraAndroid
    is AgentCommand.CapturePhoto -> Icons.Rounded.CameraAlt
    is AgentCommand.Delay -> Icons.Rounded.HourglassEmpty
    is AgentCommand.ToggleRecording -> Icons.Rounded.Videocam
    is AgentCommand.SwitchMode -> Icons.Rounded.Settings
    is AgentCommand.NavigateTo -> Icons.AutoMirrored.Rounded.OpenInNew
    is AgentCommand.GoBack -> Icons.AutoMirrored.Rounded.ArrowBack
    is AgentCommand.BatchExecute -> Icons.AutoMirrored.Rounded.FactCheck
    is AgentCommand.TextReply -> Icons.AutoMirrored.Rounded.ShortText
    is AgentCommand.ExecutePlan -> Icons.AutoMirrored.Rounded.PlaylistAddCheck
    is AgentCommand.ChangeTheme -> Icons.Rounded.DarkMode
    is AgentCommand.ChangeLanguage -> Icons.Rounded.Language
    is AgentCommand.DownloadModel -> Icons.Rounded.Download
    is AgentCommand.SwitchFaceEngine -> Icons.Rounded.Memory
    is AgentCommand.ToggleSetting -> Icons.Rounded.ToggleOn
    is AgentCommand.ViewMedia -> Icons.Rounded.Visibility
    is AgentCommand.DeleteMedia -> Icons.Rounded.Delete
    is AgentCommand.ShareMedia -> Icons.Rounded.Share
    is AgentCommand.SelectMedia -> Icons.Rounded.CheckCircle
    is AgentCommand.SearchMedia -> Icons.Rounded.Search
    is AgentCommand.RefineMediaSearch -> Icons.Rounded.Search
    is AgentCommand.SwitchViewMode -> Icons.Rounded.GridView
    is AgentCommand.FavoriteMedia -> Icons.Rounded.Favorite
    is AgentCommand.GetGallerySummary -> Icons.Rounded.PhotoLibrary
    is AgentCommand.StartTagScan -> Icons.Rounded.Sync
    is AgentCommand.AiOptimize -> Icons.Rounded.AutoFixHigh
    is AgentCommand.LaunchApp -> Icons.AutoMirrored.Rounded.Launch
    is AgentCommand.OpenSystemSettings -> Icons.Rounded.Settings
    is AgentCommand.Unknown -> Icons.AutoMirrored.Rounded.Help
    is AgentCommand.Error -> Icons.Rounded.Error
    is AgentCommand.RecordMediaFeedback -> Icons.Rounded.Favorite
    is AgentCommand.MoreLikeThis -> Icons.Rounded.Search
    is AgentCommand.ExcludeConstraint -> Icons.Rounded.Delete
}

private fun getAgentCommandDetail(command: AgentCommand): String =
    when (command) {
        is AgentCommand.AdjustBeauty -> buildString {
            val s = command.settings
            val parts = mutableListOf<String>()
            if (s.smoothing > 0) parts.add("磨皮 ${s.smoothing.toInt()}%")
            if (s.whitening > 0) parts.add("美白 ${s.whitening.toInt()}%")
            if (s.slimFace != 0f) parts.add("瘦脸 ${s.slimFace.toInt()}%")
            if (s.bigEyes > 0) parts.add("大眼 ${s.bigEyes.toInt()}%")
            if (parts.isEmpty()) append("默认参数") else append(parts.joinToString(", "))
        }
        is AgentCommand.SwitchFilter -> "滤镜: ${command.filterType.name}"
        is AgentCommand.SwitchStyle -> "风格: ${command.styleFilter.name}"
        is AgentCommand.SwitchScene -> "场景: ${command.sceneName}"
        is AgentCommand.SwitchRatio -> "比例: ${command.ratio}"
        is AgentCommand.AdjustExposure -> "曝光: ${command.exposure}"
        is AgentCommand.AdjustZoom -> "变焦: ${command.zoomRatio}x"
        is AgentCommand.NavigateTo -> "目标: ${command.destination}"
        is AgentCommand.ChangeTheme -> "主题: ${command.theme}"
        is AgentCommand.ChangeLanguage -> "语言: ${command.language}"
        is AgentCommand.DownloadModel -> "模型: ${command.modelId}"
        is AgentCommand.SwitchFaceEngine -> "引擎: ${command.engine}"
        is AgentCommand.ToggleSetting -> "${command.settingKey}: ${if (command.enabled) "开启" else "关闭"}"
        is AgentCommand.SearchMedia -> "关键词: ${command.query}"
        is AgentCommand.ExecutePlan -> "计划: ${command.plan.description}"
        is AgentCommand.Delay -> "延迟: ${command.delayMs}ms"
        is AgentCommand.LaunchApp -> command.appName ?: command.packageName ?: ""
        is AgentCommand.OpenSystemSettings -> command.setting
        else -> ""
    }

// ─────────────────────────────────────────────────────────────────────────────
// 4. 页面级初始化辅助（ASR + VoiceCoordinator 一站式配置）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 页面级 Agent Chat 初始化数据类
 *
 * 包含 ASR 引擎、VoiceCommandCoordinator 和 AiAgentUseCase 的完整配置
 */
data class AgentChatConfig(
    val asrEngine: AsrEngine,
    val aiAgentUseCase: AiAgentUseCase,
    val voiceCoordinator: VoiceCommandCoordinator
)

/**
 * 记住页面级 Agent Chat 配置（ASR + VoiceCoordinator + AiAgentUseCase）
 *
 * 使用示例：
 * ```
 * val config = rememberAgentChatConfig(
 *     context = context,
 *     logTag = "PoLang:Gallery",
 *     onCommand = { command -> /* 处理命令 */ },
 *     onTranscript = { transcript -> /* 处理识别文本 */ },
 *     onAgentResponse = { result -> /* 处理 Agent 响应 */ }
 * )
 * DisposableEffect(Unit) {
 *     onDispose {
 *         // 修复 P0-1：不应该调用 release()，而是进行"软释放"
 *         config.voiceCoordinator.stopWakeWordListening()
 *         config.voiceCoordinator.stopPushToTalk()
 *     }
 * }
 * ```
 *
 * 为什么不能调用 release()？
 * - voiceCoordinator 在多个屏幕（Camera、Gallery、Settings）间共享使用
 * - 如果在某个页面调用 release()，会完全释放 ASR 引擎
 * - 切换到其他屏幕后，ASR 引擎已被销毁，无法再使用语音功能
 * - 正确的做法：每个页面只进行"软释放"（停止监听但保留引擎状态）
 */
@Composable
fun rememberAgentChatConfig(
    context: Context,
    logTag: String,
    onCommand: (AiAgentCommand) -> Unit,
    onTranscript: ((String) -> Unit)? = null,
    onAgentResponse: ((Result<AiAgentCommand>) -> Unit)? = null
): AgentChatConfig {
    val scope = rememberCoroutineScope()

    // 读取用户 ASR 模型设置
    val settingsRepository = remember { UserPreferencesRepository(context) }
    val localAsrModel by settingsRepository.localAsrModelFlow.collectAsState(initial = "")

    // ASR 引擎 - 异步初始化避免主线程阻塞（先降级为系统ASR，后台加载本地模型）
    var asrEngine by remember(context, localAsrModel) {
        mutableStateOf<AsrEngine>(SystemAsrEngine(context))
    }
    LaunchedEffect(context, localAsrModel) {
        val engine = withContext(Dispatchers.IO) {
            createAsrEngine(context, localAsrModel, logTag)
        }
        asrEngine = engine
    }

    // 读取 Agent 模式与远程配置
    val aiAgentMode by settingsRepository.aiAgentModeFlow.collectAsState(initial = AiAgentMode.REMOTE)
    val aiAgentInferencePreference by settingsRepository.aiAgentInferencePreferenceFlow.collectAsState(initial = AiAgentInferencePreference.FORCE_REMOTE)
    val aiAgentLocalUseOpencl by settingsRepository.aiAgentLocalUseOpenclFlow.collectAsState(initial = false)
    val aiAgentRemoteModelConfigs by settingsRepository.aiAgentRemoteModelConfigsFlow.collectAsState(initial = "")
    val aiAgentSelectedRemoteModel by settingsRepository.aiAgentSelectedRemoteModelFlow.collectAsState(initial = "deepseek-v4-flash")

    // 解析远程模型配置
    // 注意：aiAgentSelectedRemoteModel 保存的是 uniqueKey（providerId:modelId），
    // 优先按 uniqueKey 查找；找不到再按 modelId 查找。
    // 用户未配置时返回 null，让 AiAgentUseCase 使用 SCF 兜底配置。
    val remoteConfig = remember(aiAgentRemoteModelConfigs, aiAgentSelectedRemoteModel) {
        val configs = if (aiAgentRemoteModelConfigs.isNotBlank()) {
            RemoteModelConfigs.fromJson(aiAgentRemoteModelConfigs)
        } else {
            RemoteModelConfigs()
        }
        configs.getConfig(aiAgentSelectedRemoteModel)
            ?: configs.getConfigByModelId(aiAgentSelectedRemoteModel)
    }

    // 读取服务端认证 token（邮箱注册）
    val serverAuthToken by settingsRepository.serverAuthTokenFlow.collectAsState(initial = "")

    // AiAgentUseCase：根据设置动态配置 mode 和 forceRemote
    val aiAgentUseCase = remember(
        aiAgentMode,
        aiAgentInferencePreference,
        aiAgentLocalUseOpencl,
        remoteConfig,
        serverAuthToken
    ) {
        AiAgentUseCase(
            context = context,
            agentMode = aiAgentMode,
            localModelId = "qwen3_5_2b",
            localUseOpencl = aiAgentLocalUseOpencl,
            remoteConfig = remoteConfig,
            forceRemote = aiAgentInferencePreference == AiAgentInferencePreference.FORCE_REMOTE,
            gatewayToken = serverAuthToken.takeIf { it.isNotBlank() }
        )
    }

    // VoiceCommandCoordinator - asrEngine 变化时自动重建
    val voiceCoordinator = remember(asrEngine, aiAgentUseCase) {
        Logger.d("AgentChatConfig", "Creating voiceCoordinator: asrEngine=$asrEngine, aiAgentUseCase=$aiAgentUseCase")
        val coordinator = createVoiceCommandCoordinator(
            asrEngine = asrEngine,
            aiAgentUseCase = aiAgentUseCase,
            scope = scope,
            onCommand = onCommand,
            onTranscript = onTranscript,
            onAgentResponse = onAgentResponse,
            context = context
        )
        Logger.d("AgentChatConfig", "VoiceCoordinator created: $coordinator")
        coordinator
    }
    // 修复 P0-1：不应该在 voiceCoordinator 变化时调用 release()
    // 原因：voiceCoordinator 在多个 Chat 屏幕（Camera、Gallery、Settings）间共享使用
    // release() 会完全释放 ASR 引擎，导致后续屏幕无法再使用语音功能
    //
    // 正确做法：每个使用 voiceCoordinator 的页面在自己的 onDispose 时，只进行"软释放"
    // （调用 stopWakeWordListening() + stopPushToTalk()，不调用 release()）
    //
    // ASR 引擎的完全释放应该由 Application 生命周期或单独的释放策略管理
    DisposableEffect(voiceCoordinator) {
        onDispose {
            // 旧代码注释：voiceCoordinator.release() // ❌ 不再调用
            Logger.d("AgentChatConfig", "rememberAgentChatConfig disposed - soft release only (not calling release())")
            voiceCoordinator.stopWakeWordListening()
            voiceCoordinator.stopPushToTalk()
        }
    }

    return AgentChatConfig(
        asrEngine = asrEngine,
        aiAgentUseCase = aiAgentUseCase,
        voiceCoordinator = voiceCoordinator
    )
}
