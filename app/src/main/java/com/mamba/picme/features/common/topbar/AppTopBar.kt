package com.mamba.picme.features.common.topbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.R

/** topbar 图标规格（chat 紧凑基准）：36dp 按钮 / 22dp 字形 / 8dp 同组间距 / 8dp 屏幕边距。 */
private val TopBarButtonSize = 36.dp
private val TopBarIconSize = 22.dp
private val TopBarSpacing = 8.dp

/** 统一顶栏左右屏幕边距（图标按钮到屏幕边缘的水平留白）。 */
private val TopBarHorizontalPadding = 8.dp

/** 统一顶栏高度，参考微信（~48dp）。 */
private val TopBarHeight = 48.dp

/** 统一顶栏标题字号，参考微信（17sp）。 */
private val TopBarTitleFontSize = 17.sp

/**
 * 槽位式主力 topbar（自建紧凑版，参考微信：48dp 高、17sp 标题、内置状态栏 + 刘海避让）。
 *
 * 不再使用 Material3 [androidx.compose.material3.TopAppBar]（其高度写死 64dp），
 * 改为自建 [Row]，保证所有核心页顶栏的高度 / 字号 / 状态栏与刘海避让一致。
 *
 * - 内置 [Modifier.statusBarsPadding] + [Modifier.displayCutoutPadding]，调用方无需再单独避让状态栏 / 刘海；
 *   状态栏避让作用于内部 [Row]，使状态栏区域仍由外层 surface 背景填充，视觉无缝；
 *   仅当顶栏外层已处理状态栏 insets 时，通过 [includeStatusBarPadding] = false 关闭状态栏避让；
 * - 标题统一 17sp / Medium，通过 [LocalTextStyle] 注入，调用方传普通 [Text] 即可继承；
 * - [centered] 为 true 时标题居中，否则左对齐。
 */
@Composable
fun AppTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    centered: Boolean = false,
    /** 是否内置状态栏避让（默认开启）；仅当顶栏外层已处理状态栏 insets 时关闭 */
    includeStatusBarPadding: Boolean = true
) {
    val titleStyle = MaterialTheme.typography.titleLarge.copy(
        fontSize = TopBarTitleFontSize,
        fontWeight = FontWeight.Medium
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (includeStatusBarPadding) Modifier.statusBarsPadding() else Modifier)
                .displayCutoutPadding()
                .height(TopBarHeight)
                .padding(horizontal = TopBarHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            navigationIcon()
            CompositionLocalProvider(LocalTextStyle provides titleStyle) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart
                ) {
                    title()
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(TopBarSpacing),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
    }
}

/** 便捷重载：文字标题 + 可选返回键 + 操作。 */
@Composable
fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    centered: Boolean = false,
    /** 是否内置状态栏避让（默认开启）；仅当顶栏外层已处理状态栏 insets 时关闭 */
    includeStatusBarPadding: Boolean = true
) {
    AppTopBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        modifier = modifier,
        navigationIcon = {
            if (onBack != null) {
                AppTopBarNavBack(onClick = onBack)
            }
        },
        actions = actions,
        centered = centered,
        includeStatusBarPadding = includeStatusBarPadding
    )
}

/** 标准操作图标 —— 一致性执行点。锁死 36dp 按钮 + 22dp 字形。 */
@Composable
fun AppTopBarAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color? = null,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(TopBarButtonSize)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint ?: LocalContentColor.current,
            modifier = Modifier.size(TopBarIconSize)
        )
    }
}

/** 标准返回键 —— 锁死 AutoMirrored.Rounded.ArrowBack + 36/22。 */
@Composable
fun AppTopBarNavBack(
    onClick: () -> Unit,
    contentDescription: String = stringResource(R.string.back)
) {
    IconButton(onClick = onClick, modifier = Modifier.size(TopBarButtonSize)) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = contentDescription,
            tint = LocalContentColor.current,
            modifier = Modifier.size(TopBarIconSize)
        )
    }
}
