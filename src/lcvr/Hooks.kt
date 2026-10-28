package lcvr

import java.io.File
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Called from the patched EntityRenderer through MethodHandles. Game objects are reached by
 * reflection because they live in Lunar's "Genesis" module/class loader, not ours.
 *
 * Frame flow (render thread):
 * 1. framePre: WaitGetPoses, then add the head yaw delta to the player's yaw and take pitch
 *    from the head, so aiming, movement and the crosshair follow the headset; the mouse still
 *    turns you left and right.
 * 2. renderWorldPass: run the world pass once per eye into per-eye framebuffers, with the
 *    headset projection, head roll and head position applied.
 * 3. Vanilla then draws the GUI into the (now cleared, transparent) main framebuffer.
 * 4. framePost: copy the GUI into a texture, draw the left eye under it for the desktop
 *    mirror, draw the GUI into both eyes as a head-locked quad and submit them.
 *
 * The hook entry points are `@JvmStatic` because the MethodHandles are looked up with
 * findStatic and invoked with invokeExact from the patched class.
 */
object Hooks {
    // ---- config (agent args) ----
    var hudDist = 1.6f
    var hudWidth = 1.5f
    var scale = 0.75f
    var recenterKey = 199 // LWJGL Keyboard.KEY_HOME
    var mirror = true
    var positional = true

    // ---- state ----
    var nativeOk = false
    private var reflectOk = false
    private var tried = false
    var active = false
    private var dead = false
    var inEye = false
    var eye = 0
    private var eyesRendered = false

    val pose = FloatArray(12)
    private val matrix = FloatArray(16)
    var poseValid = false
    private var needZero = true
    private var yaw0 = 0.0
    private var px0 = 0f
    private var py0 = 0f
    private var pz0 = 0f
    var headYaw = 0f // Minecraft convention, relative to zero
    var headRoll = 0f
    private var headPitch = 0f
    private var lastYaw = 0f
    private var poseCount = 0L
    var relX = 0f // head position in the zeroed tracking frame (meters)
    var relY = 0f
    var relZ = 0f
    private var keyWasDown = false
    private var worldSwapped = false
    private var worldW = 0
    private var worldH = 0
    private var frames = 0L
    private const val MAX_OFFSET = 0.8

    val eyeFb = arrayOfNulls<Any>(2)
    var eyeW = 0
    var eyeH = 0

    // ---- reflection ----
    lateinit var fMc: Field
    lateinit var fFramebuffer: Field
    lateinit var fDisplayW: Field
    lateinit var fDisplayH: Field
    lateinit var fPlayer: Field
    private lateinit var fScreen: Field
    private lateinit var fSettings: Field
    lateinit var fYaw: Field
    lateinit var fPitch: Field
    private lateinit var fPrevYaw: Field
    private lateinit var fPrevPitch: Field
    lateinit var fFbo: Field
    lateinit var fFbTex: Field
    lateinit var fFbW: Field
    lateinit var fFbH: Field
    lateinit var mOrigPass: Method
    private lateinit var mBind: Method
    private lateinit var mKeyDown: Method
    private lateinit var mMouseX: Method
    private lateinit var mMouseY: Method
    private var mProject: Method? = null
    private lateinit var cFramebuffer: Constructor<*>

    @JvmStatic
    fun log(s: String) = println("[lcvr] $s")

    fun configure(args: String?) {
        if (args.isNullOrEmpty()) return
        for (kv in args.split(',')) {
            val p = kv.split('=', limit = 2)
            if (p.size != 2) continue
            val k = p[0].trim()
            val v = p[1].trim()
            try {
                when (k) {
                    "hudDist" -> hudDist = v.toFloat()
                    "hudWidth" -> hudWidth = v.toFloat()
                    "scale" -> scale = v.toFloat()
                    "recenterKey" -> recenterKey = v.toInt()
                    "mirror" -> mirror = v.toBoolean()
                    "positional" -> positional = v.toBoolean()
                    else -> log("unknown option $k")
                }
            } catch (e: NumberFormatException) {
                log("bad value for $k: $v")
            }
        }
    }

    fun loadNative(lib: File) {
        try {
            System.load(lib.absolutePath)
            nativeOk = true
        } catch (t: Throwable) {
            log("could not load $lib: $t — VR disabled")
        }
    }

    @JvmStatic
    fun handles(): Array<MethodHandle?> {
        val l = MethodHandles.lookup()
        fun hook(name: String, vararg params: Class<*>) =
            l.findStatic(Hooks::class.java, name, MethodType.methodType(Void.TYPE, params))
        val obj = Any::class.java
        val f = java.lang.Float.TYPE
        val h = arrayOfNulls<MethodHandle>(Patcher.HOOK_COUNT)
        h[Patcher.H_FRAME_PRE] = hook("framePre", obj, f, java.lang.Long.TYPE)
        h[Patcher.H_FRAME_POST] = hook("framePost", obj)
        h[Patcher.H_PASS] = hook("renderWorldPass", obj, Integer.TYPE, f, java.lang.Long.TYPE)
        h[Patcher.H_ORIENT_PRE] = hook("orientPre", obj, f)
        h[Patcher.H_ORIENT_POST] = hook("orientPost", obj, f)
        h[Patcher.H_PERSPECTIVE] = hook("perspective", f, f, f, f)
        h[Patcher.H_WORLD_PRE] = hook("worldPre", obj)
        h[Patcher.H_WORLD_POST] = hook("worldPost", obj)
        return h
    }

    // ------------------------------------------------------------------------------------
    // hooks

    @JvmStatic
    fun framePre(er: Any, partialTicks: Float, nanoTime: Long) {
        if (dead) return
        try {
            Timing.frameStart()
            if (!reflectOk) initReflection(er.javaClass)
            val key = keyDown(recenterKey)
            val pressed = key && !keyWasDown
            keyWasDown = key

            if (!active) {
                if (!tried || pressed) start(er)
                if (!active) return
            } else if (pressed) {
                needZero = true
                log("recentered")
            }

            val r = Native.waitGetPoses(pose)
            Timing.afterWait()
            if (r < 0) {
                log("SteamVR session ended; press the recenter key to reconnect")
                stopActive()
                return
            }
            poseValid = r > 0
            if (r == 2) {
                needZero = true
                log("headset put on; recentered")
            }
            if (poseValid) updateHead(fMc.get(er))
        } catch (t: Throwable) {
            fail(t)
        }
    }

    @JvmStatic
    fun framePost(er: Any) {
        if (!active || dead) return
        try {
            val mc = fMc.get(er)
            ensureEyes()
            val fb = fFramebuffer.get(mc)
            val fbo = fFbo.getInt(fb)
            val w = fFbW.getInt(fb)
            val h = fFbH.getInt(fb)
            val cursor = fScreen.get(mc) != null
            Native.hudCapture(fbo, w, h, cursor, mMouseX.invoke(null) as Int, mMouseY.invoke(null) as Int)
            if (eyesRendered) {
                if (mirror) Native.mirrorUnder(fbo, w, h, fFbTex.getInt(eyeFb[0]), eyeW, eyeH)
            } else {
                // menus / loading screens: only the HUD is visible
                for (e in eyeFb) Native.clearFbo(fFbo.getInt(e), eyeW, eyeH)
            }
            for (e in 0..1) {
                Native.projection(e, 0.05f, 100f, matrix)
                Native.drawHud(fFbo.getInt(eyeFb[e]), eyeW, eyeH, matrix, hudWidth, hudDist)
            }
            lockPitch(fPlayer.get(mc))
            val hadEyes = eyesRendered
            eyesRendered = false
            Native.submit(fFbTex.getInt(eyeFb[0]), fFbTex.getInt(eyeFb[1]))
            Timing.frameEnd()
            if (++frames % 30 == 0L) {
                drainNativeLog()
                Debug.maybeDump(mc, fb, hadEyes)
            }
        } catch (t: Throwable) {
            fail(t)
        }
    }

    /**
     * With an OptiFine shader pack loaded, report the eye size for the whole of renderWorld:
     * Shaders.beginRender runs there, before the eye passes, and sizes its buffers from
     * mc.displayWidth/displayHeight.
     */
    @JvmStatic
    fun worldPre(er: Any) {
        if (!active || dead || !OptiFineShaders.active()) return
        try {
            val mc = fMc.get(er)
            ensureEyes()
            worldW = fDisplayW.getInt(mc)
            worldH = fDisplayH.getInt(mc)
            fDisplayW.setInt(mc, eyeW)
            fDisplayH.setInt(mc, eyeH)
            worldSwapped = true
        } catch (t: Throwable) {
            fail(t)
        }
    }

    @JvmStatic
    fun worldPost(er: Any) {
        if (!worldSwapped) return
        worldSwapped = false
        try {
            val mc = fMc.get(er)
            fDisplayW.setInt(mc, worldW)
            fDisplayH.setInt(mc, worldH)
        } catch (t: Throwable) {
            fail(t)
        }
    }

    @JvmStatic
    fun renderWorldPass(er: Any, pass: Int, partialTicks: Float, finishTimeNano: Long) {
        if (!active || dead || inEye) {
            callOriginalPass(er, pass, partialTicks, finishTimeNano)
            return
        }
        val mc: Any
        val mainFb: Any
        val dw: Int
        val dh: Int
        try {
            mc = fMc.get(er)
            ensureEyes()
            mainFb = fFramebuffer.get(mc)
            dw = fDisplayW.getInt(mc)
            dh = fDisplayH.getInt(mc)
        } catch (t: Throwable) {
            fail(t)
            callOriginalPass(er, pass, partialTicks, finishTimeNano)
            return
        }
        try {
            lockPitch(fPlayer.get(mc))
        } catch (ignored: IllegalAccessException) {
        }
        val shaders = OptiFineShaders.active()
        for (e in 0..1) {
            try {
                fFramebuffer.set(mc, eyeFb[e])
                fDisplayW.setInt(mc, eyeW)
                fDisplayH.setInt(mc, eyeH)
                mBind.invoke(eyeFb[e], true)
            } catch (t: Throwable) {
                restore(mc, mainFb, dw, dh)
                fail(t)
                callOriginalPass(er, pass, partialTicks, finishTimeNano)
                return
            }
            eye = e
            inEye = true
            try {
                if (shaders) {
                    if (e > 0) OptiFineShaders.rearm()
                    OptiFineShaders.beginEye(e)
                }
                callOriginalPass(er, pass, partialTicks, finishTimeNano)
            } catch (ex: ReflectiveOperationException) {
                fail(ex)
            } finally {
                inEye = false
                if (shaders) {
                    try {
                        OptiFineShaders.endEye(e)
                    } catch (ignored: IllegalAccessException) {
                    }
                }
                restore(mc, mainFb, dw, dh)
            }
        }
        try {
            mBind.invoke(mainFb, true)
            // the GUI is drawn next; give it a transparent canvas so it can be captured for the HUD
            Native.clearFbo(fFbo.getInt(mainFb), fFbW.getInt(mainFb), fFbH.getInt(mainFb))
            eyesRendered = true
        } catch (t: Throwable) {
            fail(t)
        }
    }

    @JvmStatic
    fun orientPre(er: Any, partialTicks: Float) {
        if (inEye && poseValid) Native.rotate(-headRoll, 0f, 0f, 1f)
    }

    @JvmStatic
    fun orientPost(er: Any, partialTicks: Float) {
        if (!inEye || !poseValid || !positional) return
        try {
            val player = fPlayer.get(fMc.get(er)) ?: return
            val prev = fPrevYaw.getFloat(player)
            val cur = fYaw.getFloat(player)
            val body = Math.toRadians((prev + (cur - prev) * partialTicks - headYaw).toDouble())
            val rightX = -Math.cos(body)
            val rightZ = -Math.sin(body)
            val fwdX = -Math.sin(body)
            val fwdZ = Math.cos(body)
            val wx = rightX * relX + fwdX * -relZ
            val wz = rightZ * relX + fwdZ * -relZ
            Native.translate(-wx.toFloat(), -relY, -wz.toFloat())
        } catch (t: Throwable) {
            fail(t)
        }
    }

    @JvmStatic
    fun perspective(fovy: Float, aspect: Float, zNear: Float, zFar: Float) {
        if (inEye && active) {
            Native.projection(eye, zNear, zFar, matrix)
            Native.multMatrix(matrix)
        } else if (nativeOk) {
            Native.perspective(fovy, aspect, zNear, zFar)
        } else {
            val m = mProject ?: Class.forName("org.lwjgl.util.glu.Project", true, Patcher.gameLoader).let { c ->
                open(c)
                val f = java.lang.Float.TYPE
                c.getMethod("gluPerspective", f, f, f, f).also { mProject = it }
            }
            try {
                m.invoke(null, fovy, aspect, zNear, zFar)
            } catch (e: InvocationTargetException) {
                throw e.cause ?: e
            }
        }
    }

    // ------------------------------------------------------------------------------------

    private fun start(er: Any) {
        tried = true
        if (!nativeOk) {
            dead = true
            return
        }
        val err = Native.init()
        if (err != null) {
            log("OpenVR not started: $err. Press the recenter key (default Home) to retry.")
            return
        }
        val size = Native.recommendedSize()
        eyeW = maxOf(64, Math.round(size[0] * scale))
        eyeH = maxOf(64, Math.round(size[1] * scale))
        tweakSettings(fMc.get(er))
        OptiFineShaders.reset()
        needZero = true
        active = true
        log("VR active, eye buffers ${eyeW}x$eyeH")
    }

    private fun drainNativeLog() {
        val s = Native.drainLog() ?: return
        for (line in s.split('\n')) if (line.isNotEmpty()) log(line)
    }

    private fun stopActive() {
        active = false
        poseValid = false
        inEye = false
    }

    private fun fail(t: Throwable) {
        log("error, VR disabled for this session: $t")
        t.printStackTrace()
        stopActive()
        dead = true
    }

    private fun restore(mc: Any, mainFb: Any, dw: Int, dh: Int) {
        fFramebuffer.set(mc, mainFb)
        fDisplayW.setInt(mc, dw)
        fDisplayH.setInt(mc, dh)
    }

    /** Runs the real world pass. Exceptions from the game propagate unchanged. */
    private fun callOriginalPass(er: Any, pass: Int, pt: Float, nano: Long) {
        try {
            mOrigPass.invoke(er, pass, pt, nano)
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    private fun ensureEyes() {
        if (eyeFb[0] != null) return
        for (i in 0..1) eyeFb[i] = cFramebuffer.newInstance(eyeW, eyeH, true)
        if (fFbo.getInt(eyeFb[0]) <= 0) {
            log("warning: Minecraft framebuffers seem disabled (OptiFine Fast Render?). Turn Fast Render off.")
        }
    }

    /**
     * Head rotation as yaw/pitch/roll (Y-X-Z order, OpenVR axes: +X right, +Y up, -Z forward),
     * converted to Minecraft's convention (yaw grows turning right, pitch grows looking down).
     */
    private fun updateHead(mc: Any) {
        val m = pose
        val yaw = Math.atan2(m[2].toDouble(), m[10].toDouble())
        val pitch = Math.asin((-m[6]).toDouble().coerceIn(-1.0, 1.0))
        val roll = Math.atan2(m[4].toDouble(), m[5].toDouble())
        if (needZero) {
            yaw0 = yaw
            px0 = m[3]
            py0 = m[7]
            pz0 = m[11]
        }
        val hy = (-Math.toDegrees(wrap(yaw - yaw0))).toFloat()
        val hp = (-Math.toDegrees(pitch)).toFloat()
        headRoll = Math.toDegrees(roll).toFloat()

        var dx = (m[3] - px0).toDouble()
        var dy = (m[7] - py0).toDouble()
        var dz = (m[11] - pz0).toDouble()
        if (dx * dx + dy * dy + dz * dz > MAX_OFFSET * MAX_OFFSET) {
            // zero was taken somewhere else (headset on a desk, user moved seats): re-anchor
            px0 = m[3]
            py0 = m[7]
            pz0 = m[11]
            dx = 0.0
            dy = 0.0
            dz = 0.0
            log("head moved more than $MAX_OFFSET m from the zero point; re-anchored position")
        }
        val c = Math.cos(yaw0)
        val s = Math.sin(yaw0)
        relX = (c * dx - s * dz).toFloat()
        relY = dy.toFloat()
        relZ = (s * dx + c * dz).toFloat()

        val player = fPlayer.get(mc)
        if (player != null && !needZero) {
            // yaw: the mouse turns the body, the head adds on top
            val dYaw = Math.toDegrees(wrap(Math.toRadians((hy - lastYaw).toDouble()))).toFloat()
            fYaw.setFloat(player, fYaw.getFloat(player) + dYaw)
            fPrevYaw.setFloat(player, fPrevYaw.getFloat(player) + dYaw)
        }
        headYaw = hy
        headPitch = hp.coerceIn(-90f, 90f)
        lastYaw = hy
        needZero = false
        lockPitch(player)

        if (++poseCount % 450 == 0L) {
            val py = if (player == null) Float.NaN else fYaw.getFloat(player)
            val pp = if (player == null) Float.NaN else fPitch.getFloat(player)
            log(
                "head yaw=%.1f pitch=%.1f roll=%.1f pos=(%.2f,%.2f,%.2f) player yaw=%.1f pitch=%.1f"
                    .format(headYaw, headPitch, headRoll, relX, relY, relZ, py, pp),
            )
            log(Timing.report())
        }
    }

    /**
     * Pitch comes only from the headset: mouse pitch would tilt the camera away from where
     * the head points, which makes every head turn swing the world the wrong way. Applied
     * repeatedly per frame because the mouse handler runs after framePre.
     */
    private fun lockPitch(player: Any?) {
        if (player == null || !poseValid) return
        fPitch.setFloat(player, headPitch)
        fPrevPitch.setFloat(player, headPitch)
    }

    private fun wrap(a: Double): Double {
        var r = a
        while (r > Math.PI) r -= 2 * Math.PI
        while (r < -Math.PI) r += 2 * Math.PI
        return r
    }

    private fun keyDown(key: Int): Boolean = try {
        mKeyDown.invoke(null, key) as Boolean
    } catch (e: Exception) {
        false // Keyboard not created yet
    }

    /** Settings that are wrong for VR: vsync/fps caps fight WaitGetPoses, bobbing and anaglyph. */
    private fun tweakSettings(mc: Any) {
        try {
            val gs = fSettings.get(mc)
            setIfPresent(gs, "anaglyph", false)
            setIfPresent(gs, "viewBobbing", false)
            setIfPresent(gs, "enableVsync", false)
            setIfPresent(gs, "limitFramerate", 260)
            setIfPresent(gs, "ofFastRender", false)
            // in the headset the desktop window is rarely focused; do not pause the game for it
            setIfPresent(gs, "pauseOnLostFocus", false)
            // the vignette multiplies the screen darker but writes full alpha, which turns the
            // transparent HUD canvas into a black sheet; lenses vignette on their own anyway
            setIfPresent(gs, "ofVignette", 1)
            val display = Class.forName("org.lwjgl.opengl.Display", true, mc.javaClass.classLoader)
            open(display)
            display.getMethod("setVSyncEnabled", java.lang.Boolean.TYPE).invoke(null, false)
        } catch (t: Throwable) {
            log("could not adjust video settings: $t")
        }
    }

    private fun setIfPresent(o: Any, name: String, v: Any) {
        try {
            o.javaClass.getField(name).apply { isAccessible = true }.set(o, v)
        } catch (ignored: ReflectiveOperationException) {
        } catch (ignored: RuntimeException) {
        }
    }

    // ------------------------------------------------------------------------------------
    // reflection setup

    @JvmStatic
    fun initReflection(erClass: Class<*>) {
        val l = erClass.classLoader
        val mcC = Class.forName("net.minecraft.client.Minecraft", false, l)
        val entityC = Class.forName("net.minecraft.entity.Entity", false, l)
        val fbC = Class.forName("net.minecraft.client.shader.Framebuffer", false, l)
        val kbC = Class.forName("org.lwjgl.input.Keyboard", false, l)
        val mouseC = Class.forName("org.lwjgl.input.Mouse", false, l)
        for (c in arrayOf(erClass, mcC, entityC, fbC, kbC, mouseC)) open(c)

        fMc = field(erClass, "mc")
        mOrigPass = erClass.getDeclaredMethod(Patcher.ORIG_PASS, Integer.TYPE, java.lang.Float.TYPE, java.lang.Long.TYPE)
            .apply { isAccessible = true }

        fFramebuffer = field(mcC, "framebufferMc")
        fDisplayW = field(mcC, "displayWidth")
        fDisplayH = field(mcC, "displayHeight")
        fPlayer = field(mcC, "thePlayer")
        fScreen = field(mcC, "currentScreen")
        fSettings = field(mcC, "gameSettings")

        fYaw = field(entityC, "rotationYaw")
        fPitch = field(entityC, "rotationPitch")
        fPrevYaw = field(entityC, "prevRotationYaw")
        fPrevPitch = field(entityC, "prevRotationPitch")

        fFbo = field(fbC, "framebufferObject")
        fFbTex = field(fbC, "framebufferTexture")
        fFbW = field(fbC, "framebufferWidth")
        fFbH = field(fbC, "framebufferHeight")
        mBind = fbC.getMethod("bindFramebuffer", java.lang.Boolean.TYPE)
        cFramebuffer = fbC.getConstructor(Integer.TYPE, Integer.TYPE, java.lang.Boolean.TYPE)

        mKeyDown = kbC.getMethod("isKeyDown", Integer.TYPE)
        mMouseX = mouseC.getMethod("getX")
        mMouseY = mouseC.getMethod("getY")
        OptiFineShaders.init(l)
        reflectOk = true
    }

    /** Finds a field on c or a superclass and makes it accessible. */
    fun field(c: Class<*>, name: String): Field {
        var k: Class<*>? = c
        while (k != null) {
            try {
                return k.getDeclaredField(name).apply { isAccessible = true }
            } catch (ignored: NoSuchFieldException) {
            }
            k = k.superclass
        }
        throw NoSuchFieldException("${c.name}.$name")
    }

    /** Opens the class's package to this agent if it lives in a named module (Lunar's Genesis layer). */
    fun open(c: Class<*>) {
        val m = c.module
        val me = Hooks::class.java.module
        if (!m.isNamed || m.isOpen(c.packageName, me)) return
        val pkg = mapOf(c.packageName to setOf(me))
        Agent.inst.redefineModule(m, emptySet(), pkg, pkg, emptySet(), emptyMap())
    }
}
