package com.mamba.picme.features.camera.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.agent.core.model.plan.ExecutionPlan
import com.mamba.picme.agent.core.model.plan.PlanStep

@Composable
fun PlanPreviewBubble(
    message: AgentMessage.PlanPreview,
    modifier: Modifier = Modifier,
    onConfirm: (ExecutionPlan) -> Unit = {},
    onCancel: () -> Unit = {}
) {
    val plan = message.plan

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.DarkGray.copy(alpha = 0.8f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "📋 执行计划（${plan.steps.size}步）",
            color = Color.White,
            fontSize = 14.sp
        )

        if (plan.description.isNotBlank()) {
            Text(
                text = plan.description,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            plan.steps.forEach { step ->
                StepPreviewItem(step = step)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onConfirm(plan) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("开始执行", fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) {
                Text("取消", fontSize = 12.sp, color = Color.White)
            }
        }
    }
}

@Composable
private fun StepPreviewItem(
    step: PlanStep,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${step.step}.",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp
        )
        Text(
            text = step.description.ifBlank { step.action.toString() },
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}
