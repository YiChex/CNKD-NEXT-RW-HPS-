package net.rwhps.server.plugin.tool;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class TestArchives {
    private TestArchives() {
    }

    public static Path v2(Path path, String id, String name, String supportedVersions, boolean internalReference) throws IOException {
        String className = "fixture/" + id.replace('.', '_').replace('-', '_') + "/Main";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(className + ".class", pluginClass(className, "java/lang/Object", new String[] {"net/rwhps/server/plugin/api/CnkdPlugin"}, internalReference));
        entries.put("plugin.json", descriptor(2, id, name, className.replace('/', '.'), supportedVersions, "", true));
        return zip(path, entries);
    }

    public static Path indirectV2(Path path) throws IOException {
        String base = "fixture/indirect/Base";
        String main = "fixture/indirect/Main";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(base + ".class", pluginClass(base, "java/lang/Object", new String[] {"net/rwhps/server/plugin/api/CnkdPlugin"}, false));
        entries.put(main + ".class", pluginClass(main, base, new String[0], false));
        entries.put("plugin.json", descriptor(2, "fixture.indirect", "Indirect", main.replace('/', '.'), ">= 1.2.0", "", true));
        return zip(path, entries);
    }

    public static Path v1(Path path, String id, String name, String importName, boolean logReference) throws IOException {
        String className = "fixture/" + id.replace('.', '_').replace('-', '_') + "/Legacy";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(className + ".class", legacyClass(className, logReference));
        entries.put("plugin.json", descriptor(1, id, name, className.replace('/', '.'), ">= 1.1.0", importName, false));
        return zip(path, entries);
    }

    public static Path unsafe(Path path) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("../escape.txt", new byte[] {1});
        entries.put("plugin.json", "{}".getBytes(StandardCharsets.UTF_8));
        return zip(path, entries);
    }

    public static Path customDescriptor(Path path, String descriptor, byte[] mainClass, String mainEntry) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(mainEntry, mainClass);
        entries.put("plugin.json", descriptor.getBytes(StandardCharsets.UTF_8));
        return zip(path, entries);
    }

    public static Path corrupt(Path path) throws IOException {
        Files.writeString(path, "not a zip", StandardCharsets.UTF_8);
        return path;
    }

    public static byte[] pluginClass(String name, String superName, String[] interfaces, boolean internalReference) {
        return pluginClass(Opcodes.V11, name, superName, interfaces, internalReference);
    }

    public static byte[] pluginClass(int version, String name, String superName, String[] interfaces, boolean internalReference) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(version, Opcodes.ACC_PUBLIC, name, null, superName, interfaces);
        constructor(writer, superName);
        if (internalReference) {
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "internal", "()V", null, null);
            method.visitCode();
            method.visitTypeInsn(Opcodes.NEW, "net/rwhps/server/game/player/PlayerHess");
            method.visitInsn(Opcodes.POP);
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(1, 1);
            method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] legacyClass(String name, boolean logReference) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, name, null, "net/rwhps/server/plugin/Plugin", null);
        constructor(writer, "net/rwhps/server/plugin/Plugin");
        MethodVisitor register = writer.visitMethod(Opcodes.ACC_PUBLIC, "registerCoreCommands", "()V", null, null);
        register.visitCode();
        if (logReference) {
            register.visitLdcInsn("legacy");
            register.visitMethodInsn(Opcodes.INVOKESTATIC, "net/rwhps/server/util/log/Log", "info", "(Ljava/lang/String;)V", false);
        }
        register.visitInsn(Opcodes.RETURN);
        register.visitMaxs(logReference ? 1 : 0, 1);
        register.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void constructor(ClassWriter writer, String superName) {
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
    }

    private static byte[] descriptor(
        int apiVersion,
        String id,
        String name,
        String main,
        String supportedVersions,
        String importName,
        boolean capabilities
    ) {
        String json = "{" +
            "\"apiVersion\":" + apiVersion + "," +
            "\"name\":\"" + name + "\"," +
            "\"internalName\":\"" + id + "\"," +
            "\"author\":\"test\"," +
            "\"main\":\"" + main + "\"," +
            "\"description\":\"fixture\"," +
            "\"version\":\"1.0.0\"," +
            "\"supportedVersions\":\"" + supportedVersions + "\"" +
            (importName.isEmpty() ? "" : ",\"import\":\"" + importName + "\"") +
            (capabilities ? ",\"capabilities\":[]" : "") +
            "}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static Path zip(Path path, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return path;
    }
}
