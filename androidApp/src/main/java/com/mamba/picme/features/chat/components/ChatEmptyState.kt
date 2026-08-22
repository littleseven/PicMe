package com.mamba.picme.features.chat.components

import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
 * 聊天空状态（豆包范式，设计稿 chat/empty-v2-guest 定稿，2026-08-22 改版）：
 * 圆角 Logo 块 + 渐变欢迎标题 + 能力副标题 + 底部**分组**示例 chips（找照片/问相册各 4 条）。
 * 渐变色 = chatBubble/brandGradient{Start,End}（青玉 #0F766E→#5EA88F）。
 * 页面横向 padding 20dp（spec §4）；chips 用 FlowRow 自适应换行。
 *
 * 注册引导不再以前置卡片占位：访客模式仅在副标题下显示一行小字链接；
 * 渐进引导由 ChatViewModel 的 20 条阈值 + quota 兜底触发（spec §4 guest_nudge）。
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(60.dp))

        // Logo 块（72dp r22）：App launcher 前景 1.75 倍放大居中，adaptive 透明边全部裁出框外
        // （不透明区宽 190/324，需 ≥1.71 才能铺满盒宽；spec §4 logo）
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
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.75f),
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

        // 访客轻量入口：一行小字链接，不抢占核心区域（设计稿 guestLink）
        if (isGuestMode) {
            TextButton(onClick = onRegisterClick) {
                Text(
                    text = stringResource(R.string.chat_guest_link),
                    fontSize = 13.sp,
                    color = ChatBubbleTokens.brandGradientStart,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // 可滚动容器内不能用 weight 弹性占位（无限高度），改用固定间距衔接示例分组
        Spacer(Modifier.height(24.dp))

        ExampleGroup(
            labelRes = R.string.chat_example_group_search,
            promptsRes = R.array.chat_example_prompts_search,
            accents = SEARCH_GROUP_ACCENTS,
            onExampleClick = onExampleClick,
        )
        Spacer(Modifier.height(16.dp))
        ExampleGroup(
            labelRes = R.string.chat_example_group_ask,
            promptsRes = R.array.chat_example_prompts_ask,
            accents = ASK_GROUP_ACCENTS,
            onExampleClick = onExampleClick,
        )
        Spacer(Modifier.height(12.dp))
    }
}

/** 找照片组图标配色：日历蓝 / 相册青 / 人物粉 / 夜色紫。 */
private val SEARCH_GROUP_ACCENTS = listOf(
    Icons.Rounded.CalendarMonth to Color(0xFF6BA6FF),
    Icons.Rounded.Image to Color(0xFF22D3EE),
    Icons.Rounded.Person to Color(0xFFFF7EB0),
    Icons.Rounded.DarkMode to Color(0xFF9B8CFF),
)

/** 问相册组图标配色：统计绿 / 搜索琥珀 / 相册青 / 人物粉。 */
private val ASK_GROUP_ACCENTS = listOf(
    Icons.Rounded.QueryStats to Color(0xFF4ADE80),
    Icons.Rounded.Search to Color(0xFFFFB020),
    Icons.Rounded.Image to Color(0xFF22D3EE),
    Icons.Rounded.Person to Color(0xFFFF7EB0),
)

/**
 * 单组示例：居中组小标题（13sp onSurfaceVariant）+ FlowRow 流式 chips（行距 12 / 列距 14）。
 * chip 随行宽不足自动落到下一行；文本强制单行，极端窄屏/大字体下省略号截断而不是折行撑高 chip。
 */
@Composable
private fun ExampleGroup(
    @StringRes labelRes: Int,
    @ArrayRes promptsRes: Int,
    accents: List<Pair<ImageVector, Color>>,
    onExampleClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(labelRes),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val prompts = stringArrayResource(promptsRes)
            prompts.forEachIndexed { index, prompt ->
                val (icon, iconColor) = accents[index % accents.size]
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

/** 单个示例 chip：surfaceContainerHigh 底 r22，彩色图标 16 + onSurface 单行文本（溢出省略号截断）。 */
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
            modifier = Modifier.padding(start = 12.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
