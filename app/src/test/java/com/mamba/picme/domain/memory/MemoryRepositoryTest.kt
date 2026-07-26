package com.mamba.picme.domain.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.data.local.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [MemoryRepository] 单测（Robolectric + Room 内存库，真实 DAO）。
 *
 * 覆盖：remember/update/findFacts LIKE/forgetFact 幂等/
 * forgetByUniqueMatch 唯一删与多候选不删/clearAllFacts/observeAllFacts。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MemoryRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: MemoryRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MemoryRepository(db.memoryFactDao())
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `rememberFact persists content category and source`() = runTest {
        val factId = repository.rememberFact("小宝对花粉过敏", category = "健康", source = MemorySource.CHAT_TOOL)

        val fact = db.memoryFactDao().getById(factId)
        assertEquals("小宝对花粉过敏", fact?.content)
        assertEquals("健康", fact?.category)
        assertEquals(MemorySource.CHAT_TOOL.name, fact?.source)
    }

    @Test
    fun `findFacts matches by LIKE and updateFact changes content`() = runTest {
        val factId = repository.rememberFact("小宝对花粉过敏", source = MemorySource.CHAT_TOOL)
        repository.rememberFact("喜欢低饱和度滤镜", source = MemorySource.JS_DISPATCH)

        assertEquals(1, repository.findFacts("花粉").size)
        assertEquals("空串 LIKE 命中全部", 2, repository.findFacts("").size)
        assertTrue(repository.findFacts("不存在的关键词").isEmpty())

        assertTrue(repository.updateFact(factId, "小宝对花粉和猫毛过敏", "健康"))
        val updated = db.memoryFactDao().getById(factId)
        assertEquals("小宝对花粉和猫毛过敏", updated?.content)
        assertEquals("健康", updated?.category)
        assertEquals(1, repository.findFacts("猫毛").size)

        assertFalse("不存在的 factId 更新返回 false", repository.updateFact(999L, "x"))
    }

    @Test
    fun `forgetFact deletes by id and is idempotent`() = runTest {
        val factId = repository.rememberFact("待删除", source = MemorySource.CHAT_TOOL)

        assertTrue(repository.forgetFact(factId))
        assertFalse("重复删除幂等", repository.forgetFact(factId))
        assertNull(db.memoryFactDao().getById(factId))
    }

    @Test
    fun `forgetByUniqueMatch deletes exactly one and keeps multiple candidates`() = runTest {
        repository.rememberFact("小宝对花粉过敏", source = MemorySource.CHAT_TOOL)
        repository.rememberFact("小宝喜欢花粉味的饼干", source = MemorySource.CHAT_TOOL)
        repository.rememberFact("喜欢低饱和度滤镜", source = MemorySource.CHAT_TOOL)

        // 多条命中：不删，返回候选
        val multiple = repository.forgetByUniqueMatch("花粉")
        assertTrue(multiple is MemoryRepository.ForgetByMatchResult.MultipleCandidates)
        assertEquals(2, (multiple as MemoryRepository.ForgetByMatchResult.MultipleCandidates).candidates.size)
        assertEquals("多候选不删", 3, repository.findFacts("").size)

        // 唯一命中：删除
        val deleted = repository.forgetByUniqueMatch("滤镜")
        assertTrue(deleted is MemoryRepository.ForgetByMatchResult.Deleted)
        assertEquals(2, repository.findFacts("").size)

        // 零命中
        assertTrue(repository.forgetByUniqueMatch("不存在") is MemoryRepository.ForgetByMatchResult.NotFound)
    }

    @Test
    fun `clearAllFacts empties table and observeAllFacts emits changes`() = runTest {
        repository.rememberFact("事实一", source = MemorySource.CHAT_TOOL)
        repository.rememberFact("事实二", source = MemorySource.CHAT_TOOL)

        assertEquals(2, repository.observeAllFacts().first().size)

        repository.clearAllFacts()
        assertTrue(repository.observeAllFacts().first().isEmpty())
    }
}
