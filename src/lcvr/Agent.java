package lcvr;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;

/**
 * -javaagent entry point. Usage (Lunar: the 1.8 profile's JVM arguments, see launch-lunar-vr.sh):
 * <pre>-javaagent:/path/to/lunar-vr/dist/lcvr.jar[=key=value,key=value]</pre>
 * Keys: hudDist, hudWidth, scale, recenterKey, mirror, positional.
 */
public final class Agent {
    static Instrumentation inst;
    static File home;

    private Agent() {}

    public static void premain(String args, Instrumentation instrumentation) {
        inst = instrumentation;
        try {
            home = new File(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile();
        } catch (Exception e) {
            home = new File(".");
        }
        Hooks.configure(args);
        Hooks.log("agent loaded from " + home + " (" + System.getProperty("java.version") + ")");

        // Patched game classes fetch these through System properties so they never have to
        // link against agent classes, which live in a different class loader and module.
        MethodHandle[] handles = Hooks.handles();
        System.getProperties().put(Patcher.HOOKS_KEY, handles);

        Hooks.loadNative(new File(home, "liblcvr.so"));
        instrumentation.addTransformer(new Patcher(), false);
    }
}
