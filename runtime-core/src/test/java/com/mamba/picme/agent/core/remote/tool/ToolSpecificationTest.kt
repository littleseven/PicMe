package com.mamba.picme.agent.core.inference.remote.tool

import com.mamba.tool.P
import org.junit.Test
import org.junit.Assert.*

/**
 * 验证 Kotlin 编译后的 @P 注解参数名是否正确保留
 *
 * 问题背景：@P("description") 简写形式只设置 value，不设置 name，
 * 导致 ToolSpecExtractor fallback 到 param_type 作为参数名，产生重复。
 */
class ToolSpecificationTest {

    @Test
    fun `verify click method parameter annotations`() {
        val method = RemoteControlToolService::class.java.getDeclaredMethod(
            "click",
            Integer::class.java, Integer::class.java, String::class.java
        )

        val params = method.parameters
        assertEquals("click should have 3 parameters", 3, params.size)

        // 验证每个参数都有 @P 注解且 name 不为空
        val expectedNames = listOf("x", "y", "text")
        for (i in params.indices) {
            val pAnnotation = params[i].getAnnotation(P::class.java)
            assertNotNull("Parameter $i should have @P annotation", pAnnotation)
            assertEquals(
                "Parameter $i name should be '${expectedNames[i]}'",
                expectedNames[i],
                pAnnotation!!.name
            )
            println("Param ${expectedNames[i]}: name='${pAnnotation.name}', value='${pAnnotation.value}'")
        }
    }

    @Test
    fun `verify adjust_beauty method parameter annotations`() {
        val method = RemoteControlToolService::class.java.getDeclaredMethod(
            "adjustBeauty",
            java.lang.Double::class.java, java.lang.Double::class.java, java.lang.Double::class.java,
            java.lang.Double::class.java, java.lang.Double::class.java, java.lang.Double::class.java, java.lang.Double::class.java
        )

        val params = method.parameters
        assertEquals("adjustBeauty should have 7 parameters", 7, params.size)

        val expectedNames = listOf("smoothing", "whitening", "slim_face", "big_eyes", "lip_color", "blush", "eyebrow")
        for (i in params.indices) {
            val pAnnotation = params[i].getAnnotation(P::class.java)
            assertNotNull("Parameter $i should have @P annotation", pAnnotation)
            assertEquals(
                "Parameter $i name should be '${expectedNames[i]}'",
                expectedNames[i],
                pAnnotation!!.name
            )
        }
    }

    @Test
    fun `verify no param_ fallback names exist`() {
        // 检查所有 @Tool 注解方法的参数，确保没有使用 param_ 前缀的 fallback 名称
        val methods = RemoteControlToolService::class.java.declaredMethods
        for (method in methods) {
            if (method.getAnnotation(com.mamba.tool.Tool::class.java) == null) continue

            for (param in method.parameters) {
                val pAnnotation = param.getAnnotation(P::class.java)
                if (pAnnotation != null) {
                    // 如果 @P 注解存在，name 不应该为空
                    assertTrue(
                        "Method '${method.name}' parameter should have non-empty @P.name, found: '${pAnnotation.name}'",
                        pAnnotation.name.isNotEmpty()
                    )
                }
            }
        }
    }
}
