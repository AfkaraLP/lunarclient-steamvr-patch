package lcvr

/**
 * JNI entry points implemented in native/lcvr.cpp. All calls must be made on the render thread.
 * `@JvmStatic external` keeps these static natives on class lcvr.Native, so the C symbols stay
 * `Java_lcvr_Native_*`.
 */
object Native {
    /** Returns null on success, otherwise a human-readable error. */
    @JvmStatic external fun init(): String?

    @JvmStatic external fun recommendedSize(): IntArray

    /** 1 = valid pose written to out (3x4 row-major), 2 = same and the headset was just put on, 0 = no pose, -1 = VR session gone. */
    @JvmStatic external fun waitGetPoses(out: FloatArray): Int

    @JvmStatic external fun projection(eye: Int, zNear: Float, zFar: Float, out16: FloatArray)

    @JvmStatic external fun multMatrix(m16: FloatArray)

    @JvmStatic external fun perspective(fovy: Float, aspect: Float, zNear: Float, zFar: Float)

    @JvmStatic external fun rotate(angle: Float, x: Float, y: Float, z: Float)

    @JvmStatic external fun translate(x: Float, y: Float, z: Float)

    @JvmStatic external fun clearFbo(fbo: Int, w: Int, h: Int)

    @JvmStatic external fun hudCapture(srcFbo: Int, w: Int, h: Int, cursor: Boolean, mx: Int, my: Int)

    @JvmStatic external fun mirrorUnder(dstFbo: Int, w: Int, h: Int, eyeTex: Int, ew: Int, eh: Int)

    /** Draws the captured GUI as a head-locked quad into an eye framebuffer. */
    @JvmStatic external fun drawHud(fbo: Int, w: Int, h: Int, proj16: FloatArray, width: Float, dist: Float)

    @JvmStatic external fun submit(texL: Int, texR: Int)

    /** Debug readback of a texture into RGBA8 bottom-up rows; tex 0 reads the HUD texture. */
    @JvmStatic external fun readTexture(tex: Int, w: Int, h: Int, out: ByteArray)

    @JvmStatic external fun hudSize(): IntArray

    /** Messages queued by the native side since the last call, or null. */
    @JvmStatic external fun drainLog(): String?

    /** SteamVR frame timing, see lcvr.cpp. False if unavailable. */
    @JvmStatic external fun frameTiming(out7: FloatArray): Boolean

    /** Binds fbo and attaches textures[0..n) as its color attachments, leaving it bound. */
    @JvmStatic external fun attachColorTextures(fbo: Int, textures: IntArray, n: Int)
}
