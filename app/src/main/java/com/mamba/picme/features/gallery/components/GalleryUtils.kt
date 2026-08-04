package com.mamba.picme.features.gallery.components

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.mamba.picme.R
import com.mamba.picme.domain.model.GroupTitleType
import com.mamba.picme.domain.model.GroupedMedia
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.model.MediaEntity

fun resolveGroupTitle(
    context: Context,
    group: GroupedMedia,
    personNameMap: Map<String, String>? = null
): String {
    return when (group.titleType) {
        GroupTitleType.NONE -> group.titleValue
        GroupTitleType.DATE -> group.titleValue
        GroupTitleType.WITH_FACES -> context.getString(R.string.with_faces)
        GroupTitleType.NO_FACES -> context.getString(R.string.no_faces)
        GroupTitleType.PERSON -> {
            val resolvedName = personNameMap?.get(group.titleValue) ?: "人物 ${group.titleValue}"
            context.getString(R.string.person_group, resolvedName)
        }
        GroupTitleType.LANDSCAPE -> context.getString(R.string.landscape)
        GroupTitleType.SWIMWEAR -> context.getString(R.string.swimwear)
        GroupTitleType.SEXY -> context.getString(R.string.sexy)
        GroupTitleType.LOCATION -> group.titleValue
        GroupTitleType.NO_LOCATION -> context.getString(R.string.group_no_location)
        GroupTitleType.SEARCH -> "搜索 ${group.titleValue}（${group.items.size} 张）"
    }
}

fun shareMediaAssets(context: Context, assets: List<MediaAsset>) {
    if (assets.isEmpty()) {
        return
    }

    val uris = assets.map { mediaAsset -> mediaAsset.uri.toUri() }
    val shareIntent = if (uris.size == 1) {
        val firstAsset = assets.first()
        Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uris.first())
            type = if (firstAsset.type == MediaType.VIDEO) "video/*" else "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    context.startActivity(Intent.createChooser(shareIntent, null))
}

/**
 * 将 DB 媒体实体映射为 UI/网格用的 [MediaAsset]。
 *
 * 必须完整传递 [MediaEntity.faceFocusY]：网格缩略图依赖它做「人脸感知」纵向对齐
 * （core.image.faceAwareVerticalAlignment），漏传会让对齐退化为居中裁剪，
 * 配合 ContentScale.Crop 即产生「砍头杀」。
 * 历史上人物聚类结果页（GalleryScreen 以 personId 过滤的 faceMedia 手写 map）曾因此漏传。
 */
internal fun MediaEntity.toMediaAsset(): MediaAsset = MediaAsset(
    id = id,
    uri = uri,
    type = type,
    captureDate = captureDate,
    fileName = fileName,
    duration = duration,
    hasFace = hasFace,
    faceId = faceId,
    source = source,
    labels = labels,
    ocrText = ocrText,
    latitude = latitude,
    longitude = longitude,
    locationName = locationName,
    city = city,
    indexedAt = indexedAt,
    faceFocusY = faceFocusY,
    aestheticScore = aestheticScore,
    faceQualityScore = faceQualityScore
)
