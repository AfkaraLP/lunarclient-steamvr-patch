package lcvr

/**
 * Where frame time goes, as smoothed averages:
 * - wait: blocked in WaitGetPoses (SteamVR pacing; large = we are ahead or a frame was missed)
 * - render: from the pose to Submit (both eye passes, GUI, HUD)
 * - outside: the rest of the game loop (ticks, desktop window blit, buffer swap). If this is
 *   large, the desktop window is throttling the game, e.g. because it is hidden or unfocused.
 */
object Timing {
    private const val SMOOTH = 0.05
    private var frameStart = 0L
    private var afterWait = 0L
    private var lastEnd = 0L
    private var waitMs = 0.0
    private var renderMs = 0.0
    private var outsideMs = 0.0
    private val vr = FloatArray(7)

    fun frameStart() {
        val now = System.nanoTime()
        if (lastEnd != 0L) outsideMs = ema(outsideMs, (now - lastEnd) / 1e6)
        frameStart = now
    }

    fun afterWait() {
        afterWait = System.nanoTime()
        waitMs = ema(waitMs, (afterWait - frameStart) / 1e6)
    }

    fun frameEnd() {
        lastEnd = System.nanoTime()
        if (afterWait != 0L) renderMs = ema(renderMs, (lastEnd - afterWait) / 1e6)
    }

    fun report(): String {
        var s = "frame: wait=%.1fms render=%.1fms outside=%.1fms".format(waitMs, renderMs, outsideMs)
        if (Native.frameTiming(vr)) {
            s += " | SteamVR: scene gpu=%.1fms interval=%.1fms after-submit gpu=%.1fms submit=%.1fms dropped=%d reprojection=0x%x"
                .format(vr[0], vr[1], vr[5], vr[6], vr[2].toInt(), vr[4].toInt())
        }
        return s
    }

    private fun ema(avg: Double, v: Double) = if (avg == 0.0) v else avg + (v - avg) * SMOOTH
}
