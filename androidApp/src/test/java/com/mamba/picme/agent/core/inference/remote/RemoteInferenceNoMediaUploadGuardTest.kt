package com.mamba.picme.agent.core.inference.remote

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 隐私红线防回归守卫（决策1 / ADR-008）——androidApp 侧副本。
 *
 * 契约：远程 LLM 推理链路（`inference/remote/` 下）**只发文本**——禁止上传用户图片/视频文件到
 * 远程大模型/推理服务器。本测试静态扫描该包源码，断言不出现多模态/媒体上传符号。
 *
 * 存在理由（Phase 4 Task 13）：`RemoteControlToolService` 自 runtime-core 迁入 androidApp 后
 * 脱离 shared 侧同名守卫（仅扫 commonMain）的覆盖范围，但其包路径仍属红线契约管辖，故在
 * 本模块补一份守卫。shared/src/jvmTest 副本继续守卫 commonMain 侧。
 */
class RemoteInferenceNoMediaUploadGuardTest {

    /**
     * 媒体文件上传到远程 LLM 的强信号 token。任一在 `inference/remote/` 源码出现即视为红线被触碰。
     * 与 shared 侧副本列表保持一致。
     */
    private val forbiddenTokens = listOf(
        "ImageContent", // langchain4j 多模态图片消息体
        "generateWithImage", // 本地视觉推理入口（不应在远程链路出现）
        "imageInference", // 同上
        "MultipartBody", // HTTP multipart 上传（OkHttp）
        "multipart/", // multipart content-type
    )

    private val remoteSourceDir =
        File("src/main/java/com/mamba/picme/agent/core/inference/remote")

    @Test
    fun remoteInferenceSourcesMustNotUploadMediaFiles() {
        assertTrue(
            "守卫源码目录不存在：${remoteSourceDir.absolutePath}（测试需在 :androidApp 模块根目录运行）",
            remoteSourceDir.isDirectory
        )

        val offenders = mutableListOf<String>()
        remoteSourceDir.walkTopDown()
            .filter { file -> file.isFile && file.extension.equals("kt", ignoreCase = true) }
            .forEach { file ->
                val text = file.readText()
                forbiddenTokens.forEach { token ->
                    if (token in text) {
                        offenders += "${file.relativeTo(remoteSourceDir)} → '$token'"
                    }
                }
            }

        if (offenders.isNotEmpty()) {
            fail(
                "隐私红线违规（ADR-008）：远程推理链路检测到媒体上传相关符号：\n" +
                    offenders.joinToString("\n") + "\n" +
                    "远程 LLM 链路只允许发送文本；媒体处理须留端侧。"
            )
        }
    }
}
