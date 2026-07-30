package com.mamba.picme.features.common.topbar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R

/** topbar 图标规格（chat 紧凑基准）：36dp 按钮 / 22dp 字形 / 8dp 同组间距。 */
private val TopBarButtonSize = 36.dp
private val TopBarIconSize = 22.dp
private val TopBarSpacing = 8.dp

/** 槽位式主力 topbar。centered=true 用 CenterAlignedTopAppBar，否则 TopAppBar。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    centered: Boolean = false,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors()
) {
    val wrappedActions: @Composable RowScope.() -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(TopBarSpacing), content = actions)
    }
    if (centered) {
        CenterAlignedTopAppBar(
            title = title,
            modifier = modifier,
            navigationIcon = { navigationIcon() },
            actions = wrappedActions,
            colors = colors
        )
    } else {
        TopAppBar(
            title = title,
            modifier = modifier,
            navigationIcon = { navigationIcon() },
            actions = wrappedActions,
            colors = colors
        )
    }
}

/** 便捷重载：文字标题 + 可选返回键 + 操作。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    centered: Boolean = false,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors()
) {
    AppTopBar(
        title = { Text(title) },
        modifier = modifier,
        navigationIcon = {
            if (onBack != null) {
                AppTopBarNavBack(onClick = onBack)
            }
        },
        actions = actions,
        centered = centered,
        colors = colors
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
