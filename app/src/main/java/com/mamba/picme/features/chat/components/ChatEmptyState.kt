package com.mamba.picme.features.chat.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mamba.picme.R

/**
 * 聊天空状态：中央 logo + 居中欢迎/能力 + 注册引导 + 底部搜索示例。
 * 取代曾经的「大白屏」。视觉参考豆包 / 元宝等主流 AI 对话 App 的空状态：
 * 一个居中的品牌焦点 + 简短文案，示例入口贴近底部输入栏。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatEmptyState(
    isGuestMode: Boolean,
    onExampleClick: (String) -> Unit,
    onRegisterClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))

        // 中央 logo：ic_launcher_foreground 是 vector drawable（painterResource 可加载），
        // 放在圆形品牌色背景上作为视觉焦点（不可用 mipmap adaptive icon——非 vector 会 crash）。
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.chat_empty_welcome),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = stringResource(R.string.chat_empty_capabilities),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (isGuestMode) {
            Spacer(Modifier.height(20.dp))
            GuestRegisterCard(onRegisterClick = onRegisterClick)
        }

        // 占满剩余空间，把「试试这些」推到底部贴近输入栏
        Spacer(Modifier.weight(1f))

        Text(
            text = stringResource(R.string.chat_empty_try_these),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        ) {
            stringArrayResource(R.array.chat_example_prompts).forEach { prompt ->
                ExampleChip(text = prompt, onClick = { onExampleClick(prompt) })
            }
        }
    }
}

@Composable
private fun ExampleChip(text: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(24.dp),
        onClick = onClick,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun GuestRegisterCard(onRegisterClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_guest_card_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = stringResource(R.string.chat_guest_card_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onRegisterClick,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Text(stringResource(R.string.chat_guest_card_button))
            }
        }
    }
}
