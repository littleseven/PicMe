package com.mamba.picme.beauty.render

import android.content.Context
import android.opengl.GLES20
import com.mamba.picme.beauty.api.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 美颜独立 Pass 渲染器
 *
 * 封装单个 Pass 的 Shader 编译、uniform 绑定和绘制。
 * 用于多 Pass 美颜管线中的各个阶段（磨皮、美白等）。
 */
class BeautyPass(private val context: Context) {
    companion object {
        private const val TAG = "BeautyPass"

        // 全屏 quad 顶点数据
        private val VERTICES = floatArrayOf(
            -1.0f, -1.0f,  // 左下
             1.0f, -1.0f,  // 右下
            -1.0f,  1.0f,  // 左上
             1.0f,  1.0f   // 右上
        )

        private val TEX_COORDS = floatArrayOf(
            0.0f, 0.0f,  // 左下
            1.0f, 0.0f,  // 右下
            0.0f, 1.0f,  // 左上
            1.0f, 1.0f   // 右上
        )
    }

    private val shaderProgram = ShaderProgram()
    private var isCompiled = false

    private var aPositionLocation: Int = -1
    private var aTextureCoordLocation: Int = -1
    private var uInputTextureLocation: Int = -1

    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    init {
        val vb = ByteBuffer.allocateDirect(VERTICES.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        vb.put(VERTICES).position(0)
        vertexBuffer = vb

        val tb = ByteBuffer.allocateDirect(TEX_COORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        tb.put(TEX_COORDS).position(0)
        texCoordBuffer = tb
    }

    /**
     * 编译 Shader
     * @param vertexSource 顶点 Shader 源码
     * @param fragmentSource 片段 Shader 源码
     */
    fun compile(vertexSource: String, fragmentSource: String): Boolean {
        if (isCompiled) return true

        val success = shaderProgram.compile(vertexSource, fragmentSource)
        if (success) {
            aPositionLocation = shaderProgram.getAttribLocation("aPosition")
            aTextureCoordLocation = shaderProgram.getAttribLocation("aTextureCoord")
            uInputTextureLocation = shaderProgram.getUniformLocation("uInputTexture")
            isCompiled = true
            Logger.d(TAG, "Shader compiled successfully")
        } else {
            Logger.e(TAG, "Failed to compile shader")
        }
        return success
    }

    /**
     * 从 assets 加载并编译 Shader
     */
    fun compileFromAssets(vertexPath: String, fragmentPath: String): Boolean {
        return try {
            val vertexSource = context.assets.open(vertexPath).bufferedReader().use { it.readText() }
            val fragmentSource = context.assets.open(fragmentPath).bufferedReader().use { it.readText() }
            compile(vertexSource, fragmentSource)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load shader from assets: ${e.message}")
            false
        }
    }

    /**
     * 渲染 Pass
     * @param inputTextureId 输入纹理 ID
     * @param outputFbo 输出 FBO（null 表示输出到屏幕）
     * @param textureTarget 纹理目标类型（GL_TEXTURE_2D 或 GL_TEXTURE_EXTERNAL_OES）
     * @param setupUniforms 设置 uniform 的回调
     */
    fun render(
        inputTextureId: Int,
        outputFbo: Framebuffer? = null,
        viewportWidth: Int = 0,
        viewportHeight: Int = 0,
        textureTarget: Int = GLES20.GL_TEXTURE_2D,
        setupUniforms: (ShaderProgram.() -> Unit)? = null
    ) {
        if (!isCompiled) {
            Logger.w(TAG, "Shader not compiled, skipping render")
            return
        }

        // 绑定输出 FBO
        if (outputFbo != null) {
            outputFbo.bind()
        }

        // 使用 Shader
        shaderProgram.use()

        // 绑定输入纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(textureTarget, inputTextureId)
        if (uInputTextureLocation >= 0) {
            GLES20.glUniform1i(uInputTextureLocation, 0)
        }

        // 设置额外的 uniform
        setupUniforms?.invoke(shaderProgram)

        // 绑定顶点数据
        GLES20.glEnableVertexAttribArray(aPositionLocation)
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(
            aPositionLocation, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer
        )

        GLES20.glEnableVertexAttribArray(aTextureCoordLocation)
        texCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(
            aTextureCoordLocation, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer
        )

        // 绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 清理
        GLES20.glDisableVertexAttribArray(aPositionLocation)
        GLES20.glDisableVertexAttribArray(aTextureCoordLocation)
        GLES20.glBindTexture(textureTarget, 0)

        if (outputFbo != null) {
            outputFbo.unbind()
        }
    }

    /**
     * 获取 ShaderProgram 用于设置 uniform
     */
    fun getShaderProgram(): ShaderProgram = shaderProgram

    fun release() {
        shaderProgram.release()
        isCompiled = false
    }
}
