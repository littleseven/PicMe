package com.mamba.picme.features.gallery.dedup

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.MatrixCursor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.domain.repository.AndroidMediaRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 回归测试：生产取数必须以 content uri 关联 MediaStore 元数据。
 *
 * `MediaAsset.id` 是 MediaRepositoryImpl 的 syntheticMediaId 负值编码，
 * 与 MediaStore `_ID` 不相等；按 id join 会全部 miss（扫描 0 项秒完）。
 */
@RunWith(RobolectricTestRunner::class)
class MediaStoreDedupMediaSourceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `photoScanItems joins MediaStore meta by content uri, not synthetic asset id`() = runTest {
        val contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uriA = ContentUris.withAppendedId(contentUri, 101L).toString()
        val uriB = ContentUris.withAppendedId(contentUri, 202L).toString()
        val resolver = mockk<ContentResolver>()
        every { resolver.query(any(), any(), null, null, null) } returns MatrixCursor(
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.MIME_TYPE,
            ),
        ).apply {
            addRow(arrayOf<Any>(101L, 2_000L, 1_700_000L, "image/jpeg"))
            addRow(arrayOf<Any>(202L, 3_000L, 1_700_001L, "image/png"))
        }
        val mockContext = mockk<Context>()
        every { mockContext.contentResolver } returns resolver
        val photos = listOf(
            // syntheticMediaId 负值编码：与 MediaStore _ID 101/202 不相等
            MediaAsset(id = -1011L, uri = uriA, type = MediaType.PHOTO, captureDate = 1L, fileName = "a.jpg"),
            MediaAsset(id = -2021L, uri = uriB, type = MediaType.PHOTO, captureDate = 2L, fileName = "b.png"),
            MediaAsset(id = -9L, uri = "content://media/external/video/media/9", type = MediaType.VIDEO, captureDate = 3L, fileName = "v.mp4"),
        )
        val repository = mockk<AndroidMediaRepository>()
        every { repository.allMedia } returns flowOf(photos)

        val items = MediaStoreDedupMediaSource(mockContext, repository).photoScanItems()

        assertEquals(2, items.size) // 修复前为 0：id join 全 miss
        val byUri = items.associateBy { item -> item.uri }
        assertEquals(2_000L, byUri.getValue(uriA).sizeBytes)
        assertEquals(1_700_000_000L, byUri.getValue(uriA).modifiedAt)
        assertEquals("image/png", byUri.getValue(uriB).mime)
    }

    @Test
    fun `photoScanItems returns empty when repository has no photos`() = runTest {
        val repository = mockk<AndroidMediaRepository>()
        every { repository.allMedia } returns flowOf(emptyList())

        val items = MediaStoreDedupMediaSource(context, repository).photoScanItems()

        assertEquals(0, items.size)
    }
}
