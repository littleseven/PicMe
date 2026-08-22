package com.mamba.picme.features.camera.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.R
import com.mamba.picme.agent.core.model.plan.StepResult

@Composable
fun PlanResultBubble(
    message: AgentMessage.PlanResult,
    modifier: Modifier = Modifier
) {
    val result = message.result
    val isSuccess = result.isSuccess
    val successCount = result.successCount
    val skippedCount = result.skippedCount
    val failedCount = result.failedCount
    val totalCount = result.stepResults.size

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.DarkGray.copy(alpha = 0.8f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val headerIcon = if (isSuccess) "✅" else "⚠️"
        val headerText = if (isSuccess) {
            stringResource(R.string.camera_plan_result_success, successCount, totalCount)
        } else {
            stringResource(R.string.camera_plan_result_partial, successCount, totalCount, failedCount)
        }

        Text(
            text = "$headerIcon $headerText",
            color = Color.White,
            fontSize = 14.sp
        )

        if (skippedCount > 0) {
            Text(
                text = stringResource(R.string.camera_plan_skipped_steps, skippedCount),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
        }

        if (failedCount > 0) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                result.stepResults.forEach { stepResult ->
                    if (stepResult is StepResult.Failed) {
                        Row {
                            Text("❌ ", fontSize = 12.sp)
                            Text(
                                text = stepResult.step.description.ifBlank {
                                    stringResource(R.string.camera_plan_step_fallback, stepResult.step.step)
                                },
                                color = Color(0xFFFFA000),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
