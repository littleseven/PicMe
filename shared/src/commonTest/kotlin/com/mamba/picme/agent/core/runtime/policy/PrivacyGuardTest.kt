package com.mamba.picme.agent.core.runtime.policy

import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.model.config.AiAgentPrivacyLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PrivacyGuard 分级规则测试（Phase 4 Task 15 覆盖盲区补齐：纯 common 输入隐私分级）。
 *
 * 分级契约（见 [PrivacyGuard.classify] KDoc）：
 * - RESTRICTED：坐标模式（"数字,数字" 开头）或精确人脸数据关键词
 * - SENSITIVE：敏感关键词（"我的照片"/"OCR结果" 等）
 * - PUBLIC：其余
 */
class PrivacyGuardTest {

    private val guard = PrivacyGuard()

    @Test
    fun coordinatePatternIsRestricted() {
        assertEquals(PrivacyLevel.RESTRICTED, guard.classify("100,200"))
        assertEquals(PrivacyLevel.RESTRICTED, guard.classify("  100,200  ")) // trim 后命中

        // 锁定现行语义：COORDINATE_PATTERN 用 Regex.matches() 全串匹配，
        // 坐标前缀后接其他文本不命中（落 PUBLIC），此处固定该行为而非改逻辑。
        assertEquals(PrivacyLevel.PUBLIC, guard.classify("100,200 点击这里"))
    }

    @Test
    fun restrictedKeywordsAreRestricted() {
        assertEquals(PrivacyLevel.RESTRICTED, guard.classify("给我人脸关键点"))
        assertEquals(PrivacyLevel.RESTRICTED, guard.classify("bbox 是多少"))
    }

    @Test
    fun sensitiveKeywordsAreSensitive() {
        assertEquals(PrivacyLevel.SENSITIVE, guard.classify("找找我的照片"))
        assertEquals(PrivacyLevel.SENSITIVE, guard.classify("OCR结果是什么"))
    }

    @Test
    fun plainCommandIsPublic() {
        assertEquals(PrivacyLevel.PUBLIC, guard.classify("把美颜调到 50"))
        assertEquals(PrivacyLevel.PUBLIC, guard.classify(""))
    }

    @Test
    fun sensitiveDoesNotEscalateToRestricted() {
        // "人脸坐标" 只在 SENSITIVE 列表（RESTRICTED 是 "坐标" 关键词——注意 "人脸坐标" 同时含 "坐标"，
        // 按规则 RESTRICTED 优先命中）。此处锁定现行优先级：RESTRICTED 判定在 SENSITIVE 之前。
        assertEquals(PrivacyLevel.RESTRICTED, guard.classify("人脸坐标"))
        assertEquals(PrivacyLevel.SENSITIVE, guard.classify("人脸数据"))
    }

    @Test
    fun remoteAllowedOnlyWhenPermissiveAndRemote() {
        val g = PrivacyGuard(AiAgentPrivacyLevel.STRICT, AiAgentMode.REMOTE)
        assertFalse(g.isRemoteAllowed())

        g.updateConfig(AiAgentPrivacyLevel.PERMISSIVE, AiAgentMode.REMOTE)
        assertTrue(g.isRemoteAllowed())

        g.updateConfig(AiAgentPrivacyLevel.PERMISSIVE, AiAgentMode.OFF)
        assertFalse(g.isRemoteAllowed())
    }
}
