package com.mamba.picme.beauty.render

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import com.mamba.picme.beauty.api.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.EnumMap

/**
 * 面部妆容 Pass（GPUPixel 风格）
 *
 * 使用三角网格 + 纹理贴图实现唇色/腮红渲染：
 * - 106 个顶点构成人脸三角网格（纯 106 点关键点，不扩展）
 * - 嘴唇与脸颊分别使用独立的索引网格和妆容纹理
 * - 纹理 alpha 负责确定精确的生效区域，颜色由业务色板共同决定
 *
 * 与 GPUPixel 原始实现保持一致：
 * 1. 先把输入帧完整复制到输出 FBO
 * 2. 再只在妆容三角网格区域覆盖渲染
 */
class FaceMakeupPass(private val context: Context) {

    companion object {
        private const val TAG = "FaceMakeupPass"
        private const val TEXTURE_SIZE = 1280f

        const val VERTEX_COUNT = 106

        private val FULLSCREEN_VERTICES = floatArrayOf(
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
        )

        private val FULLSCREEN_TEX_COORDS = floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
        )

        private const val BASE_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTextureCoord;
            varying vec2 vTextureCoord;

            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTextureCoord = aTextureCoord;
            }
        """

        private const val BASE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uInputTexture;
            varying vec2 vTextureCoord;

            void main() {
                gl_FragColor = texture2D(uInputTexture, vTextureCoord);
            }
        """

        // GPUPixel 111 点基准纹理坐标的前 106 点
        private val FACE_TEXTURE_COORDS = floatArrayOf(
            0.302451f, 0.384169f, 0.302986f, 0.409377f, 0.304336f, 0.434977f, 0.306984f, 0.460683f, 0.311010f, 0.486447f,
            0.316537f, 0.511947f, 0.323069f, 0.536942f, 0.331312f, 0.561627f, 0.342011f, 0.585088f, 0.355477f, 0.607217f,
            0.371142f, 0.627774f, 0.388459f, 0.646991f, 0.407041f, 0.665229f, 0.426325f, 0.682694f, 0.447468f, 0.697492f,
            0.471782f, 0.707060f, 0.500000f, 0.709867f, 0.528218f, 0.707060f, 0.552532f, 0.697492f, 0.573675f, 0.682694f,
            0.592959f, 0.665229f, 0.611541f, 0.646991f, 0.628858f, 0.627774f, 0.644523f, 0.607217f, 0.657989f, 0.585088f,
            0.668688f, 0.561627f, 0.676931f, 0.536942f, 0.683463f, 0.511947f, 0.688990f, 0.486447f, 0.693016f, 0.460683f,
            0.695664f, 0.434977f, 0.697014f, 0.409377f, 0.697549f, 0.384169f,
            0.331655f, 0.354725f, 0.354609f, 0.331785f, 0.387080f, 0.325436f, 0.420446f, 0.330125f, 0.452685f, 0.339996f,
            0.547315f, 0.339996f, 0.579554f, 0.330125f, 0.612920f, 0.325436f, 0.645391f, 0.331785f, 0.668345f, 0.354725f,
            0.500000f, 0.405156f,
            0.500000f, 0.442322f, 0.500000f, 0.480116f, 0.500000f, 0.517378f, 0.457729f, 0.542442f, 0.476911f, 0.546376f,
            0.500000f, 0.550557f, 0.523089f, 0.546376f, 0.542271f, 0.542442f,
            0.366597f, 0.404028f, 0.385132f, 0.392425f, 0.428177f, 0.397495f, 0.442446f, 0.414082f, 0.422818f, 0.419177f,
            0.382917f, 0.415929f,
            0.557554f, 0.414082f, 0.571823f, 0.397495f, 0.614868f, 0.392425f, 0.633403f, 0.404028f, 0.617083f, 0.415929f,
            0.577182f, 0.419177f,
            0.360880f, 0.349748f, 0.391440f, 0.348304f, 0.421788f, 0.352051f, 0.451601f, 0.358026f, 0.548399f, 0.358026f,
            0.578212f, 0.352051f, 0.608560f, 0.348304f, 0.639120f, 0.349748f,
            0.407165f, 0.390906f, 0.402591f, 0.420584f, 0.406113f, 0.405280f,
            0.592835f, 0.390906f, 0.597409f, 0.420584f, 0.593887f, 0.405280f,
            0.471223f, 0.409619f, 0.528777f, 0.409619f, 0.455607f, 0.495169f, 0.544393f, 0.495169f, 0.441855f, 0.523363f,
            0.558145f, 0.523363f,
            0.426186f, 0.593516f, 0.453348f, 0.586128f, 0.481258f, 0.582594f, 0.500000f, 0.584476f, 0.518742f, 0.582594f,
            0.546652f, 0.586128f, 0.573814f, 0.593516f, 0.556544f, 0.620391f, 0.531320f, 0.639672f, 0.500000f, 0.644911f,
            0.468680f, 0.639672f, 0.443456f, 0.620391f,
            0.433718f, 0.595595f, 0.466898f, 0.597025f, 0.500000f, 0.599883f, 0.533102f, 0.597025f, 0.566282f, 0.595595f,
            0.534634f, 0.610720f, 0.500000f, 0.616173f, 0.465366f, 0.610720f,
            0.406113f, 0.405280f, 0.593887f, 0.405280f
        )

        private val LIP_INDICES = intArrayOf(
            84, 85, 96,
            85, 96, 97,
            85, 86, 97,
            86, 97, 98,
            86, 87, 98,
            87, 98, 99,
            87, 88, 99,
            88, 99, 100,
            88, 89, 100,
            89, 90, 100,
            90, 91, 100,
            91, 100, 101,
            91, 92, 101,
            92, 101, 102,
            92, 93, 102,
            93, 102, 103,
            93, 94, 103,
            94, 103, 96,
            94, 95, 96,
            95, 84, 96,
            96, 97, 103,
            97, 98, 102,
            97, 102, 103,
            98, 99, 102,
            99, 100, 101,
            99, 101, 102
        )

        private val BLUSH_INDICES = intArrayOf(
            2, 3, 78,
            3, 78, 44,
            3, 4, 44,
            4, 44, 45,
            4, 5, 45,
            5, 45, 46,
            5, 6, 46,
            // 右脸严格使用 32-i 对称索引：2↔30, 3↔29, ..., 6↔26, 78↔79。
            29, 30, 79,
            79, 29, 44,
            28, 29, 44,
            44, 28, 45,
            27, 28, 45,
            45, 27, 46,
            26, 27, 46
        )

        val LIP_INDEX_COUNT = LIP_INDICES.size
        val BLUSH_INDEX_COUNT = BLUSH_INDICES.size

        const val BLEND_MODE_MULTIPLY = 15
        const val BLEND_MODE_OVERLAY = 17
    }

    enum class MakeupType {
        LIP,
        BLUSH
    }

    private data class LoadedTexture(
        val assetPath: String,
        val textureId: Int,
        val bounds: FrameBounds
    )

    private val shaderProgram = ShaderProgram()
    private val baseCopyProgram = ShaderProgram()
    private var isCompiled = false

    private var aPositionLocation: Int = -1
    private var aTextureCoordLocation: Int = -1
    private var uInputTextureLocation: Int = -1
    private var uMakeupTextureLocation: Int = -1
    private var uIntensityLocation: Int = -1
    private var uBlendModeLocation: Int = -1
    private var uTintColorLocation: Int = -1
    private var uUseTintColorLocation: Int = -1

    private var basePositionLocation: Int = -1
    private var baseTextureCoordLocation: Int = -1
    private var baseInputTextureLocation: Int = -1

    // [双缓冲 + 顶点插值] 解决人脸关键点更新与渲染帧率不同步导致的妆容甩飞问题
    // 人脸检测约10fps，渲染约30-60fps，直接渲染会导致妆容位置突变
    // 方案：保存上一帧和当前帧顶点，根据时间进度进行线性插值，让妆容平滑跟随
    private val writeBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(VERTEX_COUNT * 2 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val readBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(VERTEX_COUNT * 2 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    // 用于插值：上一帧和当前帧的顶点数据（NDC空间 [-1,1]）
    private val prevFrameVertices = FloatArray(VERTEX_COUNT * 2)
    private val currFrameVertices = FloatArray(VERTEX_COUNT * 2)
    private val interpolatedVertices = FloatArray(VERTEX_COUNT * 2)
    private val interpolatedBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(VERTEX_COUNT * 2 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val bufferLock = Any()
    private var hasNewLandmarks = false
    private var prevFrameTimeMs: Long = 0
    private var currFrameTimeMs: Long = 0
    private var isFirstFrame = true

    /**
     * [帧同步] 标记当前是否使用帧同步路径。
     * 当为 true 时，computeInterpolatedVertices() 直接返回 readBuffer，跳过所有插值。
     */
    private var frameSyncActive = false

    private val runtimeTexCoordBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(FACE_TEXTURE_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val fullScreenVertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(FULLSCREEN_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(FULLSCREEN_VERTICES)
            position(0)
        }

    private val fullScreenTexCoordBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(FULLSCREEN_TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(FULLSCREEN_TEX_COORDS)
            position(0)
        }

    private val lipIndexByteBuffer: ByteBuffer = createIndexBuffer(LIP_INDICES)
    private val blushIndexByteBuffer: ByteBuffer = createIndexBuffer(BLUSH_INDICES)
    private val loadedTextures = EnumMap<MakeupType, LoadedTexture>(MakeupType::class.java)

    private var intensity: Float = 0.5f
    private var blendMode: Int = BLEND_MODE_MULTIPLY
    private var tintColor: FloatArray? = null

    fun compileFromAssets(vertexPath: String, fragmentPath: String): Boolean {
        if (isCompiled) return true

        val vertexSource = context.assets.open(vertexPath).bufferedReader().use { reader -> reader.readText() }
        val fragmentSource = context.assets.open(fragmentPath).bufferedReader().use { reader -> reader.readText() }

        val makeupCompiled = shaderProgram.compile(vertexSource, fragmentSource)
        val baseCompiled = baseCopyProgram.compile(BASE_VERTEX_SHADER.trimIndent(), BASE_FRAGMENT_SHADER.trimIndent())
        if (!makeupCompiled || !baseCompiled) {
            shaderProgram.release()
            baseCopyProgram.release()
            return false
        }

        aPositionLocation = shaderProgram.getAttribLocation("aPosition")
        aTextureCoordLocation = shaderProgram.getAttribLocation("aTextureCoord")
        uInputTextureLocation = shaderProgram.getUniformLocation("uInputTexture")
        uMakeupTextureLocation = shaderProgram.getUniformLocation("uMakeupTexture")
        uIntensityLocation = shaderProgram.getUniformLocation("uIntensity")
        uBlendModeLocation = shaderProgram.getUniformLocation("uBlendMode")
        uTintColorLocation = shaderProgram.getUniformLocation("uTintColor")
        uUseTintColorLocation = shaderProgram.getUniformLocation("uUseTintColor")

        basePositionLocation = baseCopyProgram.getAttribLocation("aPosition")
        baseTextureCoordLocation = baseCopyProgram.getAttribLocation("aTextureCoord")
        baseInputTextureLocation = baseCopyProgram.getUniformLocation("uInputTexture")

        isCompiled = true
        Logger.d(TAG, "FaceMakeupPass compiled")
        return true
    }

    fun loadMakeupTexture(type: MakeupType, assetPath: String, bounds: FrameBounds) {
        val current = loadedTextures[type]
        if (current != null && current.assetPath == assetPath && current.bounds == bounds && current.textureId != 0) {
            return
        }

        current?.let { texture ->
            if (texture.textureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(texture.textureId), 0)
            }
        }

        val textureId = loadTextureFromAssets(assetPath)
        loadedTextures[type] = LoadedTexture(assetPath = assetPath, textureId = textureId, bounds = bounds)
        Logger.d(TAG, "Makeup texture loaded: type=$type, asset=$assetPath, id=$textureId, bounds=$bounds")
    }

    private fun loadTextureFromAssets(assetPath: String): Int {
        return try {
            val bitmap = context.assets.open(assetPath).use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: run {
                Logger.w(TAG, "Failed to decode bitmap: $assetPath")
                return 0
            }

            val width = bitmap.width
            val height = bitmap.height
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            val textureId = textures[0]
            if (textureId == 0) {
                Logger.e(TAG, "Failed to generate texture for $assetPath")
                bitmap.recycle()
                return 0
            }

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            bitmap.recycle()

            Logger.d(TAG, "Texture loaded: $assetPath = $textureId (${width}x${height})")
            textureId
        } catch (error: Exception) {
            Logger.w(TAG, "Failed to load texture: $assetPath - ${error.message}")
            0
        }
    }

    fun updateFaceLandmarks(landmarks: FloatArray) {
        if (landmarks.size < VERTEX_COUNT * 2) {
            Logger.w(TAG, "Invalid landmarks size: ${landmarks.size}, expected >= ${VERTEX_COUNT * 2}")
            return
        }

        // [双缓冲] 写入 writeBuffer，标记有新数据待交换
        synchronized(bufferLock) {
            writeBuffer.clear()
            for (index in 0 until VERTEX_COUNT) {
                val x = landmarks[index * 2]
                val y = landmarks[index * 2 + 1]
                writeBuffer.put(x * 2f - 1f)
                writeBuffer.put(y * 2f - 1f)
            }
            writeBuffer.flip()
            hasNewLandmarks = true
        }
    }

    /**
     * [帧同步] 直接更新同步后的人脸关键点，绕过双缓冲插值。
     *
     * **输入坐标系约定**（CR-P1-5）：
     * - 输入必须是 **纹理 UV 坐标 [0, 1]**，与 `CameraPreviewRenderer.mapNormalizedToUv` 输出一致
     * - 内部会转换为 **NDC [-1, 1]** 用于三角网格渲染
     * - 调用方严禁传入已转换的 NDC 坐标，否则会导致妆容位置错误
     *
     * @param uvLandmarks 106 点 UV 坐标，FloatArray(212) = [u0,v0, u1,v1, ...]
     */
    fun updateFaceLandmarksSynced(uvLandmarks: FloatArray) {
        if (uvLandmarks.size < VERTEX_COUNT * 2) {
            Logger.w(TAG, "Invalid synced landmarks size: ${uvLandmarks.size}, expected >= ${VERTEX_COUNT * 2}")
            return
        }

        synchronized(bufferLock) {
            // [帧同步] 直接写入 readBuffer，供 render() 直接使用
            // 帧同步系统已在 CPU 侧完成预测补偿，此处无需额外插值
            readBuffer.clear()
            for (index in 0 until VERTEX_COUNT) {
                val x = uvLandmarks[index * 2]
                val y = uvLandmarks[index * 2 + 1]
                // 输入已经是 UV [0,1]，需要转换为 NDC [-1,1]
                readBuffer.put(x * 2f - 1f)
                readBuffer.put(y * 2f - 1f)
            }
            readBuffer.flip()
            // 标记不是第一帧，避免旧插值路径
            isFirstFrame = false
            hasNewLandmarks = false
            // 显式标记帧同步路径激活，确保 computeInterpolatedVertices() 直接返回 readBuffer
            frameSyncActive = true
        }
    }

    /**
     * 交换读写缓冲区，并更新插值用的历史帧数据。
     * 在 render() 开始时调用，保证一帧内顶点数据一致。
     */
    private fun swapBuffers() {
        synchronized(bufferLock) {
            if (hasNewLandmarks) {
                // 将当前帧移为上一帧
                System.arraycopy(currFrameVertices, 0, prevFrameVertices, 0, VERTEX_COUNT * 2)
                prevFrameTimeMs = currFrameTimeMs

                // 将 writeBuffer 内容复制到 currFrame
                writeBuffer.position(0)
                for (i in 0 until VERTEX_COUNT * 2) {
                    currFrameVertices[i] = writeBuffer.get()
                }
                currFrameTimeMs = System.currentTimeMillis()

                // 将 writeBuffer 内容复制到 readBuffer（用于非插值路径的兼容）
                readBuffer.clear()
                writeBuffer.position(0)
                val tempArray = FloatArray(VERTEX_COUNT * 2)
                writeBuffer.get(tempArray)
                readBuffer.put(tempArray)
                readBuffer.flip()

                hasNewLandmarks = false
                isFirstFrame = false
            }
        }
    }

    /**
     * 获取用于渲染的顶点数据。
     *
     * 行为分两种模式：
     * 1. **帧同步模式**：CPU 侧已完成预测补偿，直接返回 readBuffer。
     * 2. **旧路径模式**：基于 prev/curr 历史帧进行线性外推插值，消除检测帧之间的妆容甩飞。
     *
     * 插值公式：`result = curr + t * (curr - prev)`，其中 `t ∈ [0, 1.5]`
     * - t = 0：使用最新检测位置
     * - t = 1：外推到下一检测时刻的预测位置
     * - t > 1：允许少量超调，但限制在 1.5 避免过度外推
     *
     * @return 当前顶点 FloatBuffer
     */
    private fun computeInterpolatedVertices(): FloatBuffer {
        // 帧同步路径：直接使用同步后的顶点，无需额外插值
        // FrameSyncManager 已在 CPU 侧完成精确匹配/历史回退/预测补偿
        if (frameSyncActive) {
            readBuffer.position(0)
            return readBuffer
        }

        // 旧路径：第一帧或没有历史数据时直接返回 readBuffer
        if (isFirstFrame || prevFrameTimeMs == 0L) {
            readBuffer.position(0)
            return readBuffer
        }

        val now = System.currentTimeMillis()
        val dt = (currFrameTimeMs - prevFrameTimeMs).coerceAtLeast(1L)
        val elapsed = now - currFrameTimeMs
        val t = (elapsed / dt.toFloat()).coerceIn(0f, 1.5f)

        // 线性外推插值：result = curr + t * (curr - prev)
        for (i in 0 until VERTEX_COUNT * 2) {
            interpolatedVertices[i] = currFrameVertices[i] +
                t * (currFrameVertices[i] - prevFrameVertices[i])
        }

        interpolatedBuffer.clear()
        interpolatedBuffer.put(interpolatedVertices)
        interpolatedBuffer.flip()
        return interpolatedBuffer
    }

    fun setIntensity(value: Float) {
        intensity = value.coerceIn(0f, 1f)
    }

    fun setBlendMode(mode: Int) {
        blendMode = mode
    }

    fun setTintColor(color: FloatArray?) {
        tintColor = color?.takeIf { values -> values.size >= 3 }?.copyOf(3)
    }

    private fun updateTextureCoordinates(bounds: FrameBounds) {
        runtimeTexCoordBuffer.clear()
        for (index in 0 until VERTEX_COUNT) {
            val baseU = FACE_TEXTURE_COORDS[index * 2]
            val baseV = FACE_TEXTURE_COORDS[index * 2 + 1]
            val u = (baseU * TEXTURE_SIZE - bounds.x) / bounds.width
            val v = (baseV * TEXTURE_SIZE - bounds.y) / bounds.height
            runtimeTexCoordBuffer.put(u)
            runtimeTexCoordBuffer.put(v)
        }
        runtimeTexCoordBuffer.flip()
    }

    fun render(
        inputTextureId: Int,
        outputFbo: Framebuffer? = null,
        makeupType: MakeupType = MakeupType.LIP
    ): Boolean {
        val loadedTexture = loadedTextures[makeupType]
        if (!isCompiled || loadedTexture == null || loadedTexture.textureId == 0) {
            Logger.w(
                TAG,
                "FaceMakeupPass not ready: compiled=$isCompiled, type=$makeupType, texture=${loadedTexture?.textureId ?: 0}"
            )
            return false
        }

        // [双缓冲+插值] 在渲染前交换缓冲区，确保本帧使用一致的顶点数据
        swapBuffers()

        updateTextureCoordinates(loadedTexture.bounds)

        val previousViewport = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, previousViewport, 0)

        if (outputFbo != null) {
            outputFbo.bind()
            GLES20.glViewport(0, 0, outputFbo.getWidth(), outputFbo.getHeight())
        } else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }

        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        renderBaseFrame(inputTextureId)
        renderMakeupTriangles(inputTextureId, loadedTexture.textureId, makeupType)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        if (outputFbo != null) {
            outputFbo.unbind()
        }
        GLES20.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3])
        return true
    }

    private fun renderBaseFrame(inputTextureId: Int) {
        baseCopyProgram.use()

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureId)
        if (baseInputTextureLocation >= 0) {
            GLES20.glUniform1i(baseInputTextureLocation, 0)
        }

        GLES20.glEnableVertexAttribArray(basePositionLocation)
        fullScreenVertexBuffer.position(0)
        GLES20.glVertexAttribPointer(basePositionLocation, 2, GLES20.GL_FLOAT, false, 0, fullScreenVertexBuffer)

        GLES20.glEnableVertexAttribArray(baseTextureCoordLocation)
        fullScreenTexCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(baseTextureCoordLocation, 2, GLES20.GL_FLOAT, false, 0, fullScreenTexCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(basePositionLocation)
        GLES20.glDisableVertexAttribArray(baseTextureCoordLocation)
    }

    private fun renderMakeupTriangles(inputTextureId: Int, makeupTextureId: Int, makeupType: MakeupType) {
        shaderProgram.use()

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureId)
        if (uInputTextureLocation >= 0) {
            GLES20.glUniform1i(uInputTextureLocation, 0)
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, makeupTextureId)
        if (uMakeupTextureLocation >= 0) {
            GLES20.glUniform1i(uMakeupTextureLocation, 1)
        }

        if (uIntensityLocation >= 0) {
            GLES20.glUniform1f(uIntensityLocation, intensity)
        }
        if (uBlendModeLocation >= 0) {
            GLES20.glUniform1i(uBlendModeLocation, blendMode)
        }

        val activeTintColor = tintColor
        if (activeTintColor != null && uTintColorLocation >= 0 && uUseTintColorLocation >= 0) {
            GLES20.glUniform3f(
                uTintColorLocation,
                activeTintColor[0].coerceIn(0f, 1f),
                activeTintColor[1].coerceIn(0f, 1f),
                activeTintColor[2].coerceIn(0f, 1f)
            )
            GLES20.glUniform1f(uUseTintColorLocation, 1f)
        } else if (uUseTintColorLocation >= 0) {
            GLES20.glUniform1f(uUseTintColorLocation, 0f)
        }

        GLES20.glEnableVertexAttribArray(aPositionLocation)
        val vertexBufferForRender = computeInterpolatedVertices()
        GLES20.glVertexAttribPointer(aPositionLocation, 2, GLES20.GL_FLOAT, false, 0, vertexBufferForRender)

        GLES20.glEnableVertexAttribArray(aTextureCoordLocation)
        runtimeTexCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(aTextureCoordLocation, 2, GLES20.GL_FLOAT, false, 0, runtimeTexCoordBuffer)

        val (indexBuffer, indexCount) = when (makeupType) {
            MakeupType.LIP -> lipIndexByteBuffer to LIP_INDEX_COUNT
            MakeupType.BLUSH -> blushIndexByteBuffer to BLUSH_INDEX_COUNT
        }

        indexBuffer.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

        GLES20.glDisableVertexAttribArray(aPositionLocation)
        GLES20.glDisableVertexAttribArray(aTextureCoordLocation)
    }

    /**
     * [帧同步] 重置帧同步状态，当切换回旧路径时调用
     */
    fun resetFrameSync() {
        synchronized(bufferLock) {
            frameSyncActive = false
            isFirstFrame = true
            prevFrameTimeMs = 0
            currFrameTimeMs = 0
        }
    }

    fun release() {
        loadedTextures.values.forEach { texture ->
            if (texture.textureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(texture.textureId), 0)
            }
        }
        loadedTextures.clear()
        shaderProgram.release()
        baseCopyProgram.release()
        isCompiled = false
    }

    private fun createIndexBuffer(indices: IntArray): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder())
        indices.forEach { index ->
            byteBuffer.putShort(index.toShort())
        }
        byteBuffer.position(0)
        return byteBuffer
    }
}

/**
 * 纹理边界框（GPUPixel FrameBounds 兼容）
 */
data class FrameBounds(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)