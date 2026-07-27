package com.mamba.picme.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.mamba.picme.domain.repository.ChatImageStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ChatImageStoreImplTest {

    private lateinit var cacheDir: File
    private lateinit var context: Context
    private lateinit var dao: FakeChatImageCacheDao
    private lateinit var collectionUri: Uri

    @Before
    fun setUp() {
        cacheDir = File(System.getProperty("java.io.tmpdir"), "chat_edit_cache_test_${System.nanoTime()}")
        cacheDir.mkdirs()
        context = mockk(relaxed = true)
        every { context.filesDir } returns File(System.getProperty("java.io.tmpdir"))
        dao = FakeChatImageCacheDao()
        // 显式传入 collectionUri，避免构造器默认值 MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        // 在无 Robolectric 的 JVM 单测里触发 "not mocked"
        collectionUri = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
        cacheDir.deleteRecursively()
    }

    private fun store(
        maxBytes: Long = ChatImageStore.DEFAULT_MAX_SIZE_BYTES,
        sdkInt: Int = 21 // 预 Q：copyToGallery 走 outputCollectionUri 分支，避开 MediaStore.getContentUri 静态调用
    ) = ChatImageStoreImpl(
        context = context,
        dao = dao,
        cacheDir = cacheDir,
        maxSizeBytes = maxBytes,
        outputCollectionUri = collectionUri,
        sdkInt = sdkInt
    )

    private fun bitmap(size: Int = 100): Bitmap {
        val bmp = mockk<Bitmap>(relaxed = true)
        every { bmp.width } returns size
        every { bmp.height } returns size
        // 让 compress 真正写字节，便于断言文件存在与大小
        every { bmp.compress(any(), any(), any()) } answers {
            val out = thirdArg<java.io.OutputStream>()
            out.write(ByteArray(50))
            true
        }
        return bmp
    }

    @Test
    fun `writeResult writes file and ACTIVE row`() = runTest {
        val path = store().writeResult("default", bitmap(), "image/jpeg")
        assertTrue(path.startsWith("file://"))
        val abs = path.removePrefix("file://")
        assertTrue(File(abs).exists())
        val row = dao.getByPath(abs)
        assertEquals("default", row?.sessionId)
        assertEquals(ChatImageStore.Status.ACTIVE, row?.status)
    }

    @Test
    fun `enforceCap evicts oldest ACTIVE by lastAccessedAt until under cap`() = runTest {
        // 3×50=150 > 120，淘汰最旧 a 后剩 100 ≤ 120 即停（恰好淘汰一张）
        val store = store(maxBytes = 120)
        val a = absPath("a"); val b = absPath("b"); val c = absPath("c")
        seed(a, lastAccessedAt = 100)
        seed(b, lastAccessedAt = 200)
        seed(c, lastAccessedAt = 300)
        store.enforceCap()
        // 最旧的 a 应被 EVICTED 且文件删除；b/c 仍在
        assertEquals(ChatImageStore.Status.EVICTED, dao.getByPath(a)?.status)
        assertFalse(File(a).exists())
        assertEquals(ChatImageStore.Status.ACTIVE, dao.getByPath(b)?.status)
        assertTrue(File(b).exists())
        assertEquals(ChatImageStore.Status.ACTIVE, dao.getByPath(c)?.status)
        assertTrue(dao.sumSizeWhereActive() <= 120)
    }

    @Test
    fun `touch bumps lastAccessedAt so row survives longer`() = runTest {
        val store = store(maxBytes = 120)
        val a = absPath("a"); val b = absPath("b"); val c = absPath("c")
        seed(a, lastAccessedAt = 100)
        seed(b, lastAccessedAt = 200)
        seed(c, lastAccessedAt = 300)
        store.touch(a) // a 变最新
        store.enforceCap()
        // 现在 b 最旧，应被淘汰；a/c 保留
        assertEquals(ChatImageStore.Status.EVICTED, dao.getByPath(b)?.status)
        assertEquals(ChatImageStore.Status.ACTIVE, dao.getByPath(a)?.status)
        assertEquals(ChatImageStore.Status.ACTIVE, dao.getByPath(c)?.status)
    }

    @Test
    fun `enforceCap does not evict a single file larger than cap`() = runTest {
        val store = store(maxBytes = 10)
        val big = absPath("big")
        seed(big, lastAccessedAt = 100, size = 50)
        store.enforceCap()
        assertEquals(ChatImageStore.Status.ACTIVE, dao.getByPath(big)?.status)
        assertTrue(File(big).exists())
    }

    private fun absPath(name: String): String =
        File(cacheDir, "$name.jpg").apply { writeBytes(ByteArray(50)) }.absolutePath

    private suspend fun seed(path: String, lastAccessedAt: Long, size: Long = 50) {
        dao.upsert(
            com.mamba.picme.data.local.entity.ChatImageCacheEntity(
                filePath = path,
                sessionId = "default",
                createdAt = lastAccessedAt,
                lastAccessedAt = lastAccessedAt,
                sizeBytes = size,
                status = ChatImageStore.Status.ACTIVE
            )
        )
    }
}
