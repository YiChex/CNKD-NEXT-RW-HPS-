package net.rwhps.server.plugin.tool.api;

import net.rwhps.server.plugin.tool.PluginToolMain;
import net.rwhps.server.plugin.tool.inspection.PluginArchiveInspector;
import net.rwhps.server.plugin.tool.model.ApiClassSurface;
import net.rwhps.server.plugin.tool.model.ApiFieldSurface;
import net.rwhps.server.plugin.tool.model.ApiMethodSurface;
import net.rwhps.server.plugin.tool.model.ApiSurfaceSnapshot;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class ApiSurfaceScanner {
    private static final String API_PREFIX = "net/rwhps/server/plugin/api/";
    private static final int MEMBER_ACCESS_MASK = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC |
        Opcodes.ACC_FINAL | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE | Opcodes.ACC_VARARGS |
        Opcodes.ACC_NATIVE | Opcodes.ACC_STRICT | Opcodes.ACC_ENUM;
    private static final int CLASS_ACCESS_MASK = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC |
        Opcodes.ACC_FINAL | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION |
        Opcodes.ACC_ENUM | Opcodes.ACC_SYNTHETIC;

    public ApiSurfaceSnapshot scan(Path jarPath) throws IOException {
        Path normalized = jarPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized) || Files.isSymbolicLink(normalized)) {
            throw new IOException("API artifact must be a regular non-symbolic-link JAR: " + normalized);
        }
        long archiveSize = Files.size(normalized);
        if (archiveSize <= 0L || archiveSize > PluginArchiveInspector.MAX_ARCHIVE_BYTES) {
            throw new IOException("API JAR size must be within 1.." + PluginArchiveInspector.MAX_ARCHIVE_BYTES + " bytes");
        }
        ApiSurfaceSnapshot snapshot = new ApiSurfaceSnapshot();
        snapshot.artifactFileName = normalized.getFileName().toString();
        snapshot.artifactSha256 = PluginArchiveInspector.sha256(normalized);
        snapshot.toolVersion = PluginToolMain.TOOL_VERSION;
        Set<String> classNames = new HashSet<>();
        try (JarFile jar = new JarFile(normalized.toFile(), false)) {
            if (jar.getManifest() != null) {
                Attributes attributes = jar.getManifest().getMainAttributes();
                snapshot.apiVersion = value(attributes.getValue("Implementation-Version"));
                snapshot.implementationTitle = value(attributes.getValue("Implementation-Title"));
            }
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !name.startsWith(API_PREFIX) || !name.endsWith(".class")) continue;
                if (classNames.size() >= 4_096) throw new IOException("API JAR contains more than 4096 public API classes");
                if (!classNames.add(name)) throw new IOException("duplicate API class entry: " + name);
                byte[] bytes;
                try (InputStream input = jar.getInputStream(entry)) {
                    bytes = input.readNBytes((int) PluginArchiveInspector.MAX_ENTRY_BYTES + 1);
                }
                if (bytes.length > PluginArchiveInspector.MAX_ENTRY_BYTES) throw new IOException("API class is too large: " + name);
                if (bytes.length < 8) throw new IOException("truncated API class: " + name);
                snapshot.maxClassMajor = Math.max(snapshot.maxClassMajor, ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff));
                ApiClassSurface surface = readClass(bytes);
                if (surface != null) snapshot.classes.add(surface);
            }
        }
        snapshot.classes.sort(Comparator.comparing(surface -> surface.name));
        String constantVersion = apiVersionConstant(snapshot);
        if (snapshot.apiVersion.isEmpty()) snapshot.apiVersion = constantVersion;
        else if (!constantVersion.isEmpty() && !snapshot.apiVersion.equals(constantVersion)) {
            throw new IOException("API manifest version " + snapshot.apiVersion + " differs from ApiVersions.SERVER_API " + constantVersion);
        }
        if (snapshot.apiVersion.isEmpty()) throw new IOException("API version is missing from manifest and ApiVersions.SERVER_API");
        if (snapshot.maxClassMajor > PluginArchiveInspector.MAX_PLUGIN_CLASS_MAJOR) {
            throw new IOException("API classes must target Java 11 or lower; max major=" + snapshot.maxClassMajor);
        }
        return snapshot;
    }

    private ApiClassSurface readClass(byte[] bytes) {
        final ApiClassSurface[] result = new ApiClassSurface[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                if ((access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0) return;
                ApiClassSurface surface = new ApiClassSurface();
                surface.name = dotted(name);
                surface.access = access & CLASS_ACCESS_MASK;
                surface.signature = value(signature);
                surface.superName = dotted(superName);
                if ((access & Opcodes.ACC_ANNOTATION) != 0) surface.kind = "ANNOTATION";
                else if ((access & Opcodes.ACC_ENUM) != 0) surface.kind = "ENUM";
                else if ((access & Opcodes.ACC_INTERFACE) != 0) surface.kind = "INTERFACE";
                if (interfaces != null) Arrays.stream(interfaces).map(ApiSurfaceScanner::dotted).sorted().forEach(surface.interfaces::add);
                result[0] = surface;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object fieldValue) {
                ApiClassSurface surface = result[0];
                if (surface == null || (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0) return null;
                ApiFieldSurface field = new ApiFieldSurface();
                field.name = name;
                field.descriptor = descriptor;
                field.signature = value(signature);
                field.access = access & MEMBER_ACCESS_MASK;
                field.constantValue = constant(fieldValue);
                surface.fields.add(field);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                ApiClassSurface surface = result[0];
                if (surface == null || (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0) return null;
                ApiMethodSurface method = new ApiMethodSurface();
                method.name = name;
                method.descriptor = descriptor;
                method.signature = value(signature);
                method.access = access & MEMBER_ACCESS_MASK;
                if (exceptions != null) Arrays.stream(exceptions).map(ApiSurfaceScanner::dotted).sorted().forEach(method.exceptions::add);
                surface.methods.add(method);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotationDefault() {
                        method.annotationDefault = true;
                        return new AnnotationVisitor(Opcodes.ASM9) {};
                    }
                };
            }

            @Override
            public void visitEnd() {
                ApiClassSurface surface = result[0];
                if (surface == null) return;
                surface.fields.sort(Comparator.comparing(ApiFieldSurface::key));
                surface.methods.sort(Comparator.comparing(ApiMethodSurface::key));
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result[0];
    }

    private static String apiVersionConstant(ApiSurfaceSnapshot snapshot) {
        for (ApiClassSurface surface : snapshot.classes) {
            if (!"net.rwhps.server.plugin.api.ApiVersions".equals(surface.name)) continue;
            for (ApiFieldSurface field : surface.fields) {
                if ("SERVER_API".equals(field.name) && field.constantValue.startsWith("java.lang.String:")) {
                    return field.constantValue.substring("java.lang.String:".length());
                }
            }
        }
        return "";
    }

    private static String constant(Object value) {
        return value == null ? "" : value.getClass().getName() + ":" + value;
    }

    private static String dotted(String internalName) {
        return internalName == null ? "" : internalName.replace('/', '.');
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
