package lcvr;

/**
 * Where frame time goes, as smoothed averages:
 * <ul>
 * <li>wait: blocked in WaitGetPoses (SteamVR pacing; large = we are ahead or a frame was missed)</li>
 * <li>render: from the pose to Submit (both eye passes, GUI, HUD)</li>
 * <li>outside: the rest of the game loop (ticks, desktop window blit, buffer swap). If this is
 *     large, the desktop window is throttling the game, e.g. because it is hidden or unfocused.</li>
 * </ul>
 */
final class Timing {
    private Timing() {}

    private static final double SMOOTH = 0.05;
    private static long frameStart, afterWait, lastEnd;
    static double waitMs, renderMs, outsideMs;
    private static final float[] vr = new float[7];

    static void frameStart() {
        long now = System.nanoTime();
        if (lastEnd != 0) outsideMs = ema(outsideMs, (now - lastEnd) / 1e6);
        frameStart = now;
    }

    static void afterWait() {
        afterWait = System.nanoTime();
        waitMs = ema(waitMs, (afterWait - frameStart) / 1e6);
    }

    static void frameEnd() {
        lastEnd = System.nanoTime();
        if (afterWait != 0) renderMs = ema(renderMs, (lastEnd - afterWait) / 1e6);
    }

    static String report() {
        String s = String.format("frame: wait=%.1fms render=%.1fms outside=%.1fms", waitMs, renderMs, outsideMs);
        if (Native.frameTiming(vr)) {
            s += String.format(" | SteamVR: scene gpu=%.1fms interval=%.1fms after-submit gpu=%.1fms submit=%.1fms dropped=%d reprojection=0x%x",
                    vr[0], vr[1], vr[5], vr[6], (int) vr[2], (int) vr[4]);
        }
        return s;
    }

    private static double ema(double avg, double v) {
        return avg == 0 ? v : avg + (v - avg) * SMOOTH;
    }
}
