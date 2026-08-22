package com.mamba.picme.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import com.mamba.picme.domain.repository.ChatImageStore
import com.mamba.picme.features.editor.EditRecipe
import com.mamba.picme.features.editor.RecipeApplier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ChatEditProcessorTest {

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `process writes via ChatImageStore and returns file uri`() = runTest {
        mockkStatic(Uri::class)
        mockkStatic(BitmapFactory::class)

        val context = mockk<Context>(relaxed = true)
        val photoProcessor = mockk<PhotoProcessor>(relaxed = true)
        val faceDetector = mockk<FaceDetector>(relaxed = true)
        val store = mockk<ChatImageStore>(relaxed = true)
        val applier = mockk<RecipeApplier>(relaxed = true)
        val bitmap = mockk<Bitmap>(relaxed = true)

        every { Uri.parse(any<String>()) } returns mockk(relaxed = true)
        // 生产代码经 BitmapSampling.decodeStream 两遍解码（量界 + 真解），走 3 参重载
        every { BitmapFactory.decodeStream(any(), any(), any()) } returns bitmap
        every { context.contentResolver.openInputStream(any()) } returns ByteArrayInputStream(byteArrayOf())
        every { applier.applyCrop(any(), any()) } returns bitmap
        coEvery { applier.applyGpuEffects(any(), any(), any()) } returns bitmap
        coEvery { store.writeResult(any(), any(), any()) } returns "file:///cache/edit_x.jpg"

        val processor = ChatEditProcessor(
            photoProcessor, faceDetector, store,
            recipeApplierFactory = { _, _ -> applier }
        )
        val result = processor.execute(context, "file:///test.jpg", EditRecipe(sourceUri = "file:///test.jpg"), "default")

        assertTrue(result.isSuccess)
        assertEquals("file:///cache/edit_x.jpg", result.getOrNull())
        coVerify { store.writeResult("default", bitmap, "image/jpeg") }
    }
}
