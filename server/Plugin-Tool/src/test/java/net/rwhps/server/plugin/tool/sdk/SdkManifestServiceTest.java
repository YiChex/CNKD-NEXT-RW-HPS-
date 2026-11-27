package net.rwhps.server.plugin.tool.sdk;

import net.rwhps.server.plugin.api.ApiVersions;
import net.rwhps.server.plugin.tool.PluginToolMain;
import net.rwhps.server.plugin.tool.inspection.PluginArchiveInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdkManifestServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void reportsCompleteSdkAndDetectsTemplateApiHashDrift() throws Exception {
        Path sdk = tempDir.resolve("plugin-sdk");
        createCompleteSdk(sdk);
        SdkManifestService service = new SdkManifestService();

        assertEquals("PASS", service.inspect(sdk).status);
        Files.writeString(
            sdk.resolve("java-minimal/libs/server-plugin-api-" + ApiVersions.SERVER_API + ".jar"),
            "changed",
            StandardCharsets.UTF_8
        );
        assertEquals("WARN", service.inspect(sdk).status);
        assertTrue(service.inspect(sdk).warnings.stream().anyMatch(value -> value.contains("java-minimal API JAR hash differs")));
    }

    @Test
    void reportsMissingAssetsAsFailure() throws Exception {
        Path sdk = tempDir.resolve("incomplete-sdk");
        Files.createDirectories(sdk);
        SdkManifestService service = new SdkManifestService();

        assertEquals("FAIL", service.inspect(sdk).status);
        assertTrue(service.inspect(sdk).missingCount > 0);
    }

    @Test
    void reportsGeneratedTemplateDirectoryAsWarning() throws Exception {
        Path sdk = tempDir.resolve("sdk-with-build-cache");
        createCompleteSdk(sdk);
        Files.createDirectories(sdk.resolve("kotlin-minimal/build/classes"));

        SdkManifestService service = new SdkManifestService();

        assertEquals("WARN", service.inspect(sdk).status);
        assertTrue(service.inspect(sdk).warnings.stream().anyMatch(value ->
            value.equals("generated SDK directory must not be distributed: kotlin-minimal/build")
        ));
    }

    private void createCompleteSdk(Path sdk) throws Exception {
        Files.createDirectories(sdk);
        List<String> files = List.of(
            "README-CN.md", "PLUGIN_TOOL_CLI_CN.md", "PLUGIN_DEVELOPMENT_GUIDE_CN.md",
            "PLUGIN_DEVELOPMENT_RUNTIME_CN.md", "API-CHANGELOG-CN.md", "PLUGIN-LOCK-OPERATIONS-CN.md",
            "THIRD_PARTY_PLUGIN_INTEGRATION_CN.md",
            "plugin-v2.schema.json", "plugin-lock.schema.json", "sdk-manifest.schema.json",
            "api-baselines/server-plugin-api-1.1.0.json",
            "api-compatibility.json",
            "cnkd-plugin-tool.sh", "cnkd-plugin-tool.bat", "validate-plugin-v2.ps1", "approve-plugin-lock.ps1",
            "java-minimal/build.gradle.kts", "java-minimal/src/main/resources/plugin.json",
            "kotlin-minimal/build.gradle.kts", "kotlin-minimal/src/main/resources/plugin.json"
        );
        for (String file : files) {
            Path path = sdk.resolve(file);
            Files.createDirectories(path.getParent());
            String content;
            if (file.endsWith("plugin.json")) {
                content = "{\"supportedVersions\":\">= " + ApiVersions.SERVER_API + "\"}";
            } else if (file.endsWith("api-baselines/server-plugin-api-1.1.0.json")) {
                content = "{\"schemaVersion\":1,\"apiVersion\":\"1.1.0\",\"artifactSha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"classes\":[{\"name\":\"fixture.Api\",\"kind\":\"CLASS\",\"interfaces\":[],\"fields\":[],\"methods\":[]}] }";
            } else if (file.endsWith("api-compatibility.json")) {
                content = "{}";
            } else {
                content = "fixture";
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
        }
        Path rootApi = sdk.resolve("server-plugin-api-" + ApiVersions.SERVER_API + ".jar");
        writeApiJar(rootApi);
        Files.writeString(
            sdk.resolve("api-compatibility.json"),
            "{\"status\":\"PASS\",\"candidateVersion\":\"" + ApiVersions.SERVER_API +
                "\",\"policyViolations\":0,\"candidateArtifactSha256\":\"" + PluginArchiveInspector.sha256(rootApi) +
                "\",\"baselineArtifactSha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}",
            StandardCharsets.UTF_8
        );
        Files.copy(rootApi, sdk.resolve("server-plugin-api-" + ApiVersions.SERVER_API + "-sources.jar"));
        Files.copy(rootApi, sdk.resolve("server-plugin-api-" + ApiVersions.SERVER_API + "-javadoc.jar"));
        Path tool = sdk.resolve("tools/cnkd-plugin-tool.jar");
        writeToolJar(tool);
        Files.createDirectories(sdk.resolve("java-minimal/libs"));
        Files.createDirectories(sdk.resolve("kotlin-minimal/libs"));
        Files.copy(rootApi, sdk.resolve("java-minimal/libs/server-plugin-api-" + ApiVersions.SERVER_API + ".jar"));
        Files.copy(rootApi, sdk.resolve("kotlin-minimal/libs/server-plugin-api-" + ApiVersions.SERVER_API + ".jar"));
    }

    private void writeApiJar(Path path) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Implementation-Version", ApiVersions.SERVER_API);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            output.putNextEntry(new java.util.jar.JarEntry("net/rwhps/server/plugin/api/ApiVersions.class"));
            ClassWriter classWriter = new ClassWriter(0);
            classWriter.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "net/rwhps/server/plugin/api/ApiVersions", null, "java/lang/Object", null);
            classWriter.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "SERVER_API", "Ljava/lang/String;", null, ApiVersions.SERVER_API).visitEnd();
            classWriter.visitEnd();
            output.write(classWriter.toByteArray());
            output.closeEntry();
        }
    }

    private void writeToolJar(Path path) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Implementation-Version", PluginToolMain.TOOL_VERSION);
        Files.createDirectories(path.getParent());
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(path), manifest)) {
        }
    }
}
