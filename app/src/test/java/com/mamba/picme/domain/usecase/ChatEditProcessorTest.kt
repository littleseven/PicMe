package com.mamba.picme.domain.usecase

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import com.mamba.picme.domain.repository.MediaRepository
import com.mamba.picme.features.editor.EditRecipe
import com.mamba.picme.features.editor.RecipeApplier
import android.content.ContentValues
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ChatEditProcessorTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `process returns result uri when successful`() = runTest {
        mockkStatic(Uri::class)
        mockkStatic(BitmapFactory::class)
        mockkConstructor(ContentValues::class)

        val context = mockk<Context>(relaxed = true)
        val contentResolver = mockk<ContentResolver>(relaxed = true)
        val photoProcessor = mockk<PhotoProcessor>(relaxed = true)
        val faceDetector = mockk<FaceDetector>(relaxed = true)
        val mediaRepository = mockk<MediaRepository>(relaxed = true)
        val applier = mockk<RecipeApplier>(relaxed = true)

        val bitmap = mockk<Bitmap>(relaxed = true)
        val outputUri = mockk<Uri>(relaxed = true)

        every { Uri.parse(any<String>()) } returns mockk(relaxed = true)
        every { BitmapFactory.decodeStream(any()) } returns bitmap
        every { context.contentResolver } returns contentResolver
        every { contentResolver.openInputStream(any()) } returns ByteArrayInputStream(byteArrayOf())
        every { anyConstructed<ContentValues>().put(any<String>(), any<String>()) } just io.mockk.Runs
        every { contentResolver.insert(any(), any()) } returns outputUri
        every { contentResolver.openOutputStream(any()) } returns ByteArrayOutputStream()
        every { outputUri.toString() } returns "content://media/external/images/media/123"
        every { applier.applyCrop(any(), any()) } returns bitmap
        coEvery { applier.applyGpuEffects(any(), any(), any()) } returns bitmap

        val processor = ChatEditProcessor(
            photoProcessor,
            faceDetector,
            mediaRepository,
            outputCollectionUri = mockk(relaxed = true),
            sdkInt = 34,
            recipeApplierFactory = { _, _ -> applier }
        )
        val recipe = EditRecipe(sourceUri = "file:///test.jpg")
        val result = processor.execute(context, "file:///test.jpg", recipe)

        assertTrue(result.isSuccess)
        assertEquals("content://media/external/images/media/123", result.getOrNull())
        verify { applier.applyCrop(any(), any()) }
        coVerify { applier.applyGpuEffects(any(), any(), any()) }
        coVerify { mediaRepository.refreshMediaLibrary() }
    }
}
