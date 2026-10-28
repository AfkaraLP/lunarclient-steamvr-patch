package lcvr;

/** JNI entry points implemented in native/lcvr.cpp. All calls must be made on the render thread. */
final class Native {
    private Native() {}

    /** Returns null on success, otherwise a human-readable error. */
    static native String init();

    static native int[] recommendedSize();

    /** 1 = valid pose written to out (3x4 row-major), 2 = same and the headset was just put on, 0 = no pose, -1 = VR session gone. */
    static native int waitGetPoses(float[] out);

    static native void projection(int eye, float zNear, float zFar, float[] out16);

    static native void multMatrix(float[] m16);

    static native void perspective(float fovy, float aspect, float zNear, float zFar);

    static native void rotate(float angle, float x, float y, float z);

    static native void translate(float x, float y, float z);

    static native void clearFbo(int fbo, int w, int h);

    static native void hudCapture(int srcFbo, int w, int h, boolean cursor, int mx, int my);

    static native void mirrorUnder(int dstFbo, int w, int h, int eyeTex, int ew, int eh);

    /** Draws the captured GUI as a head-locked quad into an eye framebuffer. */
    static native void drawHud(int fbo, int w, int h, float[] proj16, float width, float dist);

    static native void submit(int texL, int texR);

    /** Debug readback of a texture into RGBA8 bottom-up rows; tex 0 reads the HUD texture. */
    static native void readTexture(int tex, int w, int h, byte[] out);

    static native int[] hudSize();

    /** Messages queued by the native side since the last call, or null. */
    static native String drainLog();

    /** SteamVR frame timing, see lcvr.cpp. False if unavailable. */
    static native boolean frameTiming(float[] out7);

    /** Binds fbo and attaches textures[0..n) as its color attachments, leaving it bound. */
    static native void attachColorTextures(int fbo, int[] textures, int n);
}
