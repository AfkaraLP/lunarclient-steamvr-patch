package lcvr

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.FloatBuffer

/**
 * Makes OptiFine shader packs (net.optifine.shaders.Shaders) behave per eye.
 *
 * OptiFine keeps one set of camera matrices and copies them to "previous" once per frame in
 * Shaders.beginRender. With two eye passes per frame, each eye would see the other eye's
 * matrices as last frame's, and motion blur / temporal reprojection would treat the eye
 * separation as camera movement. So each eye keeps its own previous-frame state, which is
 * restored before its pass and captured after it.
 *
 * Not handled: packs that accumulate history in color buffers (TAA, some temporal
 * reflections) still share those buffers between eyes. Turn TAA off in the pack settings.
 */
object OptiFineShaders {
    private var available = false
    private lateinit var fLoaded: Field
    private lateinit var fProj: Field
    private lateinit var fModelView: Field
    private lateinit var fPrevProj: Field
    private lateinit var fPrevModelView: Field
    private lateinit var fCam: Array<Field>
    private lateinit var fPrevCam: Array<Field>

    // per-frame state that Shaders.beginRender sets and Shaders.endRender (end of every pass) clears
    private lateinit var fRenderingWorld: Field
    private lateinit var fCompositeRendered: Field
    private lateinit var fShadowPass: Field
    private lateinit var fHandFlags: Array<Field>
    private lateinit var fDfb: Field
    private lateinit var fUsedColorBuffers: Field
    private lateinit var fColorFlip: Field
    private lateinit var mBindGbuffers: Method
    private lateinit var mFlipGetA: Method
    private val colorTextures = IntArray(16)

    private val lastProj = Array(2) { FloatArray(16) }
    private val lastModelView = Array(2) { FloatArray(16) }
    private val lastCam = Array(2) { DoubleArray(3) }
    private val haveLast = BooleanArray(2)

    fun init(loader: ClassLoader?) {
        try {
            val c = Class.forName("net.optifine.shaders.Shaders", false, loader)
            Hooks.open(c)
            fun f(name: String) = Hooks.field(c, name)
            fLoaded = f("shaderPackLoaded")
            fProj = f("projection")
            fModelView = f("modelView")
            fPrevProj = f("previousProjection")
            fPrevModelView = f("previousModelView")
            fCam = arrayOf(f("cameraPositionX"), f("cameraPositionY"), f("cameraPositionZ"))
            fPrevCam = arrayOf(f("previousCameraPositionX"), f("previousCameraPositionY"), f("previousCameraPositionZ"))
            fRenderingWorld = f("isRenderingWorld")
            fCompositeRendered = f("isCompositeRendered")
            fShadowPass = f("isShadowPass")
            fHandFlags = arrayOf(f("isHandRenderedMain"), f("isHandRenderedOff"), f("skipRenderHandMain"), f("skipRenderHandOff"))
            fDfb = f("dfb")
            fUsedColorBuffers = f("usedColorBuffers")
            fColorFlip = f("dfbColorTexturesFlip")
            mBindGbuffers = c.getDeclaredMethod("bindGbuffersTextures").apply { isAccessible = true }
            val flip = fColorFlip.type
            Hooks.open(flip)
            mFlipGetA = flip.getMethod("getA", Integer.TYPE)
            available = true
            Hooks.log("OptiFine shaders found; per-eye shader state enabled")
        } catch (e: ClassNotFoundException) {
            available = false
        } catch (t: Throwable) {
            available = false
            Hooks.log("OptiFine shaders present but not usable for VR ($t); shader packs may render incorrectly")
        }
    }

    fun active(): Boolean = try {
        available && fLoaded.getBoolean(null)
    } catch (e: IllegalAccessException) {
        false
    }

    /**
     * Before a second (or later) eye pass: redo what Shaders.beginRender sets up once per frame,
     * because Shaders.endRender at the end of the previous eye's pass cleared it. Without this
     * the second eye renders with no shader programs and no composite (no shadows, AO, waving).
     * The shadow map from beginRender is reused, so it is rendered once per frame for both eyes.
     */
    fun rearm() {
        fRenderingWorld.setBoolean(null, true)
        fCompositeRendered.setBoolean(null, false)
        fShadowPass.setBoolean(null, false)
        for (f in fHandFlags) f.setBoolean(null, false)
        mBindGbuffers.invoke(null)
        val flip = fColorFlip.get(null)
        val n = minOf(fUsedColorBuffers.getInt(null), colorTextures.size)
        for (i in 0 until n) colorTextures[i] = mFlipGetA.invoke(flip, i) as Int
        Native.attachColorTextures(fDfb.getInt(null), colorTextures, n)
    }

    /** Before eye e's pass: make "previous frame" mean this eye's previous frame. */
    fun beginEye(e: Int) {
        if (!haveLast[e]) return
        write(fPrevProj.get(null) as FloatBuffer, lastProj[e])
        write(fPrevModelView.get(null) as FloatBuffer, lastModelView[e])
        for (i in 0..2) fPrevCam[i].setDouble(null, lastCam[e][i])
    }

    /** After eye e's pass: remember the matrices it rendered with. */
    fun endEye(e: Int) {
        read(fProj.get(null) as FloatBuffer, lastProj[e])
        read(fModelView.get(null) as FloatBuffer, lastModelView[e])
        for (i in 0..2) lastCam[e][i] = fCam[i].getDouble(null)
        haveLast[e] = true
    }

    fun reset() {
        haveLast.fill(false)
    }

    // absolute get/put so OptiFine's own buffer positions are left alone
    private fun write(b: FloatBuffer, v: FloatArray) {
        for (i in 0 until 16) b.put(i, v[i])
    }

    private fun read(b: FloatBuffer, v: FloatArray) {
        for (i in 0 until 16) v[i] = b.get(i)
    }
}
