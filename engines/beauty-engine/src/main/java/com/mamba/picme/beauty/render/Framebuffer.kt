package com.mamba.picme.beauty.render

import android.opengl.GLES20
import com.mamba.picme.beauty.api.Logger

/**
 * 离屏 Framebuffer 封装
 *
 * 用于多 Pass 渲染中的中间纹理存储。
 * 包含一个颜色纹理附件，无深度/模板缓冲（2D 图像处理不需要）。
 */
class Framebuffer(private val width: Int, private val height: Int) {
    companion object {
        private const val TAG = "Framebuffer"
    }

    private var framebufferId: Int = -1
    private var textureId: Int = -1
    var isInitialized: Boolean = false
        private set

    fun initialize(): Boolean {
        if (isInitialized) return true
        if (width <= 0 || height <= 0) {
            Logger.e(TAG, "Invalid size: ${width}x${height}")
            return false
        }

        // 创建纹理
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        if (textureId == 0) {
            Logger.e(TAG, "Failed to create texture")
            return false
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            width,
            height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        // 创建 FBO
        val framebuffers = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffers, 0)
        framebufferId = framebuffers[0]
        if (framebufferId == 0) {
            Logger.e(TAG, "Failed to create framebuffer")
            GLES20.glDeleteTextures(1, textures, 0)
            textureId = 0
            return false
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebufferId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            textureId,
            0
        )

        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Logger.e(TAG, "Framebuffer incomplete: status=$status")
            release()
            return false
        }

        isInitialized = true
        Logger.d(TAG, "Framebuffer initialized: ${width}x${height}, fbo=$framebufferId, tex=$textureId")
        return true
    }

    fun bind() {
        if (isInitialized) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebufferId)
            GLES20.glViewport(0, 0, width, height)
        }
    }

    fun unbind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    fun getTextureId(): Int = textureId

    fun getWidth(): Int = width
    fun getHeight(): Int = height

    fun release() {
        if (framebufferId != -1) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(framebufferId), 0)
            framebufferId = -1
        }
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        isInitialized = false
        Logger.d(TAG, "Framebuffer released")
    }
}
