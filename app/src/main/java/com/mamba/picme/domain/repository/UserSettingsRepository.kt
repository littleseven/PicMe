package com.mamba.picme.domain.repository

import com.mamba.picme.agent.core.model.config.AiAgentInferencePreference
import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.model.config.AiAgentPrivacyLevel
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.model.BeautyStrategy
import com.mamba.picme.domain.model.CameraMemoryState
import com.mamba.picme.domain.model.FaceDetectIntervalProfile
import com.mamba.picme.domain.model.FaceDetectionEngineMode
import com.mamba.picme.domain.model.StageConfig
import com.mamba.picme.domain.model.ThemeMode
import com.mamba.picme.domain.model.VoiceCommandMode
import kotlinx.coroutines.flow.Flow
import com.mamba.picme.domain.model.LogModuleConfig

/**
 * 用户偏好设置仓储接口（Domain 层契约）
 *
 * Features 层应依赖此接口，而非直接依赖 data 层的 UserPreferencesRepository。
 * 实现类：data/preferences/UserPreferencesRepository（通过 DI 注入）。
 */
interface UserSettingsRepository {

    // ── 主题 ──────────────────────────────────────────────
    val themeModeFlow: Flow<ThemeMode>
    suspend fun updateThemeMode(mode: ThemeMode)

    // ── 语言 ──────────────────────────────────────────────
    val appLanguageFlow: Flow<AppLanguage>
    fun getAppLanguageBlocking(): AppLanguage
    suspend fun updateAppLanguage(language: AppLanguage)

    // ── 美颜引擎策略 ───────────────────────────────────────
    val beautyStrategyFlow: Flow<BeautyStrategy>
    fun getBeautyStrategyBlocking(): BeautyStrategy
    suspend fun updateBeautyStrategy(strategy: BeautyStrategy)

    // ── GL 引擎回退与恢复 ──────────────────────────────────
    val glEngineRecoveryAvailableAtFlow: Flow<Long>
    suspend fun persistGlEngineFallback(cooldownMs: Long)
    suspend fun triggerManualGlEngineRecovery()
    suspend fun clearGlEngineRecoveryCooldown()

    // ── 调试开关 ───────────────────────────────────────────
    val debugUiEnabledFlow: Flow<Boolean>
    suspend fun updateDebugUiEnabled(enabled: Boolean)

    val showCameraInfoInPreviewFlow: Flow<Boolean>
    suspend fun updateShowCameraInfoInPreview(show: Boolean)

    val showFaceDebugOverlayFlow: Flow<Boolean>
    suspend fun updateShowFaceDebugOverlay(show: Boolean)

    val showLogOverlayFlow: Flow<Boolean>
    suspend fun updateShowLogOverlay(show: Boolean)

    // ── Shader 调试模式 ────────────────────────────────────
    val debugShaderModeFlow: Flow<Int>
    suspend fun updateDebugShaderMode(mode: Int)

    // ── 人脸检测 ───────────────────────────────────────────
    val faceDetectionEngineModeFlow: Flow<FaceDetectionEngineMode>
    suspend fun updateFaceDetectionEngineMode(mode: FaceDetectionEngineMode)

    val faceDetectionLandmarkModeFlow: Flow<Boolean>
    suspend fun updateFaceDetectionLandmarkMode(enabled: Boolean)

    val adaptiveFaceDetectionIntervalEnabledFlow: Flow<Boolean>
    suspend fun updateAdaptiveFaceDetectionIntervalEnabled(enabled: Boolean)

    val faceDetectIntervalProfileFlow: Flow<FaceDetectIntervalProfile>
    suspend fun updateFaceDetectIntervalProfile(profile: FaceDetectIntervalProfile)

    // ── 阶段独立配置（ROI / Landmark）────────────────────────
    val roiStageConfigFlow: Flow<StageConfig>
    suspend fun updateRoiStageConfig(config: StageConfig)

    val landmarkStageConfigFlow: Flow<StageConfig>
    suspend fun updateLandmarkStageConfig(config: StageConfig)

    // ── AI Agent ────────────────────────────────────────────
    val aiAgentModeFlow: Flow<AiAgentMode>
    suspend fun updateAiAgentMode(mode: AiAgentMode)

    val aiAgentPrivacyLevelFlow: Flow<AiAgentPrivacyLevel>
    suspend fun updateAiAgentPrivacyLevel(level: AiAgentPrivacyLevel)

    val aiAgentLocalModelFlow: Flow<String>
    suspend fun updateAiAgentLocalModel(modelId: String)

    val aiAgentLocalUseOpenclFlow: Flow<Boolean>
    suspend fun updateAiAgentLocalUseOpencl(enabled: Boolean)

    // ── TAG 生成 ────────────────────────────────────────────
    val tagGenerationUseOpencl: Flow<Boolean>
    suspend fun updateTagGenerationUseOpencl(enabled: Boolean)

    /** 相册打标模型 key（AUTO / florence2_base / qwen3_vl_2b；默认 AUTO，由 TaggerModelSelector 解析为首选 Florence-2） */
    val taggerModelKeyFlow: Flow<String>
    suspend fun updateTaggerModelKey(key: String)
    fun getTaggerModelKeyBlocking(): String

    val openClDegradedDevices: Flow<String>
    suspend fun updateOpenClDegradedDevices(devicesJson: String)

    // ── 模型预下载 ──────────────────────────────────────────
    /** WiFi 下静默预下载推荐模型（默认开启）。 */
    val autoDownloadRecommendedOnWifiFlow: Flow<Boolean>
    suspend fun updateAutoDownloadRecommendedOnWifi(enabled: Boolean)

    // ── 远程模型配置（供应商维度） ────────────────────────────────
    val aiAgentRemoteModelConfigsFlow: Flow<String>
    suspend fun updateAiAgentRemoteModelConfigs(configsJson: String)

    val aiAgentSelectedRemoteModelFlow: Flow<String>
    suspend fun updateAiAgentSelectedRemoteModel(modelId: String)

    // ── AI Agent 推理偏好（LOCAL 模式下的本地/远程路由策略） ────
    val aiAgentInferencePreferenceFlow: Flow<AiAgentInferencePreference>
    suspend fun updateAiAgentInferencePreference(preference: AiAgentInferencePreference)

    // ── AI Agent L1 意图缓存调试开关 ─────────────────────────
    val aiAgentL1CacheEnabledFlow: Flow<Boolean>
    suspend fun updateAiAgentL1CacheEnabled(enabled: Boolean)

    // ── Cloudflare AI Gateway Token ─────────────────────────
    val cloudflareGatewayTokenFlow: Flow<String>
    suspend fun updateCloudflareGatewayToken(token: String)

    // ── 语音控制 ────────────────────────────────────────────
    val voiceCommandModeFlow: Flow<VoiceCommandMode>
    suspend fun updateVoiceCommandMode(mode: VoiceCommandMode)

    val localAsrModelFlow: Flow<String>
    suspend fun updateLocalAsrModel(modelId: String)

    val localKwsModelFlow: Flow<String>
    suspend fun updateLocalKwsModel(modelId: String)

    // ── 相机参数记忆 ──────────────────────────────────────────
    val cameraMemoryStateFlow: Flow<CameraMemoryState>
    suspend fun updateCameraMemoryState(state: CameraMemoryState)
    suspend fun resetCameraMemoryState()

    // ── 日志模块配置 ──────────────────────────────────────────
    val logModuleConfigFlow: Flow<LogModuleConfig>
    suspend fun updateLogModuleConfig(config: LogModuleConfig)

    // ── Chat 输入模式记忆 ────────────────────────────────────
    val chatInputModeFlow: Flow<String>
    suspend fun updateChatInputMode(mode: String)

    // ── Chat 当前会话记忆 ────────────────────────────────────
    val chatCurrentSessionIdFlow: Flow<String>
    suspend fun updateChatCurrentSessionId(sessionId: String)

    // ── 飞书远程控制 ──────────────────────────────────────────
    val feishuAppIdFlow: Flow<String>
    val feishuAppSecretFlow: Flow<String>
    suspend fun updateFeishuAppId(appId: String)
    suspend fun updateFeishuAppSecret(appSecret: String)

    // ── 远程通道选择 + Telegram ───────────────────────────────
    val selectedRemoteChannelFlow: Flow<String>
    suspend fun updateSelectedRemoteChannel(type: String)
    val telegramBotTokenFlow: Flow<String>
    val telegramAllowedChatIdFlow: Flow<String>
    suspend fun updateTelegramConfig(botToken: String, allowedChatId: String)

    // ── 服务端邮箱认证 ──────────────────────────────────────────
    val serverAuthTokenFlow: Flow<String>
    val serverAuthEmailFlow: Flow<String>
    suspend fun updateServerAuth(token: String, email: String)
    suspend fun clearServerAuth()
}


