package com.mamba.picme.agent.core.inference.local.llm

import android.content.Context
import android.graphics.Bitmap
import com.mamba.picme.agent.core.inference.local.llm.MnnLlmClient.NativeReleaseTarget
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.mnn.MnnGlobalReleaseLock
import com.mamba.picme.mnn.MnnResourceManager
import com.mamba.picme.agent.core.platform.thread.ThreadPoolManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LLM 模型未找到异常
 *
 * 用于区分"模型未下载"和"其他加载错误"，便于 UI 层引导用户下载。
 */
class LlmModelNotFoundException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * 本地 LLM 推理引擎（**VLM 打标专用**）
 *
 * 封装 MNN-LLM 客户端，支持多模型管理和懒加载。
 *
 * **适用范围（2026-08 端侧文本 LLM 移除后）**：本引擎仅保留端侧 VLM 能力——
 * TAG 生成 Pass 3 图像打标与图像理解（[imageInference]/[imageInferenceWithTimeout]）
 * 及模型生命周期管理（[loadModel]/[unload]/[trimMemory] 等）。
 * 端侧文本聊天/指令推理链路（原 chat()/流式 chat）已移除，相机 AI 指令改走远程
 * tool_calls（见 `inference/remote/tool/CameraToolService`）。
 *
 * **线程模型**：所有模型操作（load/unload/trimMemory/generate）统一在专用单线程上执行，
 * 避免 Compose 协程重组取消和多线程并发竞争导致的 MNN 全局状态冲突。
 *
 * @param context Application Context
 */
class LocalLlmEngine(private val context: Context) {

    private val tag = "LocalLlmEngine"
    private val client = MnnLlmClient(context)
    private val engineMutex = Mutex()
    private val resourceManager = MnnResourceManager.getInstance(context)

    private val modelDispatcher: CoroutineDispatcher = ThreadPoolManager.getInstance().modelDispatcher

    /**
     * 后台协程作用域，用于 fire-and-forget 异步任务（如 trimMemory、unload 投递）。
     * 所有任务在 [modelDispatcher] 上串行执行。
     */
    private val backgroundScope = CoroutineScope(SupervisorJob() + modelDispatcher)

    /**
     * 当前加载的模型 ID
     *
     * **注意**：仅在 [modelDispatcher] 线程上读写，通过 [engineMutex] 保护。
     */
    private var currentModelId: String? = null

    /**
     * 当前加载是否使用 OpenCL 后端
     *
     * **注意**：仅在 [modelDispatcher] 线程上读写，通过 [engineMutex] 保护。
     */
    private var currentUseOpencl: Boolean = false

    /**
     * 模型是否已加载
     */
    val isLoaded: Boolean
        get() = client.isLoaded

    /**
     * 是否已向 ResourceManager 注册引用
     */
    private val isRegistered = AtomicBoolean(false)

    init {
        resourceManager.registerSoftTrimListener(::onSoftTrim)
        resourceManager.registerSafeUnloadListener(::onSafeUnload)

        // [P0-4] 注册分级释放回调，供 releaseAtLevel("llm", level) 使用
        resourceManager.registerLlmReleaseCallback(MnnResourceManager.ReleaseLevel.SOFT) {
            enqueueTrimMemory()
        }
        resourceManager.registerLlmReleaseCallback(MnnResourceManager.ReleaseLevel.SESSION) {
            if (client.isLoaded) {
                client.releaseNative(NativeReleaseTarget.KV_CACHE)
            }
        }
        resourceManager.registerLlmReleaseCallback(MnnResourceManager.ReleaseLevel.FULL) {
            enqueueUnload()
        }
    }

    /**
     * 加载指定模型
     *
     * 所有操作在专用单线程 [modelDispatcher] 上串行执行，避免：
     * - Compose 协程重组取消（LeftCompositionCancellationException）
     * - 多线程并发竞争（两个线程同时进入 loadModel）
     * - IO 线程池分散执行导致的状态不一致
     *
     * @param modelId 模型注册表中的 key，如 "qwen3_5_2b" 或 "qwen3_0_6b"
     * @return 加载结果，失败时返回具体错误原因
     */
    suspend fun loadModel(modelId: String, useOpencl: Boolean = false): Result<Unit> = withContext(modelDispatcher) {
        engineMutex.withLock {
            // 双重检查：已加载且是同一模型、同一后端，直接返回
            if (client.isLoaded && currentModelId == modelId && currentUseOpencl == useOpencl) {
                ensureRegistered()
                Logger.d(tag, "Model $modelId already loaded (useOpencl=$useOpencl)")
                return@withLock Result.success(Unit)
            }

            // 如果已加载的是其他模型或不同后端，先卸载
            if (client.isLoaded) {
                Logger.d(tag, "Unloading previous model: $currentModelId (useOpencl=$currentUseOpencl)")
                client.unload()
                currentModelId = null
                currentUseOpencl = false
                isRegistered.set(false)
            }

            try {
                Logger.i(tag, "Loading LLM model: $modelId, useOpencl=$useOpencl")
                val success = client.load(modelId, useOpencl)
                if (success) {
                    // 原子性设置：nativeHandle 和 currentModelId 在同一把锁内完成
                    currentModelId = modelId
                    currentUseOpencl = useOpencl
                    ensureRegistered()
                    Logger.i(tag, "Model $modelId loaded successfully")
                    Result.success(Unit)
                } else {
                    Logger.e(tag, "Failed to load model $modelId (native load returned false)")
                    Result.failure(
                        LlmModelNotFoundException(
                            "模型加载失败，请确认模型已下载。设置 → AI 模型管理 → 下载 $modelId"
                        )
                    )
                }
            } catch (exception: CancellationException) {
                // 取消异常必须重新抛出，不吞没
                Logger.w(tag, "Model loading cancelled: $modelId")
                throw exception
            } catch (exception: IllegalStateException) {
                // LlmModelManager 抛出的模型未找到异常
                Logger.e(tag, "Model not found: $modelId", exception)
                Result.failure(
                    LlmModelNotFoundException(
                        "模型未下载，请前往设置 → AI 模型管理下载模型",
                        exception
                    )
                )
            } catch (exception: Exception) {
                Logger.e(tag, "Exception loading model $modelId", exception)
                Result.failure(exception)
            }
        }
    }

    /**
     * 检查指定模型是否已下载可用
     */
    fun isModelAvailable(modelId: String, context: Context): Boolean {
        return LlmModelManager(context).isModelCached(modelId)
    }

    /**
     * 检查指定模型与后端是否已加载
     *
     * 供调用方在加载前快速判断是否可以复用当前模型，避免不必要的 unload/reload。
     */
    suspend fun isLoadedAs(modelId: String, useOpencl: Boolean): Boolean = withContext(modelDispatcher) {
        engineMutex.withLock {
            client.isLoaded && currentModelId == modelId && currentUseOpencl == useOpencl
        }
    }

    /**
     * 最近一次本地生成的性能指标（仅图像推理完成后有效）。
     */
    @Volatile
    var lastGenerationMetrics: LlmGenerationMetrics? = null
        private set

    /**
     * 使用本地多模态模型对图片进行推理。
     *
     * 将 [systemPrompt] 和 [userPrompt] 与 [bitmap] 一起发送给 MNN-LLM 视觉编码器，
     * 返回模型生成的文本回复。
     *
     * **注意**：此方法在 [modelDispatcher] 上阻塞执行，调用方应在 IO 协程中调用。
     *
     * @param bitmap       输入图片（建议最长边 ≤ 512px）
     * @param systemPrompt 系统提示词（定义任务，如 "简短描述图片内容"）
     * @param userPrompt   用户提示词（具体问题）
     * @param maxTokens    最大生成 token 数，默认 128
     * @return 模型生成的文本回复，失败时返回空字符串
     */
    suspend fun imageInference(
        bitmap: Bitmap,
        systemPrompt: String,
        userPrompt: String = "请描述这张图片",
        maxTokens: Int = 256
    ): String = withContext(modelDispatcher) {
        engineMutex.withLock {
            if (!client.isLoaded) {
                Logger.w(tag, "LLM not loaded, cannot do image inference")
                return@withLock ""
            }

            try {
                val result = client.generateWithImage(
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    bitmap = bitmap,
                    maxNewTokens = maxTokens
                )
                if (result.error != null) {
                    Logger.w(tag, "Image inference error: ${result.error}")
                    return@withLock ""
                }
                lastGenerationMetrics = LlmGenerationMetrics(
                    promptLen = result.promptLen,
                    decodeLen = result.decodeLen,
                    prefillTime = result.prefillTime,
                    decodeTime = result.decodeTime,
                    prefillSpeed = result.prefillSpeed,
                    decodeSpeed = result.decodeSpeed
                )
                Logger.d(tag, "[Vision] inference done: ${result.response.take(100)}, " +
                    "promptLen=${result.promptLen}, decodeLen=${result.decodeLen}, " +
                    "vision=${result.visionTime}us, decode=${result.decodeTime}us")
                result.response
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Logger.e(tag, "Image inference failed", exception)
                ""
            }
        }
    }

    /**
     * 带超时的多模态图片推理。
     *
     * @param timeoutMs 最大等待时间（毫秒），默认 30 秒
     * @return 模型生成的文本回复；超时或失败时返回空字符串
     */
    suspend fun imageInferenceWithTimeout(
        bitmap: Bitmap,
        systemPrompt: String,
        userPrompt: String = "请描述这张图片",
        maxTokens: Int = 128,
        timeoutMs: Int = 30_000
    ): String = withContext(modelDispatcher) {
        engineMutex.withLock {
            if (!client.isLoaded) {
                Logger.w(tag, "LLM not loaded, cannot do image inference")
                return@withLock ""
            }

            try {
                val result = client.generateWithImageTimeout(
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    bitmap = bitmap,
                    maxNewTokens = maxTokens,
                    timeoutMs = timeoutMs
                )
                if (result.error != null) {
                    Logger.w(tag, "Image inference with timeout error: ${result.error}")
                    return@withLock "__ERROR_${result.error}__"
                }
                lastGenerationMetrics = LlmGenerationMetrics(
                    promptLen = result.promptLen,
                    decodeLen = result.decodeLen,
                    prefillTime = result.prefillTime,
                    decodeTime = result.decodeTime,
                    prefillSpeed = result.prefillSpeed,
                    decodeSpeed = result.decodeSpeed
                )
                Logger.d(tag, "[Vision] inference with timeout done: ${result.response.take(100)}, " +
                    "promptLen=${result.promptLen}, decodeLen=${result.decodeLen}, " +
                    "vision=${result.visionTime}us, decode=${result.decodeTime}us")
                result.response
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Logger.e(tag, "Image inference with timeout failed", exception)
                ""
            }
        }
    }

    /**
     * 卸载当前模型，释放内存
     *
     * 通过 ResourceManager 协调释放，避免与 ASR 的 MNN 全局状态冲突。
     */
    fun unload() {
        resourceManager.releaseLlm(
            owner = "LocalLlmEngine",
            onSafeUnload = { enqueueUnload() },
            onSoftRelease = { enqueueTrimMemory() }
        )
        isRegistered.set(false)
    }

    /**
     * 重置模型状态，清理历史记录和 KV Cache（不卸载模型）
     *
     * 用于相机场景降低内存占用，避免与 Sherpa-MNN ASR 的 MNN 全局状态冲突。
     * 比 unload() 更安全，不会破坏 MNN 全局内存分配器。
     */
    fun trimMemory() {
        enqueueTrimMemory()
    }

    /**
     * 显式释放 LLM native 权重/interpreter/tensor。
     */
    fun releaseWeightsInterpreterTensors() {
        enqueueUnload()
    }

    /**
     * [P0-2] 反注册 ResourceManager 监听器，释放所有资源。
     *
     * 应在不再使用此引擎时调用，防止监听器累积和 native 泄漏。
     */
    fun release() {
        resourceManager.unregisterSoftTrimListener(::onSoftTrim)
        resourceManager.unregisterSafeUnloadListener(::onSafeUnload)
        resourceManager.unregisterReleaseCallbacks("llm")
        unload()
    }

    /**
     * 将 trimMemory 任务投递到专用线程执行
     */
    private fun enqueueTrimMemory() {
        backgroundScope.launch {
            if (!engineMutex.tryLock()) {
                Logger.w(tag, "Skip trimMemory: engine is busy")
                return@launch
            }
            try {
                if (client.isLoaded) {
                    client.releaseNative(NativeReleaseTarget.KV_CACHE)
                    Logger.i(tag, "LLM memory trimmed (history cleared, model still loaded)")
                }
            } finally {
                engineMutex.unlock()
            }
        }
    }

    /**
     * 将 unload 任务投递到专用线程执行
     *
     * 避免递归 runBlocking 导致的死锁。
     * 使用 MnnGlobalReleaseLock 串行化 MNN native 释放。
     */
    private fun enqueueUnload() {
        backgroundScope.launch {
            if (!engineMutex.tryLock()) {
                Logger.w(tag, "Skip unload: engine is busy, will retry on next operation")
                return@launch
            }
            try {
                if (client.isLoaded) {
                    // 使用 MNN 全局锁串行化 native 释放
                    MnnGlobalReleaseLock.withLock {
                        client.releaseNative(NativeReleaseTarget.WEIGHTS_INTERPRETER_TENSORS)
                    }
                    currentModelId = null
                    currentUseOpencl = false
                    Logger.i(tag, "LLM fully unloaded")
                }
            } finally {
                engineMutex.unlock()
            }
        }
    }

    private fun ensureRegistered() {
        if (isRegistered.compareAndSet(false, true)) {
            resourceManager.acquireLlm("LocalLlmEngine")
        }
    }

    private fun onSoftTrim() {
        if (client.isLoaded) {
            enqueueTrimMemory()
        }
    }

    /**
     * 安全卸载回调（由 ResourceManager 触发）
     *
     * 注意：此回调已在 MnnGlobalReleaseLock 保护下执行，直接同步卸载即可。
     * 不要投递到 modelExecutor，否则真正的释放会脱离锁保护。
     *
     * 必须使用 engineMutex.tryLock() 保护，防止与推理并发执行时
     * 在推理检查 client.isLoaded 为 true 后、实际推理前将模型销毁，
     * 导致 IllegalStateException("LLM model not loaded")。
     * 若 engine 正忙（推理进行中），则跳过本次卸载，模型保持加载。
     */
    private fun onSafeUnload() {
        if (!client.isLoaded) {
            isRegistered.set(false)
            return
        }

        if (!engineMutex.tryLock()) {
            Logger.w(tag, "Skip sync unload: engine is busy (inference in progress), model stays loaded")
            return
        }

        try {
            client.releaseNative(NativeReleaseTarget.WEIGHTS_INTERPRETER_TENSORS)
            currentModelId = null
            currentUseOpencl = false
            Logger.i(tag, "LLM fully unloaded (sync)")
        } catch (e: Exception) {
            Logger.e(tag, "LLM sync unload failed", e)
        } finally {
            engineMutex.unlock()
        }
        isRegistered.set(false)
    }
}
