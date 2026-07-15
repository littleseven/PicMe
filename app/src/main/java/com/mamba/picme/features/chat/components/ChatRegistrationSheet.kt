package com.mamba.picme.features.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import com.mamba.picme.features.common.auth.EmailCodeAuthForm

/**
 * 聊天页注册引导弹层：复用 [EmailCodeAuthForm]，并提供「自配 API Key」「用本地模型」两条旁路。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRegistrationSheet(
    onDismiss: () -> Unit,
    onUseOwnKey: () -> Unit,
    onUseLocal: () -> Unit,
    sendCode: (String, (Result<Unit>) -> Unit) -> Unit,
    verifyCode: (String, String, (Result<*>) -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_register_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.chat_register_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EmailCodeAuthForm(sendCode = sendCode, verifyCode = verifyCode)
            HorizontalDivider()
            TextButton(onClick = onUseOwnKey) {
                Text(stringResource(R.string.chat_register_use_own_key))
            }
            TextButton(onClick = onUseLocal) {
                Text(stringResource(R.string.chat_register_use_local))
            }
        }
    }
}
