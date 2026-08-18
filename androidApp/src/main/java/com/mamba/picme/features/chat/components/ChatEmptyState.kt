package com.mamba.picme.features.chat.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Search
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.ChatBubbleTokens

/**
 * 聊天空状态（豆包范式，设计稿 chat/empty 定稿）：
 * 品牌渐变 Logo 块 + 渐变欢迎标题 + 能力副标题 + 底部两列彩色图标示例 chips。
 * 渐变色 = chatBubble/brandGradient{Start,End}（青玉 #0F766E→#5EA88F）。
 */
@Composable
fun ChatEmptyState(
    isGuestMode: Boolean,
    onExampleClick: (String) -> Unit,
    onRegisterClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val brandBrush = Brush.linearGradient(
        listOf(ChatBubbleTokens.brandGradientStart, ChatBubbleTokens.brandGradientEnd)
    )
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(60.dp))

        // 品牌渐变 Logo 块（72dp r22）：launcher 前景放大居中，渐变底透过 adaptive 透明区
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(brandBrush),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.requiredSize(96.dp),
            )
        }

        Spacer(Modifier.height(24.dp))

        // 渐变标题（26sp SemiBold，品牌渐变着色）
        Text(
            text = stringResource(R.string.chat_empty_welcome),
            style = TextStyle(
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                brush = brandBrush,
            ),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

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
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        val prompts = stringArrayResource(R.array.chat_example_prompts)
        ExampleChipGrid(prompts = prompts, onExampleClick = onExampleClick)
        Spacer(Modifier.height(12.dp))
    }
}

/** 示例 chips 布局（设计稿 ChipsGrid）：两列成对 + 单列尾行，行距 12 / 列距 14。 */
@Composable
private fun ExampleChipGrid(prompts: Array<String>, onExampleClick: (String) -> Unit) {
    val rows = prompts.toList().chunked(2)
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        rows.forEachIndexed { rowIndex, rowPrompts ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                rowPrompts.forEachIndexed { colIndex, prompt ->
                    val index = rowIndex * 2 + colIndex
                    val (icon, iconColor) = exampleChipAccent(index)
                    ExampleChip(
                        text = prompt,
                        icon = icon,
                        iconColor = iconColor,
                        onClick = { onExampleClick(prompt) },
                    )
                }
            }
        }
    }
}

/** 六彩功能图标（设计稿 chips 定稿配色）：日历蓝/相册青/人物粉/夜色紫/统计绿/搜索琥珀。 */
private fun exampleChipAccent(index: Int): Pair<ImageVector, Color> = when (index % 6) {
    0 -> Icons.Rounded.CalendarMonth to Color(0xFF6BA6FF)
    1 -> Icons.Rounded.Image to Color(0xFF22D3EE)
    2 -> Icons.Rounded.Person to Color(0xFFFF7EB0)
    3 -> Icons.Rounded.DarkMode to Color(0xFF9B8CFF)
    4 -> Icons.Rounded.QueryStats to Color(0xFF4ADE80)
    else -> Icons.Rounded.Search to Color(0xFFFFB020)
}

/** 单个示例 chip：surfaceContainerHigh 底 r22 高 44，彩色图标 16 + onSurface 文本。 */
@Composable
private fun ExampleChip(
    text: String,
    icon: ImageVector,
    iconColor: Color,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(22.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
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
