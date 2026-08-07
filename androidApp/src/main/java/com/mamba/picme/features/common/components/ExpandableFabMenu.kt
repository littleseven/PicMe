package com.mamba.picme.features.common.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 可展开 FAB 菜单项
 *
 * @param icon 图标
 * @param label 文字标签
 * @param onClick 点击回调（组件内部会在展开状态下先折叠菜单再回调）
 */
data class ExpandableFabMenuItem(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit
)

/**
 * 可展开浮动按钮菜单
 *
 * 行为：
 * - 收起态：右下角一个 FAB，图标为 "+"。
 * - 展开态：全屏半透明遮罩，点击遮罩或再次点击 FAB 可收起；
 *   右下角纵向排列多个带文字标签的 mini-FAB。
 *
 * 用于首页（相册）聚合 Chat/Camera/Settings/ModelCenter 等二级入口，
 * 也用于替换 ChatScreen 中手写的 QuickActionPanel。
 */
@Composable
fun ExpandableFabMenu(
    items: List<ExpandableFabMenuItem>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    Box(modifier = modifier) {
        // 展开态：遮罩 + 菜单项
        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggleExpanded
                    )
                    .padding(end = 16.dp, bottom = 80.dp)
            ) {
                Column(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    items.forEach { item ->
                        ExpandableFabMenuRow(
                            item = item,
                            onClick = {
                                onToggleExpanded()
                                item.onClick()
                            }
                        )
                    }
                }
            }
        }

        // 触发 FAB（始终显示）
        FloatingActionButton(
            onClick = onToggleExpanded,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(52.dp),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun ExpandableFabMenuRow(
    item: ExpandableFabMenuItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = item.label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        FloatingActionButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
