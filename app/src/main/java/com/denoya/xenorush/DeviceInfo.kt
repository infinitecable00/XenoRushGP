package com.denoya.xenorush

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import java.io.File

/** Reads RAM, CPU, performance class, and (when EGL allows) the GPU string. Failures become zeros. */
object DeviceInfo {
    fun profile(context: Context): Graphics.DeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val ramMb = (info.totalMem / (1024L * 1024L)).toInt().coerceAtLeast(0)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return Graphics.DeviceProfile(ramMb, cores, maxFreqMhz(), performanceClass(), gpuRenderer())
    }

    private fun performanceClass(): Int =
        if (Build.VERSION.SDK_INT >= 31) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0

    private fun maxFreqMhz(): Int {
        var maxKhz = 0
        for (i in 0 until 16) {
            val khz = try {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toIntOrNull() ?: 0
            } catch (_: Exception) {
                0
            }
            if (khz > maxKhz) maxKhz = khz
        }
        return if (maxKhz > 0) maxKhz / 1000 else 0
    }

    private fun gpuRenderer(): String {
        var display: android.opengl.EGLDisplay? = null
        var context: android.opengl.EGLContext? = null
        var surface: android.opengl.EGLSurface? = null
        return try {
            val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            display = d
            if (d == EGL14.EGL_NO_DISPLAY) return ""
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(d, ver, 0, ver, 1)) return ""
            val cfgAttrib = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE)
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(d, cfgAttrib, 0, configs, 0, 1, num, 0) || num[0] == 0 || configs[0] == null) return ""
            val ctxAttrib = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            val ctx = EGL14.eglCreateContext(d, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttrib, 0)
            context = ctx
            if (ctx == EGL14.EGL_NO_CONTEXT) return ""
            val surfAttrib = intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE)
            val surf = EGL14.eglCreatePbufferSurface(d, configs[0], surfAttrib, 0)
            surface = surf
            if (surf == EGL14.EGL_NO_SURFACE) return ""
            if (!EGL14.eglMakeCurrent(d, surf, surf, ctx)) return ""
            GLES20.glGetString(GLES20.GL_RENDERER) ?: ""
        } catch (_: Exception) {
            ""
        } finally {
            try {
                val d = display
                if (d != null && d != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    val s = surface
                    if (s != null && s != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(d, s)
                    val ctx = context
                    if (ctx != null && ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(d, ctx)
                    EGL14.eglTerminate(d)
                }
            } catch (_: Exception) {
            }
        }
    }
}
