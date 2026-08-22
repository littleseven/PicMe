package com.mamba.picme.features.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.ChatBubbleTokens
import com.mamba.picme.features.common.auth.EmailCodeAuthForm

/**
 * 聊天页注册引导弹层（设计稿 chat/guest-nudge-sheet 定稿）：
 * 二选一结构——主按钮「邮箱注册 · 领免费额度」展开 [EmailCodeAuthForm]，
 * 次按钮「配置自己的 Token」跳设置页供应商配置。触发时机见 ChatViewModel 阈值/quota 逻辑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRegistrationSheet(
    guestMessageCount: Int,
    onDismiss: () -> Unit,
    onUseOwnKey: () -> Unit,
    sendCode: (String, (Result<Unit>) -> Unit) -> Unit,
    verifyCode: (String, String, (Result<*>) -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showAuthForm by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_guest_nudge_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.chat_guest_nudge_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (showAuthForm) {
                EmailCodeAuthForm(
                    sendCode = sendCode,
                    verifyCode = verifyCode,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                val brandBrush = Brush.linearGradient(
                    listOf(ChatBubbleTokens.brandGradientStart, ChatBubbleTokens.brandGradientEnd)
                )
                // 主按钮：品牌渐变底 + 白字（设计稿 btnRegister）
                Box(
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(brandBrush)
                        .clickable { showAuthForm = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.chat_guest_nudge_register),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
                // 次按钮：品牌色描边（设计稿 btnOwnKey）
                OutlinedButton(
                    onClick = onUseOwnKey,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(22.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.5.dp,
                        SolidColor(ChatBubbleTokens.brandGradientStart),
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.chat_guest_nudge_own_key),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ChatBubbleTokens.brandGradientStart,
                    )
                }
            }
            Text(
                text = stringResource(R.string.chat_guest_nudge_note, guestMessageCount),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )
        }
    }
}
