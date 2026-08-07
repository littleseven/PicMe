package com.mamba.picme.beauty.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import com.mamba.picme.beauty.api.Logger
import android.view.Surface

/**
 * R 计划 - EGL 核心管理类
 *
 * 功能：
 * 1. 初始化 EGL 显示连接
 * 2. 创建和管理 EGL 上下文
 * 3. 创建 EGL Surface（Pbuffer 和 WindowSurface）
 * 4. 管理上下文切换
 */
class EGLCore {
    companion object {
        private const val TAG = "EGLCore"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }

    var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private set

    private var eglConfig: EGLConfig? = null
    private var sharedContext: EGLContext = EGL14.EGL_NO_CONTEXT

    fun init(): Boolean {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            Logger.w(TAG, "EGL already initialized")
            return true
        }

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Logger.e(TAG, "Unable to get EGL14 display")
            return false
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Logger.e(TAG, "Unable to initialize EGL14")
            eglDisplay = EGL14.EGL_NO_DISPLAY
            return false
        }

        Logger.d(TAG, "EGL version: ${version[0]}.${version[1]}")

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(16)
        val numConfigs = IntArray(1)

        if (!EGL14.eglChooseConfig(
                eglDisplay, configAttribs, 0,
                configs, 0, configs.size,
                numConfigs, 0
            )
        ) {
            Logger.e(TAG, "Unable to find RGB888+recordable ES2 EGL config")
            return false
        }

        val num = numConfigs[0]
        if (num <= 0) {
            Logger.e(TAG, "No matching EGL config found")
            return false
        }

        // 遍历所有 config，选择同时支持 WINDOW + RECORDABLE 的 config
        var selectedConfig: EGLConfig? = null
        for (i in 0 until num) {
            val config = configs[i] ?: continue
            val surfaceType = IntArray(1)
            EGL14.eglGetConfigAttrib(eglDisplay, config, EGL14.EGL_SURFACE_TYPE, surfaceType, 0)
            val recordable = IntArray(1)
            EGL14.eglGetConfigAttrib(eglDisplay, config, EGL_RECORDABLE_ANDROID, recordable, 0)
            val redSize = IntArray(1)
            EGL14.eglGetConfigAttrib(eglDisplay, config, EGL14.EGL_RED_SIZE, redSize, 0)
            Logger.d(TAG, "Config[$i]: surfaceType=${surfaceType[0]}, recordable=${recordable[0]}, redSize=${redSize[0]}")
            if (surfaceType[0] and EGL14.EGL_WINDOW_BIT != 0 && recordable[0] == 1) {
                selectedConfig = config
                Logger.i(TAG, "Selected config[$i] for recording")
                break
            }
        }

        eglConfig = selectedConfig ?: configs[0]
        Logger.d(TAG, "EGL initialized successfully, selected config recordable=${eglConfig != null}")
        return true
    }

    fun createContext(): EGLContext {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw IllegalStateException("EGL not initialized")
        }

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )

        val context = EGL14.eglCreateContext(
            eglDisplay, eglConfig, sharedContext, contextAttribs, 0
        )

        if (context == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("Failed to create EGL context")
        }

        if (sharedContext == EGL14.EGL_NO_CONTEXT) {
            sharedContext = context
        }

        Logger.d(TAG, "EGL context created: ${context.hashCode()}")
        return context
    }

    fun createSurface(surface: Surface?, width: Int = 1, height: Int = 1): EGLSurface {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw IllegalStateException("EGL not initialized")
        }

        val eglSurface = if (surface == null) {
            val surfaceAttribs = intArrayOf(
                EGL14.EGL_WIDTH, width,
                EGL14.EGL_HEIGHT, height,
                EGL14.EGL_NONE
            )
            EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfaceAttribs, 0)
        } else {
            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
        }

        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("Failed to create EGL surface")
        }

        Logger.d(TAG, "EGL surface created: ${eglSurface.hashCode()}")
        return eglSurface
    }

    fun makeCurrent(surface: EGLSurface, context: EGLContext): Boolean {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Logger.e(TAG, "EGL not initialized")
            return false
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, surface, surface, context)) {
            Logger.e(TAG, "eglMakeCurrent failed")
            return false
        }
        return true
    }

    fun clearCurrent(): Boolean {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Logger.e(TAG, "EGL not initialized")
            return false
        }
        return EGL14.eglMakeCurrent(
            eglDisplay,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT
        )
    }

    fun swapBuffers(surface: EGLSurface): Boolean {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Logger.e(TAG, "EGL not initialized")
            return false
        }
        return EGL14.eglSwapBuffers(eglDisplay, surface)
    }

    fun setPresentationTime(surface: EGLSurface, nsecs: Long): Boolean {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Logger.e(TAG, "EGL not initialized")
            return false
        }
        val result = EGLExt.eglPresentationTimeANDROID(eglDisplay, surface, nsecs)
        if (!result) {
            Logger.e(TAG, "eglPresentationTimeANDROID failed, error=${EGL14.eglGetError()}")
        }
        return result
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )

            if (sharedContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, sharedContext)
                sharedContext = EGL14.EGL_NO_CONTEXT
            }

            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
            Logger.d(TAG, "EGL released")
        }
    }
}

