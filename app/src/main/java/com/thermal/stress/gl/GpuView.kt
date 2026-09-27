package com.thermal.stress.gl

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GPU 烧负载视图。
 * 全屏四边形 + 高开销片元着色器（每像素最多 256 轮三角函数/距离运算），
 * 负载滑杆映射为着色器循环次数，持续 RENDERMODE_CONTINUOUSLY 渲染给 GPU 加压。
 */
class GpuView(context: Context) : GLSurfaceView(context) {

    @Volatile
    var loadPercent: Int = 60

    private val renderer = StressRenderer()

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 0, 0, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onResume() {
        super.onResume()
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private inner class StressRenderer : Renderer {

        private var program = 0
        private var uTime = 0
        private var uComplexity = 0
        private var uRes = 0
        private var w = 1
        private var h = 1
        private var t0 = System.nanoTime()

        private val vert = """
            #version 300 es
            layout(location = 0) in vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """.trimIndent()

        private val frag = """
            #version 300 es
            precision highp float;
            out vec4 fragColor;
            uniform float uTime;
            uniform int uComplexity;
            uniform vec2 uRes;
            void main() {
                vec2 p = (gl_FragCoord.xy / uRes) * 2.0 - 1.0;
                p.x *= uRes.x / uRes.y;
                vec3 c = vec3(0.0);
                for (int i = 0; i < 256; i++) {
                    if (i >= uComplexity) break;
                    float fi = float(i);
                    vec2 q = p + vec2(sin(uTime * 0.7 + fi * 0.13),
                                      cos(uTime * 0.6 + fi * 0.17)) * 0.6;
                    float d = length(q * 1.5);
                    c += 0.012 * vec3(
                        sin(d * 8.0 - uTime + fi) * 0.5 + 0.5,
                        cos(d * 6.0 + fi * 1.3) * 0.5 + 0.5,
                        sin(d * 10.0 + uTime * 0.5 + fi * 0.7) * 0.5 + 0.5);
                }
                fragColor = vec4(c, 1.0);
            }
        """.trimIndent()

        private fun compile(type: Int, src: String): Int {
            val sh = GLES30.glCreateShader(type)
            GLES30.glShaderSource(sh, src)
            GLES30.glCompileShader(sh)
            val status = IntArray(1)
            GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(sh)
                GLES30.glDeleteShader(sh)
                throw RuntimeException("shader compile error: $log")
            }
            return sh
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            val vs = compile(GLES30.GL_VERTEX_SHADER, vert)
            val fs = compile(GLES30.GL_FRAGMENT_SHADER, frag)
            program = GLES30.glCreateProgram()
            GLES30.glAttachShader(program, vs)
            GLES30.glAttachShader(program, fs)
            GLES30.glLinkProgram(program)
            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) throw RuntimeException("program link: ${GLES30.glGetProgramInfoLog(program)}")
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            uTime = GLES30.glGetUniformLocation(program, "uTime")
            uComplexity = GLES30.glGetUniformLocation(program, "uComplexity")
            uRes = GLES30.glGetUniformLocation(program, "uRes")

            val vbo = IntArray(1)
            GLES30.glGenBuffers(1, vbo, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
            val quad = floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)
            val bb = java.nio.ByteBuffer.allocateDirect(quad.size * 4)
                .order(java.nio.ByteOrder.nativeOrder())
            bb.asFloatBuffer().put(quad).position(0)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, bb, GLES30.GL_STATIC_DRAW)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
            t0 = System.nanoTime()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            w = width.coerceAtLeast(1)
            h = height.coerceAtLeast(1)
            GLES30.glViewport(0, 0, w, h)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES30.glClearColor(0.05f, 0.05f, 0.08f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glUseProgram(program)
            val secs = (System.nanoTime() - t0) / 1_000_000_000.0f
            GLES30.glUniform1f(uTime, secs)
            val complexity = (loadPercent.coerceIn(1, 100) / 100f * 255f + 1f).toInt()
            GLES30.glUniform1i(uComplexity, complexity)
            GLES30.glUniform2f(uRes, w.toFloat(), h.toFloat())
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }
    }
}
