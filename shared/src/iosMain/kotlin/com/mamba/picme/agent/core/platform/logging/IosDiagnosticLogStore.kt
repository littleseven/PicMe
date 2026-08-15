@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.mamba.picme.agent.core.platform.logging

import com.mamba.picme.agent.core.inference.remote.log.LlmCallRecorder
import com.mamba.picme.agent.core.js.JsRunEvent
import com.mamba.picme.agent.core.js.JsRunRecorder
import com.mamba.picme.agent.core.js.JsRuntime
import com.mamba.picme.agent.core.remote.config.RemoteModelFactory
import com.mamba.picme.agent.core.runtime.capability.CommandExecutionRecorder
import com.mamba.picme.agent.core.runtime.capability.CommandExecutor
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSUserDefaults
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite

/**
 * iOS 诊断日志落盘与接线（开发者选项「诊断与日志」的数据层）。
 *
 * 对标 Android `PoLangApplication` 的 recorder 安装段（Room polang_llm_log.db 三表）：
 * iOS 等价物为 JSONL 文件——Application Support/llm_log/ 下
 * `llm_calls.jsonl` / `tool_calls.jsonl` / `js_runs.jsonl`，每行一条 JSON 记录，
 * 字段与 Android 实体一一对应（spec settings.yaml §6.6）。
 *
 * - 注入点与 Android 完全相同：[RemoteModelFactory.recorder] / [CommandExecutor.recorder] /
 *   [JsRuntime.recorder]（commonMain 产出记录，平台层只负责持久化）。
 * - captureContent：[debugBuild] 由 Swift `#if DEBUG` 传入——DEBUG 记全文，Release 仅纯指标
 *   （隐私红线，双端一致）。
 * - recorder 契约：fire-and-forget（后台串行落盘 + 异常自吞，绝不冒泡到 LLM/tool/JS 链路）。
 * - 容量：单文件超 [MAX_FILE_BYTES] 截断保留尾部 [KEEP_TAIL_BYTES]（按行对齐）。
 * - 文件 IO 走 POSIX（fopen/fwrite），规避 K/N Foundation cinterop 工厂方法签名坑。
 *
 * 查看端：Swift `DiagnosticLogView` 直接读本目录 JSONL（无需跨 KN 边界查询）。
 */
object IosDiagnosticLogStore {

    private const val TAG = "IosDiagnosticLogStore"
    private const val DIR_NAME = "llm_log"
    private const val LLM_FILE = "llm_calls.jsonl"
    private const val TOOL_FILE = "tool_calls.jsonl"
    private const val JS_FILE = "js_runs.jsonl"
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024
    private const val KEEP_TAIL_BYTES = 2L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    @Volatile
    private var installed = false

    /**
     * 安装三层 recorder + 模块门控 Logger（幂等）。由 [com.mamba.picme.agent.IosAgentComposition]
     * initialize 调用。
     */
    fun install(debugBuild: Boolean) {
        if (installed) return
        installed = true

        RemoteModelFactory.captureContent = debugBuild
        RemoteModelFactory.recorder = LlmCallRecorder { record ->
            enqueue(LLM_FILE) {
                buildJsonObject {
                    put("createdAt", record.createdAt)
                    put("source", record.source)
                    put("model", record.model)
                    put("success", record.success)
                    put("latencyMs", record.latencyMs)
                    put("promptTokens", record.promptTokens)
                    put("completionTokens", record.completionTokens)
                    put("totalTokens", record.totalTokens)
                    put("requestJson", record.requestJson)
                    put("responseJson", record.responseJson)
                    put("errorMessage", record.errorMessage)
                    put("traceId", record.traceId)
                }
            }
        }

        CommandExecutor.recorder = CommandExecutionRecorder {
                capability, commandType, latencyMs, success, errorCode, errorMessage, traceId ->
            enqueue(TOOL_FILE) {
                buildJsonObject {
                    put("createdAt", Clock.System.now().toEpochMilliseconds())
                    put("capability", capability)
                    put("commandType", commandType)
                    put("latencyMs", latencyMs)
                    put("success", success)
                    put("errorCode", errorCode)
                    put("errorMessage", errorMessage)
                    put("traceId", traceId)
                }
            }
        }

        JsRuntime.captureContent = debugBuild
        JsRuntime.recorder = object : JsRunRecorder {
            override fun record(event: JsRunEvent) {
                enqueue(JS_FILE) {
                    buildJsonObject {
                        put("createdAt", event.createdAt)
                        put("source", event.source)
                        put("kind", event.kind)
                        put("script", event.script)
                        put("scriptLength", event.scriptLength)
                        put("success", event.success)
                        put("errorCode", event.errorCode)
                        put("errorMessage", event.errorMessage)
                        put("resultPreview", event.resultPreview)
                        put("latencyMs", event.latencyMs)
                        put("traceId", event.traceId)
                    }
                }
            }
        }

        Logger.setDelegate(IosModuleGatedLogger())
        Logger.i(TAG, "diagnostic log store installed (captureContent=$debugBuild)")
    }

    /** 查看器（Swift）与本类共用的目录路径解析。 */
    fun logDirPath(): String {
        val base = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory, NSUserDomainMask, true
        ).firstOrNull() as? String ?: return DIR_NAME
        return "$base/$DIR_NAME"
    }

    private fun enqueue(fileName: String, build: () -> kotlinx.serialization.json.JsonObject) {
        scope.launch {
            runCatching {
                val line = build().toString()
                mutex.withLock { appendLine(fileName, line) }
            }.onFailure { println("[WARN] Agent:$TAG: append $fileName failed: ${it.message}") }
        }
    }

    private fun appendLine(fileName: String, line: String) {
        val fm = NSFileManager.defaultManager
        val dir = logDirPath()
        fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        val path = "$dir/$fileName"
        if (!fm.fileExistsAtPath(path)) {
            fm.createFileAtPath(path, contents = null, attributes = null)
        }
        val bytes = (line + "\n").encodeToByteArray()
        val fp = fopen(path, "a") ?: return
        try {
            bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.toULong(), fp) }
        } finally {
            fclose(fp)
        }
        trimIfNeeded(path)
    }

    private fun trimIfNeeded(path: String) {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) ?: return
        val size = (attrs[NSFileSize] as? NSNumber)?.longValue ?: return
        if (size <= MAX_FILE_BYTES) return
        val text = readFileText(path) ?: return
        // UTF-8 中文 3 字节/字符，保守按字符数截尾，保证不超 KEEP_TAIL_BYTES
        val keepChars = (KEEP_TAIL_BYTES / 3).toInt()
        if (text.length <= keepChars) return
        var tail = text.takeLast(keepChars)
        // 对齐到下一行边界，避免半行 JSON
        val nl = tail.indexOf('\n')
        if (nl >= 0) tail = tail.substring(nl + 1)
        val bytes = tail.encodeToByteArray()
        val fp = fopen(path, "w") ?: return
        try {
            bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.toULong(), fp) }
        } finally {
            fclose(fp)
        }
    }

    private fun readFileText(path: String): String? {
        val fp = fopen(path, "rb") ?: return null
        return try {
            fseek(fp, 0, SEEK_END)
            val size = ftell(fp)
            if (size <= 0) return null
            fseek(fp, 0, SEEK_SET)
            val buf = ByteArray(size.toInt())
            buf.usePinned { fread(it.addressOf(0), 1u, buf.size.toULong(), fp) }
            buf.decodeToString()
        } finally {
            fclose(fp)
        }
    }
}

/**
 * 模块门控 Logger（iOS 端 [Logger] delegate）。
 *
 * 语义对标 Android `LogModuleConfig`：UserDefaults `log_module_config`（JSON
 * `{"enabledModules":[...]}`，与 Android `toJson()` 同构）驱动；tag 前缀最长匹配定位模块，
 * 未分类 tag 默认放行；键缺失时用默认启用集（同 Android `LogModuleConfig.default()`）。
 * 只门控 shared Kotlin 侧日志（Swift 侧 print 不经此通道），输出走 println（Console.app 可见）。
 */
class IosModuleGatedLogger : Logger {

    private enum class Module(val prefixes: List<String>) {
        FACE_DETECTION(listOf("FaceDetector", "MediaPipe", "Mnn", "LandmarkAdapter")),
        RENDERING(listOf("BeautyRenderer", "CameraPreview", "EGLCore", "FaceMakeupPass", "BeautyPass")),
        BEAUTY(listOf("ImageProc", "BeautyPreview", "BeautyRecorder", "Framebuffer", "FrameSync", "ModelManager")),
        AGENT(listOf("Agent")),
        CAMERA(listOf("Camera")),
        DOWNLOAD(listOf("Download")),
        SETTINGS(listOf("Settings")),
        ORCHESTRATOR(listOf("Orchestrator")),
        CHAT(listOf("ChatViewModel", "ChatScreen", "ChatThreadSidebar")),
        SEMANTIC(listOf("MobileClip", "ClipTokenizer", "SemanticSearch")),
    }

    private companion object {
        const val PREFS_KEY = "log_module_config"
        val DEFAULT_ENABLED = setOf("AGENT", "ORCHESTRATOR", "DOWNLOAD", "SETTINGS", "CHAT", "SEMANTIC")
        // 预构建前缀 → 模块映射（小写），对齐 Android prefixToModule
        val PREFIX_TO_MODULE: Map<String, Module> = buildMap {
            Module.entries.forEach { module ->
                module.prefixes.forEach { prefix -> put(prefix.lowercase(), module) }
            }
        }
    }

    override fun isLogEnabled(tag: String): Boolean {
        val module = fromTag(tag) ?: return true // 未分类默认放行
        return module.name in enabledModules()
    }

    private fun enabledModules(): Set<String> {
        val raw = NSUserDefaults.standardUserDefaults.stringForKey(PREFS_KEY) ?: return DEFAULT_ENABLED
        return runCatching {
            val obj = Json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject
                ?: return DEFAULT_ENABLED
            (obj["enabledModules"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?.toSet()
                ?: DEFAULT_ENABLED
        }.getOrDefault(DEFAULT_ENABLED)
    }

    private fun fromTag(tag: String): Module? {
        val lower = tag.lowercase()
        var best: Module? = null
        var bestLen = 0
        PREFIX_TO_MODULE.forEach { (prefix, module) ->
            if (lower.contains(prefix) && prefix.length > bestLen) {
                best = module
                bestLen = prefix.length
            }
        }
        return best
    }

    override fun d(tag: String, message: String) = println("[DEBUG] Agent:$tag: $message")
    override fun i(tag: String, message: String) = println("[INFO] Agent:$tag: $message")
    override fun w(tag: String, message: String) = println("[WARN] Agent:$tag: $message")
    override fun w(tag: String, message: String, throwable: Throwable) =
        println("[WARN] Agent:$tag: $message (${throwable.message})")
    override fun e(tag: String, message: String, throwable: Throwable?) =
        println("[ERROR] Agent:$tag: $message${throwable?.let { " (${it.message})" } ?: ""}")
}
