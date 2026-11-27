package net.rwhps.server.plugin.tool.sdk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.rwhps.server.plugin.tool.PluginToolMain;
import net.rwhps.server.plugin.api.ApiVersions;
import net.rwhps.server.plugin.tool.api.ApiSurfaceScanner;
import net.rwhps.server.plugin.tool.api.ApiSurfaceStore;
import net.rwhps.server.plugin.tool.inspection.PluginArchiveInspector;
import net.rwhps.server.plugin.tool.model.SdkAsset;
import net.rwhps.server.plugin.tool.model.SdkManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/** Validates and inventories the public SDK directory without executing plugins. */
public final class SdkManifestService {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public SdkManifest inspect(Path sdkDirectory) throws IOException {
        Path root = sdkDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) {
            throw new IOException("SDK directory must be a regular non-symbolic-link directory: " + root);
        }
        SdkManifest manifest = new SdkManifest();
        manifest.generatedAt = Instant.now().toString();
        manifest.toolVersion = PluginToolMain.TOOL_VERSION;

        String apiVersion = ApiVersions.SERVER_API;
        for (AssetSpec spec : requiredAssets(apiVersion)) {
            Path assetPath = root.resolve(spec.relativePath).normalize();
            if (!assetPath.startsWith(root)) {
                manifest.missing.add(spec.relativePath);
                continue;
            }
            if (!Files.isRegularFile(assetPath) || Files.isSymbolicLink(assetPath)) {
                manifest.missing.add(spec.relativePath);
                continue;
            }
            SdkAsset asset = new SdkAsset();
            asset.path = spec.relativePath;
            asset.kind = spec.kind;
            asset.size = Files.size(assetPath);
            asset.sha256 = PluginArchiveInspector.sha256(assetPath);
            manifest.assets.add(asset);
        }

        Path apiJar = root.resolve("server-plugin-api-" + apiVersion + ".jar");
        if (Files.isRegularFile(apiJar) && !Files.isSymbolicLink(apiJar)) {
            try {
                manifest.apiVersion = new ApiSurfaceScanner().scan(apiJar).apiVersion;
            } catch (IOException error) {
                manifest.warnings.add("root API JAR could not be scanned: " + safeMessage(error));
            }
        }
        if (manifest.apiVersion.isEmpty()) manifest.apiVersion = apiVersion;
        compareTemplateApiCopies(root, manifest);
        validateApiBaseline(root, manifest);
        validateApiCompatibilityReport(root, manifest);
        validateToolManifest(root, manifest);
        validateTemplateDescriptors(root, manifest);
        validateGeneratedDirectories(root, manifest);

        manifest.assetCount = manifest.assets.size();
        manifest.missingCount = manifest.missing.size();
        manifest.status = manifest.missingCount > 0 ? "FAIL" : manifest.warnings.isEmpty() ? "PASS" : "WARN";
        return manifest;
    }

    public String toJson(SdkManifest manifest) {
        return gson.toJson(manifest);
    }

    private void compareTemplateApiCopies(Path root, SdkManifest manifest) throws IOException {
        String apiVersion = ApiVersions.SERVER_API;
        Path rootApi = root.resolve("server-plugin-api-" + apiVersion + ".jar");
        if (!Files.isRegularFile(rootApi)) return;
        String rootHash = PluginArchiveInspector.sha256(rootApi);
        for (String template : Arrays.asList("java-minimal", "kotlin-minimal")) {
            Path copy = root.resolve(template).resolve("libs/server-plugin-api-" + apiVersion + ".jar");
            if (Files.isRegularFile(copy) && !Files.isSymbolicLink(copy)) {
                if (!rootHash.equalsIgnoreCase(PluginArchiveInspector.sha256(copy))) {
                    manifest.warnings.add(template + " API JAR hash differs from root API JAR");
                }
            }
        }
    }

    private void validateToolManifest(Path root, SdkManifest manifest) {
        Path tool = root.resolve("tools/cnkd-plugin-tool.jar");
        if (!Files.isRegularFile(tool) || Files.isSymbolicLink(tool)) return;
        try (JarFile jar = new JarFile(tool.toFile(), false)) {
            if (jar.getManifest() == null) {
                manifest.warnings.add("tool JAR has no manifest");
                return;
            }
            String version = jar.getManifest().getMainAttributes().getValue("Implementation-Version");
            if (version == null || !version.equals(PluginToolMain.TOOL_VERSION)) {
                manifest.warnings.add("tool JAR Implementation-Version does not match SDK tool version");
            }
        } catch (IOException error) {
            manifest.warnings.add("tool JAR manifest could not be read: " + safeMessage(error));
        }
    }

    private void validateApiBaseline(Path root, SdkManifest manifest) {
        Path baseline = root.resolve("api-baselines/server-plugin-api-1.1.0.json");
        if (!Files.isRegularFile(baseline) || Files.isSymbolicLink(baseline)) return;
        try {
            new ApiSurfaceStore().read(baseline);
        } catch (IOException error) {
            manifest.warnings.add("API baseline could not be parsed: " + safeMessage(error));
        }
    }

    private void validateApiCompatibilityReport(Path root, SdkManifest manifest) {
        Path report = root.resolve("api-compatibility.json");
        if (!Files.isRegularFile(report) || Files.isSymbolicLink(report)) return;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(report, StandardCharsets.UTF_8)).getAsJsonObject();
            String status = json.has("status") ? json.get("status").getAsString() : "";
            String candidate = json.has("candidateVersion") ? json.get("candidateVersion").getAsString() : "";
            String candidateHash = json.has("candidateArtifactSha256") ? json.get("candidateArtifactSha256").getAsString() : "";
            String baselineHash = json.has("baselineArtifactSha256") ? json.get("baselineArtifactSha256").getAsString() : "";
            int violations = json.has("policyViolations") ? json.get("policyViolations").getAsInt() : -1;
            Path apiJar = root.resolve("server-plugin-api-" + manifest.apiVersion + ".jar");
            Path baseline = root.resolve("api-baselines/server-plugin-api-1.1.0.json");
            String currentApiHash = Files.isRegularFile(apiJar) ? PluginArchiveInspector.sha256(apiJar) : "";
            String currentBaselineHash = new ApiSurfaceStore().read(baseline).artifactSha256;
            if (!"PASS".equals(status) || !manifest.apiVersion.equals(candidate) || violations != 0 ||
                !currentApiHash.equalsIgnoreCase(candidateHash) || !currentBaselineHash.equalsIgnoreCase(baselineHash)) {
                manifest.warnings.add("API compatibility report is not a passing report for SDK API " + manifest.apiVersion);
            }
        } catch (RuntimeException | IOException error) {
            manifest.warnings.add("API compatibility report is not readable JSON");
        }
    }

    private void validateTemplateDescriptors(Path root, SdkManifest manifest) {
        for (String template : Arrays.asList("java-minimal", "kotlin-minimal")) {
            Path descriptor = root.resolve(template).resolve("src/main/resources/plugin.json");
            if (!Files.isRegularFile(descriptor)) continue;
            try {
                JsonObject json = JsonParser.parseString(Files.readString(descriptor, StandardCharsets.UTF_8)).getAsJsonObject();
                String supported = json.has("supportedVersions") ? json.get("supportedVersions").getAsString() : "";
                if (supported.isEmpty() || !supported.contains(manifest.apiVersion)) {
                    manifest.warnings.add(template + " descriptor does not mention SDK API " + manifest.apiVersion);
                }
            } catch (RuntimeException | IOException error) {
                manifest.warnings.add(template + " descriptor is not readable JSON");
            }
        }
    }

    private void validateGeneratedDirectories(Path root, SdkManifest manifest) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> !path.equals(root))
                .filter(path -> Files.isDirectory(path) && !Files.isSymbolicLink(path))
                .filter(path -> Arrays.asList(".gradle", ".kotlin", "build").contains(path.getFileName().toString()))
                .map(root::relativize)
                .map(path -> path.toString().replace('\\', '/'))
                .sorted()
                .findFirst()
                .ifPresent(path -> manifest.warnings.add("generated SDK directory must not be distributed: " + path));
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
    }

    private static List<AssetSpec> requiredAssets(String apiVersion) {
        return Arrays.asList(
            new AssetSpec("server-plugin-api-" + apiVersion + ".jar", "api-jar"),
            new AssetSpec("server-plugin-api-" + apiVersion + "-sources.jar", "api-sources"),
            new AssetSpec("server-plugin-api-" + apiVersion + "-javadoc.jar", "api-javadoc"),
            new AssetSpec("tools/cnkd-plugin-tool.jar", "tool-jar"),
            new AssetSpec("README-CN.md", "documentation"),
            new AssetSpec("PLUGIN_TOOL_CLI_CN.md", "documentation"),
            new AssetSpec("PLUGIN_DEVELOPMENT_GUIDE_CN.md", "documentation"),
            new AssetSpec("PLUGIN_DEVELOPMENT_RUNTIME_CN.md", "documentation"),
            new AssetSpec("API-CHANGELOG-CN.md", "documentation"),
            new AssetSpec("PLUGIN-LOCK-OPERATIONS-CN.md", "documentation"),
            new AssetSpec("THIRD_PARTY_PLUGIN_INTEGRATION_CN.md", "documentation"),
            new AssetSpec("plugin-v2.schema.json", "schema"),
            new AssetSpec("plugin-lock.schema.json", "schema"),
            new AssetSpec("sdk-manifest.schema.json", "schema"),
            new AssetSpec("api-baselines/server-plugin-api-1.1.0.json", "api-baseline"),
            new AssetSpec("api-compatibility.json", "api-compatibility-report"),
            new AssetSpec("cnkd-plugin-tool.sh", "launcher"),
            new AssetSpec("cnkd-plugin-tool.bat", "launcher"),
            new AssetSpec("validate-plugin-v2.ps1", "compatibility-launcher"),
            new AssetSpec("approve-plugin-lock.ps1", "compatibility-launcher"),
            new AssetSpec("java-minimal/build.gradle.kts", "java-template"),
            new AssetSpec("java-minimal/src/main/resources/plugin.json", "java-template"),
            new AssetSpec("java-minimal/libs/server-plugin-api-" + apiVersion + ".jar", "java-template-api"),
            new AssetSpec("kotlin-minimal/build.gradle.kts", "kotlin-template"),
            new AssetSpec("kotlin-minimal/src/main/resources/plugin.json", "kotlin-template"),
            new AssetSpec("kotlin-minimal/libs/server-plugin-api-" + apiVersion + ".jar", "kotlin-template-api")
        );
    }

    private static final class AssetSpec {
        private final String relativePath;
        private final String kind;

        private AssetSpec(String relativePath, String kind) {
            this.relativePath = relativePath;
            this.kind = kind;
        }
    }
}
