package lcvr

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Creating `request` in [Agent.debugDir] (`debug/` in a source checkout) makes the agent
 * write what it is sending to the headset: both eye images, the HUD texture, the desktop
 * mirror and the tracking state.
 */
object Debug {
    fun maybeDump(mc: Any, mainFb: Any, hadEyes: Boolean) {
        val dir = Agent.debugDir
        val request = File(dir, "request")
        if (!request.exists()) return
        request.delete()
        try {
            val h = Hooks
            for (e in 0..1) save(h.fFbTex.getInt(h.eyeFb[e]), h.eyeW, h.eyeH, File(dir, "eye$e.png"), 2)
            val hud = Native.hudSize()
            save(0, hud[0], hud[1], File(dir, "hud.png"), 1)
            save(0, hud[0], hud[1], File(dir, "hud_alpha.png"), 1, keepAlpha = true)
            save(h.fFbTex.getInt(mainFb), h.fFbW.getInt(mainFb), h.fFbH.getInt(mainFb), File(dir, "mirror.png"), 1)

            val proj = FloatArray(16)
            File(dir, "state.txt").printWriter().use { w ->
                w.println("eyesRenderedThisFrame=$hadEyes poseValid=${h.poseValid} eye=${h.eyeW}x${h.eyeH}")
                w.println("pose3x4=${h.pose.contentToString()}")
                w.println("headYaw=${h.headYaw} headRoll=${h.headRoll} rel=${h.relX},${h.relY},${h.relZ}")
                h.fPlayer.get(mc)?.let { p -> w.println("playerYaw=${h.fYaw.getFloat(p)} playerPitch=${h.fPitch.getFloat(p)}") }
                for (e in 0..1) {
                    Native.projection(e, 0.05f, 256f, proj)
                    w.println("proj$e(col-major)=${proj.contentToString()}")
                }
                w.println(
                    "mainFb fbo=${h.fFbo.getInt(mainFb)} ${h.fFbW.getInt(mainFb)}x${h.fFbH.getInt(mainFb)} " +
                        "display=${h.fDisplayW.getInt(mc)}x${h.fDisplayH.getInt(mc)}",
                )
                w.println("eyeFbo=${h.fFbo.getInt(h.eyeFb[0])},${h.fFbo.getInt(h.eyeFb[1])}")
                w.println(
                    "foveation=${h.foveation} on=${h.foveaOn} center(NDC l,r; NaN=lens)=${h.foveaCenter.contentToString()} " +
                        "lastGaze=${h.gaze.contentToString()}",
                )
            }
            Hooks.log("debug dump written to $dir")
        } catch (t: Throwable) {
            Hooks.log("debug dump failed: $t")
        }
    }

    /**
     * Reads a GL texture and writes it as PNG, flipped to top-down and downsampled by `step`.
     * keepAlpha writes the texture's real alpha (the HUD is premultiplied, so this shows how
     * opaque each GUI pixel ends up over the world).
     */
    private fun save(tex: Int, w: Int, h: Int, f: File, step: Int, keepAlpha: Boolean = false) {
        if (w <= 0 || h <= 0) return
        val px = ByteArray(w * h * 4)
        Native.readTexture(tex, w, h, px)
        val ow = w / step
        val oh = h / step
        val img = BufferedImage(ow, oh, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until oh) {
            val row = (h - 1 - y * step) * w
            for (x in 0 until ow) {
                val i = (row + x * step) * 4
                val a = px[i + 3].toInt() and 255
                if (keepAlpha) {
                    // un-premultiply so the PNG shows the GUI's colors at its real opacity
                    fun ch(c: Int) = if (a == 0) 0 else minOf(255, (px[i + c].toInt() and 255) * 255 / a)
                    img.setRGB(x, y, (a shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2))
                    continue
                }
                // transparent HUD pixels show as dark grey, anything with alpha shows its color
                if (a == 0 && tex == 0) {
                    img.setRGB(x, y, 0xFF202020.toInt())
                    continue
                }
                val r = px[i].toInt() and 255
                val g = px[i + 1].toInt() and 255
                val b = px[i + 2].toInt() and 255
                img.setRGB(x, y, (0xFF shl 24) or (r shl 16) or (g shl 8) or b)
            }
        }
        ImageIO.write(img, "png", f)
    }
}
