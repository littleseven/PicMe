package com.mamba.picme.beauty.render

/**
 * R 计划 - 美颜顶点着色器定义
 *
 * Fragment Shader 已迁移至 assets/shaders/ 模块化路径，
 * 通过 ShaderModuleLoader 加载。详见：
 * - assets/shaders/uniforms.glsl
 * - assets/shaders/warp.glsl
 * - assets/shaders/skin.glsl
 * - assets/shaders/lip.glsl
 * - assets/shaders/blush.glsl
 * - assets/shaders/colorgrade.glsl
 * - assets/shaders/main.glsl
 */
object BeautyShaders {

    val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        uniform mat4 uTextureTransform;
        varying vec2 vTextureCoord;
        varying vec2 vWarpCoord;

        void main() {
            gl_Position = aPosition;
            vTextureCoord = (uTextureTransform * aTextureCoord).xy;
            vWarpCoord = vTextureCoord;
        }
    """.trimIndent()
}

