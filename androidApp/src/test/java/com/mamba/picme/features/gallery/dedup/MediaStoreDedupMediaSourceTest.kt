package com.mamba.picme.features.gallery.dedup

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.MatrixCursor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.domain.dedup.DedupContentType
import com.mamba.picme.domain.repository.AndroidMediaRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 回归测试：生产取数必须以 content uri 关联 MediaStore 元数据。
 *
 * `MediaAsset.id` 是 MediaRepositoryImpl 的 syntheticMediaId 负值编码，
 * 与 MediaStore `_ID` 不相等；按 id join 会全部 miss（扫描 0 项秒完）。
 */
@RunWith(RobolectricTestRunner::class)
// RELATIVE_PATH 需 API 29+：钉到 33 使截图目录识别分支生效（默认 SDK 低于 29）
@Config(sdk = [33])
class MediaStoreDedupMediaSourceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun fakeResolver(rows: List<Array<Any>>): ContentResolver {
        val resolver = mockk<ContentResolver>()
        every { resolver.query(any(), any(), null, null, null) } returns MatrixCursor(
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.RELATIVE_PATH,
            ),
        ).apply { rows.forEach { row -> addRow(row) } }
        return resolver
    }

    private fun mockContextWith(resolver: ContentResolver): Context {
        val mockContext = mockk<Context>()
        every { mockContext.contentResolver } returns resolver
        return mockContext
    }

    @Test
    fun `photoScanItems joins MediaStore meta by content uri, not synthetic asset id`() = runTest {
        val contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uriA = ContentUris.withAppendedId(contentUri, 101L).toString()
        val uriB = ContentUris.withAppendedId(contentUri, 202L).toString()
        val resolver = fakeResolver(
            listOf(
                arrayOf(101L, 2_000L, 1_700_000L, "image/jpeg", "DCIM/Camera/"),
                arrayOf(202L, 3_000L, 1_700_001L, "image/png", "DCIM/Camera/"),
            )
        )
        val photos = listOf(
            // syntheticMediaId 负值编码：与 MediaStore _ID 101/202 不相等
            MediaAsset(id = -1011L, uri = uriA, type = MediaType.PHOTO, captureDate = 1L, fileName = "a.jpg"),
            MediaAsset(id = -2021L, uri = uriB, type = MediaType.PHOTO, captureDate = 2L, fileName = "b.png"),
            MediaAsset(id = -9L, uri = "content://media/external/video/media/9", type = MediaType.VIDEO, captureDate = 3L, fileName = "v.mp4"),
        )
        val repository = mockk<AndroidMediaRepository>()
        every { repository.allMedia } returns flowOf(photos)

        val items = MediaStoreDedupMediaSource(mockContextWith(resolver), repository).photoScanItems()

        assertEquals(2, items.size) // 修复前为 0：id join 全 miss
        val byUri = items.associateBy { item -> item.uri }
        assertEquals(2_000L, byUri.getValue(uriA).sizeBytes)
        assertEquals(1_700_000_000L, byUri.getValue(uriA).modifiedAt)
        assertEquals("image/png", byUri.getValue(uriB).mime)
    }

    @Test
    fun `photoScanItems detects contentType from RELATIVE_PATH and TAG signals`() = runTest {
        val contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uriShot = ContentUris.withAppendedId(contentUri, 301L).toString()
        val uriDoc = ContentUris.withAppendedId(contentUri, 302L).toString()
        val uriPortrait = ContentUris.withAppendedId(contentUri, 303L).toString()
        val uriGeneral = ContentUris.withAppendedId(contentUri, 304L).toString()
        val resolver = fakeResolver(
            listOf(
                arrayOf(301L, 2_000L, 1_700_000L, "image/png", "DCIM/Screenshots/"),
                arrayOf(302L, 2_000L, 1_700_000L, "image/jpeg", "DCIM/Camera/"),
                arrayOf(303L, 2_000L, 1_700_000L, "image/jpeg", "DCIM/Camera/"),
                arrayOf(304L, 2_000L, 1_700_000L, "image/jpeg", "DCIM/Camera/"),
            )
        )
        val photos = listOf(
            MediaAsset(id = -301L, uri = uriShot, type = MediaType.PHOTO, captureDate = 1L, fileName = "s.png"),
            MediaAsset(
                id = -302L, uri = uriDoc, type = MediaType.PHOTO, captureDate = 2L, fileName = "d.jpg",
                ocrText = "字".repeat(DOCUMENT_OCR_CHAR_THRESHOLD + 1),
            ),
            MediaAsset(
                id = -303L, uri = uriPortrait, type = MediaType.PHOTO, captureDate = 3L, fileName = "p.jpg",
                hasFace = true, faceQualityScore = 0.8f,
            ),
            MediaAsset(id = -304L, uri = uriGeneral, type = MediaType.PHOTO, captureDate = 4L, fileName = "g.jpg"),
        )
        val repository = mockk<AndroidMediaRepository>()
        every { repository.allMedia } returns flowOf(photos)

        val items = MediaStoreDedupMediaSource(mockContextWith(resolver), repository).photoScanItems()

        val byUri = items.associateBy { item -> item.uri }
        assertEquals(DedupContentType.SCREENSHOT, byUri.getValue(uriShot).contentType)
        assertEquals(DedupContentType.DOCUMENT, byUri.getValue(uriDoc).contentType)
        assertEquals(DedupContentType.PORTRAIT, byUri.getValue(uriPortrait).contentType)
        assertEquals(0.8f, byUri.getValue(uriPortrait).faceQualityScore)
        assertEquals(DedupContentType.GENERAL, byUri.getValue(uriGeneral).contentType)
    }

    @Test
    fun `photoScanItems returns empty when repository has no photos`() = runTest {
        val repository = mockk<AndroidMediaRepository>()
        every { repository.allMedia } returns flowOf(emptyList())

        val items = MediaStoreDedupMediaSource(context, repository).photoScanItems()

        assertEquals(0, items.size)
    }
}
