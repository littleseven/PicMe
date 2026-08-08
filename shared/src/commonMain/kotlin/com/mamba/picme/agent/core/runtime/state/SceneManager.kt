package com.mamba.picme.agent.core.runtime.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 场景管理器
 *
 * 负责跟踪当前活跃场景，管理场景切换时的上下文保存/恢复。
 * 纯 Kotlin 实现，无 Android 依赖。
 */
class SceneManager private constructor() {

    companion object {
        // KMP commonMain 无 synchronized，lazy 默认 SYNCHRONIZED 模式保证同款线程安全单例语义
        private val singleton: SceneManager by lazy { SceneManager() }

        fun getInstance(): SceneManager = singleton
    }

    enum class Scene {
        CHAT,
        CAMERA,
        GALLERY,
        SETTINGS,
        DEBUG,
        UNKNOWN
    }

    private val _currentScene = MutableStateFlow(Scene.UNKNOWN)
    val currentScene: StateFlow<Scene> = _currentScene.asStateFlow()

    private val sceneHistory = mutableListOf<Scene>()
    private val activeSceneRefs = mutableMapOf<Scene, Int>()

    fun transitionTo(scene: Scene, saveToHistory: Boolean = true) {
        if (saveToHistory && _currentScene.value != Scene.UNKNOWN) {
            sceneHistory.add(_currentScene.value)
        }
        activeSceneRefs[scene] = (activeSceneRefs[scene] ?: 0) + 1
        _currentScene.value = scene
    }

    fun leaveScene(scene: Scene) {
        val currentCount = activeSceneRefs[scene] ?: 0
        if (currentCount > 0) {
            activeSceneRefs[scene] = currentCount - 1
        }
        if ((activeSceneRefs[scene] ?: 0) == 0 && _currentScene.value == scene) {
            _currentScene.value = Scene.UNKNOWN
        }
    }

    fun navigateBack(): Boolean {
        return if (sceneHistory.isNotEmpty()) {
            val previous = sceneHistory.removeAt(sceneHistory.size - 1)
            _currentScene.value = previous
            true
        } else {
            false
        }
    }

    fun clearHistory() {
        sceneHistory.clear()
        activeSceneRefs.clear()
    }

    fun getCapabilitiesForScene(scene: Scene): List<String> = when (scene) {
        Scene.CHAT -> listOf("chat", "navigation")
        Scene.CAMERA -> listOf("camera", "navigation")
        Scene.GALLERY -> listOf("gallery", "navigation")
        Scene.SETTINGS -> listOf("settings", "navigation")
        Scene.DEBUG -> listOf("navigation")
        Scene.UNKNOWN -> listOf("navigation")
    }

    fun getSceneDescription(scene: Scene): String = when (scene) {
        Scene.CHAT -> "AI 对话页，从相册首页进入的二级页面，可以与本地/远程模型进行多轮对话"
        Scene.CAMERA -> "相机拍摄页面，可以拍照、录像、调节美颜参数"
        Scene.GALLERY -> "相册首页，应用默认入口，可以查看、删除、分享照片"
        Scene.SETTINGS -> "设置页面，可以调整应用配置"
        Scene.DEBUG -> "调试页面"
        Scene.UNKNOWN -> "未知页面"
    }

    fun isCapabilityAvailableInScene(capabilityName: String, scene: Scene): Boolean {
        return getCapabilitiesForScene(scene).contains(capabilityName)
    }
}
