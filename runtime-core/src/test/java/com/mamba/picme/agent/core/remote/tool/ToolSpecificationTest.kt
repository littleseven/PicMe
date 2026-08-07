package com.mamba.picme.agent.core.inference.remote.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool as KoogTool
import org.junit.Test
import org.junit.Assert.*

/**
 * 验证三个 ToolService 的 Koog @Tool 方法参数注解完整性（Phase 5 起扫 Koog 注解）。
 *
 * 问题背景（对齐 langchain4j 期同名测试的意图）：Koog 经 Kotlin 反射从方法签名派生
 * ToolSpecification，参数描述依赖参数级 @LLMDescription；缺注解的参数在 schema 里
 * 没有描述，LLM 容易乱传值。此处 fail-fast 防漏标。
 */
class ToolSpecificationTest {

    private val toolServices = listOf(
        ChatToolService::class.java,
        CameraToolService::class.java,
        RemoteControlToolService::class.java,
    )

    @Test
    fun `every Koog @Tool method parameter carries @LLMDescription`() {
        val missing = mutableListOf<String>()
        for (serviceClass in toolServices) {
            for (method in serviceClass.declaredMethods) {
                if (method.getAnnotation(KoogTool::class.java) == null) continue
                for (param in method.parameters) {
                    if (param.getAnnotation(LLMDescription::class.java) == null) {
                        missing.add("${serviceClass.simpleName}.${method.name}(${param.name})")
                    }
                }
            }
        }
        assertTrue("缺少参数级 @LLMDescription：$missing", missing.isEmpty())
    }

    @Test
    fun `every Koog @Tool method has explicit customName and method description`() {
        val missing = mutableListOf<String>()
        for (serviceClass in toolServices) {
            for (method in serviceClass.declaredMethods) {
                val tool = method.getAnnotation(KoogTool::class.java) ?: continue
                if (tool.customName.isBlank()) {
                    missing.add("${serviceClass.simpleName}.${method.name} 缺 customName")
                }
                if (method.getAnnotation(LLMDescription::class.java)?.value.isNullOrBlank()) {
                    missing.add("${serviceClass.simpleName}.${method.name} 缺方法级 @LLMDescription")
                }
            }
        }
        assertTrue("@Tool 元数据不完整：$missing", missing.isEmpty())
    }
}
