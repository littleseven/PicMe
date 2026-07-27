package com.mamba.picme.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.mamba.picme.domain.repository.ChatImageStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ChatImageStoreImplTest {

    private lateinit var cacheDir: File
    private lateinit var context: Context
    private lateinit var dao: FakeChatImageCacheDao

    @Before
    fun setUp() {
        cacheDir = File(System.getProperty("java.io.tmpdir"), "chat_edit_cache_test_${System.nanoTime()}")
        cacheDir.mkdirs()
        context = mockk(relaxed = true)
        every { context.filesDir } returns File(System.getProperty("java.io.tmpdir")!!)
        dao = FakeChatImageCacheDao()
    }

    @After
    fun tearDown() {
        unmockkAll()
        cacheDir.deleteRecursively()
    }

    private fun store(
        maxBytes: Long = ChatImageStore.DEFAULT_MAX_SIZE_BYTES,
        galleryInserter: ((File, String) -> String?)? = null
    ) = ChatImageStoreImpl(
        context = context,
        dao = dao,
        cacheDir = cacheDir,
        maxSizeBytes = maxBytes,
        galleryInserter = galleryInserter
    )

    private fun bitmap(): Bitmap {
        val bmp = mockk<Bitmap>(relaxed = true)
        // 让 compress 真正写字节，便于断言文件存在
        every { bmp.compress(any(), any(), any()) } answers {
            thirdArg<java.io.OutputStream>().write(ByteArray(50))
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

    @Test
    fun `copyToGallery delegates to galleryInserter and returns content uri`() = runTest {
        val src = absPath("src")
        val result = store(galleryInserter = { _, _ -> "content://media/external/images/media/99" })
            .copyToGallery(src)
        assertEquals("content://media/external/images/media/99", result)
    }

    @Test
    fun `copyToGallery returns null when source missing`() = runTest {
        val result = store(galleryInserter = { _, _ -> "content://x" })
            .copyToGallery(File(cacheDir, "nope.jpg").absolutePath)
        assertNull(result)
    }

    @Test
    fun `markSaved deletes file and sets SAVED`() = runTest {
        val path = absPath("toSave")
        seed(path, lastAccessedAt = 100)
        assertEquals(ChatImageStore.Status.ACTIVE, dao.getByPath(path)?.status)
        store().markSaved(path)
        assertEquals(ChatImageStore.Status.SAVED, dao.getByPath(path)?.status)
        assertFalse(File(path).exists())
    }

    @Test
    fun `reconcileColdStart prunes ACTIVE rows whose file is missing`() = runTest {
        val store = store()
        val live = absPath("live")
        val gone = absPath("gone")
        seed(live, lastAccessedAt = 100)
        seed(gone, lastAccessedAt = 200)
        File(gone).delete() // 文件被外部删了
        store.reconcileColdStart()
        assertEquals(ChatImageStore.Status.ACTIVE, dao.getByPath(live)?.status)
        // 文件缺失的 ACTIVE 行：step1 标 EVICTED，step3 prune 终态行 → 行被清理
        assertNull(dao.getByPath(gone))
    }

    @Test
    fun `reconcileColdStart deletes orphan files not tracked by any row`() = runTest {
        val store = store()
        val tracked = absPath("tracked")
        seed(tracked, lastAccessedAt = 100)
        val orphan = File(cacheDir, "orphan.jpg").apply { writeBytes(ByteArray(10)) }
        assertTrue(orphan.exists())
        store.reconcileColdStart()
        assertFalse(orphan.exists())
        assertTrue(File(tracked).exists())
    }

    @Test
    fun `reconcileColdStart prunes terminal rows and deletes leftover SAVED files`() = runTest {
        val store = store()
        val savedLeftover = absPath("savedLeftover")
        seed(savedLeftover, lastAccessedAt = 100)
        dao.updateStatus(savedLeftover, ChatImageStore.Status.SAVED) // 行 SAVED 但文件还在
        store.reconcileColdStart()
        assertFalse(File(savedLeftover).exists())
        assertNull(dao.getByPath(savedLeftover)) // 终态行被 prune
    }

    @Test
    fun `evictForSession deletes ACTIVE files of the session`() = runTest {
        val store = store()
        val s1 = absPath("s1")
        val s2 = absPath("s2")
        dao.upsert(row(s1, "sessA", 1))
        dao.upsert(row(s2, "sessB", 1))
        store.evictForSession("sessA")
        assertEquals(ChatImageStore.Status.EVICTED, dao.getByPath(s1)?.status)
        assertFalse(File(s1).exists())
        assertEquals(ChatImageStore.Status.ACTIVE, dao.getByPath(s2)?.status)
    }

    private fun absPath(name: String): String =
        File(cacheDir, "$name.jpg").apply { writeBytes(ByteArray(50)) }.absolutePath

    private suspend fun seed(path: String, lastAccessedAt: Long, size: Long = 50) {
        dao.upsert(row(path, "default", lastAccessedAt, size))
    }

    private fun row(path: String, sessionId: String, lastAccessedAt: Long, size: Long = 50) =
        com.mamba.picme.data.local.entity.ChatImageCacheEntity(
            filePath = path,
            sessionId = sessionId,
            createdAt = lastAccessedAt,
            lastAccessedAt = lastAccessedAt,
            sizeBytes = size,
            status = ChatImageStore.Status.ACTIVE
        )
}
