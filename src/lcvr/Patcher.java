package lcvr;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Patches net.minecraft.client.renderer.EntityRenderer (MCP names, which is what Lunar's
 * runtime remapper produces for 1.8.9):
 * <ul>
 * <li>updateCameraAndRender(FJ)V: hook at head (pose fetch) and before each return (submit)</li>
 * <li>renderWorldPass(IFJ)V: body moved to lcvr$renderWorldPass, the original name becomes a
 *     stub that lets the agent run it once per eye</li>
 * <li>renderWorld(FJ)V: hook at head and before each return, so OptiFine shaders (which
 *     size their buffers in Shaders.beginRender, called from here) see the eye size</li>
 * <li>orientCamera(F)V: hook at head (head roll) and before each return (head position)</li>
 * <li>every Project.gluPerspective call: routed through the agent so eye passes use the
 *     headset's per-eye projection</li>
 * </ul>
 * Only straight-line code is injected, so the existing stack map frames stay valid and no
 * frame recomputation (which would need to load game classes) is required.
 */
final class Patcher implements ClassFileTransformer {
    static final String HOOKS_KEY = "lcvr.hooks";
    static final String TARGET = "net/minecraft/client/renderer/EntityRenderer";
    static final String FIELD = "lcvr$hooks";
    static final String MH = "java/lang/invoke/MethodHandle";
    static final String MH_ARRAY = "[Ljava/lang/invoke/MethodHandle;";
    static final String ORIG_PASS = "lcvr$renderWorldPass";
    static final String PERSPECTIVE = "lcvr$perspective";

    // indices into the MethodHandle[] built by Hooks.handles()
    static final int H_FRAME_PRE = 0, H_FRAME_POST = 1, H_PASS = 2, H_ORIENT_PRE = 3, H_ORIENT_POST = 4, H_PERSPECTIVE = 5,
            H_WORLD_PRE = 6, H_WORLD_POST = 7;
    static final String D_FRAME_PRE = "(Ljava/lang/Object;FJ)V";
    static final String D_FRAME_POST = "(Ljava/lang/Object;)V";
    static final String D_WORLD = "(Ljava/lang/Object;)V";
    static final String D_PASS = "(Ljava/lang/Object;IFJ)V";
    static final String D_ORIENT = "(Ljava/lang/Object;F)V";
    static final String D_PERSPECTIVE = "(FFFF)V";

    static volatile ClassLoader gameLoader;

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
        if (!TARGET.equals(name)) return null;
        try {
            gameLoader = loader;
            byte[] out = patch(bytes);
            if (Boolean.getBoolean("lcvr.dump")) {
                try (FileOutputStream f = new FileOutputStream(new File(Agent.home, "EntityRenderer.patched.class"))) {
                    f.write(out);
                }
            }
            return out;
        } catch (Throwable t) {
            Hooks.log("failed to patch EntityRenderer, VR disabled: " + t);
            t.printStackTrace();
            return null;
        }
    }

    static byte[] patch(byte[] in) {
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        Adapter a = new Adapter(cw);
        cr.accept(a, 0);
        Hooks.log("patched EntityRenderer: updateCameraAndRender=" + a.frame + " renderWorldPass=" + a.pass
                 + " renderWorld=" + a.world + " orientCamera=" + a.orient + " gluPerspective calls=" + a.perspective);
        if (!a.frame || !a.pass || !a.orient || a.perspective == 0) {
            throw new IllegalStateException("EntityRenderer does not look like Minecraft 1.8.9 with MCP names");
        }
        return cw.toByteArray();
    }

    static void pushHook(MethodVisitor mv, int index) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, TARGET, FIELD, MH_ARRAY);
        mv.visitIntInsn(Opcodes.BIPUSH, index);
        mv.visitInsn(Opcodes.AALOAD);
    }

    static void invokeHook(MethodVisitor mv, String desc) {
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MH, "invokeExact", desc, false);
    }

    private static final class Adapter extends ClassVisitor {
        boolean frame, pass, orient, world, clinit;
        int perspective;
        int passAccess;
        String[] passExceptions;

        Adapter(ClassVisitor cv) {
            super(Opcodes.ASM9, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
            if (name.equals("renderWorldPass") && desc.equals("(IFJ)V")) {
                pass = true;
                passAccess = access;
                passExceptions = exc;
                int priv = (access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PRIVATE;
                return new Rewrite(super.visitMethod(priv, ORIG_PASS, desc, sig, exc));
            }
            MethodVisitor mv = new Rewrite(super.visitMethod(access, name, desc, sig, exc));
            if (name.equals("<clinit>")) {
                clinit = true;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        initHooks(mv);
                    }
                };
            }
            if (name.equals("updateCameraAndRender") && desc.equals("(FJ)V")) {
                frame = true;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        pushHook(mv, H_FRAME_PRE);
                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        mv.visitVarInsn(Opcodes.FLOAD, 1);
                        mv.visitVarInsn(Opcodes.LLOAD, 2);
                        invokeHook(mv, D_FRAME_PRE);
                    }

                    @Override
                    public void visitInsn(int op) {
                        if (op == Opcodes.RETURN) {
                            pushHook(mv, H_FRAME_POST);
                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                            invokeHook(mv, D_FRAME_POST);
                        }
                        super.visitInsn(op);
                    }
                };
            }
            if (name.equals("renderWorld") && desc.equals("(FJ)V")) {
                world = true;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        pushHook(mv, H_WORLD_PRE);
                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        invokeHook(mv, D_WORLD);
                    }

                    @Override
                    public void visitInsn(int op) {
                        if (op == Opcodes.RETURN) {
                            pushHook(mv, H_WORLD_POST);
                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                            invokeHook(mv, D_WORLD);
                        }
                        super.visitInsn(op);
                    }
                };
            }
            if (name.equals("orientCamera") && desc.equals("(F)V")) {
                orient = true;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        pushHook(mv, H_ORIENT_PRE);
                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        mv.visitVarInsn(Opcodes.FLOAD, 1);
                        invokeHook(mv, D_ORIENT);
                    }

                    @Override
                    public void visitInsn(int op) {
                        if (op == Opcodes.RETURN) {
                            pushHook(mv, H_ORIENT_POST);
                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                            mv.visitVarInsn(Opcodes.FLOAD, 1);
                            invokeHook(mv, D_ORIENT);
                        }
                        super.visitInsn(op);
                    }
                };
            }
            return mv;
        }

        @Override
        public void visitEnd() {
            cv.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, FIELD, MH_ARRAY, null, null).visitEnd();

            if (!clinit) {
                MethodVisitor mv = cv.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
                mv.visitCode();
                initHooks(mv);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
            }

            if (pass) {
                MethodVisitor mv = cv.visitMethod(passAccess, "renderWorldPass", "(IFJ)V", null, passExceptions);
                mv.visitCode();
                pushHook(mv, H_PASS);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                mv.visitVarInsn(Opcodes.FLOAD, 2);
                mv.visitVarInsn(Opcodes.LLOAD, 3);
                invokeHook(mv, D_PASS);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
            }

            MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    PERSPECTIVE, D_PERSPECTIVE, null, null);
            mv.visitCode();
            pushHook(mv, H_PERSPECTIVE);
            for (int i = 0; i < 4; i++) mv.visitVarInsn(Opcodes.FLOAD, i);
            invokeHook(mv, D_PERSPECTIVE);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();

            super.visitEnd();
        }

        private static void initHooks(MethodVisitor mv) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
            mv.visitLdcInsn(HOOKS_KEY);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Hashtable", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            mv.visitTypeInsn(Opcodes.CHECKCAST, MH_ARRAY);
            mv.visitFieldInsn(Opcodes.PUTSTATIC, TARGET, FIELD, MH_ARRAY);
        }

        /** Redirects Project.gluPerspective to the synthetic lcvr$perspective. */
        private final class Rewrite extends MethodVisitor {
            Rewrite(MethodVisitor mv) {
                super(Opcodes.ASM9, mv);
            }

            @Override
            public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                if (op == Opcodes.INVOKESTATIC && owner.equals("org/lwjgl/util/glu/Project")
                        && name.equals("gluPerspective") && desc.equals(D_PERSPECTIVE)) {
                    perspective++;
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, TARGET, PERSPECTIVE, desc, false);
                    return;
                }
                super.visitMethodInsn(op, owner, name, desc, itf);
            }
        }
    }
}
