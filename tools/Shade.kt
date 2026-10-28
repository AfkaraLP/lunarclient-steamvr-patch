import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

/**
 * Builds the agent jar from our classes plus the libraries they use, with the libraries
 * relocated under lcvr/ so they cannot clash with copies already on Lunar's class path:
 * ASM -> lcvr/asm, the Kotlin stdlib -> lcvr/kotlin.
 *
 * Only library classes reachable from our classes are included, so the jar carries the few
 * hundred stdlib classes the agent actually uses instead of the whole stdlib.
 *
 * Usage: Shade out.jar classesDir lib.jar...
 */
private val RELOCATIONS = listOf(
    "org/objectweb/asm/" to "lcvr/asm/",
    "kotlin/" to "lcvr/kotlin/",
    "org/jetbrains/annotations/" to "lcvr/annotations/",
)

private fun relocate(name: String): String {
    for ((from, to) in RELOCATIONS) if (name.startsWith(from)) return to + name.substring(from.length)
    return name
}

/** Relocates names and records every class name it sees, to compute reachability. */
private class Collector : Remapper() {
    val seen = HashSet<String>()

    override fun map(internalName: String): String {
        seen += internalName
        return relocate(internalName)
    }
}

fun main(args: Array<String>) {
    val out = File(args[0])
    val classesDir = File(args[1])
    val libs = args.drop(2).map(::ZipFile)
    val libClasses = HashMap<String, ZipFile>()
    for (lib in libs) {
        for (e in lib.entries()) {
            val n = e.name
            if (n.endsWith(".class") && !n.startsWith("META-INF/") && !n.endsWith("module-info.class")) {
                libClasses.putIfAbsent(n.removeSuffix(".class"), lib)
            }
        }
    }

    val mf = Manifest().apply {
        mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        mainAttributes.putValue("Premain-Class", "lcvr.Agent")
        mainAttributes.putValue("Can-Redefine-Classes", "false")
        mainAttributes.putValue("Can-Retransform-Classes", "false")
    }

    val written = HashSet<String>()
    val queue = ArrayDeque<Pair<String, ByteArray>>()
    classesDir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { f ->
        queue += f.relativeTo(classesDir).invariantSeparatorsPath.removeSuffix(".class") to f.readBytes()
    }

    JarOutputStream(out.outputStream(), mf).use { jar ->
        while (queue.isNotEmpty()) {
            val (name, bytes) = queue.removeFirst()
            if (!written.add(name)) continue
            val collector = Collector()
            val cw = ClassWriter(0)
            ClassReader(bytes).accept(ClassRemapper(cw, collector), 0)
            jar.putNextEntry(JarEntry(relocate(name) + ".class"))
            jar.write(cw.toByteArray())
            jar.closeEntry()
            for (ref in collector.seen) {
                val lib = libClasses[ref] ?: continue
                if (ref !in written) queue += ref to lib.getInputStream(lib.getEntry("$ref.class")).readBytes()
            }
        }
    }
    println("shaded ${written.size} classes into $out")
}
