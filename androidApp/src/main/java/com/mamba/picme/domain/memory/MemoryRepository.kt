package com.mamba.picme.domain.memory

import com.mamba.picme.data.local.dao.MemoryFactDao
import com.mamba.picme.data.local.entity.MemoryFactEntity
import kotlinx.coroutines.flow.Flow

/**
 * 事实记忆来源（封闭枚举）—— 数据库存储枚举名
 */
enum class MemorySource {
    /** 聊天工具（"帮我记住…"经 LLM tool 调用） */
    CHAT_TOOL,

    /** JS 沙盒 capability.dispatch 写通路 */
    JS_DISPATCH;

    companion object {
        fun fromStored(stored: String): MemorySource? =
            values().firstOrNull { source -> source.name == stored }
    }
}

/**
 * 通用事实记忆仓库 —— "帮我记住…"声明的事实的唯一收口
 *
 * 聊天工具、JS 写通路、设置页管理界面都必须走这里，不直调 DAO。
 */
class MemoryRepository(
    private val memoryFactDao: MemoryFactDao
) {

    /** 按内容遗忘的唯一匹配结果（枚举所有分支，调用方穷举处理） */
    sealed interface ForgetByMatchResult {
        /** 恰好命中一条并已删除 */
        data class Deleted(val fact: MemoryFactEntity) : ForgetByMatchResult

        /** 没有命中 */
        data object NotFound : ForgetByMatchResult

        /** 命中多条，返回候选由用户/调用方选择（不删除） */
        data class MultipleCandidates(val candidates: List<MemoryFactEntity>) : ForgetByMatchResult
    }

    /** 记住一条事实，返回 factId */
    suspend fun rememberFact(
        content: String,
        category: String? = null,
        source: MemorySource
    ): Long {
        return memoryFactDao.insert(
            MemoryFactEntity(
                content = content,
                category = category,
                source = source.name
            )
        )
    }

    /** 更新事实内容/分类；返回是否命中（false = factId 不存在） */
    suspend fun updateFact(factId: Long, content: String, category: String? = null): Boolean {
        return memoryFactDao.update(factId = factId, content = content, category = category) > 0
    }

    /** 按 id 遗忘；返回是否命中（false = 幂等无操作） */
    suspend fun forgetFact(factId: Long): Boolean {
        return memoryFactDao.deleteById(factId) > 0
    }

    suspend fun clearAllFacts() {
        memoryFactDao.clearAll()
    }

    /** 按内容 LIKE 模糊检索（v1 召回方式，无 FTS） */
    suspend fun findFacts(query: String): List<MemoryFactEntity> {
        return memoryFactDao.findByContentLike(query)
    }

    /**
     * 按内容遗忘：恰好命中一条才删除；多条返回候选不删除，避免误伤。
     */
    suspend fun forgetByUniqueMatch(query: String): ForgetByMatchResult {
        val matches = memoryFactDao.findByContentLike(query)
        return when {
            matches.isEmpty() -> ForgetByMatchResult.NotFound
            matches.size > 1 -> ForgetByMatchResult.MultipleCandidates(matches)
            else -> {
                val target = matches.first()
                memoryFactDao.deleteById(target.factId)
                ForgetByMatchResult.Deleted(target)
            }
        }
    }

    /** 管理界面列表驱动源（Room 自动在表变更时重发） */
    fun observeAllFacts(): Flow<List<MemoryFactEntity>> = memoryFactDao.observeAll()
}
