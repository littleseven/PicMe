package com.mamba.picme.data.indexing.geo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.indexing.LocationIndexUpdater
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.dao.LocationDao
import com.mamba.picme.data.model.MediaEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocationIndexUpdaterTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: LocationDao
    private lateinit var updater: LocationIndexUpdater

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.locationDao()
        updater = LocationIndexUpdater(dao)
    }

    @After
    fun teardown() = db.close()

    @Test
    fun `updateIndex writes province city district from resolved`() = runTest {
        val mediaId = db.mediaDao().insertMedia(
            MediaEntity(uri = "content://x/1", type = MediaType.PHOTO, captureDate = 1L, fileName = "a.jpg")
        )
        updater.updateIndex(
            mediaId,
            ResolvedLocation(
                country = "中国",
                province = "广东省",
                city = "深圳市",
                district = "南山区",
                latitude = 22.53,
                longitude = 113.97
            )
        )
        val loc = dao.findByCoordinate(22.53, 113.97)
        assertNotNull(loc)
        assertEquals("广东省", loc!!.province)
        assertEquals("深圳市", loc.city)
        assertEquals("南山区", loc.district)
    }

    @Test
    fun `updateIndex with null resolved clears and does nothing`() = runTest {
        val mediaId = db.mediaDao().insertMedia(
            MediaEntity(uri = "content://x/2", type = MediaType.PHOTO, captureDate = 1L, fileName = "b.jpg")
        )
        updater.updateIndex(mediaId, null)
        assertEquals(0, dao.getAllLocations().size)
    }
}
