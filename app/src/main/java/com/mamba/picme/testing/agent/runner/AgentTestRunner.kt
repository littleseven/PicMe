package com.mamba.picme.testing.agent.runner

import android.content.Context
import com.mamba.picme.core.common.Logger
import com.mamba.picme.testing.agent.core.AgentTestCase


import com.mamba.picme.testing.agent.core.AgentTestEvent
import com.mamba.picme.testing.agent.core.AgentTestFramework
import com.mamba.picme.testing.agent.core.AgentTestResult
import com.mamba.picme.testing.agent.core.TestCategory
import com.mamba.picme.testing.agent.core.TestPriority
import com.mamba.picme.testing.agent.device.DeviceTestController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import com.mamba.picme.testing.agent.cases.BeautyAgentTestCases
import com.mamba.picme.testing.agent.cases.CameraAgentTestCases
import java.io.File

/**
 * Agent 测试运行器
 *
 * 负责：
 * 1. 管理测试用例生命周期
 * 2. 收集和报告测试结果
 * 3. 生成 JSON/Markdown 报告
 * 4. 与 AI Agent 通信（通过广播或文件）
 */
class AgentTestRunner(context: Context) {

    companion object {
        private const val TAG = "AgentTestRunner"
        private const val REPORT_DIR = "/sdcard/PoLang_Agent_Test/reports"
    }

    private val controller = DeviceTestController(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<RunnerState>(RunnerState.Idle)
    val state: StateFlow<RunnerState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(RunnerProgress(0, 0, emptyList()))
    val progress: StateFlow<RunnerProgress> = _progress.asStateFlow()

    private val results = mutableListOf<TestCaseResult>()
    private var currentSuite: String = ""

    init {
        scope.launch {
            AgentTestFramework.eventFlow.collect { event ->
                handleEvent(event)
            }
        }
    }

    // ============================================
    // 公开 API
    // ============================================

    /**
     * 运行单个测试用例
     */
    fun runCase(case: AgentTestCase<*>, onComplete: ((AgentTestResult<*>) -> Unit)? = null) {
        scope.launch {
            _state.value = RunnerState.Running(case.id, case.name)
            val result = AgentTestFramework.runTestCase(case)
            handleResult(case, result)
            onComplete?.invoke(result)
        }
    }

    /**
     * 运行测试套件
     */
    fun runSuite(
        name: String,
        cases: List<AgentTestCase<*>>,
        filter: TestFilter = TestFilter(),
        onComplete: ((SuiteReport) -> Unit)? = null
    ) {
        currentSuite = name
        results.clear()

        val filteredCases = cases.filter { case ->
            (filter.categories.isEmpty() || case.category in filter.categories) &&
                (filter.priorities.isEmpty() || case.priority in filter.priorities) &&
                (!filter.requiresDeviceOnly || case.requiresDevice)
        }

        scope.launch {
            _state.value = RunnerState.RunningSuite(name, filteredCases.size)
            _progress.value = RunnerProgress(0, filteredCases.size, emptyList())

            filteredCases.forEachIndexed { index, case ->
                _progress.value = _progress.value.copy(
                    currentIndex = index,
                    completedCases = results.map { it.toProgressItem() }
                )

                val result = AgentTestFramework.runTestCase(case)
                handleResult(case, result)
            }

            val report = generateSuiteReport(name)
            _state.value = RunnerState.Completed(report)
            onComplete?.invoke(report)
        }
    }

    /**
     * 运行相机模块全部测试
     */
    fun runCameraTests(onComplete: ((SuiteReport) -> Unit)? = null) {
        val cases = CameraAgentTestCases.allCases(controller)
        runSuite("Camera", cases, onComplete = onComplete)
    }

    /**
     * 运行美颜模块全部测试
     */
    fun runBeautyTests(onComplete: ((SuiteReport) -> Unit)? = null) {
        val cases = BeautyAgentTestCases.allCases(controller)
        runSuite("Beauty", cases, onComplete = onComplete)
    }

    /**
     * 运行 P0 回归测试
     */
    fun runP0Regression(onComplete: ((SuiteReport) -> Unit)? = null) {
        val allCases = mutableListOf<AgentTestCase<*>>()
        allCases.addAll(CameraAgentTestCases.allCases(controller))
        allCases.addAll(BeautyAgentTestCases.allCases(controller))

        runSuite(
            "P0-Regression",
            allCases,
            filter = TestFilter(priorities = setOf(TestPriority.P0)),
            onComplete = onComplete
        )
    }

    /**
     * 导出 JSON 报告到文件
     */
    fun exportJsonReport(): String {
        val report = if (_state.value is RunnerState.Completed) {
            (_state.value as RunnerState.Completed).report
        } else {
            generateSuiteReport(currentSuite)
        }

        val json = report.toJson()
        val filename = "agent_test_report_${System.currentTimeMillis()}.json"
        val path = "$REPORT_DIR/$filename"

        try {
            File(REPORT_DIR).mkdirs()
            File(path).writeText(json.toString(2))
            Logger.i(TAG, "Report exported: $path")
            return path
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to export report", e)
            return ""
        }
    }

    /**
     * 生成 Markdown 报告
     */
    fun generateMarkdownReport(): String {
        val report = if (_state.value is RunnerState.Completed) {
            (_state.value as RunnerState.Completed).report
        } else {
            generateSuiteReport(currentSuite)
        }

        return buildString {
            appendLine("# PoLang Agent 测试报告")
            appendLine()
            appendLine("**套件**: ${report.suiteName}")
            appendLine("**时间**: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(report.timestamp))}")
            appendLine("**总计**: ${report.totalCases} | **通过**: ${report.passedCount} | **失败**: ${report.failedCount}")
            appendLine()
            appendLine("---")
            appendLine()

            report.caseResults.forEach { caseResult ->
                val icon = when (caseResult.status) {
                    TestStatus.PASSED -> "✅"
                    TestStatus.FAILED -> "❌"
                    TestStatus.SKIPPED -> "⏭️"
                }
                appendLine("## $icon ${caseResult.caseId}: ${caseResult.caseName}")
                appendLine()
                appendLine("- **分类**: ${caseResult.category}")
                appendLine("- **优先级**: ${caseResult.priority}")
                appendLine("- **耗时**: ${caseResult.durationMs}ms")

                if (caseResult.status == TestStatus.FAILED) {
                    appendLine("- **失败步骤**: #${caseResult.failedStep}")
                    appendLine("- **失败原因**: ${caseResult.failureReason}")
                    appendLine()
                    appendLine("### 上下文快照")
                    appendLine("```json")
                    appendLine(caseResult.contextSnapshot)
                    appendLine("```")
                }

                if (caseResult.screenshots.isNotEmpty()) {
                    appendLine()
                    appendLine("### 截屏")
                    caseResult.screenshots.forEach { screenshot ->
                        appendLine("- `${screenshot}`")
                    }
                }

                appendLine()
                appendLine("---")
                appendLine()
            }

            appendLine("## 汇总")
            appendLine()
            appendLine("| 状态 | 数量 |")
            appendLine("|------|------|")
            appendLine("| ✅ 通过 | ${report.passedCount} |")
            appendLine("| ❌ 失败 | ${report.failedCount} |")
            appendLine("| ⏭️ 跳过 | ${report.skippedCount} |")
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        scope.cancel()
    }

    // ============================================
    // 私有方法
    // ============================================

    private fun handleEvent(event: AgentTestEvent) {
        when (event) {
            is AgentTestEvent.CaseStarted -> {
                Logger.i(TAG, "[START] ${event.caseId}: ${event.caseName}")
            }
            is AgentTestEvent.CasePassed -> {
                Logger.i(TAG, "[PASS] ${event.caseId}")
            }
            is AgentTestEvent.CaseFailed -> {
                Logger.e(TAG, "[FAIL] ${event.caseId}: ${event.result.reason}")
            }
            is AgentTestEvent.StepStarted -> {
                Logger.d(TAG, "  Step ${event.stepIndex}: ${event.description}")
            }
            is AgentTestEvent.StepPassed -> {
                Logger.d(TAG, "  Step ${event.stepIndex}: PASS")
            }
            is AgentTestEvent.StepFailed -> {
                Logger.e(TAG, "  Step ${event.stepIndex}: FAIL - ${event.reason}")
            }
        }
    }

    private fun handleResult(case: AgentTestCase<*>, result: AgentTestResult<*>) {
        val caseResult = when (result) {
            is AgentTestResult.Success -> TestCaseResult(
                caseId = case.id,
                caseName = case.name,
                category = case.category.name,
                priority = case.priority.name,
                status = TestStatus.PASSED,
                durationMs = System.currentTimeMillis(), // 简化处理
                contextSnapshot = result.context.toString(),
                screenshots = result.context.screenshots.map { it.path },
                failedStep = -1,
                failureReason = null
            )
            is AgentTestResult.Failure -> TestCaseResult(
                caseId = case.id,
                caseName = case.name,
                category = case.category.name,
                priority = case.priority.name,
                status = TestStatus.FAILED,
                durationMs = System.currentTimeMillis(),
                contextSnapshot = result.context.toString(),
                screenshots = result.context.screenshots.map { it.path },
                failedStep = result.failedStep,
                failureReason = result.reason
            )
        }
        results.add(caseResult)
    }

    private fun generateSuiteReport(name: String): SuiteReport {
        val passed = results.count { it.status == TestStatus.PASSED }
        val failed = results.count { it.status == TestStatus.FAILED }
        val skipped = results.count { it.status == TestStatus.SKIPPED }

        return SuiteReport(
            suiteName = name,
            timestamp = System.currentTimeMillis(),
            totalCases = results.size,
            passedCount = passed,
            failedCount = failed,
            skippedCount = skipped,
            caseResults = results.toList()
        )
    }

    // ============================================
    // 数据类定义
    // ============================================

    sealed class RunnerState {
        data object Idle : RunnerState()
        data class Running(val caseId: String, val caseName: String) : RunnerState()
        data class RunningSuite(val suiteName: String, val totalCases: Int) : RunnerState()
        data class Completed(val report: SuiteReport) : RunnerState()
    }

    data class RunnerProgress(
        val currentIndex: Int,
        val totalCases: Int,
        val completedCases: List<ProgressItem>
    )

    data class ProgressItem(
        val caseId: String,
        val caseName: String,
        val status: TestStatus
    )

    data class TestFilter(
        val categories: Set<TestCategory> = emptySet(),
        val priorities: Set<TestPriority> = emptySet(),
        val requiresDeviceOnly: Boolean = false
    )

    data class SuiteReport(
        val suiteName: String,
        val timestamp: Long,
        val totalCases: Int,
        val passedCount: Int,
        val failedCount: Int,
        val skippedCount: Int,
        val caseResults: List<TestCaseResult>
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("suiteName", suiteName)
                put("timestamp", timestamp)
                put("totalCases", totalCases)
                put("passedCount", passedCount)
                put("failedCount", failedCount)
                put("skippedCount", skippedCount)
                put("caseResults", JSONArray(caseResults.map { it.toJson() }))
            }
        }
    }

    data class TestCaseResult(
        val caseId: String,
        val caseName: String,
        val category: String,
        val priority: String,
        val status: TestStatus,
        val durationMs: Long,
        val contextSnapshot: String,
        val screenshots: List<String>,
        val failedStep: Int,
        val failureReason: String?
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("caseId", caseId)
                put("caseName", caseName)
                put("category", category)
                put("priority", priority)
                put("status", status.name)
                put("durationMs", durationMs)
                put("screenshots", JSONArray(screenshots))
                put("failedStep", failedStep)
                put("failureReason", failureReason ?: JSONObject.NULL)
            }
        }

        fun toProgressItem(): ProgressItem = ProgressItem(caseId, caseName, status)
    }

    enum class TestStatus { PASSED, FAILED, SKIPPED }
}
