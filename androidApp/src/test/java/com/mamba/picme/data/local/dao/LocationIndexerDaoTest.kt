package com.mamba.picme.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.model.MediaEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocationIndexerDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun teardown() = db.close()

    private suspend fun insert(name: String, locationName: String?): Long =
        db.mediaDao().insertMedia(
            MediaEntity(uri = "content://x/$name", type = MediaType.PHOTO, captureDate = 1L, fileName = name, locationName = locationName)
        )

    @Test
    fun `scan selector returns only locationName-null, excludes sentinel and named`() = runTest {
        val a = insert("a.jpg", null)       // 待扫描
        insert("b.jpg", "")                 // 哨兵(无 GPS 已处理)→ 排除
        insert("c.jpg", "深圳市")           // 已有地名 → 排除
        val pending = db.mediaDao().getMediaNeedingLocationScan(10)
        assertEquals(listOf(a), pending.map { it.id })
    }

    @Test
    fun `updateLocation writes fields and excludes from next scan`() = runTest {
        val dao = db.mediaDao()
        val id = insert("a.jpg", null)
        assertEquals(1, dao.getMediaNeedingLocationScan(10).size)
        dao.updateLocation(id, 22.54, 114.06, "广东省 深圳市 福田区", "深圳市")
        assertTrue(dao.getMediaNeedingLocationScan(10).isEmpty())
        val reloaded = dao.getMediaByIds(listOf(id)).first()
        assertEquals("深圳市", reloaded.city)
        assertEquals(22.54, reloaded.latitude!!, 0.0)
        assertEquals("广东省 深圳市 福田区", reloaded.locationName)
    }

    @Test
    fun `updateLocation sentinel clears pending without coords`() = runTest {
        val dao = db.mediaDao()
        val id = insert("a.jpg", null)
        dao.updateLocation(id, null, null, "", null)
        assertTrue(dao.getMediaNeedingLocationScan(10).isEmpty())
        val reloaded = dao.getMediaByIds(listOf(id)).first()
        assertNull(reloaded.latitude)
        assertEquals("", reloaded.locationName)
    }

    @Test
    fun `batch transaction writes all updates and excludes them from next scan`() = runTest {
        val dao = db.mediaDao()
        val ids = (1..5).map { insert("img$it.jpg", null) }
        // 模拟 LocationIndexer:一个事务内批量 updateLocation
        db.withTransaction {
            ids.forEach { id ->
                dao.updateLocation(id, 22.54, 114.06, "广东省 深圳市 福田区", "深圳市")
            }
        }
        // 全部应被排除出下一轮扫描
        assertTrue(dao.getMediaNeedingLocationScan(10).isEmpty())
        // 全部写入正确
        val reloaded = dao.getMediaByIds(ids)
        assertEquals(5, reloaded.size)
        reloaded.forEach { entity ->
            assertEquals("深圳市", entity.city)
            assertEquals(22.54, entity.latitude!!, 0.0)
            assertEquals("广东省 深圳市 福田区", entity.locationName)
        }
    }
}
