package com.mamba.picme.domain.tag

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import com.mamba.picme.agent.core.inference.local.llm.LocalLlmEngine
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.domain.repository.UserSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

/**
 * OpenCL 健康守护与自动降级
 *
 * 职责：
 * - 批量 Pass 3 前执行短 warmup，提前发现 OpenCL 挂起
 * - 单次推理带超时包装
 * - 连续失败后标记设备降级，后续默认使用 CPU
 * - 记录 OpenCL 推理事件，用于问题定位
 */
class OpenClGuardian(
    private val context: Context,
    private val engine: LocalLlmEngine,
    private val prefs: UserSettingsRepository,
    private val modelId: String = "qwen3_5_2b"
) {

    companion object {
        private const val TAG = "OpenClGuardian"

        /** Warmup 超时（毫秒）。Qwen3-VL-2B 在 OpenCL 上首次编译 kernel 可能超过 5s，放宽到 20s。 */
        private const val WARMUP_TIMEOUT_MS = 20_000

        /** 单次推理超时（毫秒） */
        private const val INFERENCE_TIMEOUT_MS = 30_000

        /** 连续失败多少次后强制降级 */
        private const val DEGRADE_THRESHOLD = 3

        /** 降级后冷却时间（毫秒）：即使手动开启 OpenCL，也先跑 CPU 一段时间 */
        private const val DEGRADE_COOLDOWN_MS = 24 * 60 * 60 * 1000L
    }

    private val guardianMutex = Mutex()

    /** 本次会话连续 OpenCL 失败次数 */
    private var consecutiveFailures = 0

    /** 最近一次标记降级的时间 */
    private var degradedAtMs: Long? = null

    /** 是否已在本会话中确认 OpenCL 可用 */
    private var warmupPassed: Boolean? = null

    /**
     * 是否应使用 CPU 后端
     */
    suspend fun shouldUseCpu(): Boolean {
        // 1. 全局黑名单检查
        if (isDeviceBlacklisted()) {
            Logger.i(TAG, "shouldUseCpu=true: device blacklisted")
            return true
        }

        // 2. 本次会话降级冷却期检查
        val degraded = degradedAtMs
        if (degraded != null && System.currentTimeMillis() - degraded < DEGRADE_COOLDOWN_MS) {
            Logger.i(TAG, "shouldUseCpu=true: cooldown active since $degraded")
            return true
        }

        // 3. 用户显式关闭 OpenCL
        val useOpencl = prefs.tagGenerationUseOpencl.first()
        Logger.i(TAG, "shouldUseCpu=${!useOpencl}: user preference useOpencl=$useOpencl")
        return !useOpencl
    }

    /**
     * 批量 Pass 3 前 warmup：用空白图跑一次短超时推理
     */
    suspend fun warmup(): OpenClHealth {
        guardianMutex.withLock {
            if (warmupPassed == true) return OpenClHealth.Healthy
            if (shouldUseCpu()) return OpenClHealth.CpuByPolicy

            val bitmap = createBlankBitmap(224, 224)
            return try {
                val response = engine.imageInferenceWithTimeout(
                    bitmap = bitmap,
                    systemPrompt = "描述图片",
                    userPrompt = "描述",
                    maxTokens = 16,
                    timeoutMs = WARMUP_TIMEOUT_MS
                )
                if (response.startsWith("__ERROR_OPENCL_TIMEOUT__")) {
                    markDegraded("warmup timeout")
                    OpenClHealth.Timeout
                } else if (response.startsWith("__ERROR_")) {
                    markDegraded("warmup error: $response")
                    OpenClHealth.Error
                } else {
                    warmupPassed = true
                    consecutiveFailures = 0
                    OpenClHealth.Healthy
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    /**
     * 单次 Pass 3 推理，带超时与自动降级。
     *
     * 注意：CPU 路径的实际推理在锁外执行，避免 CPU fallback 时被 Guardian 串行阻塞；
     * OpenCL 路径仍保持串行，以便准确统计连续失败并安全降级。
     */
    suspend fun inference(
        bitmap: Bitmap,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 256
    ): OpenClInferenceResult {
        // 1. 在锁内快速判定策略（读连续失败、降级冷却、用户偏好）。
        val useCpu = guardianMutex.withLock { shouldUseCpu() }

        if (useCpu) {
            // 2a. CPU 路径：确保模型切换到 CPU（锁内），然后释放锁执行实际推理。
            guardianMutex.withLock { ensureCpuLoaded() }
            val response = engine.imageInference(bitmap, systemPrompt, userPrompt, maxTokens)
            return OpenClInferenceResult.Success(response, backend = "CPU")
        }

        // 2b. OpenCL 路径：保持串行，便于失败后更新 Guardian 状态。
        return guardianMutex.withLock {
            // 双重检查：在等锁期间可能已被其他任务降级。
            if (shouldUseCpu()) {
                ensureCpuLoaded()
                val response = engine.imageInference(bitmap, systemPrompt, userPrompt, maxTokens)
                return@withLock OpenClInferenceResult.Success(response, backend = "CPU")
            }

            val backend = "OpenCL"
            val response = engine.imageInferenceWithTimeout(
                bitmap = bitmap,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                maxTokens = maxTokens,
                timeoutMs = INFERENCE_TIMEOUT_MS
            )

            when {
                response.startsWith("__ERROR_OPENCL_TIMEOUT__") -> {
                    consecutiveFailures++
                    if (consecutiveFailures >= DEGRADE_THRESHOLD) {
                        markDegraded("consecutive $consecutiveFailures timeouts")
                    }
                    OpenClInferenceResult.Timeout(backend)
                }
                response.startsWith("__ERROR_") -> {
                    consecutiveFailures++
                    if (consecutiveFailures >= DEGRADE_THRESHOLD) {
                        markDegraded("consecutive $consecutiveFailures errors")
                    }
                    OpenClInferenceResult.Error(backend, response.removePrefix("__ERROR_"))
                }
                else -> {
                    consecutiveFailures = 0
                    OpenClInferenceResult.Success(response, backend)
                }
            }
        }
    }

    /**
     * 强制切换到 CPU 后端并重新加载模型
     */
    suspend fun fallbackToCpu(reason: String) {
        guardianMutex.withLock {
            markDegraded(reason)
            ensureCpuLoaded()
        }
    }

    /**
     * 标记设备降级
     */
    private suspend fun markDegraded(reason: String) {
        consecutiveFailures = 0
        degradedAtMs = System.currentTimeMillis()
        warmupPassed = false

        val deviceKey = buildDeviceKey()
        val current = getBlacklistedDevices().toMutableSet()
        current.add(deviceKey)
        saveBlacklistedDevices(current)

        Logger.w(TAG, "OpenCL degraded: $reason, device=$deviceKey")
    }

    private suspend fun ensureCpuLoaded() {
        // 如果当前已经是 CPU 加载的同一模型，直接复用，避免每次 CPU fallback 都重装。
        if (engine.isLoadedAs(modelId, useOpencl = false)) {
            return
        }
        engine.unload()
        val orchestrator = com.mamba.picme.agent.core.facade.AgentOrchestrator.getInstance(context)
        orchestrator.localModelService.ensureModelLoaded(
            modelId = modelId,
            useOpencl = false,
            caller = "OpenClGuardian:ensureCpuLoaded"
        )
    }

    private suspend fun isDeviceBlacklisted(): Boolean {
        val key = buildDeviceKey()
        return getBlacklistedDevices().contains(key)
    }

    private suspend fun getBlacklistedDevices(): Set<String> {
        return try {
            val json = prefs.openClDegradedDevices.first()
            if (json.isBlank()) return emptySet()
            val array = JSONArray(json)
            (0 until array.length()).map { array.getString(it) }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private suspend fun saveBlacklistedDevices(devices: Set<String>) {
        val array = JSONArray(devices.toList())
        prefs.updateOpenClDegradedDevices(array.toString())
    }

    private fun buildDeviceKey(): String {
        val manufacturer = Build.MANUFACTURER ?: "unknown"
        val model = Build.MODEL ?: "unknown"
        val hardware = Build.HARDWARE ?: "unknown"
        val board = Build.BOARD ?: "unknown"
        // 注意：不能调用 GLES20.glGetString，因为 TAG 扫描线程没有 EGL Context，会触发 SIGSEGV
        return "$manufacturer|$model|$hardware|$board"
    }

    private fun createBlankBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GRAY)
        return bitmap
    }
}

enum class OpenClHealth {
    Healthy,
    Timeout,
    Error,
    CpuByPolicy
}

sealed class OpenClInferenceResult {
    data class Success(val response: String, val backend: String) : OpenClInferenceResult()
    data class Timeout(val backend: String) : OpenClInferenceResult()
    data class Error(val backend: String, val message: String) : OpenClInferenceResult()
}
