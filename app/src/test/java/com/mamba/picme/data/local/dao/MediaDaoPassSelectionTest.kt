package com.mamba.picme.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.model.MediaEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 回归测试：Pass 1（人脸检测）/ Pass 3（图像打标）的选片与计数必须排除视频。
 *
 * 背景：人脸检测/图像打标都依赖 loadBitmap 解码图片，视频会被 MIME 拦截返回 null，
 * faceRoiResult 永远写不进去。若选片/计数 SQL 不过滤 type=PHOTO，视频会被永久计入
 * “待 Pass 1” → 计数器永不归零、增量扫描无限重选同一批视频（“永远扫不完”）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MediaDaoPassSelectionTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MediaDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.mediaDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    private suspend fun seed(): Pair<Long, Long> {
        // 一张缺人脸检测的照片 + 一个缺人脸检测的视频，二者 faceRoiResult 均为 NULL。
        val photoId = dao.insertMedia(
            MediaEntity(uri = "content://photo/1", type = MediaType.PHOTO, captureDate = 100L, fileName = "p1.jpg")
        )
        val videoId = dao.insertMedia(
            MediaEntity(uri = "content://video/1", type = MediaType.VIDEO, captureDate = 200L, fileName = "v1.mp4")
        )
        return photoId to videoId
    }

    @Test
    fun `getMediaWithoutFaceRoiCount excludes videos`() = runTest {
        seed()

        // 只有照片计入“待 Pass 1”，视频不计入。
        assertEquals(1, dao.getMediaWithoutFaceRoiCount())
    }

    @Test
    fun `getMediaWithoutFaceRoiIds excludes videos`() = runTest {
        val (photoId, _) = seed()
        assertEquals(listOf(photoId), dao.getMediaWithoutFaceRoiIds())
    }

    @Test
    fun `incremental scan projection excludes videos`() = runTest {
        val (photoId, _) = seed()

        val candidates = dao.getMediaForIncrementalScanNewestProjection(before = Long.MAX_VALUE, limit = 10)

        assertEquals(listOf(photoId), candidates.map { it.id })
    }

    @Test
    fun `decode-failure sentinel makes remaining-for-pass1 converge`() = runTest {
        val (photoId, _) = seed()

        // 模拟 executeFaceDetection 对解码失败的照片写哨兵（与 DECODE_FAILURE_ROI_JSON 同构）。
        dao.updateFaceRoiResult(photoId, """{"hasFace":false,"faceCount":0,"decodeError":true}""", false)

        // 写入哨兵后该照片不再“缺 faceRoiResult”，计数归零 —— 即死循环收敛。
        assertEquals(0, dao.getMediaWithoutFaceRoiCount())
        assertTrue(dao.getMediaWithoutFaceRoiIds().isEmpty())
    }

    @Test
    fun `video never counted even after photo is resolved`() = runTest {
        val (photoId, _) = seed()
        dao.updateFaceRoiResult(photoId, """{"hasFace":false,"faceCount":0}""", false)

        // 照片已处理，视频本就排除 → 计数 0（回归保证：不会因视频把计数卡住）。
        assertEquals(0, dao.getMediaWithoutFaceRoiCount())
    }
}
