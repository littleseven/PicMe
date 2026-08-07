package com.mamba.picme.domain.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 日志模块枚举
 *
 * 每个模块对应一个日志标签前缀，用于分类和控制日志输出。
 */
enum class LogModule(val tagPrefixes: List<String>, val displayName: String) {
    FACE_DETECTION(
        listOf("FaceDetector", "MediaPipe", "Mnn", "LandmarkAdapter"),
        "Face Detection"
    ),
    RENDERING(
        listOf("BeautyRenderer", "CameraPreview", "EGLCore", "FaceMakeupPass", "BeautyPass"),
        "Rendering"
    ),
    BEAUTY(
        listOf("ImageProc", "BeautyPreview", "BeautyRecorder", "Framebuffer", "FrameSync", "ModelManager"),
        "Beauty"
    ),
    AGENT(listOf("Agent"), "Agent"),
    CAMERA(listOf("Camera"), "Camera"),
    DOWNLOAD(listOf("Download"), "Download"),
    SETTINGS(listOf("Settings"), "Settings"),
    ORCHESTRATOR(listOf("Orchestrator"), "Orchestrator"),
    CHAT(
        listOf("ChatViewModel", "ChatScreen", "ChatThreadSidebar"),
        "Chat"
    ),
    SEMANTIC(
        listOf("MobileClip", "ClipTokenizer", "SemanticSearch"),
        "Semantic Search"
    );

    companion object {
        /**
         * 预构建的 TAG → 模块 映射表，避免每次日志调用时遍历枚举。
         * 使用 lazy 延迟初始化，确保枚举 entries 已就绪。
         */
        private val prefixToModule: Map<String, LogModule> by lazy {
            buildMap {
                LogModule.entries.forEach { module ->
                    module.tagPrefixes.forEach { prefix ->
                        put(prefix.lowercase(), module)
                    }
                }
            }
        }

        /**
         * 根据标签查找对应的日志模块。
         * 使用预构建映射表实现 O(1) 平均查找。
         * 当多个模块都能匹配时，选择最长前缀（最具体）的那个。
         */
        fun fromTag(tag: String): LogModule? {
            val lowerTag = tag.lowercase()
            var bestModule: LogModule? = null
            var bestPrefixLength = 0
            prefixToModule.forEach { (prefix, module) ->
                if (lowerTag.contains(prefix) && prefix.length > bestPrefixLength) {
                    bestModule = module
                    bestPrefixLength = prefix.length
                }
            }
            return bestModule
        }
    }
}

/**
 * 日志模块配置
 *
 * 管理各模块的日志开关状态。
 *
 * @property enabledModules 已启用的日志模块集合
 */
data class LogModuleConfig(
    val enabledModules: Set<LogModule> = emptySet()
) {
    /**
     * 检查指定模块是否启用日志
     */
    fun isEnabled(module: LogModule): Boolean = module in enabledModules

    /**
     * 检查指定标签是否允许输出日志
     */
    fun isTagEnabled(tag: String): Boolean {
        val module = LogModule.fromTag(tag)
        return if (module != null) {
            module in enabledModules
        } else {
            // 未分类的日志默认允许输出
            true
        }
    }

    /**
     * 切换模块日志开关
     */
    fun toggle(module: LogModule, enabled: Boolean): LogModuleConfig {
        val updated = enabledModules.toMutableSet()
        if (enabled) {
            updated.add(module)
        } else {
            updated.remove(module)
        }
        return copy(enabledModules = updated)
    }

    /**
     * 序列化为 JSON 字符串
     */
    fun toJson(): String {
        val json = JSONObject()
        val array = JSONArray()
        enabledModules.forEach { array.put(it.name) }
        json.put("enabledModules", array)
        return json.toString()
    }

    companion object {
        /**
         * 默认配置：Agent 和 Orchestrator 开启，Beauty 和 Camera 关闭
         */
        fun default(): LogModuleConfig = LogModuleConfig(
            enabledModules = setOf(
                LogModule.AGENT,
                LogModule.ORCHESTRATOR,
                LogModule.DOWNLOAD,
                LogModule.SETTINGS,
                LogModule.CHAT,
                LogModule.SEMANTIC
                // FACE_DETECTION 默认关闭，用户可在设置页手动开启
            )
        )

        /**
         * 从 JSON 字符串反序列化
         */
        fun fromJson(jsonString: String): LogModuleConfig {
            return try {
                val json = JSONObject(jsonString)
                val array = json.optJSONArray("enabledModules")
                val modules = mutableSetOf<LogModule>()
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val name = array.optString(i)
                        runCatching { LogModule.valueOf(name) }.getOrNull()?.let { modules.add(it) }
                    }
                }
                LogModuleConfig(modules)
            } catch (_: Exception) {
                default()
            }
        }
    }
}
