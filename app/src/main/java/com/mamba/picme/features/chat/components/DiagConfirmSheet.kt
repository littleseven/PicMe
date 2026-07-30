package com.mamba.picme.features.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R

/**
 * 远程诊断根因确认弹窗：展示根因 + [推送修复分支]/[修复并开 PR]/[取消]。
 * 仿既有 WriteConfirmation 范式，由 ChatViewModel.diagController.pending 驱动。
 *
 * @param onPick "push"|"pr" 确认；null 取消
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagConfirmSheet(
    rootCause: String,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.diag_sheet_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Text(rootCause, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onPick("push") }) { Text(stringResource(R.string.diag_sheet_push)) }
                Button(onClick = { onPick("pr") }) { Text(stringResource(R.string.diag_sheet_pr)) }
                TextButton(onClick = { onPick(null) }) { Text(stringResource(R.string.diag_sheet_cancel)) }
            }
        }
    }
}
