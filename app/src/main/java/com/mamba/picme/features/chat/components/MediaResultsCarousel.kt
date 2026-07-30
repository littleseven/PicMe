@file:OptIn(ExperimentalFoundationApi::class)

package com.mamba.picme.features.chat.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mamba.picme.R
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.agent.core.model.command.FeedbackAction
import com.mamba.picme.core.image.faceAwareVerticalAlignment
import com.mamba.picme.features.chat.MediaResultsUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 相册搜索结果横滑卡片 carousel，插入 chat 对话流。
 *
 * @param onCardClick 点击卡片主体，参数为在 assets 中的 index（用于 MediaPager initialIndex）
 * @param onViewAll 点击「查看全部」
 * @param onFeedback 用户点击 👍 / 👎 / 🔁 反馈按钮
 */
@Composable
fun MediaResultsCarousel(
    mediaResults: MediaResultsUi,
    onCardClick: (Int) -> Unit,
    onViewAll: () -> Unit = {},
    onFeedback: (mediaId: String, action: FeedbackAction) -> Unit = { _, _ -> }
) {
    val mr = mediaResults
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(
            text = if (mr.isRefinement) "细化：${mr.query}（${mr.totalCount} 张）"
                   else "找到 ${mr.totalCount} 张「${mr.query}」的照片",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        if (mr.assets.isEmpty()) {
            Text(
                text = "未找到「${mr.query}」的照片，换个词试试",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            return@Column
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(mr.assets, key = { _, asset -> asset.id }) { index, asset ->
                MediaCard(
                    asset = asset,
                    selectedAction = mr.feedbackState[asset.id.toString()],
                    onClick = { onCardClick(index) },
                    onFeedback = { action -> onFeedback(asset.id.toString(), action) },
                    modifier = Modifier
                )
            }
            if (mr.totalCount > mr.assets.size) {
                item {
                    ViewAllCard(onClick = onViewAll)
                }
            }
        }
    }
}

@Composable
private fun MediaCard(
    asset: MediaAsset,
    selectedAction: FeedbackAction?,
    onClick: () -> Unit,
    onFeedback: (FeedbackAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val dateText = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(asset.captureDate))
    }.getOrDefault("")

    Card(
        modifier = modifier.size(width = 120.dp, height = 150.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val imageAlignment = remember(asset.faceFocusY) {
                faceAwareVerticalAlignment(asset.faceFocusY)
            }
            AsyncImage(
                model = asset.uri,
                contentDescription = when (asset.type) {
                    MediaType.VIDEO -> stringResource(R.string.a11y_video_desc)
                    MediaType.DOCUMENT -> stringResource(R.string.media_type_document)
                    else -> stringResource(R.string.a11y_photo_desc)
                },
                contentScale = ContentScale.Crop,
                alignment = imageAlignment,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                FeedbackIconButton(
                    icon = Icons.Rounded.ThumbUp,
                    contentDescription = stringResource(R.string.feedback_like),
                    isSelected = selectedAction == FeedbackAction.LIKE,
                    onClick = { onFeedback(FeedbackAction.LIKE) }
                )
                FeedbackIconButton(
                    icon = Icons.Rounded.ThumbDown,
                    contentDescription = stringResource(R.string.feedback_dislike),
                    isSelected = selectedAction == FeedbackAction.DISLIKE,
                    onClick = { onFeedback(FeedbackAction.DISLIKE) }
                )
                FeedbackIconButton(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.feedback_more_like_this),
                    isSelected = false,
                    onClick = { onFeedback(FeedbackAction.MORE_LIKE_THIS) }
                )
            }

            Text(
                text = dateText,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp)
            )
        }
    }
}

@Composable
private fun FeedbackIconButton(
    icon: ImageVector,
    contentDescription: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.Black.copy(alpha = 0.4f)
    }

    Box(
        modifier = Modifier
            .size(24.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * 「查看全部」卡片：与 MediaCard 同尺寸，作为横滑末尾的入口瓦片，替代裸文本按钮。
 */
@Composable
private fun ViewAllCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.size(width = 120.dp, height = 150.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "查看全部",
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
