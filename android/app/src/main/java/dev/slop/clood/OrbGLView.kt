package dev.slop.clood

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Choreographer
import android.view.TextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Transparent TextureView-backed OpenGL ES 2.0 renderer executing the `yt-max` glass-silk
 * glowing sphere shader with critically-damped springs and real-time voice acceleration.
 * Uses premultiplied alpha over a fully transparent clear so the orb composites cleanly
 * with any background without a dark square seam.
 */
class OrbGLView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    enum class OrbMode { IDLE, RINGING, SPEAKING }

    private val renderer = OrbRenderer()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Object()

    private var surfaceTextureRef: SurfaceTexture? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var sizeChanged = false
    private var paused = false
    private var frameRequested = false
    private var choreographerScheduled = false
    private var renderThread: RenderThread? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            synchronized(lock) {
                choreographerScheduled = false
                if (!paused && renderThread != null && surfaceTextureRef != null) {
                    frameRequested = true
                    lock.notifyAll()
                    choreographerScheduled = true
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }
    }

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun setOrbState(mode: OrbMode, colorHex: String, audioLevel: Float = 0f, previewTint: Boolean = true) {
        renderer.updateTarget(mode, colorHex, audioLevel, previewTint)
    }

    fun onResume() {
        synchronized(lock) {
            paused = false
            frameRequested = true
            lock.notifyAll()
        }
        scheduleNextFrame()
    }

    fun onPause() {
        synchronized(lock) {
            paused = true
        }
        cancelNextFrame()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        stopRenderThread()
        val thread = RenderThread(surface)
        synchronized(lock) {
            surfaceTextureRef = surface
            surfaceWidth = max(1, width)
            surfaceHeight = max(1, height)
            sizeChanged = true
            frameRequested = true
            renderThread = thread
        }
        thread.start()
        if (!paused) {
            scheduleNextFrame()
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        synchronized(lock) {
            surfaceWidth = max(1, width)
            surfaceHeight = max(1, height)
            sizeChanged = true
            frameRequested = true
            lock.notifyAll()
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        cancelNextFrame()
        stopRenderThread()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun stopRenderThread() {
        val threadToJoin: RenderThread?
        synchronized(lock) {
            surfaceTextureRef = null
            threadToJoin = renderThread
            renderThread = null
            threadToJoin?.stopping = true
            lock.notifyAll()
        }
        runCatching { threadToJoin?.join(500L) }
    }

    private fun scheduleNextFrame() {
        val action = Runnable {
            synchronized(lock) {
                if (!paused && renderThread != null && surfaceTextureRef != null && !choreographerScheduled) {
                    choreographerScheduled = true
                    Choreographer.getInstance().postFrameCallback(frameCallback)
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
    }

    private fun cancelNextFrame() {
        val action = Runnable {
            synchronized(lock) {
                choreographerScheduled = false
                Choreographer.getInstance().removeFrameCallback(frameCallback)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
    }

    private inner class RenderThread(private val surfaceTexture: SurfaceTexture) : Thread("clood-orb-gl") {
        @Volatile var stopping = false

        override fun run() {
            var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
            var context: EGLContext = EGL14.EGL_NO_CONTEXT
            var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

            try {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                if (display == EGL14.EGL_NO_DISPLAY) return

                val version = IntArray(2)
                if (!EGL14.eglInitialize(display, version, 0, version, 1)) return

                val configAttribs = intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_NONE,
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val numConfigs = IntArray(1)
                if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
                    numConfigs[0] <= 0 ||
                    configs[0] == null
                ) {
                    return
                }
                val config = configs[0]!!

                val contextAttribs = intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE,
                )
                context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
                if (context == EGL14.EGL_NO_CONTEXT) return

                eglSurface = EGL14.eglCreateWindowSurface(
                    display,
                    config,
                    surfaceTexture,
                    intArrayOf(EGL14.EGL_NONE),
                    0,
                )
                if (eglSurface == EGL14.EGL_NO_SURFACE) return

                if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) return

                renderer.onSurfaceCreated()

                while (!stopping) {
                    var w = 1
                    var h = 1
                    var resized = false
                    synchronized(lock) {
                        while (!stopping && (paused || !frameRequested)) {
                            try {
                                lock.wait()
                            } catch (_: InterruptedException) {
                                // Re-check loop condition
                            }
                        }
                        if (!stopping) {
                            frameRequested = false
                            w = surfaceWidth
                            h = surfaceHeight
                            resized = sizeChanged
                            sizeChanged = false
                        }
                    }
                    if (stopping) break
                    if (resized) {
                        renderer.onSurfaceChanged(w, h)
                    }
                    renderer.onDrawFrame()
                    EGL14.eglSwapBuffers(display, eglSurface)
                }
            } finally {
                renderer.onSurfaceDestroyed()
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT,
                    )
                    if (eglSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(display, eglSurface)
                    }
                    if (context != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(display, context)
                    }
                    EGL14.eglTerminate(display)
                }
            }
        }
    }

    private class Spring(var x: Float = 0f, var v: Float = 0f) {
        fun step(target: Float, omega: Float, dt: Float) {
            val n = max(1, ceil(dt * 120f).toInt())
            val h = dt / n
            for (k in 0 until n) {
                val a = omega * omega * (target - x) - 2f * omega * v
                v += a * h
                x += v * h
            }
            if (abs(target - x) < 1e-4f && abs(v) < 1e-4f) {
                x = target
                v = 0f
            }
        }
    }

    private data class ModeParams(
        val speed: Float,
        val spin: Float,
        val turb: Float,
        val energy: Float,
        val pulse: Float,
    )

    private class OrbRenderer {
        @Volatile private var targetMode = OrbMode.IDLE
        @Volatile private var targetColor = "#7c8cff"
        @Volatile private var targetLevel = 0f
        @Volatile private var targetPreviewTint = true

        private var lastColor = "#7c8cff"
        private var currentTones = OrbColor.hexToTones("#7c8cff")
        private var targetTones = currentTones

        private val sSpeed = Spring(0.16f)
        private val sSpin = Spring(0.06f)
        private val sTurb = Spring(0f)
        private val sEnergy = Spring(0f)
        private val sPulse = Spring(0f)
        private val sTint = Spring(0.8f)

        private var phase = Random.nextFloat() * 40f
        private var spinA = Random.nextFloat() * (PI * 2.0).toFloat()
        private var time = 0f
        private val seed = Random.nextFloat() * 6.28f
        private var lastNanos = 0L

        private var width = 1
        private var height = 1
        private var program = 0
        private var uRes = -1
        private var uPhase = -1
        private var uSpin = -1
        private var uTime = -1
        private var uTurb = -1
        private var uEnergy = -1
        private var uPulse = -1
        private var uDetail = -1
        private var uSeed = -1
        private var uDeep = -1
        private var uMid = -1
        private var uLight = -1
        private var uGlow = -1

        private val quadBuf: FloatBuffer = ByteBuffer.allocateDirect(6 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f))
                position(0)
            }

        fun updateTarget(mode: OrbMode, colorHex: String, level: Float, previewTint: Boolean) {
            targetMode = mode
            targetColor = colorHex
            targetLevel = level.coerceIn(0f, 1f)
            targetPreviewTint = previewTint
        }

        fun onSurfaceCreated() {
            lastNanos = 0L
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glDisable(GLES20.GL_BLEND)

            val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glBindAttribLocation(program, 0, "aPos")
            GLES20.glLinkProgram(program)
            GLES20.glUseProgram(program)

            uRes = GLES20.glGetUniformLocation(program, "uRes")
            uPhase = GLES20.glGetUniformLocation(program, "uPhase")
            uSpin = GLES20.glGetUniformLocation(program, "uSpin")
            uTime = GLES20.glGetUniformLocation(program, "uTime")
            uTurb = GLES20.glGetUniformLocation(program, "uTurb")
            uEnergy = GLES20.glGetUniformLocation(program, "uEnergy")
            uPulse = GLES20.glGetUniformLocation(program, "uPulse")
            uDetail = GLES20.glGetUniformLocation(program, "uDetail")
            uSeed = GLES20.glGetUniformLocation(program, "uSeed")
            uDeep = GLES20.glGetUniformLocation(program, "uDeep")
            uMid = GLES20.glGetUniformLocation(program, "uMid")
            uLight = GLES20.glGetUniformLocation(program, "uLight")
            uGlow = GLES20.glGetUniformLocation(program, "uGlow")
        }

        fun onSurfaceDestroyed() {
            program = 0
            lastNanos = 0L
        }

        fun onSurfaceChanged(w: Int, h: Int) {
            width = max(1, w)
            height = max(1, h)
            GLES20.glViewport(0, 0, width, height)
        }

        fun onDrawFrame() {
            if (program == 0) return
            val now = System.nanoTime()
            val dt = if (lastNanos == 0L) 1f / 60f else min(0.1f, (now - lastNanos) / 1e9f)
            lastNanos = now

            val color = targetColor
            if (color != lastColor) {
                lastColor = color
                targetTones = OrbColor.hexToTones(color)
            }

            val lvl = targetLevel
            val mode = targetMode
            val (baseSpeed, baseSpin, baseTurb, baseEnergy, basePulse) = when (mode) {
                OrbMode.IDLE -> ModeParams(0.16f, 0.06f, 0f, 0f, 0f)
                OrbMode.RINGING -> ModeParams(0.42f, 0.36f, 0.45f, 0.55f, 1f)
                OrbMode.SPEAKING -> ModeParams(
                    0.92f + lvl * 0.85f,
                    0.96f + lvl * 0.7f,
                    min(1.5f, 1f + lvl * 0.5f),
                    min(1.4f, 1f + lvl * 0.45f),
                    0f,
                )
            }
            val tintTarget = when {
                mode == OrbMode.SPEAKING || mode == OrbMode.RINGING -> 1f
                targetPreviewTint -> 0.85f
                else -> 0.72f
            }

            sSpeed.step(baseSpeed, 7f, dt)
            sSpin.step(baseSpin, 7f, dt)
            sTurb.step(baseTurb, 7f, dt)
            sEnergy.step(baseEnergy, 7f, dt)
            sPulse.step(basePulse, 7f, dt)
            sTint.step(tintTarget, 5.5f, dt)

            val colorAlpha = (1.0 - exp(-5.5 * dt)).toFloat()
            currentTones = OrbColor.OrbTones(
                deep = OrbColor.mixLab(currentTones.deep, targetTones.deep, colorAlpha),
                mid = OrbColor.mixLab(currentTones.mid, targetTones.mid, colorAlpha),
                light = OrbColor.mixLab(currentTones.light, targetTones.light, colorAlpha),
                glow = OrbColor.mixLab(currentTones.glow, targetTones.glow, colorAlpha),
            )

            phase += sSpeed.x * 0.78f * dt
            spinA = (spinA + sSpin.x * 0.78f * dt) % (PI * 2.0).toFloat()
            time = (time + dt) % 1.5f

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glUniform2f(uRes, width.toFloat(), height.toFloat())
            GLES20.glUniform1f(uPhase, phase)
            GLES20.glUniform1f(uSpin, spinA)
            GLES20.glUniform1f(uTime, time)
            GLES20.glUniform1f(uTurb, sTurb.x)
            GLES20.glUniform1f(uEnergy, sEnergy.x)
            GLES20.glUniform1f(uPulse, sPulse.x)
            GLES20.glUniform1f(uDetail, 1f)
            GLES20.glUniform1f(uSeed, seed)

            val tint = sTint.x.coerceIn(0f, 1f)
            val deep = OrbColor.labToLinear(OrbColor.mixLab(OrbColor.PEARL_TONES.deep, currentTones.deep, tint))
            val mid = OrbColor.labToLinear(OrbColor.mixLab(OrbColor.PEARL_TONES.mid, currentTones.mid, tint))
            val light = OrbColor.labToLinear(OrbColor.mixLab(OrbColor.PEARL_TONES.light, currentTones.light, tint))
            val glow = OrbColor.labToLinear(OrbColor.mixLab(OrbColor.PEARL_TONES.glow, currentTones.glow, tint))

            GLES20.glUniform3f(uDeep, deep.x, deep.y, deep.z)
            GLES20.glUniform3f(uMid, mid.x, mid.y, mid.z)
            GLES20.glUniform3f(uLight, light.x, light.y, light.z)
            GLES20.glUniform3f(uGlow, glow.x, glow.y, glow.z)

            GLES20.glEnableVertexAttribArray(0)
            GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quadBuf)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
        }

        private fun compileShader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            return s
        }
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val FRAGMENT_SHADER = """
            precision highp float;
            uniform vec2 uRes;
            uniform float uPhase;
            uniform float uSpin;
            uniform float uTime;
            uniform float uTurb;
            uniform float uEnergy;
            uniform float uPulse;
            uniform float uDetail;
            uniform float uSeed;
            uniform vec3 uDeep;
            uniform vec3 uMid;
            uniform vec3 uLight;
            uniform vec3 uGlow;

            const float EXTENT = 1.65;
            const float PULSE_PERIOD = 1.5;
            const float PULSE_SCALE = 0.025;
            const float FLOW_SCALE = 0.95;
            const float SMALL_SCALE_MUL = 0.75;
            const float WARP = 1.2;
            const float WARP_TURB = 0.24;
            const float WARP_FALLOFF = 1.35;
            const float SILK_SHARPNESS = 4.0;
            const float SILK_BANDS = 2.8;
            const float DEEP_BANDS = 1.6;
            const float SILK_CREASE = 0.4;
            const float SILK_SCATTER = 0.5;
            const float SILK_AMOUNT = 1.2;
            const float DEEP_AMOUNT = 0.42;
            const float DEEP_DEPTH = 0.8;
            const float THICK_MIN = 0.12;
            const float CORE_STRENGTH = 0.1;
            const float CORE_ENERGY = 0.18;
            const float VOLUME_SHADOW = 0.45;
            const float BODY_BASE = 0.18;
            const float RIM_POWER = 7.0;
            const float RIM_STRENGTH = 0.5;
            const float ARC_STRENGTH = 0.55;
            const float SPEC_POWER = 120.0;
            const float SPEC_STRENGTH = 0.32;
            const float CAUSTIC_STRENGTH = 0.45;
            const float RAMP_MID_FROM = 0.08;
            const float RAMP_MID_TO = 0.65;
            const float RAMP_LIGHT_FROM = 0.5;
            const float EXPOSURE = 1.4;
            const float ENERGY_GAIN = 0.14;
            const float LIGHT_GAIN = 1.5;
            const float HALO_TIGHT = 0.22;
            const float HALO_TIGHT_FALLOFF = 16.0;
            const float HALO_WIDE = 0.14;
            const float HALO_WIDE_FALLOFF = 4.5;
            const float HALO_ENERGY = 0.75;

            const vec3 KEY = vec3(-0.48, 0.62, 0.62);

            float hash(vec2 p) {
              vec3 p3 = fract(vec3(p.xyx) * 0.1031);
              p3 += dot(p3, p3.yzx + 33.33);
              return fract((p3.x + p3.y) * p3.z);
            }

            vec3 warp(vec3 q, float ph, float amp) {
              for (int i = 1; i <= 5; i++) {
                float fi = float(i);
                q += (amp / pow(fi, WARP_FALLOFF)) * sin(q.yzx * (1.17 * fi) + ph * vec3(0.71, -0.53, 0.43) * (0.8 + 0.35 * fi) + vec3(1.7, 4.1, 2.9) * fi + uSeed);
              }
              return q;
            }

            mat3 spin(float a) {
              vec3 k = normalize(vec3(0.25, 1.0, 0.18));
              float c = cos(a), s = sin(a), t = 1.0 - c;
              return mat3(
                c + k.x*k.x*t,       k.y*k.x*t + k.z*s,  k.z*k.x*t - k.y*s,
                k.x*k.y*t - k.z*s,   c + k.y*k.y*t,      k.z*k.y*t + k.x*s,
                k.x*k.z*t + k.y*s,   k.y*k.z*t - k.x*s,  c + k.z*k.z*t
              );
            }

            vec3 toSrgb(vec3 c) {
              c = clamp(c, 0.0, 1.0);
              return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
            }

            vec3 ramp(float t) {
              vec3 c = mix(uDeep, uMid, smoothstep(RAMP_MID_FROM, RAMP_MID_TO, t));
              return mix(c, uLight, smoothstep(RAMP_LIGHT_FROM, 1.0, t));
            }

            void main() {
              float breathe = 1.0 + uPulse * PULSE_SCALE * sin(uTime * 6.2831853 / PULSE_PERIOD);
              float side = min(uRes.x, uRes.y);
              vec2 p = (gl_FragCoord.xy - 0.5 * uRes) / (0.5 * side) * EXTENT / breathe;
              float px = 2.0 * EXTENT / side / breathe;
              float r = length(p);
              float energy = uEnergy * (1.0 + 0.25 * uPulse * sin(uTime * 6.2831853 / PULSE_PERIOD));

              vec3 col = vec3(0.0);
              float a = 0.0;

              if (r < 1.0 + 2.0 * px) {
                float nz = sqrt(max(1.0 - r * r, 0.0));
                vec3 n = vec3(p, nz);
                vec2 dir = p / max(r, 1e-4);
                mat3 R = spin(uSpin);
                float scale = FLOW_SCALE * mix(SMALL_SCALE_MUL, 1.0, uDetail);
                float amp = WARP * (1.0 + WARP_TURB * uTurb);

                vec3 qf = warp(R * n * scale, uPhase, amp);
                float F = (qf.x + qf.y * 0.7 - qf.z * 0.4) * SILK_BANDS;
                float sf = 0.5 + 0.5 * sin(F);
                float sh = sin(F * 0.5);
                float crease = pow(max(1.0 - sqrt(sh * sh + 0.002), 0.0), SILK_SHARPNESS);
                float scatter = pow(1.0 - abs(sh), 2.5);
                float silk = mix(pow(sf, 5.0), crease, SILK_CREASE) + SILK_SCATTER * scatter;

                vec3 pd = vec3(p, nz * (1.0 - DEEP_DEPTH));
                vec3 qd = warp(R * pd * scale * 0.8 + vec3(3.1, -1.7, 0.6), uPhase * 0.7 + 11.0, amp * 0.8);
                float deep = 0.5 + 0.5 * sin((qd.x * 0.9 - qd.y * 0.6 + qd.z * 0.5) * DEEP_BANDS);

                vec3 key = normalize(KEY);
                float lambert = 0.5 + 0.5 * dot(n, key);
                float body = mix(VOLUME_SHADOW, 1.0, lambert);
                float thick = mix(THICK_MIN, 1.0, nz);
                vec2 cp = p - vec2(-0.08, 0.1);
                float core = exp(-dot(cp, cp) * 2.6) * (CORE_STRENGTH + CORE_ENERGY * energy);
                float emit = (SILK_AMOUNT * silk + DEEP_AMOUNT * deep) * thick * (0.55 + 0.45 * lambert) + core;
                float tone = BODY_BASE * body + emit;
                tone *= 1.0 + ENERGY_GAIN * energy;
                col = ramp(clamp(tone, 0.0, 1.0)) * tone * LIGHT_GAIN;

                float fres = pow(1.0 - nz, RIM_POWER);
                col += uLight * fres * RIM_STRENGTH * (0.5 + 0.5 * lambert);
                float edge = smoothstep(0.7, 0.93, r) * smoothstep(1.0, 0.95, r);
                float arcDir = max(dot(dir, normalize(vec2(-0.62, 0.78))), 0.0);
                col += vec3(1.0) * edge * pow(arcDir, 6.0) * ARC_STRENGTH;
                float causticDir = max(dot(dir, normalize(vec2(0.55, -0.83))), 0.0);
                float causticBand = smoothstep(0.5, 0.92, r) * smoothstep(1.0, 0.9, r);
                col += uLight * causticBand * pow(causticDir, 2.5) * CAUSTIC_STRENGTH * (0.6 + 0.6 * emit);
                vec3 h = normalize(key + vec3(0.0, 0.0, 1.0));
                float ndh = max(dot(n, h), 0.0);
                col += vec3(1.0) * pow(ndh, SPEC_POWER * mix(0.35, 1.0, uDetail)) * SPEC_STRENGTH;

                col = 1.0 - exp(-col * EXPOSURE);
                a = smoothstep(1.0 + px, 1.0 - px, r);
              }

              float d = max(r - 1.0, 0.0);
              float halo = (HALO_TIGHT * exp(-d * HALO_TIGHT_FALLOFF) + HALO_WIDE * exp(-d * HALO_WIDE_FALLOFF)) * (1.0 + HALO_ENERGY * energy);
              halo *= smoothstep(EXTENT, EXTENT - 0.4, r);
              halo = clamp(halo, 0.0, 1.0);

              vec3 cs = toSrgb(col);
              vec3 gs = toSrgb(uGlow);
              float outA = a + halo * (1.0 - a);
              vec3 outC = cs * a + gs * halo * (1.0 - a);
              float dither = (hash(gl_FragCoord.xy) + hash(gl_FragCoord.yx + 17.0) - 1.0) / 255.0 * step(0.004, outA);
              outA = clamp(outA + dither, 0.0, 1.0);
              outC = clamp(outC + dither, 0.0, outA);
              gl_FragColor = vec4(outC, outA);
            }
        """
    }
}
