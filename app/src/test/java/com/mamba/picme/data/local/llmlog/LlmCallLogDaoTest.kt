package com.mamba.picme.data.local.llmlog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LlmCallLogDaoTest {

    private lateinit var db: LlmLogDatabase
    private lateinit var dao: LlmCallLogDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LlmLogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.llmCallLogDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun entity(createdAt: Long, success: Boolean = true) = LlmCallLogEntity(
        createdAt = createdAt,
        source = "react",
        model = "m",
        success = success,
        latencyMs = createdAt,
        promptTokens = 1,
        completionTokens = 2,
        totalTokens = 3,
        requestJson = "{}",
        responseJson = "{}",
        errorMessage = if (success) null else "err"
    )

    @Test
    fun `recent returns rows newest first`() = runTest {
        repeat(5) { i -> dao.insert(entity(createdAt = i.toLong() + 1)) }
        val rows = dao.recent(10)
        assertEquals(5, rows.size)
        assertTrue(rows.first().id > rows.last().id)
    }

    @Test
    fun `prune keeps only the newest keep rows`() = runTest {
        repeat(10) { i -> dao.insert(entity(createdAt = i.toLong() + 1)) }
        val deleted = dao.prune(3)
        assertEquals(7, deleted)
        assertEquals(3, dao.count())
        // 保留的应为 id 最大的 3 条（8/9/10）
        assertTrue(dao.recent(10).all { it.id > 7 })
    }

    @Test
    fun `delete and clearAll remove rows`() = runTest {
        repeat(3) { i -> dao.insert(entity(createdAt = i.toLong() + 1)) }
        val oldest = dao.recent(10).last()
        dao.delete(oldest.id)
        assertEquals(2, dao.count())
        dao.clearAll()
        assertEquals(0, dao.count())
    }
}
