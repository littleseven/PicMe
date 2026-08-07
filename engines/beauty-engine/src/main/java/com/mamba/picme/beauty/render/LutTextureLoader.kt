package com.mamba.picme.beauty.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import com.mamba.picme.beauty.api.Logger

/**
 * LUT 纹理加载器
 *
 * 加载 GPUPixel 的 LUT 查找表图片为 OpenGL 纹理。
 * LUT 资源从 gpupixel 模块的 assets 中加载。
 */
class LutTextureLoader(private val context: Context) {
    companion object {
        private const val TAG = "LutTextureLoader"

        // LUT 资源路径（在 gpupixel 模块的 assets 中）
        private const val LUT_GRAY = "lookup_gray.png"
        private const val LUT_ORIGIN = "lookup_origin.png"
        private const val LUT_SKIN = "lookup_skin.png"
        private const val LUT_LIGHT = "lookup_light.png"
    }

    private var grayTextureId: Int = 0
    private var originTextureId: Int = 0
    private var skinTextureId: Int = 0
    private var lightTextureId: Int = 0

    private var isLoaded = false

    /**
     * 加载所有 LUT 纹理
     * 使用 gpupixel 模块的 AssetManager 加载资源
     */
    fun loadAll(): Boolean {
        if (isLoaded) return true

        try {
            // 创建 gpupixel 模块的上下文来访问其 assets
            val gpupixelContext = context.createPackageContext(
                context.packageName,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            )

            grayTextureId = loadTextureFromAssets(gpupixelContext, LUT_GRAY)
            originTextureId = loadTextureFromAssets(gpupixelContext, LUT_ORIGIN)
            skinTextureId = loadTextureFromAssets(gpupixelContext, LUT_SKIN)
            lightTextureId = loadTextureFromAssets(gpupixelContext, LUT_LIGHT)

            if (grayTextureId == 0 || originTextureId == 0 || skinTextureId == 0 || lightTextureId == 0) {
                Logger.e(TAG, "Failed to load some LUT textures")
                release()
                return false
            }

            isLoaded = true
            Logger.d(TAG, "All LUT textures loaded successfully")
            return true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load LUT textures: ${e.message}")
            release()
            return false
        }
    }

    /**
     * 尝试从 gpupixel 模块加载，失败则尝试从当前模块加载
     */
    fun loadAllFallback(): Boolean {
        if (isLoaded) return true

        grayTextureId = loadTextureFromAssets(context, LUT_GRAY)
        originTextureId = loadTextureFromAssets(context, LUT_ORIGIN)
        skinTextureId = loadTextureFromAssets(context, LUT_SKIN)
        lightTextureId = loadTextureFromAssets(context, LUT_LIGHT)

        // 检查是否都加载成功
        val allLoaded = grayTextureId != 0 && originTextureId != 0 && skinTextureId != 0 && lightTextureId != 0
        if (allLoaded) {
            isLoaded = true
            Logger.d(TAG, "All LUT textures loaded from fallback")
            return true
        }

        // 部分失败，释放已加载的
        release()
        return false
    }

    private fun loadTextureFromAssets(ctx: Context, assetPath: String): Int {
        return try {
            val bitmap = ctx.assets.open(assetPath).use { stream ->
                BitmapFactory.decodeStream(stream)
            }
            if (bitmap == null) {
                Logger.w(TAG, "Failed to decode bitmap: $assetPath")
                return 0
            }
            val textureId = createTextureFromBitmap(bitmap)
            bitmap.recycle()
            Logger.d(TAG, "Loaded LUT texture: $assetPath = $textureId (${bitmap.width}x${bitmap.height})")
            textureId
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to load LUT: $assetPath - ${e.message}")
            0
        }
    }

    private fun createTextureFromBitmap(bitmap: Bitmap): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        if (textureId == 0) {
            Logger.e(TAG, "Failed to generate texture")
            return 0
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        return textureId
    }

    fun getGrayTextureId(): Int = grayTextureId
    fun getOriginTextureId(): Int = originTextureId
    fun getSkinTextureId(): Int = skinTextureId
    fun getLightTextureId(): Int = lightTextureId

    fun isAllLoaded(): Boolean = isLoaded

    fun release() {
        val textures = intArrayOf(grayTextureId, originTextureId, skinTextureId, lightTextureId)
            .filter { it != 0 }
            .toIntArray()
        if (textures.isNotEmpty()) {
            GLES20.glDeleteTextures(textures.size, textures, 0)
        }
        grayTextureId = 0
        originTextureId = 0
        skinTextureId = 0
        lightTextureId = 0
        isLoaded = false
        Logger.d(TAG, "LUT textures released")
    }
}
