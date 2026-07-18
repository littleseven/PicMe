package com.mamba.picme.core.common

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import com.mamba.picme.domain.model.LogModuleConfig

/**
 * 日志条目数据模型
 *
 * @property timestamp 时间戳（格式：HH:mm:ss.SSS）
 * @property tag 日志标签（自动添加 PoLang: 前缀）
 * @property level 日志级别
 * @property message 日志内容
 */
data class LogEntry(
    val timestamp: String,
    val tag: String,
    val level: LogLevel,
    val message: String
)

/**
 * 日志级别枚举
 */
enum class LogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR
}

/**
 * PoLang 统一日志系统
 *
 * 职责：
 * 1. 统一日志格式（PoLang:[ModuleName]）
 * 2. 缓存日志供调试界面展示（最多 500 条）
 * 3. 封装 Android Log API
 *
 * 使用方式：
 * ```kotlin
 * Logger.d("Camera", "Preview started")
 * Logger.i("Storage", "Saved image: $path")
 * Logger.w("AI", "Face detection timeout")
 * Logger.e("Network", "Connection failed", exception)
 * ```
 *
 * 规范：
 * - 标签使用模块名（Camera、Gallery、AI 等）
 * - 禁止直接使用 android.util.Log
 * - 关键状态流转必须记录日志
 */
object Logger {
    private const val TAG_PREFIX = "PoLang:"
    private const val MAX_LOG_ENTRIES = 500

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs = _logs.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    /** 限流表：key -> 上次打印时间戳(ms) */
    private val throttleMap = ConcurrentHashMap<String, Long>(64)

    /** 日志模块开关配置 */
    private var moduleConfig: LogModuleConfig =
        LogModuleConfig.default()

    /**
     * 更新日志模块配置
     */
    fun setModuleConfig(config: LogModuleConfig) {
        moduleConfig = config
    }

    /**
     * 检查指定标签的日志是否允许输出
     */
    fun isLogEnabled(tag: String): Boolean {
        return moduleConfig.isTagEnabled(tag)
    }

    /**
     * 检测是否在真实的 Android 运行时环境中。
     *
     * 注意：Android 单元测试环境中 `android.util.Log` 类存在但方法是 stub，
     * 调用会抛出 `RuntimeException: Method * in android.util.Log not mocked`。
     * 因此需要实际调用一次来检测可用性。
     */
    private val isAndroidRuntime: Boolean by lazy {
        try {
            Log.i(TAG_PREFIX + "Logger", "Runtime check")
            true
        } catch (exception: RuntimeException) {
            false
        }
    }

    /**
     * 限流 Debug 日志：同一 key 最多每 [intervalMs] 毫秒打印一次（默认 1000ms）。
     * 适用于渲染循环、帧分析等高频场景，避免日志洪水。
     *
     * @param tag 模块标签
     * @param key 限流 key，建议用 "tag:消息类型" 格式
     * @param message 日志内容
     * @param intervalMs 最短打印间隔，默认 1000ms
     */
    fun dThrottled(tag: String, key: String, message: String, intervalMs: Long = 1_000L) {
        if (!isLogEnabled(tag)) return
        val now = System.currentTimeMillis()
        val last = throttleMap[key] ?: 0L
        if (now - last >= intervalMs) {
            throttleMap[key] = now
            d(tag, message)
        }
    }

    /**
     * Verbose 级别日志（最详细，用于调试追踪）
     *
     * @param tag 模块标签（不含 PoLang: 前缀）
     * @param message 日志内容
     */
    fun v(tag: String, message: String) {
        if (!isLogEnabled(tag)) return
        logToMemory(LogLevel.VERBOSE, tag, message)
        if (isAndroidRuntime) {
            Log.v("$TAG_PREFIX$tag", message)
        } else {
            println("[VERBOSE] $TAG_PREFIX$tag: $message")
        }
    }

    /**
     * Debug 级别日志
     *
     * @param tag 模块标签（不含 PoLang: 前缀）
     * @param message 日志内容
     */
    fun d(tag: String, message: String) {
        if (!isLogEnabled(tag)) return
        logToMemory(LogLevel.DEBUG, tag, message)
        if (isAndroidRuntime) {
            Log.d("$TAG_PREFIX$tag", message)
        } else {
            println("[DEBUG] $TAG_PREFIX$tag: $message")
        }
    }

    /**
     * Info 级别日志
     *
     * @param tag 模块标签（不含 PoLang: 前缀）
     * @param message 日志内容
     */
    fun i(tag: String, message: String) {
        if (!isLogEnabled(tag)) return
        logToMemory(LogLevel.INFO, tag, message)
        if (isAndroidRuntime) {
            Log.i("$TAG_PREFIX$tag", message)
        } else {
            println("[INFO] $TAG_PREFIX$tag: $message")
        }
    }

    /**
     * Warning 级别日志
     *
     * @param tag 模块标签（不含 PoLang: 前缀）
     * @param message 日志内容
     */
    fun w(tag: String, message: String) {
        if (!isLogEnabled(tag)) return
        logToMemory(LogLevel.WARN, tag, message)
        if (isAndroidRuntime) {
            Log.w("$TAG_PREFIX$tag", message)
        } else {
            println("[WARN] $TAG_PREFIX$tag: $message")
        }
    }

    /**
     * Warning 级别日志（带异常）
     *
     * @param tag 模块标签（不含 PoLang: 前缀）
     * @param message 日志内容
     * @param throwable 异常对象
     */
    fun w(tag: String, message: String, throwable: Throwable) {
        if (!isLogEnabled(tag)) return
        val fullMessage = "$message: ${throwable.localizedMessage}"
        logToMemory(LogLevel.WARN, tag, fullMessage)
        if (isAndroidRuntime) {
            Log.w("$TAG_PREFIX$tag", message, throwable)
        } else {
            println("[WARN] $TAG_PREFIX$tag: $message")
            throwable.printStackTrace()
        }
    }

    /**
     * Error 级别日志
     *
     * @param tag 模块标签（不含 PoLang: 前缀）
     * @param message 日志内容
     * @param throwable 异常对象（可选）
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        // Error 级别日志始终输出，不受模块开关限制
        val fullMessage = if (throwable != null) {
            "$message: ${throwable.localizedMessage}"
        } else {
            message
        }
        logToMemory(LogLevel.ERROR, tag, fullMessage)

        if (isAndroidRuntime) {
            if (throwable != null) {
                Log.e("$TAG_PREFIX$tag", message, throwable)
            } else {
                Log.e("$TAG_PREFIX$tag", message)
            }
        } else {
            println("[ERROR] $TAG_PREFIX$tag: $message")
            throwable?.printStackTrace()
        }
    }

    /**
     * 记录日志到内存缓存
     */
    private fun logToMemory(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(
            timestamp = timeFormat.format(Date()),
            tag = tag,
            level = level,
            message = message
        )

        val currentList = _logs.value.toMutableList()
        currentList.add(0, entry)

        // 维护最大条目数限制
        if (currentList.size > MAX_LOG_ENTRIES) {
            currentList.removeAt(currentList.size - 1)
        }

        _logs.value = currentList
    }

    /**
     * 清空内存中的日志缓存
     */
    fun clear() {
        _logs.value = emptyList()
    }
}

/**
 * 向后兼容的别名
 * 逐步迁移到 Logger 后可移除
 */
@Deprecated(
    message = "Use Logger instead",
    replaceWith = ReplaceWith("Logger", "Logger"),
    level = DeprecationLevel.WARNING
)
typealias PoLangLogger = Logger
