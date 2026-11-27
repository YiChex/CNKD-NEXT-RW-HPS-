package net.rwhps.server.plugin.tool.api;

import net.rwhps.server.plugin.tool.model.ApiSurfaceSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiSurfaceScannerTest {
    @TempDir
    Path tempDir;

    @Test
    void scansOnlyPublicApiClassesAndReadsManifestVersion() throws Exception {
        Path jar = tempDir.resolve("api.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Implementation-Version", "9.1.0");
        manifest.getMainAttributes().putValue("Implementation-Title", "Test API");
        try (OutputStream output = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(output, manifest)) {
            zip.putNextEntry(new JarEntry("net/rwhps/server/plugin/api/ApiVersions.class"));
            zip.write(apiVersionsClass());
            zip.closeEntry();
            zip.putNextEntry(new JarEntry("net/rwhps/server/plugin/api/internal/Hidden.class"));
            zip.write(hiddenClass());
            zip.closeEntry();
        }

        ApiSurfaceSnapshot snapshot = new ApiSurfaceScanner().scan(jar);

        assertEquals("9.1.0", snapshot.apiVersion);
        assertEquals("Test API", snapshot.implementationTitle);
        assertEquals(1, snapshot.classes.size());
        assertEquals("net.rwhps.server.plugin.api.ApiVersions", snapshot.classes.get(0).name);
        assertEquals(55, snapshot.maxClassMajor);
        assertTrue(snapshot.artifactSha256.matches("[0-9a-f]{64}"));
    }

    private static byte[] apiVersionsClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "net/rwhps/server/plugin/api/ApiVersions", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "SERVER_API", "Ljava/lang/String;", null, "9.1.0").visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] hiddenClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V11, 0, "net/rwhps/server/plugin/api/internal/Hidden", null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }
}
