package com.mamba.picme.domain.matting

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 模型位置抽象：引擎只依赖 [resolve] 返回的 File，不关心来源（assets / ModelScope 下载）。 */
interface MattingModelResolver {
    /** 返回模型目标文件；不存在返回 null（由调用方决定引导下载）。 */
    suspend fun resolve(modelId: String): File?
}

/** 读取 assets 字节的抽象，便于单测注入。 */
fun interface AssetBytesProvider {
    fun readBytes(path: String): ByteArray?
}

/**
 * assets 源解析器（demo 阶段）：首次访问把 assets 中的模型拷贝到 filesDir，之后命中缓存。
 * 未来切 ModelScope：新增 DownloadMattingModelResolver 实现同接口即可，引擎零改动。
 */
class AssetMattingModelResolver(context: Context) : MattingModelResolver {

    private val appContext = context.applicationContext
    private val modelDirRoot: File = File(appContext.filesDir, "llm_models")
    private val provider = AssetBytesProvider { path ->
        appContext.assets.open(path).use { stream -> stream.readBytes() }
    }

    override suspend fun resolve(modelId: String): File? = withContext(Dispatchers.IO) {
        val (assetPath, fileName) = MODEL_ASSET_PATHS[modelId] ?: return@withContext null
        resolveBlocking(modelDirRoot, provider, modelId, assetPath, fileName)
    }

    companion object {
        private val MODEL_ASSET_PATHS = mapOf(
            "u2netp-onnx" to ("matting/u2netp.onnx" to "u2netp.onnx")
        )

        /** 测试可见：把 bytes 写入 modelDirRoot/<modelId>/<fileName>，命中缓存（已存在且非空）则直接返回。 */
        internal fun resolveBlocking(
            modelDirRoot: File,
            provider: AssetBytesProvider,
            modelId: String,
            assetPath: String,
            fileName: String
        ): File? {
            val dir = File(modelDirRoot, modelId).apply { mkdirs() }
            val target = File(dir, fileName)
            if (target.exists() && target.length() > 0) return target
            val bytes = provider.readBytes(assetPath) ?: return null
            target.writeBytes(bytes)
            return target
        }
    }
}
