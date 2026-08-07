package com.mamba.picme.beauty.render

import android.opengl.GLES20
import android.opengl.Matrix
import com.mamba.picme.beauty.api.Logger
import java.nio.FloatBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * R 计划 - OpenGL ES 渲染器基类
 *
 * 功能：
 * 1. 管理顶点缓冲和纹理缓冲
 * 2. 管理 Shader 程序
 * 3. 提供渲染接口
 * 4. 处理 OpenGL ES 状态
 */
abstract class GLRenderer {
    companion object {
        private const val TAG = "GLRenderer"
    }

    protected var vertexBuffer: FloatBuffer? = null
    protected var textureBuffer: FloatBuffer? = null
    protected val shaderProgram = ShaderProgram()
    protected val textureMatrix = FloatArray(16)

    protected var aPositionLocation: Int = -1
        protected set

    protected var aTextureCoordLocation: Int = -1
        protected set

    protected var uTextureLocation: Int = -1
        protected set

    protected var uTextureTransformLocation: Int = -1
        protected set

    var isInitialized: Boolean = false
        protected set

    open fun onInit() {
        Logger.d(TAG, "Initializing GLRenderer")
        initBuffers()
        Matrix.setIdentityM(textureMatrix, 0)

        if (!onCompileShader()) {
            Logger.e(TAG, "Failed to compile shader")
            isInitialized = false
            return
        }

        aPositionLocation = shaderProgram.getAttribLocation("aPosition")
        aTextureCoordLocation = shaderProgram.getAttribLocation("aTextureCoord")
        uTextureLocation = shaderProgram.getUniformLocation("uTexture")
        uTextureTransformLocation = shaderProgram.getUniformLocation("uTextureTransform")

        isInitialized = true
        Logger.d(TAG, "GLRenderer initialized")
    }

    protected open fun initBuffers() {
        val vertices = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
        )
        val textureCoords = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )

        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        vertexBuffer?.put(vertices)?.position(0)

        textureBuffer = ByteBuffer.allocateDirect(textureCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        textureBuffer?.put(textureCoords)?.position(0)

        Logger.d(TAG, "Buffers initialized")
    }

    protected abstract fun onCompileShader(): Boolean

    protected open fun onBeforeRender() {
        shaderProgram.use()
        shaderProgram.setMat4("uTextureTransform", textureMatrix)
    }

    open fun onRender() {
        if (!isInitialized) {
            Logger.w(TAG, "Renderer not initialized")
            return
        }

        onBeforeRender()

        GLES20.glEnableVertexAttribArray(aPositionLocation)
        GLES20.glEnableVertexAttribArray(aTextureCoordLocation)

        vertexBuffer?.let { vb ->
            vb.position(0)
            GLES20.glVertexAttribPointer(aPositionLocation, 2, GLES20.GL_FLOAT, false, 0, vb)
        }

        textureBuffer?.let { tb ->
            tb.position(0)
            GLES20.glVertexAttribPointer(aTextureCoordLocation, 2, GLES20.GL_FLOAT, false, 0, tb)
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLocation)
        GLES20.glDisableVertexAttribArray(aTextureCoordLocation)

        onAfterRender()
    }

    protected open fun onAfterRender() {}

    open fun release() {
        Logger.d(TAG, "Releasing GLRenderer")
        shaderProgram.release()
        vertexBuffer = null
        textureBuffer = null
        isInitialized = false
    }

    fun setTextureTransform(matrix: FloatArray) {
        if (matrix.size == 16) {
            System.arraycopy(matrix, 0, textureMatrix, 0, 16)
        }
    }
}

