package com.mamba.picme.beauty.render

import android.content.Context
import android.opengl.GLES20
import com.mamba.picme.beauty.api.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 风格特效 Shader 管理
 *
 * GPUPixel 移植的 5 种风格特效，每种一个独立 ShaderProgram。
 * Shader 源码从 assets/shaders/style/ 目录加载，非硬编码。
 * 采用延迟编译策略：首次使用时才编译对应 Shader。
 */
class StyleEffectShader(private val context: Context) {
    companion object {
        private const val TAG = "StyleEffectShader"
        private const val STYLE_SHADER_DIR = "shaders/style"

        // 顶点 Shader 文件映射
        private val VERTEX_SHADER_FILES = mapOf(
            StyleEffect.TOON to "vertex_nearby.glsl",
            StyleEffect.SKETCH to "vertex_nearby.glsl",
            StyleEffect.POSTERIZE to "vertex.glsl",
            StyleEffect.EMBOSS to "vertex_nearby.glsl",
            StyleEffect.CROSSHATCH to "vertex.glsl"
        )

        // 片段 Shader 文件映射
        private val FRAGMENT_SHADER_FILES = mapOf(
            StyleEffect.TOON to "toon.glsl",
            StyleEffect.SKETCH to "sketch.glsl",
            StyleEffect.POSTERIZE to "posterize.glsl",
            StyleEffect.EMBOSS to "emboss.glsl",
            StyleEffect.CROSSHATCH to "crosshatch.glsl"
        )
    }

    private val shaderPrograms = mutableMapOf<StyleEffect, ShaderProgram>()
    private val vertexBuffers = mutableMapOf<StyleEffect, FloatBuffer>()
    private val textureBuffers = mutableMapOf<StyleEffect, FloatBuffer>()

    // 当前激活的风格
    private var activeStyle: StyleEffect = StyleEffect.NONE

    // 参数缓存
    private var toonThreshold: Float = 0.2f
    private var toonQuantizationLevels: Float = 10.0f
    private var sketchEdgeStrength: Float = 1.0f
    private var posterizeColorLevels: Float = 10.0f
    private var embossIntensity: Float = 1.0f
    private var crosshatchSpacing: Float = 0.03f
    private var crosshatchLineWidth: Float = 0.003f

    // 渲染尺寸（用于计算 texel size）
    private var renderWidth: Int = 1280
    private var renderHeight: Int = 720

    /**
     * 设置当前激活的风格特效
     */
    fun setStyleEffect(style: StyleEffect) {
        if (activeStyle == style) return
        activeStyle = style
        if (style != StyleEffect.NONE && !shaderPrograms.containsKey(style)) {
            compileShader(style)
        }
    }

    fun getActiveStyle(): StyleEffect = activeStyle

    /**
     * 设置渲染尺寸（用于 3×3 邻域采样的 texel size 计算）
     */
    fun setRenderSize(width: Int, height: Int) {
        renderWidth = width
        renderHeight = height
    }

    /**
     * 设置 Toon 参数
     */
    fun setToonParams(threshold: Float, quantizationLevels: Float) {
        toonThreshold = threshold.coerceIn(0.0f, 1.0f)
        toonQuantizationLevels = quantizationLevels.coerceIn(1.0f, 256.0f)
    }

    /**
     * 设置 Sketch 参数
     */
    fun setSketchParams(edgeStrength: Float) {
        sketchEdgeStrength = edgeStrength.coerceIn(0.0f, 4.0f)
    }

    /**
     * 设置 Posterize 参数
     */
    fun setPosterizeParams(colorLevels: Float) {
        posterizeColorLevels = colorLevels.coerceIn(1.0f, 256.0f)
    }

    /**
     * 设置 Emboss 参数
     */
    fun setEmbossParams(intensity: Float) {
        embossIntensity = intensity.coerceIn(0.0f, 4.0f)
    }

    /**
     * 设置 Crosshatch 参数
     */
    fun setCrosshatchParams(spacing: Float, lineWidth: Float) {
        crosshatchSpacing = spacing.coerceIn(0.001f, 0.5f)
        crosshatchLineWidth = lineWidth.coerceIn(0.0001f, 0.1f)
    }

    /**
     * 渲染风格特效
     * @param inputTextureId 输入纹理（BeautyPass 输出）
     * @param outputWidth 输出宽度
     * @param outputHeight 输出高度
     */
    fun render(inputTextureId: Int, outputWidth: Int, outputHeight: Int) {
        val style = activeStyle
        if (style == StyleEffect.NONE) return

        // 延迟编译：首次使用时编译 Shader
        if (!shaderPrograms.containsKey(style)) {
            compileShader(style)
        }

        val program = shaderPrograms[style] ?: return
        if (!program.isCompiled) {
            Logger.w(TAG, "Shader not compiled for style: $style")
            return
        }

        // 保存并设置 GL 状态
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        // 注意：viewport 由调用者（BeautyRenderer）统一设置，此处不再覆盖

        program.use()

        // 绑定输入纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureId)
        program.setInt("uInputTexture", 0)

        // 设置风格特有参数
        when (style) {
            StyleEffect.TOON -> {
                program.setFloat("uThreshold", toonThreshold)
                program.setFloat("uQuantizationLevels", toonQuantizationLevels)
                setTexelSizeUniforms(program)
            }
            StyleEffect.SKETCH -> {
                program.setFloat("uEdgeStrength", sketchEdgeStrength)
                setTexelSizeUniforms(program)
            }
            StyleEffect.POSTERIZE -> {
                program.setFloat("uColorLevels", posterizeColorLevels)
            }
            StyleEffect.EMBOSS -> {
                setEmbossConvolutionMatrix(program)
                setTexelSizeUniforms(program)
            }
            StyleEffect.CROSSHATCH -> {
                program.setFloat("uCrossHatchSpacing", crosshatchSpacing)
                program.setFloat("uLineWidth", crosshatchLineWidth)
            }
            StyleEffect.NONE -> {}
        }

        // 绑定顶点属性
        val aPosition = program.getAttribLocation("aPosition")
        val aTextureCoord = program.getAttribLocation("aTextureCoord")

        val vb = vertexBuffers[style]
        val tb = textureBuffers[style]

        if (vb == null || tb == null) {
            Logger.e(TAG, "Vertex/texture buffer not ready for style: $style")
            return
        }

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aTextureCoord)

        vb.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vb)

        tb.position(0)
        GLES20.glVertexAttribPointer(aTextureCoord, 2, GLES20.GL_FLOAT, false, 0, tb)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTextureCoord)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun setTexelSizeUniforms(program: ShaderProgram) {
        val texelW = 1.0f / renderWidth
        val texelH = 1.0f / renderHeight
        program.setFloat("uTexelWidth", texelW)
        program.setFloat("uTexelHeight", texelH)
    }

    private fun setEmbossConvolutionMatrix(program: ShaderProgram) {
        val intensity = embossIntensity
        // Emboss 卷积核：[-2i, -i, 0; -i, 1, i; 0, i, 2i]
        val matrix = floatArrayOf(
            -2.0f * intensity, -intensity, 0.0f,
            -intensity, 1.0f, intensity,
            0.0f, intensity, 2.0f * intensity
        )
        val location = program.getUniformLocation("uConvolutionMatrix")
        if (location != -1) {
            GLES20.glUniformMatrix3fv(location, 1, false, matrix, 0)
        }
    }

    private fun compileShader(style: StyleEffect) {
        val vertexFile = VERTEX_SHADER_FILES[style] ?: return
        val fragmentFile = FRAGMENT_SHADER_FILES[style] ?: return

        val vertexShader = ShaderModuleLoader.loadShaderFile(context, "$STYLE_SHADER_DIR/$vertexFile")
        val fragmentShader = ShaderModuleLoader.loadShaderFile(context, "$STYLE_SHADER_DIR/$fragmentFile")

        if (vertexShader.isEmpty() || fragmentShader.isEmpty()) {
            Logger.e(TAG, "Failed to load shader files for style: $style")
            return
        }

        val program = ShaderProgram()
        if (!program.compile(vertexShader, fragmentShader)) {
            Logger.e(TAG, "Failed to compile shader for style: $style")
            return
        }

        shaderPrograms[style] = program
        initBuffers(style)
        Logger.d(TAG, "Shader compiled for style: $style")
    }

    private fun initBuffers(style: StyleEffect) {
        val vertices = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
        val textureCoords = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )

        val vb = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        vb.put(vertices).position(0)
        vertexBuffers[style] = vb

        val tb = ByteBuffer.allocateDirect(textureCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        tb.put(textureCoords).position(0)
        textureBuffers[style] = tb
    }

    fun release() {
        shaderPrograms.values.forEach { it.release() }
        shaderPrograms.clear()
        vertexBuffers.clear()
        textureBuffers.clear()
        activeStyle = StyleEffect.NONE
        Logger.d(TAG, "StyleEffectShader released")
    }
}
