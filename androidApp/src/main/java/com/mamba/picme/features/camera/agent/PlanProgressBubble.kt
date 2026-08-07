package com.mamba.picme.features.camera.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.agent.core.runtime.execution.ExecutionState
import com.mamba.picme.agent.core.model.plan.PlanStep

@Composable
fun PlanProgressBubble(
    message: AgentMessage.PlanProgress,
    modifier: Modifier = Modifier,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onCancel: () -> Unit = {}
) {
    val plan = message.plan
    val state = message.state

    val (completedSteps, totalSteps) = when (state) {
        is ExecutionState.Running -> state.completedSteps to state.totalSteps
        else -> 0 to plan.steps.size
    }

    val progress = if (totalSteps > 0) completedSteps.toFloat() / totalSteps else 0f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.DarkGray.copy(alpha = 0.8f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "🔄 执行中...（$completedSteps/$totalSteps）",
            color = Color.White,
            fontSize = 14.sp
        )

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF4CAF50),
            trackColor = Color.White.copy(alpha = 0.2f)
        )

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            plan.steps.forEachIndexed { index, step ->
                val stepStatus = getStepStatus(index, state)
                StepProgressItem(step = step, status = stepStatus)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (state) {
                is ExecutionState.Running -> {
                    OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                        Text("暂停", fontSize = 12.sp, color = Color.White)
                    }
                }
                is ExecutionState.Paused -> {
                    OutlinedButton(onClick = onResume, modifier = Modifier.weight(1f)) {
                        Text("继续", fontSize = 12.sp, color = Color.White)
                    }
                }
                else -> {}
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text("取消", fontSize = 12.sp, color = Color.White)
            }
        }
    }
}

@Composable
private fun StepProgressItem(
    step: PlanStep,
    status: StepStatus,
    modifier: Modifier = Modifier
) {
    val (icon, color) = when (status) {
        StepStatus.COMPLETED -> "✅" to Color(0xFF4CAF50)
        StepStatus.RUNNING -> "🔄" to Color(0xFFFFA000)
        StepStatus.PENDING -> "⏸" to Color.White.copy(alpha = 0.5f)
        StepStatus.SKIPPED -> "⏭" to Color.White.copy(alpha = 0.5f)
        StepStatus.FAILED -> "❌" to Color(0xFFE53935)
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = icon, fontSize = 12.sp)
        Text(
            text = step.description.ifBlank { step.action.toString() },
            color = color,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

private fun getStepStatus(stepIndex: Int, state: ExecutionState): StepStatus {
    return when (state) {
        is ExecutionState.Running -> {
            when {
                stepIndex < state.completedSteps -> StepStatus.COMPLETED
                stepIndex == state.completedSteps -> StepStatus.RUNNING
                else -> StepStatus.PENDING
            }
        }
        is ExecutionState.Completed -> StepStatus.COMPLETED
        is ExecutionState.Cancelled -> StepStatus.SKIPPED
        else -> StepStatus.PENDING
    }
}

enum class StepStatus {
    COMPLETED, RUNNING, PENDING, SKIPPED, FAILED
}
