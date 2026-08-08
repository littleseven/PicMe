package com.mamba.picme.agent.core.inference.local

import com.mamba.picme.agent.core.facade.AgentConfigurator
import com.mamba.picme.agent.core.inference.local.llm.LlmGenerationMetrics
import com.mamba.picme.agent.core.inference.local.llm.LlmModelNotFoundException
import com.mamba.picme.agent.core.inference.local.llm.LocalLlmEngine
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.thread.SharedDispatcherProvider
import com.mamba.picme.agent.core.runtime.state.SceneManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 本地模型加载服务（决策3 / ADR-010 step3）。
 *
 * 从 AgentOrchestrator 抽出的本地 LLM 生命周期管理：模型加载/卸载/确保加载/withModelLoaded、
 * 场景驱动卸载（进入相机页释放内存）、加载状态流。**相机 Agent 与后台打标 Worker 共用此服务**
 * （`getLlmEngine`）。
 *
 * 引擎实例（[LocalLlmEngine]）仍由 [AgentConfigurator] 持有，本服务经只读访问操作它——与
 * RemoteChatEngine 同一模式，最小侵入。AgentOrchestrator 现阶段以薄委托暴露同等 API，消费者
 * 将逐步迁移到本服务直接调用。
 */
class LocalModelService internal constructor(
    private val configurator: AgentConfigurator
) {

    private val tag = "LocalModelService"
    private val orchestratorDispatcher = SharedDispatcherProvider.instance.orchestratorDispatcher

    /**
     * 后台作用域：场景驱动的 LLM 卸载等 fire-and-forget 任务。
     */
    private val backgroundScope = CoroutineScope(SupervisorJob())

    private val localLlmEngine: LocalLlmEngine get() = configurator.localLlmEngine
    private val sceneManager get() = configurator.sceneManager

    private val _isModelLoading = MutableStateFlow(false)

    /** 模型是否正在加载（供 UI 显示"Agent 启动中"）。 */
    val isModelLoading: StateFlow<Boolean> = _isModelLoading.asStateFlow()

    /** 模型是否已加载。 */
    val isModelLoaded: Boolean
        get() = configurator.isModelLoaded

    init {
        // 场景驱动的 LLM 生命周期：进入相机页时立即卸载本地 LLM，释放内存给美颜/相机预览。
        // 相机页触发 Agent 时再异步加载（调用方通过 ensureModelLoaded / withModelLoaded）。
        backgroundScope.launch(orchestratorDispatcher) {
            sceneManager.currentScene.collect { scene ->
                if (scene == SceneManager.Scene.CAMERA && localLlmEngine.isLoaded) {
                    Logger.i(tag, "CAMERA scene entered, unloading local LLM to free memory")
                    unloadModel()
                }
            }
        }
    }

    /**
     * 获取本地 LLM 推理引擎。
     *
     * 供非 Agent 消费者（如后台标签索引 Worker）直接使用模型进行推理。
     * **注意**：调用方应确保模型已加载后再使用。
     */
    fun getLlmEngine(): LocalLlmEngine = localLlmEngine

    /** 最近一次本地 LLM 生成的性能指标。 */
    fun getLastLocalGenerationMetrics(): LlmGenerationMetrics? = localLlmEngine.lastGenerationMetrics

    /**
     * 加载本地模型。
     *
     * @param modelId 模型 ID，为空时使用当前配置模型
     */
    suspend fun loadModel(modelId: String? = null): Result<Unit> =
        ensureModelLoaded(modelId = modelId, caller = "loadModel")

    /** 卸载模型。 */
    fun unloadModel() {
        localLlmEngine.unload()
    }

    /**
     * 确保本地模型已加载。
     *
     * 所有本地 LLM 推理入口应统一通过此方法（或 [withModelLoaded]）加载模型，
     * 避免调用方遗漏加载检查导致空结果或崩溃。
     *
     * @param modelId 模型 ID，为空时使用当前配置模型
     * @param useOpencl 是否使用 OpenCL，为 null 时使用当前配置
     * @param caller 调用方标识，用于加载审计日志
     * @return 加载结果
     */
    suspend fun ensureModelLoaded(
        modelId: String? = null,
        useOpencl: Boolean? = null,
        caller: String = "unknown"
    ): Result<Unit> {
        val targetModel = modelId ?: configurator.getCurrentModelId()
        val targetUseOpencl = useOpencl ?: configurator.getLocalUseOpencl()

        if (targetModel.isBlank()) {
            Logger.w(tag, "[ModelLoadAudit] caller=$caller, modelId is blank")
            return Result.failure(IllegalStateException("未配置模型 ID"))
        }

        Logger.i(
            tag,
            "[ModelLoadAudit] caller=$caller, model=$targetModel, " +
                "useOpencl=$targetUseOpencl, alreadyLoaded=${localLlmEngine.isLoaded}"
        )

        if (!localLlmEngine.isModelAvailable(targetModel, configurator.getContext())) {
            Logger.w(tag, "[ModelLoadAudit] caller=$caller, model not downloaded: $targetModel")
            return Result.failure(
                LlmModelNotFoundException(
                    "模型未下载，请前往设置 → AI 模型管理下载 $targetModel"
                )
            )
        }

        _isModelLoading.value = true
        val result = try {
            localLlmEngine.loadModel(targetModel, targetUseOpencl)
        } finally {
            _isModelLoading.value = false
        }

        result.onSuccess {
            Logger.i(tag, "[ModelLoadAudit] caller=$caller, model loaded successfully")
        }.onFailure { error ->
            Logger.e(tag, "[ModelLoadAudit] caller=$caller, model load failed", error)
        }

        return result
    }

    /**
     * 在模型已加载的前提下执行推理块。
     *
     * 如果模型未加载，会先尝试加载，成功后再执行 [inferenceBlock]。
     * 所有 imageInference / generate / chat 等本地 LLM 调用应统一走此入口。
     *
     * @param modelId 模型 ID，为空时使用当前配置模型
     * @param useOpencl 是否使用 OpenCL，为 null 时使用当前配置
     * @param caller 调用方标识，用于加载审计日志
     * @param inferenceBlock 推理逻辑块，接收 [LocalLlmEngine]
     * @return 推理结果；加载失败时返回 failure
     */
    suspend fun <T> withModelLoaded(
        modelId: String? = null,
        useOpencl: Boolean? = null,
        caller: String = "unknown",
        inferenceBlock: suspend (LocalLlmEngine) -> T
    ): Result<T> {
        val loadResult = ensureModelLoaded(
            modelId = modelId,
            useOpencl = useOpencl,
            caller = "$caller→withModelLoaded"
        )
        if (loadResult.isFailure) {
            @Suppress("UNCHECKED_CAST")
            return loadResult as Result<T>
        }

        return try {
            Result.success(inferenceBlock(localLlmEngine))
        } catch (e: Exception) {
            Logger.e(tag, "[ModelLoadAudit] caller=$caller, inference failed", e)
            Result.failure(e)
        }
    }

    /**
     * 场景驱动的模型加载策略（processInput 入口的二次确认）。
     *
     * 进入相机页时默认不保留 LLM；此处确保相机页触发 Agent 时先释放再异步加载
     *（而非直接使用可能已陈旧的模型上下文）。
     */
    internal fun applySceneDrivenModelPolicy() {
        val currentScene = sceneManager.currentScene.value
        when (currentScene) {
            SceneManager.Scene.CAMERA -> {
                if (localLlmEngine.isLoaded) {
                    Logger.i(tag, "CAMERA scene: unloading local LLM before agent inference")
                    unloadModel()
                }
            }
            else -> { /* 非相机场景：保持当前状态 */ }
        }
    }
}
