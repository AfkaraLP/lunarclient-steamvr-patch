package lcvr;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;

/**
 * Makes OptiFine shader packs (net.optifine.shaders.Shaders) behave per eye.
 *
 * OptiFine keeps one set of camera matrices and copies them to "previous" once per frame in
 * Shaders.beginRender. With two eye passes per frame, each eye would see the other eye's
 * matrices as last frame's, and motion blur / temporal reprojection would treat the
 * eye separation as camera movement. So each eye keeps its own previous-frame state, which
 * is restored before its pass and captured after it.
 *
 * Not handled: packs that accumulate history in color buffers (TAA, some temporal
 * reflections) still share those buffers between eyes. Turn TAA off in the pack settings.
 */
final class OptiFineShaders {
    private OptiFineShaders() {}

    static boolean available;
    static Field fLoaded, fProj, fModelView, fPrevProj, fPrevModelView;
    static Field fCamX, fCamY, fCamZ, fPrevCamX, fPrevCamY, fPrevCamZ;
    // per-frame state that Shaders.beginRender sets and Shaders.endRender (end of every pass) clears
    static Field fRenderingWorld, fCompositeRendered, fShadowPass;
    static Field[] fHandFlags;
    static Field fDfb, fUsedColorBuffers, fColorFlip;
    static Method mBindGbuffers, mFlipGetA;
    private static final int[] colorTextures = new int[16];

    private static final float[][] lastProj = new float[2][16];
    private static final float[][] lastModelView = new float[2][16];
    private static final double[][] lastCam = new double[2][3];
    private static final boolean[] haveLast = new boolean[2];

    static void init(ClassLoader loader) {
        try {
            Class<?> c = Class.forName("net.optifine.shaders.Shaders", false, loader);
            Hooks.open(c);
            fLoaded = Hooks.field(c, "shaderPackLoaded");
            fProj = Hooks.field(c, "projection");
            fModelView = Hooks.field(c, "modelView");
            fPrevProj = Hooks.field(c, "previousProjection");
            fPrevModelView = Hooks.field(c, "previousModelView");
            fCamX = Hooks.field(c, "cameraPositionX");
            fCamY = Hooks.field(c, "cameraPositionY");
            fCamZ = Hooks.field(c, "cameraPositionZ");
            fPrevCamX = Hooks.field(c, "previousCameraPositionX");
            fPrevCamY = Hooks.field(c, "previousCameraPositionY");
            fPrevCamZ = Hooks.field(c, "previousCameraPositionZ");
            fRenderingWorld = Hooks.field(c, "isRenderingWorld");
            fCompositeRendered = Hooks.field(c, "isCompositeRendered");
            fShadowPass = Hooks.field(c, "isShadowPass");
            fHandFlags = new Field[] {Hooks.field(c, "isHandRenderedMain"), Hooks.field(c, "isHandRenderedOff"),
                    Hooks.field(c, "skipRenderHandMain"), Hooks.field(c, "skipRenderHandOff")};
            fDfb = Hooks.field(c, "dfb");
            fUsedColorBuffers = Hooks.field(c, "usedColorBuffers");
            fColorFlip = Hooks.field(c, "dfbColorTexturesFlip");
            mBindGbuffers = c.getDeclaredMethod("bindGbuffersTextures");
            mBindGbuffers.setAccessible(true);
            Class<?> flip = fColorFlip.getType();
            Hooks.open(flip);
            mFlipGetA = flip.getMethod("getA", int.class);
            available = true;
            Hooks.log("OptiFine shaders found; per-eye shader state enabled");
        } catch (ClassNotFoundException e) {
            available = false;
        } catch (Throwable t) {
            available = false;
            Hooks.log("OptiFine shaders present but not usable for VR (" + t + "); shader packs may render incorrectly");
        }
    }

    static boolean active() {
        try {
            return available && fLoaded.getBoolean(null);
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    /**
     * Before a second (or later) eye pass: redo what Shaders.beginRender sets up once per frame,
     * because Shaders.endRender at the end of the previous eye's pass cleared it. Without this
     * the second eye renders with no shader programs and no composite (no shadows, AO, waving).
     * The shadow map from beginRender is reused, so it is rendered once per frame for both eyes.
     */
    static void rearm() throws ReflectiveOperationException {
        fRenderingWorld.setBoolean(null, true);
        fCompositeRendered.setBoolean(null, false);
        fShadowPass.setBoolean(null, false);
        for (Field f : fHandFlags) f.setBoolean(null, false);
        mBindGbuffers.invoke(null);
        Object flip = fColorFlip.get(null);
        int n = Math.min(fUsedColorBuffers.getInt(null), colorTextures.length);
        for (int i = 0; i < n; i++) colorTextures[i] = (Integer) mFlipGetA.invoke(flip, i);
        Native.attachColorTextures(fDfb.getInt(null), colorTextures, n);
    }

    /** Before eye e's pass: make "previous frame" mean this eye's previous frame. */
    static void beginEye(int e) throws IllegalAccessException {
        if (!haveLast[e]) return;
        write((FloatBuffer) fPrevProj.get(null), lastProj[e]);
        write((FloatBuffer) fPrevModelView.get(null), lastModelView[e]);
        fPrevCamX.setDouble(null, lastCam[e][0]);
        fPrevCamY.setDouble(null, lastCam[e][1]);
        fPrevCamZ.setDouble(null, lastCam[e][2]);
    }

    /** After eye e's pass: remember the matrices it rendered with. */
    static void endEye(int e) throws IllegalAccessException {
        read((FloatBuffer) fProj.get(null), lastProj[e]);
        read((FloatBuffer) fModelView.get(null), lastModelView[e]);
        lastCam[e][0] = fCamX.getDouble(null);
        lastCam[e][1] = fCamY.getDouble(null);
        lastCam[e][2] = fCamZ.getDouble(null);
        haveLast[e] = true;
    }

    static void reset() {
        haveLast[0] = haveLast[1] = false;
    }

    // absolute get/put so OptiFine's own buffer positions are left alone
    private static void write(FloatBuffer b, float[] v) {
        for (int i = 0; i < 16; i++) b.put(i, v[i]);
    }

    private static void read(FloatBuffer b, float[] v) {
        for (int i = 0; i < 16; i++) v[i] = b.get(i);
    }
}
