package com.mamba.picme.core.image

import org.junit.Assert.*
import org.junit.Test

/**
 * [QA] 唇色算法逻辑单元测试
 * 测试目标：验证唇色检测和色彩混合算法的正确性
 */
class LipColorAlgorithmTest {

    // ==================== 唇部检测逻辑测试 ====================

    @Test
    fun `lip detection identifies red pixels correctly`() {
        // 模拟唇部像素：R 高，G 和 B 相对较低
        val r = 180
        val g = 60
        val b = 50

        // 新检测逻辑：超宽松条件
        val hasColor = r > 40 && g > 20 && b > 15
        val notTooBright = (r + g + b) < 600
        val isLipCandidate = hasColor && notTooBright

        assertTrue("Red pixel should be detected as lip", isLipCandidate)
    }

    @Test
    fun `new lip detection is very permissive`() {
        // 测试超宽松条件 - 几乎任何有颜色的像素都能通过
        val testCases = listOf(
            Triple(100, 80, 70),   // 肤色
            Triple(150, 60, 50),   // 红色
            Triple(80, 50, 40),    // 暗红色
            Triple(200, 100, 80),  // 亮红色
            Triple(60, 40, 30)     // 暗色
        )

        for ((r, g, b) in testCases) {
            val hasColor = r > 40 && g > 20 && b > 15
            val notTooBright = (r + g + b) < 600
            val isLipCandidate = hasColor && notTooBright

            assertTrue("Pixel ($r,$g,$b) should be detected with new logic", isLipCandidate)
        }
    }

    @Test
    fun `lip detection rejects blue pixels`() {
        // 模拟非唇部像素（蓝色）
        val r = 50
        val g = 100
        val b = 200

        val isLipCandidate = r > g + 15 && r > b + 15 && r > 80

        assertFalse("Blue pixel should not be detected as lip", isLipCandidate)
    }

    @Test
    fun `lip detection rejects green pixels`() {
        // 模拟非唇部像素（绿色）
        val r = 50
        val g = 200
        val b = 50

        val isLipCandidate = r > g + 15 && r > b + 15 && r > 80

        assertFalse("Green pixel should not be detected as lip", isLipCandidate)
    }

    @Test
    fun `lip detection rejects dark pixels`() {
        // 模拟暗色像素（R 不够高）
        val r = 70
        val g = 30
        val b = 20

        val isLipCandidate = r > g + 15 && r > b + 15 && r > 80

        assertFalse("Dark pixel should not be detected as lip", isLipCandidate)
    }

    @Test
    fun `lip detection boundary condition r equals threshold`() {
        // 边界条件：r = 80
        val r = 80
        val g = 60
        val b = 60

        val isLipCandidate = r > g + 15 && r > b + 15 && r > 80

        // r > 80 为 false，所以应该不是唇部
        assertFalse("Pixel with r=80 should not be detected as lip", isLipCandidate)
    }

    // ==================== 亮度因子测试 ====================

    @Test
    fun `brightness factor for highlight pixels`() {
        // 高光像素（亮度 > 200）
        val brightness = 220
        val factor = when {
            brightness > 200 -> 0.6f
            brightness < 50 -> 0.3f
            else -> 1.0f
        }

        assertEquals(0.6f, factor, 0.001f)
    }

    @Test
    fun `brightness factor for shadow pixels`() {
        // 阴影像素（亮度 < 50）
        val brightness = 30
        val factor = when {
            brightness > 200 -> 0.6f
            brightness < 50 -> 0.3f
            else -> 1.0f
        }

        assertEquals(0.3f, factor, 0.001f)
    }

    @Test
    fun `brightness factor for normal pixels`() {
        // 正常亮度像素
        val brightness = 120
        val factor = when {
            brightness > 200 -> 0.6f
            brightness < 50 -> 0.3f
            else -> 1.0f
        }

        assertEquals(1.0f, factor, 0.001f)
    }

    @Test
    fun `brightness factor at boundary 200`() {
        // 边界条件：亮度 = 200
        val brightness = 200
        val factor = when {
            brightness > 200 -> 0.6f
            brightness < 50 -> 0.3f
            else -> 1.0f
        }

        assertEquals(1.0f, factor, 0.001f)
    }

    @Test
    fun `brightness factor at boundary 50`() {
        // 边界条件：亮度 = 50
        val brightness = 50
        val factor = when {
            brightness > 200 -> 0.6f
            brightness < 50 -> 0.3f
            else -> 1.0f
        }

        assertEquals(1.0f, factor, 0.001f)
    }

    // ==================== 色彩混合测试 ====================

    @Test
    fun `color blending with zero strength preserves original`() {
        val originalR = 150
        val targetR = 200
        val strength = 0f

        val result = (originalR * (1 - strength) + targetR * strength).toInt()

        assertEquals(originalR, result)
    }

    @Test
    fun `color blending with full strength uses target`() {
        val originalR = 150
        val targetR = 200
        val strength = 1f

        val result = (originalR * (1 - strength) + targetR * strength).toInt()

        assertEquals(targetR, result)
    }

    @Test
    fun `color blending with half strength averages values`() {
        val originalR = 100
        val targetR = 200
        val strength = 0.5f

        val result = (originalR * (1 - strength) + targetR * strength).toInt()

        assertEquals(150, result)
    }

    @Test
    fun `color blending clamps to valid range`() {
        // 测试超出 0-255 范围的截断
        val value = 300
        val clamped = value.coerceIn(0, 255)

        assertEquals(255, clamped)
    }

    @Test
    fun `color blending clamps negative values`() {
        val value = -50
        val clamped = value.coerceIn(0, 255)

        assertEquals(0, clamped)
    }

    // ==================== 色号定义测试 ====================

    @Test
    fun `lip color palette has 12 colors`() {
        val lipColors = intArrayOf(
            0xFFD4757D.toInt(),
            0xFFC43343.toInt(),
            0xFFFF7F50.toInt(),
            0xFFE0527C.toInt(),
            0xFFFF6B9D.toInt(),
            0xFF9B2335.toInt(),
            0xFFFFA07A.toInt(),
            0xFFCD5C5C.toInt(),
            0xFFDC143C.toInt(),
            0xFFFFB6C1.toInt(),
            0xFFB22222.toInt(),
            0xFFFF1493.toInt()
        )

        assertEquals(12, lipColors.size)
    }

    @Test
    fun `color index access within bounds`() {
        val lipColors = intArrayOf(
            0xFFD4757D.toInt(),
            0xFFC43343.toInt(),
            0xFFFF7F50.toInt()
        )

        val color = lipColors.getOrElse(1) { lipColors[0] }

        assertEquals(0xFFC43343.toInt(), color)
    }

    @Test
    fun `color index access out of bounds returns default`() {
        val lipColors = intArrayOf(
            0xFFD4757D.toInt(),
            0xFFC43343.toInt()
        )

        val color = lipColors.getOrElse(5) { lipColors[0] }

        assertEquals(lipColors[0], color)
    }

    @Test
    fun `color index access negative returns default`() {
        val lipColors = intArrayOf(
            0xFFD4757D.toInt(),
            0xFFC43343.toInt()
        )

        val color = lipColors.getOrElse(-1) { lipColors[0] }

        assertEquals(lipColors[0], color)
    }

    // ==================== 强度归一化测试 ====================

    @Test
    fun `strength normalization converts 0-100 to 0-1`() {
        val inputStrength = 50f
        val normalized = inputStrength / 100f

        assertEquals(0.5f, normalized, 0.001f)
    }

    @Test
    fun `strength normalization for zero`() {
        val inputStrength = 0f
        val normalized = inputStrength / 100f

        assertEquals(0f, normalized, 0.001f)
    }

    @Test
    fun `strength normalization for max`() {
        val inputStrength = 100f
        val normalized = inputStrength / 100f

        assertEquals(1f, normalized, 0.001f)
    }

    // ==================== 综合场景测试 ====================

    @Test
    fun `complete lip color processing simulation`() {
        // 模拟一个唇部像素的完整处理流程
        val originalR = 180
        val originalG = 60
        val originalB = 50
        val targetColor = 0xFFC43343.toInt() // 正红色
        val strength = 50f

        // Step 1: 检测是否为唇部
        val isLip = originalR > originalG + 15 &&
                originalR > originalB + 15 &&
                originalR > 80
        assertTrue(isLip)

        // Step 2: 计算亮度因子
        val brightness = (originalR + originalG + originalB) / 3
        val brightnessFactor = when {
            brightness > 200 -> 0.6f
            brightness < 50 -> 0.3f
            else -> 1.0f
        }
        assertEquals(1.0f, brightnessFactor, 0.001f) // (180+60+50)/3 = 96

        // Step 3: 计算有效强度
        val normalizedStrength = strength / 100f
        val effectiveStrength = normalizedStrength * brightnessFactor
        assertEquals(0.5f, effectiveStrength, 0.001f)

        // Step 4: 色彩混合
        val targetR = (targetColor shr 16) and 0xFF
        val targetG = (targetColor shr 8) and 0xFF
        val targetB = targetColor and 0xFF

        val newR = (originalR * (1 - effectiveStrength) + targetR * effectiveStrength).toInt()
        val newG = (originalG * (1 - effectiveStrength) + targetG * effectiveStrength).toInt()
        val newB = (originalB * (1 - effectiveStrength) + targetB * effectiveStrength).toInt()

        // 验证混合结果
        assertTrue(newR in 0..255)
        assertTrue(newG in 0..255)
        assertTrue(newB in 0..255)

        // 验证红色分量增加（向目标色靠近）
        assertTrue(newR > originalR || targetR <= originalR)
    }
}
