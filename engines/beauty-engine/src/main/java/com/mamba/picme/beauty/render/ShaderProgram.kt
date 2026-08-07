package com.mamba.picme.beauty.render

import android.opengl.GLES20
import com.mamba.picme.beauty.api.Logger

/**
 * R 计划 - Shader 程序管理类
 *
 * 功能：
 * 1. 编译顶点和片段着色器
 * 2. 链接 Shader 程序
 * 3. 获取 Uniform 和 Attribute 位置
 * 4. 管理 Shader 程序生命周期
 */
class ShaderProgram {
    companion object {
        private const val TAG = "ShaderProgram"
    }

    private var programId: Int = -1
    private val uniformLocations = mutableMapOf<String, Int>()
    private val attribLocations = mutableMapOf<String, Int>()

    var isCompiled: Boolean = false
        private set

    fun compile(vertexSource: String, fragmentSource: String): Boolean {
        Logger.d(TAG, "Compiling shader program")
        try {
            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            if (vertexShader == 0) {
                Logger.e(TAG, "Failed to compile vertex shader")
                return false
            }

            val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            if (fragmentShader == 0) {
                Logger.e(TAG, "Failed to compile fragment shader")
                GLES20.glDeleteShader(vertexShader)
                return false
            }

            programId = GLES20.glCreateProgram()
            GLES20.glAttachShader(programId, vertexShader)
            GLES20.glAttachShader(programId, fragmentShader)
            GLES20.glLinkProgram(programId)

            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                val error = GLES20.glGetProgramInfoLog(programId)
                Logger.e(TAG, "Failed to link program: $error")
                GLES20.glDeleteProgram(programId)
                GLES20.glDeleteShader(vertexShader)
                GLES20.glDeleteShader(fragmentShader)
                programId = -1
                return false
            }

            GLES20.glDetachShader(programId, vertexShader)
            GLES20.glDetachShader(programId, fragmentShader)
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)

            isCompiled = true
            Logger.d(TAG, "Shader program compiled successfully: $programId")
            return true
        } catch (e: Exception) {
            Logger.e(TAG, "Shader compile error: ${e.message}", e)
            if (programId != -1) {
                GLES20.glDeleteProgram(programId)
                programId = -1
            }
            isCompiled = false
            return false
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] != GLES20.GL_TRUE) {
            val error = GLES20.glGetShaderInfoLog(shader)
            Logger.e(TAG, "Shader compile error: $error")
            // 输出完整 Shader 源码以便诊断（按行号）
            val typeName = if (type == GLES20.GL_VERTEX_SHADER) "VERTEX" else "FRAGMENT"
            Logger.e(TAG, "=== $typeName SHADER SOURCE ===")
            source.lines().forEachIndexed { index, line ->
                Logger.e(TAG, "${index + 1}: $line")
            }
            Logger.e(TAG, "=== END SHADER SOURCE ===")
            GLES20.glDeleteShader(shader)
            return 0
        }
        Logger.d(TAG, "Shader compiled: type=$type, id=$shader")
        return shader
    }

    fun use() {
        if (isCompiled && programId != -1) {
            GLES20.glUseProgram(programId)
        }
    }

    fun getUniformLocation(name: String): Int {
        uniformLocations[name]?.let { return it }
        val location = GLES20.glGetUniformLocation(programId, name)
        uniformLocations[name] = location
        if (location == -1) {
            Logger.w(TAG, "Uniform not found: $name")
        }
        return location
    }

    fun getAttribLocation(name: String): Int {
        attribLocations[name]?.let { return it }
        val location = GLES20.glGetAttribLocation(programId, name)
        attribLocations[name] = location
        if (location == -1) {
            Logger.w(TAG, "Attribute not found: $name")
        }
        return location
    }

    fun setFloat(name: String, value: Float) {
        val location = getUniformLocation(name)
        if (location != -1) GLES20.glUniform1f(location, value)
    }

    fun setInt(name: String, value: Int) {
        val location = getUniformLocation(name)
        if (location != -1) GLES20.glUniform1i(location, value)
    }

    fun setVec2(name: String, x: Float, y: Float) {
        val location = getUniformLocation(name)
        if (location != -1) GLES20.glUniform2f(location, x, y)
    }

    fun setVec2Array(name: String, values: FloatArray, vec2Count: Int) {
        val location = getUniformLocation(name)
        if (location != -1 && values.isNotEmpty() && vec2Count > 0) {
            GLES20.glUniform2fv(location, vec2Count, values, 0)
        }
    }

    fun setVec4(name: String, x: Float, y: Float, z: Float, w: Float) {
        val location = getUniformLocation(name)
        if (location != -1) GLES20.glUniform4f(location, x, y, z, w)
    }

    fun setMat4(name: String, value: FloatArray) {
        val location = getUniformLocation(name)
        if (location != -1) GLES20.glUniformMatrix4fv(location, 1, false, value, 0)
    }

    fun release() {
        if (programId != -1) {
            GLES20.glDeleteProgram(programId)
            programId = -1
            uniformLocations.clear()
            attribLocations.clear()
            isCompiled = false
            Logger.d(TAG, "Shader program released")
        }
    }

    fun getProgramId(): Int = programId
}

