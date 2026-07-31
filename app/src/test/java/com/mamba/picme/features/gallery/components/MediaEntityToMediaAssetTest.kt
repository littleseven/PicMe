package com.mamba.picme.features.gallery.components

import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.model.MediaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 防回归：MediaEntity → MediaAsset 必须完整传递 faceFocusY。
 *
 * 网格缩略图用 faceFocusY 做「人脸感知」纵向对齐（faceAwareVerticalAlignment）；
 * 一旦漏传，对齐会退化为 Alignment.Center，配合 ContentScale.Crop 居中裁剪，
 * 人脸偏上方时头部被裁掉 —— 即「砍头杀」。
 *
 * 触发路径：人物页点封面 → GalleryScreen 以 personId 过滤 → faceMedia 手动 map。
 */
class MediaEntityToMediaAssetTest {
    private fun entity(
        id: Long = 1L,
        faceFocusY: Float? = null
    ) = MediaEntity(
        id = id,
        uri = "content://media/$id",
        type = MediaType.PHOTO,
        captureDate = 1_000L,
        fileName = "img_$id.jpg",
        faceFocusY = faceFocusY
    )

    @Test
    fun toMediaAsset_preservesFaceFocusY_whenPresent() {
        val asset = entity(id = 7L, faceFocusY = 0.25f).toMediaAsset()
        assertEquals(7L, asset.id)
        val focus = asset.faceFocusY
        assertNotNull(focus)
        assertEquals(0.25f, requireNotNull(focus), 0.0001f)
    }

    @Test
    fun toMediaAsset_keepsFaceFocusYNull_whenAbsent() {
        val asset = entity(faceFocusY = null).toMediaAsset()
        assertNull(asset.faceFocusY)
    }

    @Test
    fun toMediaAsset_mapsCoreFields() {
        val asset = entity(id = 42L).toMediaAsset()
        assertEquals("content://media/42", asset.uri)
        assertEquals(MediaType.PHOTO, asset.type)
        assertEquals(42L, asset.id)
    }
}
