package com.mamba.picme.features.gallery.dedup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.R
import com.mamba.picme.domain.dedup.DedupGroup
import com.mamba.picme.features.gallery.components.ZoomableImage
import java.text.DateFormat
import java.util.Date

private val KeepGreen = Color(0xFF4CAF50)

/**
 * 去重组全屏对比预览（覆盖层，晚于弹层组合以置顶）：组内成员横向翻页 + 双指缩放比较；
 * 底部展示当前页大小/日期/角色与保留状态，Results 态提供「保留这张」直达改选
 * （放大比较后立即决策，不必返回半屏再点），Scanning 态只读。
 */
@Suppress("LongMethod") // 待重构：顶栏/底栏信息面板抽子组件
@Composable
fun DedupGroupPreviewOverlay(
    group: DedupGroup,
    initialIndex: Int,
    editable: Boolean,
    onKeep: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val members = group.members
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, members.size - 1)
    ) { members.size }
    var currentPageZoomed by remember { mutableStateOf(false) }
    val currentMember = members[pagerState.currentPage]
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    // 预览层先于半屏消费返回键：back 关预览，再 back 才收半屏
    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 16.dp,
            userScrollEnabled = !currentPageZoomed
        ) { page ->
            ZoomableImage(
                uri = members[page].uri,
                onClick = {},
                onLongClick = {},
                onZoomStateChanged = { scale ->
                    if (page == pagerState.currentPage) {
                        currentPageZoomed = scale > 1.02f
                    }
                }
            )
        }

        // 顶部：返回 + 组内页码
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.close),
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${pagerState.currentPage + 1} / ${members.size}",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.weight(1f))
            // 与左侧返回钮对称占位，页码居中
            Spacer(modifier = Modifier.padding(48.dp))
        }

        // 底部：当前页 meta + 角色/保留 badge；editable 时「保留这张」直达改选
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RoleBadge(role = currentMember.role)
                if (currentMember.uri == group.keepUri) {
                    Text(
                        text = stringResource(R.string.dedup_keep_badge),
                        modifier = Modifier.background(KeepGreen, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = stringResource(
                        R.string.dedup_member_meta,
                        formatBytes(currentMember.sizeBytes),
                        dateFormat.format(Date(currentMember.captureDate))
                    ),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp
                )
            }
            if (editable) {
                Button(
                    onClick = { onKeep(currentMember.uri) },
                    modifier = Modifier.fillMaxWidth(0.62f)
                ) {
                    Text(stringResource(R.string.dedup_keep_this))
                }
            }
        }
    }
}
