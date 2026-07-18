package com.mamba.picme.domain.usecase

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mamba.picme.agent.core.model.context.GallerySummary
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.model.MediaEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GetGallerySummaryUseCaseTest {

    private lateinit var db: AppDatabase
    private lateinit var useCase: GetGallerySummaryUseCase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        useCase = GetGallerySummaryUseCase(context, db)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun emptyGallery_returnsNoData(): Unit = runBlocking {
        val summary = useCase(includeDetails = true)
        assertEquals(0, summary?.totalMedia)
        assertEquals(0, summary?.unlabeledCount)
        assertEquals(GallerySummary.ScanRecommendation.NONE, summary?.recommendation)
    }

    @Test
    fun manyUnlabeled_recommendsPass3Full(): Unit = runBlocking {
        val now = System.currentTimeMillis()
        repeat(100) {
            db.mediaDao().insertMedia(
                MediaEntity(
                    id = 0,
                    uri = "file:///test/$it.jpg",
                    type = MediaType.PHOTO,
                    captureDate = now,
                    fileName = "$it.jpg",
                    faceRoiResult = "{}"
                )
            )
        }

        val summary = useCase(includeDetails = true)
        assertEquals(100, summary?.totalMedia)
        assertEquals(100, summary?.unlabeledCount)
        assertEquals(GallerySummary.ScanRecommendation.PASS3_FULL, summary?.recommendation)
    }

    @Test
    fun fewUnlabeled_recommendsIncremental(): Unit = runBlocking {
        val now = System.currentTimeMillis()
        repeat(90) {
            db.mediaDao().insertMedia(
                MediaEntity(
                    id = 0,
                    uri = "file:///test/labeled_$it.jpg",
                    type = MediaType.PHOTO,
                    captureDate = now,
                    fileName = "labeled_$it.jpg",
                    labels = "[\"户外\"]",
                    faceRoiResult = "{}"
                )
            )
        }
        repeat(10) {
            db.mediaDao().insertMedia(
                MediaEntity(
                    id = 0,
                    uri = "file:///test/unlabeled_$it.jpg",
                    type = MediaType.PHOTO,
                    captureDate = now,
                    fileName = "unlabeled_$it.jpg",
                    faceRoiResult = "{}"
                )
            )
        }

        val summary = useCase()
        assertEquals(100, summary?.totalMedia)
        assertEquals(10, summary?.unlabeledCount)
        assertEquals(GallerySummary.ScanRecommendation.INCREMENTAL, summary?.recommendation)
    }
}
