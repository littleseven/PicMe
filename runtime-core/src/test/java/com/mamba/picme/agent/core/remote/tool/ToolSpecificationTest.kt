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
