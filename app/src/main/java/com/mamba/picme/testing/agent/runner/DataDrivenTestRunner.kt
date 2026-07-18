package com.mamba.picme.testing.agent.runner

import android.content.Context
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.core.common.Logger
import com.mamba.picme.testing.agent.data.DataDrivenTestCase
import com.mamba.picme.testing.agent.data.DataDrivenTestResult
import com.mamba.picme.testing.agent.data.TestExecutionContext
import com.mamba.picme.testing.agent.engine.AgentStateProbe
import com.mamba.picme.testing.agent.engine.AgentTestEngine
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 数据驱动测试运行器
 *
 * 从外部存储动态加载 JSON 测试用例并执行，支持套件运行和报告生成。
 * JSON 文件通过 adb push 从电脑传到手机外部存储，不打包进 APK。
 */
class DataDrivenTestRunner(private val context: Context) {

    companion object {
        private const val TAG = "DataDrivenTestRunner"
        private const val TEST_BASE_DIR = "PoLang_Agent_Test/tests"
    }

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val registry = CapabilityRegistry.getInstance()
    private val probe = AgentStateProbe(registry)
    private val engine = AgentTestEngine(registry, probe, context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<RunnerState>(RunnerState.Idle)
    val state: StateFlow<RunnerState> = _state.asStateFlow()

    private val _progress = MutableStateFlow<RunnerProgress>(RunnerProgress(0, 0, emptyList()))
    val progress: StateFlow<RunnerProgress> = _progress.asStateFlow()

    private val baseDir: File = File(context.getExternalFilesDir(null), TEST_BASE_DIR)

    /**
     * 运行单个 JSON 测试用例
     */
    fun runCase(
        filePath: String,
        onComplete: ((DataDrivenTestResult) -> Unit)? = null
    ) {
        scope.launch {
            _state.value = RunnerState.Running(filePath)

            val result = try {
                val file = File(baseDir, filePath)
                if (!file.exists()) {
                    throw IllegalArgumentException("Test file not found: ${file.absolutePath}")
                }
                val json = file.readText()
                val case = moshi.adapter(DataDrivenTestCase::class.java).fromJson(json)
                    ?: throw IllegalArgumentException("Failed to parse test case: $filePath")

                Logger.i(TAG, "Running test case: ${case.caseId} - ${case.name}")
                engine.execute(case)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to run test case: $filePath", e)
                DataDrivenTestResult.Failure(
                    caseId = filePath,
                    failedStep = -1,
                    stepDescription = "load",
                    reason = e.message ?: "Unknown error",
                    context = TestExecutionContext(
                        caseId = filePath,
                        caseName = filePath
                    )
                )
            }

            _state.value = RunnerState.Completed(result)
            onComplete?.invoke(result)
        }
    }

    /**
     * 运行测试套件（指定目录下所有测试用例）
     */
    fun runSuite(
        suiteDir: String,
        onComplete: ((SuiteReport) -> Unit)? = null
    ) {
        scope.launch {
            val suiteDirFile = File(baseDir, suiteDir)
            val caseFiles = try {
                suiteDirFile.listFiles { f -> f.extension == "json" }?.toList() ?: emptyList()
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to list test cases in: $suiteDir", e)
                emptyList()
            }

            if (caseFiles.isEmpty()) {
                _state.value = RunnerState.Error("No test cases found in: ${suiteDirFile.absolutePath}")
                return@launch
            }

            val results = mutableListOf<DataDrivenTestResult>()
            _state.value = RunnerState.RunningSuite(suiteDir, caseFiles.size)
            _progress.value = RunnerProgress(0, caseFiles.size, emptyList())

            caseFiles.sortedBy { it.name }.forEachIndexed { index, file ->
                val relativePath = "$suiteDir/${file.name}"
                Logger.i(TAG, "[$index/${caseFiles.size}] Running: $relativePath")

                val result = try {
                    val json = file.readText()
                    val case = moshi.adapter(DataDrivenTestCase::class.java).fromJson(json)
                        ?: throw IllegalArgumentException("Failed to parse: $relativePath")

                    engine.execute(case)
                } catch (e: Exception) {
                    Logger.e(TAG, "Test case failed: $relativePath", e)
                    DataDrivenTestResult.Failure(
                        caseId = file.name,
                        failedStep = -1,
                        stepDescription = "load",
                        reason = e.message ?: "Unknown error",
                        context = TestExecutionContext(
                            caseId = file.name,
                            caseName = file.name
                        )
                    )
                }

                results.add(result)
                _progress.value = RunnerProgress(
                    currentIndex = index + 1,
                    totalCases = caseFiles.size,
                    completedCases = results.map { it.toProgressItem() }
                )
            }

            val report = SuiteReport(
                suiteName = suiteDir,
                totalCases = results.size,
                passedCount = results.count { it is DataDrivenTestResult.Success },
                failedCount = results.count { it is DataDrivenTestResult.Failure }
            )

            _state.value = RunnerState.SuiteCompleted(report)
            onComplete?.invoke(report)
        }
    }

    /**
     * 运行相机模块全部 P0 测试
     */
    fun runCameraP0Tests(onComplete: ((SuiteReport) -> Unit)? = null) {
        runSuite("camera", onComplete)
    }

    /**
     * 释放资源
     */
    fun release() {
        scope.cancel()
    }

    // 数据类定义

    sealed class RunnerState {
        data object Idle : RunnerState()
        data class Running(val caseId: String) : RunnerState()
        data class RunningSuite(val suiteName: String, val totalCases: Int) : RunnerState()
        data class Completed(val result: DataDrivenTestResult) : RunnerState()
        data class SuiteCompleted(val report: SuiteReport) : RunnerState()
        data class Error(val message: String) : RunnerState()
    }

    data class RunnerProgress(
        val currentIndex: Int,
        val totalCases: Int,
        val completedCases: List<ProgressItem>
    )

    data class ProgressItem(
        val caseId: String,
        val status: String,
        val durationMs: Long = 0
    )

    data class SuiteReport(
        val suiteName: String,
        val totalCases: Int,
        val passedCount: Int,
        val failedCount: Int
    ) {
        val passRate: Float
            get() = if (totalCases > 0) passedCount.toFloat() / totalCases else 0f
    }

    private fun DataDrivenTestResult.toProgressItem(): ProgressItem {
        return when (this) {
            is DataDrivenTestResult.Success -> ProgressItem(caseId, "PASSED", durationMs)
            is DataDrivenTestResult.Failure -> ProgressItem(caseId, "FAILED", 0)
        }
    }
}
