package lcvr;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Called from the patched EntityRenderer through MethodHandles. Game objects are reached by
 * reflection because they live in Lunar's "Genesis" module/class loader, not ours.
 *
 * Frame flow (render thread):
 * <ol>
 * <li>framePre: WaitGetPoses, then add the head yaw delta to the player's yaw and take pitch
 *     from the head, so aiming, movement and the crosshair follow the headset; the mouse
 *     still turns you left and right.</li>
 * <li>renderWorldPass: run the world pass once per eye into per-eye framebuffers, with the
 *     headset projection, head roll and head position applied.</li>
 * <li>Vanilla then draws the GUI into the (now cleared, transparent) main framebuffer.</li>
 * <li>framePost: copy the GUI into a texture, draw the left eye under it for the desktop
 *     mirror, draw the GUI into both eyes as a head-locked quad and submit them.</li>
 * </ol>
 */
public final class Hooks {
    // ---- config (agent args) ----
    static float hudDist = 1.6f;
    static float hudWidth = 1.5f;
    static float scale = 0.75f;
    static int recenterKey = 199; // LWJGL Keyboard.KEY_HOME
    static boolean mirror = true;
    static boolean positional = true;

    // ---- state ----
    static boolean nativeOk;
    static boolean reflectOk;
    static boolean tried;
    static boolean active;
    static boolean dead;
    static boolean inEye;
    static int eye;
    static boolean eyesRendered;

    static final float[] pose = new float[12];
    static final float[] matrix = new float[16];
    static boolean poseValid;
    static boolean needZero = true;
    static double yaw0;
    static float px0, py0, pz0;
    static float headYaw, headRoll; // Minecraft convention, relative to zero
    static final double MAX_OFFSET = 0.8;
    static float headPitch;
    static float lastYaw;
    static long poseCount;
    static float relX, relY, relZ; // head position in the zeroed tracking frame (meters)
    static boolean keyWasDown;
    static boolean worldSwapped;
    static int worldW, worldH;
    static long frames;

    static final Object[] eyeFb = new Object[2];
    static int eyeW, eyeH;

    // ---- reflection ----
    static Field fMc, fFramebuffer, fDisplayW, fDisplayH, fPlayer, fScreen, fSettings;
    static Field fYaw, fPitch, fPrevYaw, fPrevPitch;
    static Field fFbo, fFbTex, fFbW, fFbH;
    static Method mOrigPass, mBind, mKeyDown, mMouseX, mMouseY, mProject;
    static Constructor<?> cFramebuffer;

    private Hooks() {}

    static void log(String s) {
        System.out.println("[lcvr] " + s);
    }

    static void configure(String args) {
        if (args == null || args.isEmpty()) return;
        for (String kv : args.split(",")) {
            String[] p = kv.split("=", 2);
            if (p.length != 2) continue;
            String k = p[0].trim(), v = p[1].trim();
            try {
                switch (k) {
                    case "hudDist": hudDist = Float.parseFloat(v); break;
                    case "hudWidth": hudWidth = Float.parseFloat(v); break;
                    case "scale": scale = Float.parseFloat(v); break;
                    case "recenterKey": recenterKey = Integer.parseInt(v); break;
                    case "mirror": mirror = Boolean.parseBoolean(v); break;
                    case "positional": positional = Boolean.parseBoolean(v); break;
                    default: log("unknown option " + k);
                }
            } catch (NumberFormatException e) {
                log("bad value for " + k + ": " + v);
            }
        }
    }

    static void loadNative(File lib) {
        try {
            System.load(lib.getAbsolutePath());
            nativeOk = true;
        } catch (Throwable t) {
            log("could not load " + lib + ": " + t + " — VR disabled");
        }
    }

    static MethodHandle[] handles() {
        MethodHandles.Lookup l = MethodHandles.lookup();
        try {
            MethodHandle[] h = new MethodHandle[8];
            h[Patcher.H_FRAME_PRE] = l.findStatic(Hooks.class, "framePre", MethodType.methodType(void.class, Object.class, float.class, long.class));
            h[Patcher.H_FRAME_POST] = l.findStatic(Hooks.class, "framePost", MethodType.methodType(void.class, Object.class));
            h[Patcher.H_PASS] = l.findStatic(Hooks.class, "renderWorldPass", MethodType.methodType(void.class, Object.class, int.class, float.class, long.class));
            h[Patcher.H_ORIENT_PRE] = l.findStatic(Hooks.class, "orientPre", MethodType.methodType(void.class, Object.class, float.class));
            h[Patcher.H_ORIENT_POST] = l.findStatic(Hooks.class, "orientPost", MethodType.methodType(void.class, Object.class, float.class));
            h[Patcher.H_PERSPECTIVE] = l.findStatic(Hooks.class, "perspective", MethodType.methodType(void.class, float.class, float.class, float.class, float.class));
            h[Patcher.H_WORLD_PRE] = l.findStatic(Hooks.class, "worldPre", MethodType.methodType(void.class, Object.class));
            h[Patcher.H_WORLD_POST] = l.findStatic(Hooks.class, "worldPost", MethodType.methodType(void.class, Object.class));
            return h;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------
    // hooks

    public static void framePre(Object er, float partialTicks, long nanoTime) {
        if (dead) return;
        try {
            Timing.frameStart();
            if (!reflectOk) initReflection(er.getClass());
            boolean key = keyDown(recenterKey);
            boolean pressed = key && !keyWasDown;
            keyWasDown = key;

            if (!active) {
                if (!tried || pressed) start(er);
                if (!active) return;
            } else if (pressed) {
                needZero = true;
                log("recentered");
            }

            int r = Native.waitGetPoses(pose);
            Timing.afterWait();
            if (r < 0) {
                log("SteamVR session ended; press the recenter key to reconnect");
                stopActive();
                return;
            }
            poseValid = r > 0;
            if (r == 2) {
                needZero = true;
                log("headset put on; recentered");
            }
            if (poseValid) updateHead(fMc.get(er));
        } catch (Throwable t) {
            fail(t);
        }
    }

    public static void framePost(Object er) {
        if (!active || dead) return;
        try {
            Object mc = fMc.get(er);
            ensureEyes();
            Object fb = fFramebuffer.get(mc);
            int fbo = fFbo.getInt(fb), w = fFbW.getInt(fb), h = fFbH.getInt(fb);
            boolean cursor = fScreen.get(mc) != null;
            Native.hudCapture(fbo, w, h, cursor, (Integer) mMouseX.invoke(null), (Integer) mMouseY.invoke(null));
            if (eyesRendered) {
                if (mirror) Native.mirrorUnder(fbo, w, h, fFbTex.getInt(eyeFb[0]), eyeW, eyeH);
            } else {
                // menus / loading screens: only the HUD is visible
                for (Object e : eyeFb) Native.clearFbo(fFbo.getInt(e), eyeW, eyeH);
            }
            for (int e = 0; e < 2; e++) {
                Native.projection(e, 0.05f, 100f, matrix);
                Native.drawHud(fFbo.getInt(eyeFb[e]), eyeW, eyeH, matrix, hudWidth, hudDist);
            }
            lockPitch(fPlayer.get(mc));
            boolean hadEyes = eyesRendered;
            eyesRendered = false;
            Native.submit(fFbTex.getInt(eyeFb[0]), fFbTex.getInt(eyeFb[1]));
            Timing.frameEnd();
            if (++frames % 30 == 0) {
                drainNativeLog();
                Debug.maybeDump(mc, fb, hadEyes);
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /**
     * With an OptiFine shader pack loaded, report the eye size for the whole of renderWorld:
     * Shaders.beginRender runs there, before the eye passes, and sizes its buffers from
     * mc.displayWidth/displayHeight.
     */
    public static void worldPre(Object er) {
        if (!active || dead || !OptiFineShaders.active()) return;
        try {
            Object mc = fMc.get(er);
            ensureEyes();
            worldW = fDisplayW.getInt(mc);
            worldH = fDisplayH.getInt(mc);
            fDisplayW.setInt(mc, eyeW);
            fDisplayH.setInt(mc, eyeH);
            worldSwapped = true;
        } catch (Throwable t) {
            fail(t);
        }
    }

    public static void worldPost(Object er) {
        if (!worldSwapped) return;
        worldSwapped = false;
        try {
            Object mc = fMc.get(er);
            fDisplayW.setInt(mc, worldW);
            fDisplayH.setInt(mc, worldH);
        } catch (Throwable t) {
            fail(t);
        }
    }

    public static void renderWorldPass(Object er, int pass, float partialTicks, long finishTimeNano) {
        if (!active || dead || inEye) {
            callOriginalPass(er, pass, partialTicks, finishTimeNano);
            return;
        }
        Object mc;
        Object mainFb;
        int dw, dh;
        try {
            mc = fMc.get(er);
            ensureEyes();
            mainFb = fFramebuffer.get(mc);
            dw = fDisplayW.getInt(mc);
            dh = fDisplayH.getInt(mc);
        } catch (Throwable t) {
            fail(t);
            callOriginalPass(er, pass, partialTicks, finishTimeNano);
            return;
        }
        try {
            lockPitch(fPlayer.get(mc));
        } catch (IllegalAccessException ignored) {
        }
        boolean shaders = OptiFineShaders.active();
        for (int e = 0; e < 2; e++) {
            try {
                fFramebuffer.set(mc, eyeFb[e]);
                fDisplayW.setInt(mc, eyeW);
                fDisplayH.setInt(mc, eyeH);
                mBind.invoke(eyeFb[e], true);
            } catch (Throwable t) {
                restore(mc, mainFb, dw, dh);
                fail(t);
                callOriginalPass(er, pass, partialTicks, finishTimeNano);
                return;
            }
            eye = e;
            inEye = true;
            try {
                if (shaders) {
                    if (e > 0) OptiFineShaders.rearm();
                    OptiFineShaders.beginEye(e);
                }
                callOriginalPass(er, pass, partialTicks, finishTimeNano);
            } catch (ReflectiveOperationException ex) {
                fail(ex);
            } finally {
                inEye = false;
                if (shaders) {
                    try {
                        OptiFineShaders.endEye(e);
                    } catch (IllegalAccessException ignored) {
                    }
                }
                restore(mc, mainFb, dw, dh);
            }
        }
        try {
            mBind.invoke(mainFb, true);
            // the GUI is drawn next; give it a transparent canvas so it can be captured for the HUD
            Native.clearFbo(fFbo.getInt(mainFb), fFbW.getInt(mainFb), fFbH.getInt(mainFb));
            eyesRendered = true;
        } catch (Throwable t) {
            fail(t);
        }
    }

    public static void orientPre(Object er, float partialTicks) {
        if (inEye && poseValid) Native.rotate(-headRoll, 0, 0, 1);
    }

    public static void orientPost(Object er, float partialTicks) {
        if (!inEye || !poseValid || !positional) return;
        try {
            Object player = fPlayer.get(fMc.get(er));
            if (player == null) return;
            float prev = fPrevYaw.getFloat(player), cur = fYaw.getFloat(player);
            double body = Math.toRadians(prev + (cur - prev) * partialTicks - headYaw);
            double rightX = -Math.cos(body), rightZ = -Math.sin(body);
            double fwdX = -Math.sin(body), fwdZ = Math.cos(body);
            double wx = rightX * relX + fwdX * -relZ;
            double wz = rightZ * relX + fwdZ * -relZ;
            Native.translate((float) -wx, -relY, (float) -wz);
        } catch (Throwable t) {
            fail(t);
        }
    }

    public static void perspective(float fovy, float aspect, float zNear, float zFar) {
        if (inEye && active) {
            Native.projection(eye, zNear, zFar, matrix);
            Native.multMatrix(matrix);
        } else if (nativeOk) {
            Native.perspective(fovy, aspect, zNear, zFar);
        } else {
            try {
                if (mProject == null) {
                    Class<?> c = Class.forName("org.lwjgl.util.glu.Project", true, Patcher.gameLoader);
                    open(c);
                    mProject = c.getMethod("gluPerspective", float.class, float.class, float.class, float.class);
                }
                mProject.invoke(null, fovy, aspect, zNear, zFar);
            } catch (InvocationTargetException e) {
                throw sneaky(e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("lcvr: cannot reach gluPerspective", e);
            }
        }
    }

    // ------------------------------------------------------------------------------------

    static void start(Object er) throws Exception {
        tried = true;
        if (!nativeOk) {
            dead = true;
            return;
        }
        String err = Native.init();
        if (err != null) {
            log("OpenVR not started: " + err + ". Press the recenter key (default Home) to retry.");
            return;
        }
        int[] size = Native.recommendedSize();
        eyeW = Math.max(64, Math.round(size[0] * scale));
        eyeH = Math.max(64, Math.round(size[1] * scale));
        tweakSettings(fMc.get(er));
        OptiFineShaders.reset();
        needZero = true;
        active = true;
        log("VR active, eye buffers " + eyeW + "x" + eyeH);
    }

    static void drainNativeLog() {
        String s = Native.drainLog();
        if (s == null) return;
        for (String line : s.split("\n")) if (!line.isEmpty()) log(line);
    }

    static void stopActive() {
        active = false;
        poseValid = false;
        inEye = false;
    }

    static void fail(Throwable t) {
        log("error, VR disabled for this session: " + t);
        t.printStackTrace();
        stopActive();
        dead = true;
    }

    static void restore(Object mc, Object mainFb, int dw, int dh) {
        try {
            fFramebuffer.set(mc, mainFb);
            fDisplayW.setInt(mc, dw);
            fDisplayH.setInt(mc, dh);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static void callOriginalPass(Object er, int pass, float pt, long nano) {
        try {
            mOrigPass.invoke(er, pass, pt, nano);
        } catch (InvocationTargetException e) {
            throw sneaky(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }

    static void ensureEyes() throws Exception {
        if (eyeFb[0] != null) return;
        for (int i = 0; i < 2; i++) eyeFb[i] = cFramebuffer.newInstance(eyeW, eyeH, true);
        if (fFbo.getInt(eyeFb[0]) <= 0) {
            log("warning: Minecraft framebuffers seem disabled (OptiFine Fast Render?). Turn Fast Render off.");
        }
    }

    /**
     * Head rotation as yaw/pitch/roll (Y-X-Z order, OpenVR axes: +X right, +Y up, -Z forward),
     * converted to Minecraft's convention (yaw grows turning right, pitch grows looking down).
     */
    static void updateHead(Object mc) throws Exception {
        float[] m = pose;
        double yaw = Math.atan2(m[2], m[10]);
        double pitch = Math.asin(Math.max(-1, Math.min(1, -m[6])));
        double roll = Math.atan2(m[4], m[5]);
        if (needZero) {
            yaw0 = yaw;
            px0 = m[3];
            py0 = m[7];
            pz0 = m[11];
        }
        float hy = (float) -Math.toDegrees(wrap(yaw - yaw0));
        float hp = (float) -Math.toDegrees(pitch);
        headRoll = (float) Math.toDegrees(roll);

        double dx = m[3] - px0, dy = m[7] - py0, dz = m[11] - pz0;
        if (dx * dx + dy * dy + dz * dz > MAX_OFFSET * MAX_OFFSET) {
            // zero was taken somewhere else (headset on a desk, user moved seats): re-anchor
            px0 = m[3];
            py0 = m[7];
            pz0 = m[11];
            dx = dy = dz = 0;
            log("head moved more than " + MAX_OFFSET + " m from the zero point; re-anchored position");
        }
        double c = Math.cos(yaw0), s = Math.sin(yaw0);
        relX = (float) (c * dx - s * dz);
        relY = (float) dy;
        relZ = (float) (s * dx + c * dz);

        Object player = fPlayer.get(mc);
        if (player != null && !needZero) {
            // yaw: the mouse turns the body, the head adds on top
            float dYaw = (float) Math.toDegrees(wrap(Math.toRadians(hy - lastYaw)));
            fYaw.setFloat(player, fYaw.getFloat(player) + dYaw);
            fPrevYaw.setFloat(player, fPrevYaw.getFloat(player) + dYaw);
        }
        headYaw = hy;
        headPitch = clamp(hp);
        lastYaw = hy;
        needZero = false;
        lockPitch(player);

        if (++poseCount % 450 == 0) {
            log(String.format("head yaw=%.1f pitch=%.1f roll=%.1f pos=(%.2f,%.2f,%.2f) player yaw=%.1f pitch=%.1f",
                    headYaw, headPitch, headRoll, relX, relY, relZ,
                    player == null ? Float.NaN : fYaw.getFloat(player), player == null ? Float.NaN : fPitch.getFloat(player)));
            log(Timing.report());
        }
    }

    /**
     * Pitch comes only from the headset: mouse pitch would tilt the camera away from where
     * the head points, which makes every head turn swing the world the wrong way. Applied
     * repeatedly per frame because the mouse handler runs after framePre.
     */
    static void lockPitch(Object player) throws IllegalAccessException {
        if (player == null || !poseValid) return;
        fPitch.setFloat(player, headPitch);
        fPrevPitch.setFloat(player, headPitch);
    }

    static double wrap(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    static float clamp(float p) {
        return Math.max(-90f, Math.min(90f, p));
    }

    static boolean keyDown(int key) {
        try {
            return (Boolean) mKeyDown.invoke(null, key);
        } catch (Exception e) {
            return false; // Keyboard not created yet
        }
    }

    /** Settings that are wrong for VR: vsync/fps caps fight WaitGetPoses, bobbing and anaglyph. */
    static void tweakSettings(Object mc) {
        try {
            Object gs = fSettings.get(mc);
            Class<?> c = gs.getClass();
            setIfPresent(c, gs, "anaglyph", false);
            setIfPresent(c, gs, "viewBobbing", false);
            setIfPresent(c, gs, "enableVsync", false);
            setIfPresent(c, gs, "limitFramerate", 260);
            setIfPresent(c, gs, "ofFastRender", false);
            // in the headset the desktop window is rarely focused; do not pause the game for it
            setIfPresent(c, gs, "pauseOnLostFocus", false);
            // the vignette multiplies the screen darker but writes full alpha, which turns the
            // transparent HUD canvas into a black sheet; lenses vignette on their own anyway
            setIfPresent(c, gs, "ofVignette", 1);
            Class<?> display = Class.forName("org.lwjgl.opengl.Display", true, mc.getClass().getClassLoader());
            open(display);
            display.getMethod("setVSyncEnabled", boolean.class).invoke(null, false);
        } catch (Throwable t) {
            log("could not adjust video settings: " + t);
        }
    }

    static void setIfPresent(Class<?> c, Object o, String name, Object v) {
        try {
            Field f = c.getField(name);
            f.setAccessible(true);
            f.set(o, v);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    // ------------------------------------------------------------------------------------
    // reflection setup

    static void initReflection(Class<?> erClass) throws Exception {
        ClassLoader l = erClass.getClassLoader();
        Class<?> mcC = Class.forName("net.minecraft.client.Minecraft", false, l);
        Class<?> entityC = Class.forName("net.minecraft.entity.Entity", false, l);
        Class<?> fbC = Class.forName("net.minecraft.client.shader.Framebuffer", false, l);
        Class<?> kbC = Class.forName("org.lwjgl.input.Keyboard", false, l);
        Class<?> mouseC = Class.forName("org.lwjgl.input.Mouse", false, l);
        for (Class<?> c : new Class<?>[] {erClass, mcC, entityC, fbC, kbC, mouseC}) open(c);

        fMc = field(erClass, "mc");
        mOrigPass = erClass.getDeclaredMethod(Patcher.ORIG_PASS, int.class, float.class, long.class);
        mOrigPass.setAccessible(true);

        fFramebuffer = field(mcC, "framebufferMc");
        fDisplayW = field(mcC, "displayWidth");
        fDisplayH = field(mcC, "displayHeight");
        fPlayer = field(mcC, "thePlayer");
        fScreen = field(mcC, "currentScreen");
        fSettings = field(mcC, "gameSettings");

        fYaw = field(entityC, "rotationYaw");
        fPitch = field(entityC, "rotationPitch");
        fPrevYaw = field(entityC, "prevRotationYaw");
        fPrevPitch = field(entityC, "prevRotationPitch");

        fFbo = field(fbC, "framebufferObject");
        fFbTex = field(fbC, "framebufferTexture");
        fFbW = field(fbC, "framebufferWidth");
        fFbH = field(fbC, "framebufferHeight");
        mBind = fbC.getMethod("bindFramebuffer", boolean.class);
        cFramebuffer = fbC.getConstructor(int.class, int.class, boolean.class);

        mKeyDown = kbC.getMethod("isKeyDown", int.class);
        mMouseX = mouseC.getMethod("getX");
        mMouseY = mouseC.getMethod("getY");
        OptiFineShaders.init(l);
        reflectOk = true;
    }

    static Field field(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(c.getName() + "." + name);
    }

    /** Opens the class's package to this agent if it lives in a named module (Lunar's Genesis layer). */
    static void open(Class<?> c) {
        Module m = c.getModule();
        if (!m.isNamed() || m.isOpen(c.getPackageName(), Hooks.class.getModule())) return;
        Map<String, Set<Module>> pkg = new HashMap<>();
        pkg.put(c.getPackageName(), Collections.singleton(Hooks.class.getModule()));
        Agent.inst.redefineModule(m, Collections.emptySet(), pkg, pkg, Collections.emptySet(), Collections.emptyMap());
    }
}
