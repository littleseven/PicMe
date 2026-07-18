package com.mamba.picme.testing.agent.bridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp




import com.mamba.picme.testing.agent.device.DeviceTestController
import com.mamba.picme.testing.agent.runner.AgentTestRunner

/**
 * Agent 测试可视化界面
 *
 * 在设备上直接展示测试执行状态和结果，便于现场调试。
 * 可通过 adb 启动：
 * ```
 * adb shell am start -n com.mamba.picme/.testing.agent.bridge.AgentTestActivity
 * ```
 */
class AgentTestActivity : ComponentActivity() {

    private lateinit var runner: AgentTestRunner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        runner = AgentTestRunner(this)
        val controller = DeviceTestController(this)

        setContent {
            MaterialTheme {
                AgentTestScreen(runner, controller)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runner.release()
    }
}

@Composable
private fun AgentTestScreen(runner: AgentTestRunner, controller: DeviceTestController) {
    val state by runner.state.collectAsState()
    val progress by runner.progress.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "PoLang Agent 测试控制台",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 状态显示
        StatusCard(state)

        Spacer(modifier = Modifier.height(16.dp))

        // 进度条
        if (progress.totalCases > 0) {
            LinearProgressIndicator(
                progress = { progress.currentIndex.toFloat() / progress.totalCases },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "进度: ${progress.currentIndex}/${progress.totalCases}",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 快捷操作按钮
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { runner.runCameraTests() },
                modifier = Modifier.weight(1f)
            ) {
                Text("相机测试")
            }
            Button(
                onClick = { runner.runBeautyTests() },
                modifier = Modifier.weight(1f)
            ) {
                Text("美颜测试")
            }
            Button(
                onClick = { runner.runP0Regression() },
                modifier = Modifier.weight(1f)
            ) {
                Text("P0 回归")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 已完成用例列表
        LazyColumn {
            items(progress.completedCases) { item ->
                CaseResultItem(item.caseId, item.caseName, item.status.name)
            }
        }
    }
}

@Composable
private fun StatusCard(state: AgentTestRunner.RunnerState) {
    val (title, color) = when (state) {
        is AgentTestRunner.RunnerState.Idle -> "就绪" to Color.Gray
        is AgentTestRunner.RunnerState.Running -> "运行中: ${(state as AgentTestRunner.RunnerState.Running).caseName}" to Color.Blue
        is AgentTestRunner.RunnerState.RunningSuite -> "运行套件: ${(state as AgentTestRunner.RunnerState.RunningSuite).suiteName}" to Color.Blue
        is AgentTestRunner.RunnerState.Completed -> "完成" to Color.Green
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.1f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            color = color,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
private fun CaseResultItem(caseId: String, caseName: String, status: String) {
    val color = when (status) {
        "PASSED" -> Color.Green
        "FAILED" -> Color.Red
        else -> Color.Gray
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "●",
                color = color,
                modifier = Modifier.padding(end = 8.dp)
            )
            Column {
                Text(text = caseId, style = MaterialTheme.typography.bodyMedium)
                Text(text = caseName, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
