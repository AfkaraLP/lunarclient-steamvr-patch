package lcvr;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.util.Arrays;

import javax.imageio.ImageIO;

/**
 * Creating debug/request (next to dist/) makes the agent write what it is sending to the
 * headset: both eye images, the HUD texture, the desktop mirror and the tracking state.
 */
final class Debug {
    private Debug() {}

    static void maybeDump(Object mc, Object mainFb, boolean hadEyes) {
        File dir = new File(Agent.home.getParentFile(), "debug");
        File request = new File(dir, "request");
        if (!request.exists()) return;
        request.delete();
        try {
            for (int e = 0; e < 2; e++) save(Hooks.fFbTex.getInt(Hooks.eyeFb[e]), Hooks.eyeW, Hooks.eyeH, new File(dir, "eye" + e + ".png"), 4);
            int[] hud = Native.hudSize();
            save(0, hud[0], hud[1], new File(dir, "hud.png"), 1);
            save(Hooks.fFbTex.getInt(mainFb), Hooks.fFbW.getInt(mainFb), Hooks.fFbH.getInt(mainFb), new File(dir, "mirror.png"), 1);

            float[] proj = new float[16];
            try (PrintWriter w = new PrintWriter(new File(dir, "state.txt"))) {
                w.println("eyesRenderedThisFrame=" + hadEyes + " poseValid=" + Hooks.poseValid + " eye=" + Hooks.eyeW + "x" + Hooks.eyeH);
                w.println("pose3x4=" + Arrays.toString(Hooks.pose));
                w.println("headYaw=" + Hooks.headYaw + " headRoll=" + Hooks.headRoll
                        + " rel=" + Hooks.relX + "," + Hooks.relY + "," + Hooks.relZ);
                Object p = Hooks.fPlayer.get(mc);
                if (p != null) w.println("playerYaw=" + Hooks.fYaw.getFloat(p) + " playerPitch=" + Hooks.fPitch.getFloat(p));
                for (int e = 0; e < 2; e++) {
                    Native.projection(e, 0.05f, 256f, proj);
                    w.println("proj" + e + "(col-major)=" + Arrays.toString(proj));
                }
                w.println("mainFb fbo=" + Hooks.fFbo.getInt(mainFb) + " " + Hooks.fFbW.getInt(mainFb) + "x" + Hooks.fFbH.getInt(mainFb)
                        + " display=" + Hooks.fDisplayW.getInt(mc) + "x" + Hooks.fDisplayH.getInt(mc));
                w.println("eyeFbo=" + Hooks.fFbo.getInt(Hooks.eyeFb[0]) + "," + Hooks.fFbo.getInt(Hooks.eyeFb[1]));
            }
            Hooks.log("debug dump written to " + dir);
        } catch (Throwable t) {
            Hooks.log("debug dump failed: " + t);
        }
    }

    /** Reads a GL texture and writes it as PNG, flipped to top-down and downsampled by `step`. */
    static void save(int tex, int w, int h, File f, int step) throws Exception {
        if (w <= 0 || h <= 0) return;
        byte[] px = new byte[w * h * 4];
        Native.readTexture(tex, w, h, px);
        int ow = w / step, oh = h / step;
        BufferedImage img = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < oh; y++) {
            int row = (h - 1 - y * step) * w;
            for (int x = 0; x < ow; x++) {
                int i = (row + x * step) * 4;
                int a = px[i + 3] & 255;
                // keep transparent HUD pixels visible against a checker-free dark grey
                if (a == 0 && tex == 0) { img.setRGB(x, y, 0xFF202020); continue; }
                img.setRGB(x, y, 0xFF000000 | (px[i] & 255) << 16 | (px[i + 1] & 255) << 8 | (px[i + 2] & 255));
            }
        }
        ImageIO.write(img, "png", f);
    }
}
