import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * Builds the agent jar: our classes + ASM, with ASM relocated to lcvr/asm so it cannot clash
 * with the ASM copy Lunar ships on the same class path.
 * Usage: Shade out.jar classesDir asm.jar...
 */
public class Shade {
    static final Remapper RELOCATE = new Remapper() {
        @Override
        public String map(String name) {
            return name.startsWith("org/objectweb/asm/") ? "lcvr/asm/" + name.substring(18) : name;
        }
    };

    public static void main(String[] a) throws Exception {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().putValue("Premain-Class", "lcvr.Agent");
        mf.getMainAttributes().putValue("Can-Redefine-Classes", "false");
        mf.getMainAttributes().putValue("Can-Retransform-Classes", "false");
        Set<String> seen = new HashSet<>();
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(a[0]), mf)) {
            Path root = Paths.get(a[1]);
            List<Path> files = new ArrayList<>();
            Files.walk(root).filter(p -> p.toString().endsWith(".class")).forEach(files::add);
            for (Path p : files) put(out, seen, Files.readAllBytes(p));
            for (int i = 2; i < a.length; i++) {
                try (ZipInputStream in = new ZipInputStream(new FileInputStream(a[i]))) {
                    ZipEntry e;
                    while ((e = in.getNextEntry()) != null) {
                        String n = e.getName();
                        if (n.endsWith(".class") && !n.startsWith("META-INF/") && !n.endsWith("module-info.class")) {
                            put(out, seen, in.readAllBytes());
                        }
                    }
                }
            }
        }
    }

    static void put(JarOutputStream out, Set<String> seen, byte[] bytes) throws IOException {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(0);
        cr.accept(new ClassRemapper(cw, RELOCATE), 0);
        String name = RELOCATE.map(cr.getClassName()) + ".class";
        if (!seen.add(name)) return;
        out.putNextEntry(new JarEntry(name));
        out.write(cw.toByteArray());
        out.closeEntry();
    }
}
