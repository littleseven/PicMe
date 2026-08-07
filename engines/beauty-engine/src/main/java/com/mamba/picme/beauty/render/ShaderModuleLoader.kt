package com.mamba.picme.beauty.render

import android.content.Context
import com.mamba.picme.beauty.api.Logger

/**
 * 大美丽 - Shader 模块加载器
 *
 * 功能：
 * 1. 从 assets/shaders/ 目录加载独立的 .glsl 文件
 * 2. 按逻辑顺序拼接为完整的 Fragment Shader
 * 3. 支持模块化维护与独立测试
 */
object ShaderModuleLoader {
    private const val TAG = "ShaderLoader"
    private const val SHADER_DIR = "shaders"

    private val MODULE_ORDER = listOf(
        "header.glsl",
        "uniforms.glsl",
        "warp.glsl",
        "warp_gpupixel_thinface.glsl",
        "warp_gpupixel_bigeye.glsl",
        "skin.glsl",
        "lip.glsl",
        "blush.glsl",
        "colorgrade.glsl",
        "main.glsl"
    )

    private val MODULE_ORDER_2D = listOf(
        "header_2d.glsl",
        "uniforms_2d.glsl",
        "warp.glsl",
        "warp_gpupixel_thinface.glsl",
        "warp_gpupixel_bigeye.glsl",
        "skin.glsl",
        "lip.glsl",
        "blush.glsl",
        "colorgrade.glsl",
        "main.glsl"
    )

    // 2D版本使用的vertex shader路径（支持双坐标：vTextureCoord + vWarpCoord）
    const val VERTEX_SHADER_2D_PATH = "shaders/pass_vertex_warp.glsl"

    /**
     * 加载并拼接所有 Shader 模块
     */
    fun loadFullFragmentShader(context: Context): String {
        return buildString {
            for (moduleName in MODULE_ORDER) {
                try {
                    val content = context.assets.open("$SHADER_DIR/$moduleName").bufferedReader().use { it.readText() }
                    appendLine(content)
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to load shader module: $moduleName", e)
                }
            }
        }
    }

    /**
     * 加载并拼接所有 Shader 模块（2D纹理版本，用于多Pass后从FBO采样）
     */
    fun loadFullFragmentShader2D(context: Context): String {
        return buildString {
            for (moduleName in MODULE_ORDER_2D) {
                try {
                    val content = context.assets.open("$SHADER_DIR/$moduleName").bufferedReader().use { it.readText() }
                    appendLine(content)
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to load shader module: $moduleName", e)
                }
            }
        }
    }

    /**
     * 加载单个 Shader 文件
     * @param context Context
     * @param path assets 内的相对路径，如 "shaders/style/toon.glsl"
     * @return Shader 源码字符串，加载失败返回空字符串
     */
    fun loadShaderFile(context: Context, path: String): String {
        return try {
            context.assets.open(path).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load shader file: $path", e)
            ""
        }
    }
}
