package com.mamba.picme.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.model.MediaEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MediaDaoCityTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun teardown() = db.close()

    @Test
    fun `city column round-trips via updateIndexResult`() = runTest {
        val dao = db.mediaDao()
        val id = dao.insertMedia(
            MediaEntity(uri = "content://x/1", type = MediaType.PHOTO, captureDate = 1L, fileName = "a.jpg")
        )
        dao.updateIndexResult(
            mediaId = id, labels = null, ocrText = null, latitude = 22.5, longitude = 113.9,
            locationName = "广东省 深圳市", city = "深圳市", indexedAt = 1L
        )
        val reloaded = dao.getMediaByIds(listOf(id)).first()
        assertEquals("深圳市", reloaded.city)
    }

    @Test
    fun `city defaults to null for rows without it`() = runTest {
        val dao = db.mediaDao()
        val id = dao.insertMedia(
            MediaEntity(uri = "content://x/2", type = MediaType.PHOTO, captureDate = 1L, fileName = "b.jpg")
        )
        assertNull(dao.getMediaByIds(listOf(id)).first().city)
    }
}
