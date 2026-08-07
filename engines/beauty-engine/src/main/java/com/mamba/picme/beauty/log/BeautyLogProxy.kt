package com.mamba.picme.beauty.log

import android.util.Log
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Beauty Engine 日志代理
 *
 * 职责：
 * 1. 作为 Beauty Engine 模块内统一的日志入口
 * 2. 优先通过反射调用 app 模块的 Logger（支持模块开关控制）
 * 3. 未绑定或反射失败时 fallback 到 android.util.Log
 *
 * 性能优化：
 * - 方法内联（inline）消除调用开销
 * - 反射方法只查找一次，缓存 Method 引用
 * - 使用 @JvmStatic 避免单例对象访问开销
 *
 * 初始化方式（在 app 模块 Application.onCreate 中）：
 * ```kotlin
 * BeautyLogProxy.bindLogger(Logger) // 或直接反射绑定类名
 * ```
 */
object BeautyLogProxy : BeautyLog {

    private const val TAG_PREFIX = "PoLang:"

    /** 反射缓存的 Logger 对象 */
    @Volatile
    private var loggerInstance: Any? = null

    /** 反射缓存的方法 */
    private var methodD: Method? = null
    private var methodI: Method? = null
    private var methodW: Method? = null
    private var methodWThrowable: Method? = null
    private var methodE: Method? = null
    private var methodEThrowable: Method? = null
    private var methodIsLogEnabled: Method? = null

    /** 是否已成功绑定 */
    private val isBound: Boolean
        get() = loggerInstance != null

    /**
     * 绑定外部 Logger 实例（推荐方式）
     *
     * @param logger app 模块的 Logger object 实例
     */
    fun bindLogger(logger: Any) {
        val loggerClass = logger.javaClass
        try {
            methodD = loggerClass.getMethod("d", String::class.java, String::class.java)
            methodI = loggerClass.getMethod("i", String::class.java, String::class.java)
            methodW = loggerClass.getMethod("w", String::class.java, String::class.java)
            methodWThrowable = loggerClass.getMethod("w", String::class.java, String::class.java, Throwable::class.java)
            methodE = loggerClass.getMethod("e", String::class.java, String::class.java, Throwable::class.java)
            // e(tag, message) 可能没有，检查 e(tag, message, throwable?) 的重载
            try {
                methodEThrowable = loggerClass.getMethod("e", String::class.java, String::class.java, Throwable::class.java)
            } catch (_: NoSuchMethodException) {
                methodEThrowable = null
            }
            // isLogEnabled(tag) 用于查询模块开关状态
            try {
                methodIsLogEnabled = loggerClass.getMethod("isLogEnabled", String::class.java)
            } catch (_: NoSuchMethodException) {
                methodIsLogEnabled = null
            }
            loggerInstance = logger
        } catch (e: Exception) {
            // 绑定失败，继续使用 fallback
            Log.w(TAG_PREFIX + "BeautyLogProxy", "Failed to bind logger, using fallback", e)
        }
    }

    /**
     * 通过类名反射绑定 Logger（备用方式）
     *
     * @param className 全限定类名，如 "com.mamba.picme.core.common.Logger"
     */
    fun bindLoggerByClassName(className: String) {
        try {
            val clazz = Class.forName(className)
            val instanceField = clazz.getDeclaredField("INSTANCE")
            instanceField.isAccessible = true
            val instance = instanceField.get(null)
            bindLogger(instance)
        } catch (e: Exception) {
            Log.w(TAG_PREFIX + "BeautyLogProxy", "Failed to bind logger by class name: $className", e)
        }
    }

    /** 限流表：key -> 上次打印时间戳(ms) */
    private val throttleMap = ConcurrentHashMap<String, Long>(64)

    private inline fun invoke(method: Method?, vararg args: Any?) {
        if (method == null || loggerInstance == null) return
        try {
            method.invoke(loggerInstance, *args)
        } catch (_: Exception) {
            // 反射调用失败，静默忽略，fallback 已在调用方处理
        }
    }

    /**
     * 限流 Debug 日志：同一 key 最多每 [intervalMs] 毫秒打印一次。
     */
    fun dThrottled(tag: String, key: String, message: String, intervalMs: Long = 1_000L) {
        val now = System.currentTimeMillis()
        val last = throttleMap[key] ?: 0L
        if (now - last >= intervalMs) {
            throttleMap[key] = now
            d(tag, message)
        }
    }

    override fun d(tag: String, message: String) {
        if (isBound) {
            invoke(methodD, tag, message)
        } else {
            Log.d(TAG_PREFIX + tag, message)
        }
    }

    override fun i(tag: String, message: String) {
        if (isBound) {
            invoke(methodI, tag, message)
        } else {
            Log.i(TAG_PREFIX + tag, message)
        }
    }

    override fun w(tag: String, message: String) {
        if (isBound) {
            invoke(methodW, tag, message)
        } else {
            Log.w(TAG_PREFIX + tag, message)
        }
    }

    override fun w(tag: String, message: String, throwable: Throwable) {
        if (isBound) {
            invoke(methodWThrowable, tag, message, throwable)
        } else {
            Log.w(TAG_PREFIX + tag, message, throwable)
        }
    }

    override fun e(tag: String, message: String) {
        if (isBound) {
            // 尝试找 e(String, String) 方法，如果没有就用 e(String, String, Throwable) 传 null
            val method = try {
                loggerInstance?.javaClass?.getMethod("e", String::class.java, String::class.java)
            } catch (_: NoSuchMethodException) {
                null
            }
            if (method != null) {
                invoke(method, tag, message)
            } else {
                invoke(methodEThrowable, tag, message, null)
            }
        } else {
            Log.e(TAG_PREFIX + tag, message)
        }
    }

    override fun e(tag: String, message: String, throwable: Throwable) {
        if (isBound) {
            invoke(methodEThrowable, tag, message, throwable)
        } else {
            Log.e(TAG_PREFIX + tag, message, throwable)
        }
    }

    /**
     * 检查指定标签的日志是否允许输出
     *
     * @param tag 模块标签
     * @return 如果模块开关开启或无法判断则返回 true
     */
    fun isLogEnabled(tag: String): Boolean {
        return if (isBound && methodIsLogEnabled != null) {
            try {
                methodIsLogEnabled!!.invoke(loggerInstance, tag) as? Boolean ?: true
            } catch (_: Exception) {
                true
            }
        } else {
            true
        }
    }
}
