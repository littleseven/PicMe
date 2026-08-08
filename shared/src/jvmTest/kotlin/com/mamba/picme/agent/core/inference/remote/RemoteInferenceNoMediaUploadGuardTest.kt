package com.mamba.picme.agent.core.inference.remote

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 隐私红线防回归守卫（决策1 / ADR-008）。
 *
 * 契约：远程 LLM 推理链路（`inference/remote/` 下）**只发文本**——禁止上传用户图片/视频文件到
 * 远程大模型/推理服务器。本测试静态扫描该包源码，断言不出现多模态/媒体上传符号。媒体处理
 * （`ai_optimize`/`edit_image`/打标/人脸）须在端侧 renderer/本地模型完成，绝不经此链路出境。
 *
 * 不在本守卫范围：飞书/Telegram 等用户自配置 IM 通道回传媒体给用户本人（决策1 豁免，属用户
 * 自有通道，非模型推理上传）；其 channel handler 在 `app/domain/agent/remote/`，不在本包。
 *
 * 新增远程推理代码若误引入 `ImageContent`/multipart 等，本测试会立即变红，防止红线被悄悄突破。
 *
 * KMP 抽取（Phase 4 Task 6）：本守卫随 `inference/remote/` 源码迁 :shared，扫描 commonMain
 * 新路径（java.io 文件扫描 → jvmTest，不进 commonTest）；runtime-core 同名副本继续守卫
 * 尚未迁出的 `RemoteChatEngine`/`tool/` 残留，Task 7/13 迁完后随 :runtime-core 一并删除。
 */
class RemoteInferenceNoMediaUploadGuardTest {

    /**
     * 媒体文件上传到远程 LLM 的强信号 token。任一在 `inference/remote/` 源码出现即视为红线被触碰。
     */
    private val forbiddenTokens = listOf(
        "ImageContent", // langchain4j 多模态图片消息体
        "generateWithImage", // 本地视觉推理入口（不应在远程链路出现）
        "imageInference", // 同上
        "MultipartBody", // HTTP multipart 上传（OkHttp）
        "multipart/", // multipart content-type
    )

    private val remoteSourceDir =
        File("src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote")

    @Test
    fun remoteInferenceSourcesMustNotUploadMediaFiles() {
        assertTrue(
            "守卫源码目录不存在：${remoteSourceDir.absolutePath}（测试需在 :shared 模块根目录运行）",
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
