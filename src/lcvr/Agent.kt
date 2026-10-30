package lcvr

import java.io.File
import java.lang.instrument.Instrumentation

/**
 * -javaagent entry point. Usage (Lunar: the 1.8 profile's JVM arguments, see launch-lunar-vr.sh):
 * ```
 * -javaagent:/path/to/lunar-vr/dist/lcvr.jar[=key=value,key=value]
 * ```
 * Keys: hudDist, hudWidth, scale, recenterKey, mirror, positional, foveation, foveaInner, foveaOuter.
 */
object Agent {
    lateinit var inst: Instrumentation
    lateinit var home: File

    /**
     * Where debug dumps go: -Dlcvr.debugDir if set, else `debug/` next to dist/ when that is
     * writable (a source checkout), else ~/.cache/lcvr/debug (e.g. installed in the Nix store).
     */
    val debugDir: File by lazy {
        System.getProperty("lcvr.debugDir")?.let(::File)
            ?: home.parentFile?.takeIf { it.canWrite() }?.let { File(it, "debug") }
            ?: File(cacheDir, "debug")
    }

    /** ~/.cache/lcvr (or under $XDG_CACHE_HOME): generated files such as the SteamVR action manifest. */
    val cacheDir: File by lazy {
        File(System.getenv("XDG_CACHE_HOME") ?: (System.getProperty("user.home") + "/.cache"), "lcvr")
    }

    /** Static (via @JvmStatic) so the JVM finds `lcvr.Agent.premain(String, Instrumentation)`. */
    @JvmStatic
    fun premain(args: String?, instrumentation: Instrumentation) {
        inst = instrumentation
        home = try {
            File(Agent::class.java.protectionDomain.codeSource.location.toURI()).parentFile
        } catch (e: Exception) {
            File(".")
        }
        Hooks.configure(args)
        Hooks.log("agent loaded from $home (${System.getProperty("java.version")})")

        // Patched game classes fetch these through System properties so they never have to
        // link against agent classes, which live in a different class loader and module.
        System.getProperties()[Patcher.HOOKS_KEY] = Hooks.handles()

        Hooks.loadNative(File(home, "liblcvr.so"))
        instrumentation.addTransformer(Patcher(), false)
    }
}
