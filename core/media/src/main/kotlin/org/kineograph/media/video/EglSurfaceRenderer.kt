package org.kineograph.media.video

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws bitmaps onto a video encoder's input surface with OpenGL ES, and stamps each with an exact presentation
 * time, which is the only way to give a surface-input encoder precise timing. Must be used from one thread.
 */
internal class EglSurfaceRenderer(surface: Surface) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private val quad: FloatBuffer

    init {
        quad = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        quad.put(QUAD).position(0)
        try {
            setUpEgl(surface)
            setUpGl()
        } catch (e: IOException) {
            release()
            throw e
        }
    }

    private fun setUpEgl(surface: Surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw IOException("No EGL display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw IOException("Could not initialize EGL")
        val attributes =
            intArrayOf(
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID,
                1,
                EGL14.EGL_NONE,
            )
        val configs = arrayOfNulls<EGLConfig>(1)
        val found = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, found, 0) || found[0] == 0) {
            throw IOException("No recordable EGL config")
        }
        val config = configs[0] ?: throw IOException("No recordable EGL config")
        context =
            EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
        if (context == EGL14.EGL_NO_CONTEXT) throw IOException("Could not create an EGL context")
        eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw IOException("Could not create an EGL surface")
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) throw IOException("Could not activate EGL")
    }

    private fun setUpGl() {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) throw IOException("Could not link shaders: " + GLES20.glGetProgramInfoLog(program))
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) throw IOException("Could not compile a shader: " + GLES20.glGetShaderInfoLog(shader))
        return shader
    }

    /** Draws [frame] over the whole surface and hands it to the encoder, stamped with [presentationTimeNs]. */
    fun render(frame: Bitmap, surfaceWidth: Int, surfaceHeight: Int, presentationTimeNs: Long) {
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frame, 0)
        quad.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, quad)
        GLES20.glEnableVertexAttribArray(positionHandle)
        quad.position(2)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, quad)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, presentationTimeNs)
        if (!EGL14.eglSwapBuffers(display, eglSurface)) throw IOException("Could not send a frame to the encoder")
    }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val STRIDE_BYTES = 16

        /** A full-surface rectangle: x, y, u, v. V is flipped because bitmaps start at the top and GL at the bottom. */
        val QUAD =
            floatArrayOf(
                -1f,
                -1f,
                0f,
                1f,
                1f,
                -1f,
                1f,
                1f,
                -1f,
                1f,
                0f,
                0f,
                1f,
                1f,
                1f,
                0f,
            )

        const val VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
                "attribute vec2 aTexCoord;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "  gl_Position = aPosition;\n" +
                "  vTexCoord = aTexCoord;\n" +
                "}\n"

        const val FRAGMENT_SHADER =
            "precision mediump float;\n" +
                "varying vec2 vTexCoord;\n" +
                "uniform sampler2D uTexture;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                "}\n"
    }
}
