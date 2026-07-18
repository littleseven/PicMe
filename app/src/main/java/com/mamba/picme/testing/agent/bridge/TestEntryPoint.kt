package com.mamba.picme.testing.agent.bridge

import android.app.Activity
import android.os.Bundle
import com.mamba.picme.PoLangApplication
import com.mamba.picme.core.common.Logger
import com.mamba.picme.testing.agent.launcher.DataDrivenTestLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 测试入口点
 *
 * 从 MainActivity 外部独立管理测试生命周期，
 * 通过 Intent extras 接收测试参数，避免侵入主 Activity。
 */
class TestEntryPoint(private val activity: Activity) {

    companion object {
        private const val TAG = "TestEntryPoint"
        private const val KEY_TEST_PATH = "test_path"
        private const val KEY_TEST_MODE = "test_mode"
        private const val KEY_HAS_EXECUTED = "has_test_executed"

        /**
         * 从 Intent 中提取测试参数并创建入口点
         *
         * 支持传入相对路径（如 "camera"）或绝对路径（如 "/sdcard/.../tests/camera"），
         * 绝对路径会自动转换为相对路径。
         */
        fun fromIntent(activity: Activity, savedState: Bundle? = null): TestEntryPoint? {
            val testPath = activity.intent.getStringExtra(KEY_TEST_PATH)
            val testMode = activity.intent.getStringExtra(KEY_TEST_MODE)
            return if (testMode == "data" && testPath != null) {
                TestEntryPoint(activity).apply {
                    this.testPath = normalizeTestPath(testPath)
                    this.hasExecuted = savedState?.getBoolean(KEY_HAS_EXECUTED, false) ?: false
                }
            } else null
        }

        /**
         * 将绝对路径转换为相对路径（基于 TEST_BASE_DIR）
         */
        private fun normalizeTestPath(path: String): String {
            val baseDirMarker = "PoLang_Agent_Test/tests"
            return if (path.contains(baseDirMarker)) {
                path.substringAfter("$baseDirMarker/")
            } else {
                path
            }
        }
    }

    private var testPath: String? = null
    private var launcher: DataDrivenTestLauncher? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var hasExecuted = false
    private var testJob: kotlinx.coroutines.Job? = null

    /**
     * 保存状态到 Bundle（在 onSaveInstanceState 中调用）
     */
    fun saveState(outState: Bundle) {
        outState.putBoolean(KEY_HAS_EXECUTED, hasExecuted)
    }

    /**
     * 在应用主页面就绪后调用，启动测试执行
     */
    fun onAppReady() {
        if (hasExecuted || testPath == null) return
        hasExecuted = true

        val path = testPath!!
        Logger.i(TAG, "App ready, launching data-driven test: $path")

        // 使用应用级 Scope 避免 Activity 重建导致测试被取消
        val app = activity.application as PoLangApplication
        testJob = app.applicationScope.launch(Dispatchers.Main) {
            Logger.i(TAG, "Test coroutine started")
            try {
                delay(2000) // 等待页面初始化
                Logger.i(TAG, "Test coroutine delay completed")
                launcher = DataDrivenTestLauncher(activity)
                Logger.i(TAG, "DataDrivenTestLauncher created")
                launcher!!.launch(path) { success ->
                    Logger.i(TAG, "Test completed, success=$success")
                }
                Logger.i(TAG, "DataDrivenTestLauncher.launch() called")
            } catch (e: Exception) {
                Logger.e(TAG, "Test coroutine failed: ${e.message}", e)
            }
        }
    }

    /**
     * 在 Activity 销毁时释放资源
     */
    fun release() {
        launcher?.release()
        scope.cancel()
    }
}
