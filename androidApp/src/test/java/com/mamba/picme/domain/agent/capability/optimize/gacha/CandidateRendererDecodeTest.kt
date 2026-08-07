package com.mamba.picme.domain.agent.capability.optimize.gacha

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.domain.agent.capability.optimize.openImageInputStream
import com.mamba.picme.features.editor.RecipeApplier
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException

/**
 * decodeDownscaled 输入来源回归：chat 附件持久化为无 scheme 裸路径
 * （/data/user/0/.../picme_images/img_xxx.jpg），真机上
 * ContentResolver.openInputStream 抛 "No content provider" 导致抽卡整体降级单发。
 *
 * Robolectric 的 ShadowContentResolver 对裸路径过于宽容（伪造 Bitmap），
 * 无法复现真机行为，因此路由正确性由 openImageInputStream 的 mockk 测试保证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CandidateRendererDecodeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun newRenderer() = CandidateRenderer(context, mockk<RecipeApplier>(), faceData = null)

    private fun writeTempImage(): File {
        val file = File.createTempFile("gacha_decode_test", ".png")
        file.deleteOnExit()
        val bmp = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    @Test
    fun `decodeDownscaled supports bare file path without scheme`() {
        val file = writeTempImage()

        val out = newRenderer().decodeDownscaled(file.absolutePath)

        assertNotNull(out)
        assertEquals(128, out!!.width)
        assertEquals(64, out.height)
    }

    @Test
    fun `decodeDownscaled still supports file scheme uri`() {
        val file = writeTempImage()

        val out = newRenderer().decodeDownscaled("file://${file.absolutePath}")

        assertNotNull(out)
    }

    @Test
    fun `decodeDownscaled returns null for nonexistent bare path`() {
        assertNull(newRenderer().decodeDownscaled("/nonexistent/path/img_000.jpg"))
    }

    @Test
    fun `openImageInputStream opens bare path as file without touching contentResolver`() {
        val mockContext = mockk<Context>(relaxed = true)
        val file = writeTempImage()

        val stream = openImageInputStream(mockContext, file.absolutePath)

        assertNotNull(stream)
        stream!!.close()
        verify(exactly = 0) { mockContext.contentResolver }
    }

    @Test
    fun `openImageInputStream delegates content uri to contentResolver`() {
        val resolver = mockk<ContentResolver>()
        val payload = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        every { resolver.openInputStream(any()) } returns payload
        val mockContext = mockk<Context>()
        every { mockContext.contentResolver } returns resolver

        val stream = openImageInputStream(mockContext, "content://media/123")

        assertEquals(payload, stream)
        verify(exactly = 1) { resolver.openInputStream(any()) }
    }

    @Test(expected = FileNotFoundException::class)
    fun `openImageInputStream throws for nonexistent bare path`() {
        openImageInputStream(mockk(relaxed = true), "/nonexistent/path/img_000.jpg")
    }
}
