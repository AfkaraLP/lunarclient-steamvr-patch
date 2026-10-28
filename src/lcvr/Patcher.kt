package lcvr

import java.io.File
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Patches net.minecraft.client.renderer.EntityRenderer (MCP names, which is what Lunar's
 * runtime remapper produces for 1.8.9):
 * - updateCameraAndRender(FJ)V: hook at head (pose fetch) and before each return (submit)
 * - renderWorldPass(IFJ)V: body moved to lcvr$renderWorldPass, the original name becomes a
 *   stub that lets the agent run it once per eye
 * - renderWorld(FJ)V: hook at head and before each return, so OptiFine shaders (which size
 *   their buffers in Shaders.beginRender, called from here) see the eye size
 * - orientCamera(F)V: hook at head (head roll) and before each return (head position)
 * - every Project.gluPerspective call: routed through the agent so eye passes use the
 *   headset's per-eye projection
 *
 * Only straight-line code is injected, so the existing stack map frames stay valid and no
 * frame recomputation (which would need to load game classes) is required.
 */
class Patcher : ClassFileTransformer {
    override fun transform(
        loader: ClassLoader?, name: String?, redefined: Class<*>?, pd: ProtectionDomain?, bytes: ByteArray,
    ): ByteArray? {
        if (name != TARGET) return null
        return try {
            gameLoader = loader
            patch(bytes).also { out ->
                if (java.lang.Boolean.getBoolean("lcvr.dump")) File(Agent.debugDir.apply { mkdirs() }, "EntityRenderer.patched.class").writeBytes(out)
            }
        } catch (t: Throwable) {
            Hooks.log("failed to patch EntityRenderer, VR disabled: $t")
            t.printStackTrace()
            null
        }
    }

    companion object {
        const val HOOKS_KEY = "lcvr.hooks"
        const val TARGET = "net/minecraft/client/renderer/EntityRenderer"
        const val FIELD = "lcvr\$hooks"
        const val MH = "java/lang/invoke/MethodHandle"
        const val MH_ARRAY = "[Ljava/lang/invoke/MethodHandle;"
        const val ORIG_PASS = "lcvr\$renderWorldPass"
        const val PERSPECTIVE = "lcvr\$perspective"

        // indices into the MethodHandle[] built by Hooks.handles()
        const val H_FRAME_PRE = 0
        const val H_FRAME_POST = 1
        const val H_PASS = 2
        const val H_ORIENT_PRE = 3
        const val H_ORIENT_POST = 4
        const val H_PERSPECTIVE = 5
        const val H_WORLD_PRE = 6
        const val H_WORLD_POST = 7
        const val HOOK_COUNT = 8

        const val D_FRAME_PRE = "(Ljava/lang/Object;FJ)V"
        const val D_THIS = "(Ljava/lang/Object;)V"
        const val D_PASS = "(Ljava/lang/Object;IFJ)V"
        const val D_ORIENT = "(Ljava/lang/Object;F)V"
        const val D_PERSPECTIVE = "(FFFF)V"

        @Volatile
        @JvmStatic
        var gameLoader: ClassLoader? = null

        @JvmStatic
        fun patch(input: ByteArray): ByteArray {
            val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
            val a = Adapter(cw)
            ClassReader(input).accept(a, 0)
            Hooks.log(
                "patched EntityRenderer: updateCameraAndRender=${a.frame} renderWorldPass=${a.pass} " +
                    "renderWorld=${a.world} orientCamera=${a.orient} gluPerspective calls=${a.perspective}",
            )
            check(a.frame && a.pass && a.orient && a.perspective > 0) {
                "EntityRenderer does not look like Minecraft 1.8.9 with MCP names"
            }
            return cw.toByteArray()
        }

        /** Emits `lcvr$hooks[index]` (a MethodHandle) onto the stack. */
        private fun MethodVisitor.pushHook(index: Int) {
            visitFieldInsn(Opcodes.GETSTATIC, TARGET, FIELD, MH_ARRAY)
            visitIntInsn(Opcodes.BIPUSH, index)
            visitInsn(Opcodes.AALOAD)
        }

        private fun MethodVisitor.invokeHook(desc: String) =
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, MH, "invokeExact", desc, false)

        /** `lcvr$hooks = (MethodHandle[]) System.getProperties().get(HOOKS_KEY)` */
        private fun MethodVisitor.initHooks() {
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false)
            visitLdcInsn(HOOKS_KEY)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Hashtable", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false)
            visitTypeInsn(Opcodes.CHECKCAST, MH_ARRAY)
            visitFieldInsn(Opcodes.PUTSTATIC, TARGET, FIELD, MH_ARRAY)
        }

        /** Hook call that passes `this` plus the given (opcode, local slot) arguments. */
        private class HookCall(val index: Int, val desc: String, vararg val args: Pair<Int, Int>) {
            fun emit(mv: MethodVisitor) {
                mv.pushHook(index)
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                for ((op, slot) in args) mv.visitVarInsn(op, slot)
                mv.invokeHook(desc)
            }
        }

        /** Calls `head` at method entry and `beforeReturn` before every RETURN. */
        private class HeadAndReturn(next: MethodVisitor, val head: HookCall, val beforeReturn: HookCall) :
            MethodVisitor(Opcodes.ASM9, next) {
            override fun visitCode() {
                super.visitCode()
                head.emit(mv)
            }

            override fun visitInsn(op: Int) {
                if (op == Opcodes.RETURN) beforeReturn.emit(mv)
                super.visitInsn(op)
            }
        }

        private class Adapter(cv: ClassVisitor) : ClassVisitor(Opcodes.ASM9, cv) {
            var frame = false
            var pass = false
            var orient = false
            var world = false
            var clinit = false
            var perspective = 0
            var passAccess = 0
            var passExceptions: Array<String>? = null

            override fun visitMethod(access: Int, name: String, desc: String, sig: String?, exc: Array<String>?): MethodVisitor {
                if (name == "renderWorldPass" && desc == "(IFJ)V") {
                    pass = true
                    passAccess = access
                    passExceptions = exc
                    val priv = (access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED).inv()) or Opcodes.ACC_PRIVATE
                    return Rewrite(super.visitMethod(priv, ORIG_PASS, desc, sig, exc))
                }
                val mv: MethodVisitor = Rewrite(super.visitMethod(access, name, desc, sig, exc))
                return when {
                    name == "<clinit>" -> {
                        clinit = true
                        object : MethodVisitor(Opcodes.ASM9, mv) {
                            override fun visitCode() {
                                super.visitCode()
                                this.mv.initHooks()
                            }
                        }
                    }
                    name == "updateCameraAndRender" && desc == "(FJ)V" -> {
                        frame = true
                        HeadAndReturn(
                            mv,
                            HookCall(H_FRAME_PRE, D_FRAME_PRE, Opcodes.FLOAD to 1, Opcodes.LLOAD to 2),
                            HookCall(H_FRAME_POST, D_THIS),
                        )
                    }
                    name == "renderWorld" && desc == "(FJ)V" -> {
                        world = true
                        HeadAndReturn(mv, HookCall(H_WORLD_PRE, D_THIS), HookCall(H_WORLD_POST, D_THIS))
                    }
                    name == "orientCamera" && desc == "(F)V" -> {
                        orient = true
                        HeadAndReturn(
                            mv,
                            HookCall(H_ORIENT_PRE, D_ORIENT, Opcodes.FLOAD to 1),
                            HookCall(H_ORIENT_POST, D_ORIENT, Opcodes.FLOAD to 1),
                        )
                    }
                    else -> mv
                }
            }

            override fun visitEnd() {
                cv.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC, FIELD, MH_ARRAY, null, null).visitEnd()

                if (!clinit) {
                    cv.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
                        visitCode()
                        initHooks()
                        visitInsn(Opcodes.RETURN)
                        visitMaxs(0, 0)
                        visitEnd()
                    }
                }

                if (pass) {
                    cv.visitMethod(passAccess, "renderWorldPass", "(IFJ)V", null, passExceptions).apply {
                        visitCode()
                        HookCall(H_PASS, D_PASS, Opcodes.ILOAD to 1, Opcodes.FLOAD to 2, Opcodes.LLOAD to 3).emit(this)
                        visitInsn(Opcodes.RETURN)
                        visitMaxs(0, 0)
                        visitEnd()
                    }
                }

                cv.visitMethod(
                    Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC, PERSPECTIVE, D_PERSPECTIVE, null, null,
                ).apply {
                    visitCode()
                    pushHook(H_PERSPECTIVE)
                    for (i in 0 until 4) visitVarInsn(Opcodes.FLOAD, i)
                    invokeHook(D_PERSPECTIVE)
                    visitInsn(Opcodes.RETURN)
                    visitMaxs(0, 0)
                    visitEnd()
                }

                super.visitEnd()
            }

            /** Redirects Project.gluPerspective to the synthetic lcvr$perspective. */
            private inner class Rewrite(next: MethodVisitor) : MethodVisitor(Opcodes.ASM9, next) {
                override fun visitMethodInsn(op: Int, owner: String, name: String, desc: String, itf: Boolean) {
                    if (op == Opcodes.INVOKESTATIC && owner == "org/lwjgl/util/glu/Project" &&
                        name == "gluPerspective" && desc == D_PERSPECTIVE
                    ) {
                        perspective++
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, TARGET, PERSPECTIVE, desc, false)
                        return
                    }
                    super.visitMethodInsn(op, owner, name, desc, itf)
                }
            }
        }
    }
}
