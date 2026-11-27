package net.rwhps.server.plugin.tool.inspection;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.rwhps.server.plugin.tool.model.ClassScanSummary;
import net.rwhps.server.plugin.tool.model.IssueSeverity;
import net.rwhps.server.plugin.tool.model.PluginDescriptorData;
import net.rwhps.server.plugin.tool.model.PluginReport;
import net.rwhps.server.plugin.tool.version.SemVersion;
import net.rwhps.server.plugin.tool.version.VersionConstraint;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public final class PluginArchiveInspector {
    public static final long MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L;
    public static final long MAX_ENTRY_BYTES = 32L * 1024L * 1024L;
    public static final long MAX_EXPANDED_BYTES = 128L * 1024L * 1024L;
    public static final int MAX_ENTRIES = 2_048;
    public static final int MAX_DESCRIPTOR_BYTES = 1024 * 1024;
    public static final int MAX_PLUGIN_CLASS_MAJOR = 55;

    private static final Set<String> RESERVED_IDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "hessserver", "uplist", "connectlimit", "bindplayer"
    )));
    private static final Set<String> CAPABILITIES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "console-commands", "player-commands", "player-events", "game-events", "broadcast", "scheduler", "read-state", "read-statistics"
    )));
    private static final Set<String> DESCRIPTOR_FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "$schema", "apiVersion", "name", "internalName", "author", "main", "description", "version",
        "supportedVersions", "capabilities", "import"
    )));
    private static final Set<String> REQUIRED_FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "name", "internalName", "author", "main", "description", "version", "supportedVersions"
    )));
    private static final Pattern PLUGIN_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern JAVA_MAIN = Pattern.compile(
        "^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$"
    );
    private static final String LEGACY_PLUGIN = "net/rwhps/server/plugin/Plugin";
    private static final String V2_PLUGIN = "net/rwhps/server/plugin/api/CnkdPlugin";
    private static final String API_PREFIX = "net/rwhps/server/plugin/api/";
    private static final String SERVER_PREFIX = "net/rwhps/server/";

    private final String serverApi;
    private final SemVersion serverVersion;
    private final ClassReferenceScanner classScanner = new ClassReferenceScanner();

    public PluginArchiveInspector(String serverApi) {
        this.serverApi = serverApi;
        this.serverVersion = SemVersion.parse(serverApi);
    }

    public PluginReport inspect(Path archivePath) {
        PluginReport report = new PluginReport();
        Path normalized = archivePath.toAbsolutePath().normalize();
        report.path = normalized.toString();
        report.fileName = normalized.getFileName() == null ? "" : normalized.getFileName().toString();
        report.serverApi = serverApi;

        if (!Files.isRegularFile(normalized) || Files.isSymbolicLink(normalized)) {
            report.addIssue(IssueSeverity.ERROR, "archive_not_regular", "Archive is not a regular non-symbolic-link file", "Select a direct JAR or ZIP file");
            finalizeMigration(report);
            return report;
        }
        String lowerName = report.fileName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".jar") && !lowerName.endsWith(".zip")) {
            report.addIssue(IssueSeverity.ERROR, "archive_extension", "Archive must use .jar or .zip", "Rename or rebuild the plugin archive");
        }
        try {
            long size = Files.size(normalized);
            if (size <= 0L || size > MAX_ARCHIVE_BYTES) {
                report.addIssue(IssueSeverity.ERROR, "archive_size", "Archive size must be within 1.." + MAX_ARCHIVE_BYTES + " bytes", "Reduce the archive and remove bundled server/API classes");
            }
            report.archiveSha256 = sha256(normalized);
        } catch (IOException error) {
            report.addIssue(IssueSeverity.ERROR, "archive_read", "Cannot read archive: " + safeMessage(error), "Check file permissions and retry while the server is stopped");
            finalizeMigration(report);
            return report;
        }

        try (ZipFile zip = new ZipFile(normalized.toFile())) {
            inspectZip(zip, report);
        } catch (ZipException error) {
            report.addIssue(IssueSeverity.ERROR, "archive_zip", "Invalid ZIP/JAR archive: " + safeMessage(error), "Rebuild the archive with a standard ZIP/JAR tool");
        } catch (IOException error) {
            report.addIssue(IssueSeverity.ERROR, "archive_io", "Failed to inspect archive: " + safeMessage(error), "Check file permissions and archive integrity");
        } catch (RuntimeException error) {
            report.addIssue(IssueSeverity.ERROR, "archive_inspection", "Archive inspection failed: " + safeMessage(error), "Submit this report with the plugin archive to the developer");
        }

        report.refreshStatus();
        finalizeMigration(report);
        return report;
    }

    private void inspectZip(ZipFile zip, PluginReport report) throws IOException {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        Set<String> names = new HashSet<>();
        List<ZipEntry> classEntries = new ArrayList<>();
        ZipEntry descriptorEntry = null;
        long expanded = 0L;
        int count = 0;
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            count++;
            if (count > MAX_ENTRIES) {
                report.addIssue(IssueSeverity.ERROR, "archive_entry_count", "Archive contains more than " + MAX_ENTRIES + " entries", "Remove generated caches and unnecessary dependencies");
                break;
            }
            String rawName = entry.getName();
            String name = rawName.replace('\\', '/');
            if (rawName.indexOf('\\') >= 0 || name.startsWith("/") || containsTraversal(name)) {
                report.addIssue(IssueSeverity.ERROR, "archive_unsafe_path", "Unsafe archive entry path: " + limit(name, 160), "Rebuild the archive without absolute, backslash or parent paths");
            }
            if (!names.add(name)) {
                report.addIssue(IssueSeverity.ERROR, "archive_duplicate_entry", "Duplicate archive entry: " + limit(name, 160), "Ensure every ZIP entry name is unique");
            }
            if (name.endsWith("/plugin.json")) {
                report.addIssue(IssueSeverity.ERROR, "descriptor_nested", "Nested plugin.json is not allowed: " + limit(name, 160), "Keep exactly one plugin.json at the archive root");
            }
            if (entry.isDirectory()) continue;
            long entrySize = entry.getSize();
            if (entrySize < 0L) {
                report.addIssue(IssueSeverity.ERROR, "entry_unknown_size", "Archive entry has unknown expanded size: " + limit(name, 160), "Rebuild with a ZIP tool that writes central-directory sizes");
            } else {
                if (entrySize > MAX_ENTRY_BYTES) {
                    report.addIssue(IssueSeverity.ERROR, "entry_size", "Archive entry exceeds " + MAX_ENTRY_BYTES + " bytes: " + limit(name, 160), "Remove or split the oversized dependency/resource");
                }
                expanded += entrySize;
                if (expanded > MAX_EXPANDED_BYTES) {
                    report.addIssue(IssueSeverity.ERROR, "archive_expanded_size", "Archive expanded size exceeds " + MAX_EXPANDED_BYTES + " bytes", "Reduce shaded dependencies and resources");
                }
            }
            if ("plugin.json".equals(name)) descriptorEntry = entry;
            if ("imports.json".equals(name)) report.hasImportsJson = true;
            if (name.endsWith(".class")) classEntries.add(entry);
        }

        if (descriptorEntry == null) {
            report.addIssue(IssueSeverity.ERROR, "descriptor_missing", "Root plugin.json is missing", "Add one plugin.json at the archive root");
            scanClasses(zip, classEntries, report);
            return;
        }
        byte[] descriptorBytes = readBounded(zip.getInputStream(descriptorEntry), MAX_DESCRIPTOR_BYTES, "plugin.json");
        report.descriptorSha256 = sha256(descriptorBytes);
        JsonObject descriptorJson = parseDescriptor(descriptorBytes, report);
        if (descriptorJson != null) {
            report.descriptor = readDescriptor(descriptorJson, report);
            validateDescriptor(descriptorJson, report);
        }
        scanClasses(zip, classEntries, report);
        validateArchiveContents(zip, names, classEntries, report);
        analyzeReferences(report);
    }

    private JsonObject parseDescriptor(byte[] bytes, PluginReport report) {
        final String raw;
        try {
            raw = decodeUtf8(bytes);
        } catch (CharacterCodingException error) {
            report.addIssue(IssueSeverity.ERROR, "descriptor_utf8", "plugin.json is not valid UTF-8", "Save plugin.json as UTF-8 without invalid bytes");
            return null;
        }
        Set<String> fields = new LinkedHashSet<>();
        JsonObject object = new JsonObject();
        try (JsonReader reader = new JsonReader(new StringReader(raw))) {
            reader.setLenient(false);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                report.addIssue(IssueSeverity.ERROR, "descriptor_root", "plugin.json root must be an object", "Use the SDK plugin-v2.schema.json structure");
                return null;
            }
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!fields.add(name)) {
                    report.addIssue(IssueSeverity.ERROR, "descriptor_duplicate_field", "Duplicate plugin.json field: " + name, "Keep each descriptor field exactly once");
                }
                JsonElement value = JsonParser.parseReader(reader);
                object.add(name, value);
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                report.addIssue(IssueSeverity.ERROR, "descriptor_trailing_content", "Content exists after plugin.json root object", "Remove trailing JSON values");
            }
        } catch (Exception error) {
            report.addIssue(IssueSeverity.ERROR, "descriptor_json", "Malformed plugin.json: " + safeMessage(error), "Validate plugin.json with the SDK schema");
            return null;
        }
        return object;
    }

    private PluginDescriptorData readDescriptor(JsonObject object, PluginReport report) {
        PluginDescriptorData descriptor = new PluginDescriptorData();
        descriptor.name = stringValue(object, "name", report);
        descriptor.internalName = stringValue(object, "internalName", report);
        descriptor.author = stringValue(object, "author", report);
        descriptor.main = stringValue(object, "main", report);
        descriptor.description = stringValue(object, "description", report);
        descriptor.version = stringValue(object, "version", report);
        descriptor.supportedVersions = stringValue(object, "supportedVersions", report);
        descriptor.importName = stringValue(object, "import", report);
        if (object.has("apiVersion")) {
            JsonElement value = object.get("apiVersion");
            try {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() ||
                    !value.getAsString().matches("-?(0|[1-9][0-9]*)")) {
                    throw new NumberFormatException();
                }
                descriptor.apiVersion = value.getAsInt();
            } catch (Exception error) {
                report.addIssue(IssueSeverity.ERROR, "descriptor_api_type", "apiVersion must be an integer", "Use apiVersion 1 or 2");
            }
        }
        if (object.has("capabilities")) {
            JsonElement value = object.get("capabilities");
            if (!value.isJsonArray()) {
                report.addIssue(IssueSeverity.ERROR, "descriptor_capabilities_type", "capabilities must be an array", "Use an array of lowercase capability strings");
            } else {
                JsonArray array = value.getAsJsonArray();
                for (JsonElement capability : array) {
                    if (!capability.isJsonPrimitive() || !capability.getAsJsonPrimitive().isString()) {
                        report.addIssue(IssueSeverity.ERROR, "descriptor_capability_type", "Each capability must be a string", "Remove non-string capability values");
                    } else {
                        descriptor.capabilities.add(capability.getAsString());
                    }
                }
            }
        }
        return descriptor;
    }

    private void validateDescriptor(JsonObject object, PluginReport report) {
        PluginDescriptorData descriptor = report.descriptor;
        for (String field : object.keySet()) {
            if (!DESCRIPTOR_FIELDS.contains(field)) {
                report.addIssue(IssueSeverity.ERROR, "descriptor_unknown_field", "Unsupported plugin.json field: " + field, "Remove fields not defined by the SDK schema");
            }
        }
        for (String required : REQUIRED_FIELDS) {
            if (!object.has(required)) {
                report.addIssue(IssueSeverity.ERROR, "descriptor_required_field", "Missing required plugin.json field: " + required, "Add the field using the SDK template");
            }
        }
        if (descriptor.apiVersion < 1 || descriptor.apiVersion > 2) {
            report.addIssue(IssueSeverity.ERROR, "descriptor_api_version", "apiVersion must be 1 or 2", "Use V2 for new plugins");
        }
        validateLength(report, "name", descriptor.name, 1, 96);
        validateLength(report, "author", descriptor.author, 1, 160);
        validateLength(report, "description", descriptor.description, 0, 500);
        if (!PLUGIN_ID.matcher(descriptor.internalName).matches()) {
            report.addIssue(IssueSeverity.ERROR, "descriptor_internal_name", "internalName has invalid syntax", "Use 1..64 ASCII letters, numbers, dot, underscore or hyphen");
        }
        String normalizedId = descriptor.internalName.toLowerCase(Locale.ROOT);
        if (RESERVED_IDS.contains(normalizedId)) {
            report.addIssue(IssueSeverity.ERROR, "descriptor_reserved_id", "internalName is reserved: " + descriptor.internalName, "Choose a developer-owned unique ID");
        }
        if (descriptor.apiVersion == 2 && !descriptor.internalName.equals(normalizedId)) {
            report.addIssue(IssueSeverity.ERROR, "descriptor_v2_id_case", "V2 internalName must be lowercase", "Convert internalName to lowercase and preserve it after release");
        }
        report.javascript = isJavaScriptMain(descriptor.main);
        if (descriptor.main.isEmpty()) {
            report.addIssue(IssueSeverity.ERROR, "descriptor_main", "main cannot be blank", "Set the fully qualified JVM main class");
        } else if (report.javascript) {
            String normalized = descriptor.main.replace('\\', '/');
            if (descriptor.apiVersion == 2) {
                report.addIssue(IssueSeverity.ERROR, "descriptor_v2_javascript", "V2 supports JVM plugins only", "Rewrite the plugin as Java or Kotlin V2");
            }
            if (descriptor.main.indexOf('\\') >= 0 || normalized.startsWith("/") || containsTraversal(normalized)) {
                report.addIssue(IssueSeverity.ERROR, "descriptor_javascript_path", "JavaScript main must be a safe relative archive path", "Use a root-relative archive path without parent segments");
            }
        } else {
            if (!JAVA_MAIN.matcher(descriptor.main).matches()) {
                report.addIssue(IssueSeverity.ERROR, "descriptor_java_main", "main is not a valid fully qualified class name", "Use a class name such as example.plugin.Main");
            }
            if (descriptor.apiVersion == 2 && descriptor.main.startsWith("net.rwhps.server.")) {
                report.addIssue(IssueSeverity.ERROR, "descriptor_reserved_package", "V2 main cannot use net.rwhps.server", "Move the plugin to a developer-owned package");
            }
        }
        try {
            SemVersion.parse(descriptor.version);
        } catch (Exception error) {
            report.addIssue(IssueSeverity.ERROR, "descriptor_version", "Plugin version is not valid SemVer: " + safeMessage(error), "Use a version such as 1.0.0");
        }
        try {
            VersionConstraint constraint = VersionConstraint.parse(descriptor.supportedVersions);
            report.compatible = constraint.matches(serverVersion);
            report.versionCompatibility.put(serverApi, report.compatible);
            if (!report.compatible) {
                report.addIssue(IssueSeverity.ERROR, "api_incompatible", "Plugin supports '" + descriptor.supportedVersions + "' but server API is " + serverApi, "Rebuild and test against the matching SDK before changing supportedVersions");
            }
        } catch (Exception error) {
            report.compatible = false;
            report.addIssue(IssueSeverity.ERROR, "descriptor_supported_versions", "Invalid supportedVersions: " + safeMessage(error), "Use an exact/comparator version or one interval");
        }
        if (descriptor.capabilities.size() > 32) {
            report.addIssue(IssueSeverity.ERROR, "descriptor_capability_count", "capabilities cannot contain more than 32 entries", "Declare only capabilities used by this plugin");
        }
        Set<String> seen = new HashSet<>();
        for (String capability : descriptor.capabilities) {
            if (!seen.add(capability)) {
                report.addIssue(IssueSeverity.ERROR, "descriptor_duplicate_capability", "Duplicate capability: " + capability, "Keep each capability once");
            }
            if (descriptor.apiVersion == 2) {
                if (!capability.equals(capability.toLowerCase(Locale.ROOT))) {
                    report.addIssue(IssueSeverity.ERROR, "descriptor_capability_case", "Capability must be lowercase: " + capability, "Use the exact lowercase SDK capability");
                }
                if (!CAPABILITIES.contains(capability)) {
                    report.addIssue(IssueSeverity.ERROR, "descriptor_capability_unknown", "Unsupported capability: " + capability, "Remove it or use a server version that provides it");
                }
            } else if (capability.trim().isEmpty() || !PLUGIN_ID.matcher(capability).matches()) {
                report.addIssue(IssueSeverity.ERROR, "descriptor_v1_capability_syntax", "Legacy capability has invalid ID syntax: " + capability, "Use a valid capability ID or remove the optional field");
            }
        }
        if (descriptor.apiVersion == 2 && !object.has("capabilities")) {
            report.addIssue(IssueSeverity.ERROR, "descriptor_capabilities_missing", "V2 plugin.json must include capabilities", "Add an empty array or the exact required capabilities");
        }
        if (descriptor.apiVersion == 2 && object.has("import")) {
            report.addIssue(IssueSeverity.ERROR, "descriptor_v2_import", "V2 cannot use the legacy import field", "Shade/relocate dependencies and remove import");
        }
        if (descriptor.apiVersion == 1) {
            report.addIssue(IssueSeverity.WARN, "legacy_v1", "Legacy V1 depends on server internals and has no binary compatibility promise", "Run the migrate command and move to V2 when the required APIs exist");
        }
    }

    private void scanClasses(ZipFile zip, List<ZipEntry> classEntries, PluginReport report) throws IOException {
        for (ZipEntry entry : classEntries) {
            if (entry.getSize() > MAX_ENTRY_BYTES || entry.getSize() < 0L) continue;
            try {
                classScanner.scan(readBounded(zip.getInputStream(entry), (int) MAX_ENTRY_BYTES, entry.getName()), report.classes);
            } catch (Exception error) {
                report.addIssue(IssueSeverity.ERROR, "class_scan", "Cannot scan class " + limit(entry.getName(), 160) + ": " + safeMessage(error), "Rebuild the class with a supported Java compiler");
            }
        }
    }

    private void validateArchiveContents(
        ZipFile zip,
        Set<String> names,
        List<ZipEntry> classEntries,
        PluginReport report
    ) throws IOException {
        PluginDescriptorData descriptor = report.descriptor;
        if (descriptor.main.isEmpty()) return;
        if (report.javascript) {
            if (!names.contains(descriptor.main.replace('\\', '/'))) {
                report.addIssue(IssueSeverity.ERROR, "main_missing", "JavaScript main is missing: " + descriptor.main, "Include the declared script in the archive");
            }
        } else if (JAVA_MAIN.matcher(descriptor.main).matches()) {
            String mainEntryName = descriptor.main.replace('.', '/') + ".class";
            ZipEntry mainEntry = zip.getEntry(mainEntryName);
            if (mainEntry == null) {
                report.addIssue(IssueSeverity.ERROR, "main_missing", "Main class is missing: " + mainEntryName, "Include the declared main class at the matching package path");
            } else if (mainEntry.getSize() >= 0L && mainEntry.getSize() <= MAX_ENTRY_BYTES) {
                ClassScanSummary mainSummary = new ClassScanSummary();
                try {
                    classScanner.scan(readBounded(zip.getInputStream(mainEntry), (int) MAX_ENTRY_BYTES, mainEntryName), mainSummary);
                    if (descriptor.apiVersion == 2 && !implementsType(mainEntryName.substring(0, mainEntryName.length() - 6), V2_PLUGIN, report.classes, new HashSet<>())) {
                        report.addIssue(IssueSeverity.ERROR, "main_v2_interface", "V2 main class does not implement CnkdPlugin", "Implement net.rwhps.server.plugin.api.CnkdPlugin");
                    }
                    if (descriptor.apiVersion == 1 && !implementsType(mainEntryName.substring(0, mainEntryName.length() - 6), LEGACY_PLUGIN, report.classes, new HashSet<>())) {
                        report.addIssue(IssueSeverity.ERROR, "main_v1_base", "V1 main class does not extend Plugin", "Extend the legacy Plugin base or migrate to CnkdPlugin V2");
                    }
                } catch (Exception error) {
                    report.addIssue(IssueSeverity.ERROR, "main_scan", "Cannot inspect main class: " + safeMessage(error), "Rebuild the main class with Java 11-compatible bytecode");
                }
            }
        }
        if (descriptor.apiVersion == 2 && report.hasImportsJson) {
            report.addIssue(IssueSeverity.ERROR, "v2_imports_json", "V2 cannot contain imports.json", "Shade and relocate dependencies into the plugin archive");
        }
        for (ZipEntry entry : classEntries) {
            String name = entry.getName().replace('\\', '/');
            if (descriptor.apiVersion == 2 && name.startsWith(SERVER_PREFIX)) {
                report.addIssue(IssueSeverity.ERROR, "v2_bundled_server_class", "V2 bundles a server/API class: " + limit(name, 160), "Use compileOnly for the API and never bundle server classes");
            }
        }
        if (report.classes.maxClassMajor > MAX_PLUGIN_CLASS_MAJOR) {
            IssueSeverity severity = descriptor.apiVersion == 2 ? IssueSeverity.ERROR : IssueSeverity.WARN;
            report.addIssue(severity, "class_java_target", "Archive class major " + report.classes.maxClassMajor + " exceeds Java 11 major 55", "Compile with --release 11 or jvmTarget 11");
        }
    }

    private void analyzeReferences(PluginReport report) {
        PluginDescriptorData descriptor = report.descriptor;
        Set<String> internalReferences = new TreeSet<>();
        for (String reference : report.classes.referencedClasses) {
            if (reference.startsWith(SERVER_PREFIX) && !reference.startsWith(API_PREFIX)) {
                internalReferences.add(reference);
            }
        }
        internalReferences.addAll(report.classes.reflectiveClassNames);
        if (descriptor.apiVersion == 2 && !internalReferences.isEmpty()) {
            for (String reference : internalReferences) {
                report.addIssue(IssueSeverity.ERROR, "v2_internal_reference", "V2 references server internal class: " + reference.replace('/', '.'), "Replace it with Server-Plugin-API or remove the unsupported feature");
            }
        }
        if (report.hasImportsJson) {
            report.addIssue(IssueSeverity.WARN, "legacy_imports_json", "imports.json dynamically changes the shared class loader", "Shade and relocate dependencies for V2");
        }
        if (referencesAny(report, "java/lang/Thread", "java/util/concurrent/Executors", "java/util/concurrent/ExecutorService", "kotlinx/coroutines/GlobalScope")) {
            report.addIssue(IssueSeverity.WARN, "unmanaged_concurrency", "Plugin references unmanaged thread/executor APIs", "Use context.scheduler() and context.manage() where possible");
        }
        buildMigrationActions(report, internalReferences);
    }

    private void buildMigrationActions(PluginReport report, Set<String> internalReferences) {
        if (report.descriptor.apiVersion == 2) {
            report.migrationReadiness = "ALREADY_V2";
            return;
        }
        if (report.javascript) {
            report.migrationReadiness = "MANUAL_REWRITE";
            addAction(report, "Rewrite the JavaScript entry point as a Java/Kotlin CnkdPlugin");
            return;
        }
        addAction(report, "Replace 'extends Plugin' with 'implements CnkdPlugin'");
        if (declaresMethod(report, "registerCoreCommands") || declaresMethod(report, "registerServerCommands")) {
            addAction(report, "Move console command registration to context.commands().registerConsole()");
        }
        if (declaresMethod(report, "registerServerClientCommands")) {
            addAction(report, "Move player command registration to context.commands().registerPlayer()");
        }
        if (declaresMethod(report, "registerEvents") || declaresMethod(report, "registerGlobalEvents")) {
            addAction(report, "Replace legacy event listeners with context.events().subscribe()");
        }
        if (declaresMethod(report, "init")) {
            addAction(report, "Move post-registration initialization to onReady()");
        }
        if (referencesAny(report, "net/rwhps/server/util/log/Log")) {
            addAction(report, "Replace global Log calls with context.logger()");
        }
        if (referencesAny(report, "net/rwhps/server/util/file/FileUtils")) {
            addAction(report, "Replace pluginDataFileUtils/FileUtils access with context.dataDirectory() and java.nio.file");
        }
        if (referencesAny(report, "net/rwhps/server/game/player/PlayerHess")) {
            addAction(report, "Replace PlayerHess with immutable PlayerSnapshot and stable player IDs");
        }
        if (referencesAny(report, "net/rwhps/server/util/game/command/CommandHandler")) {
            addAction(report, "Replace CommandHandler access with PluginCommands");
        }
        if (referencesAny(report, "net/rwhps/server/core/thread/Threads")) {
            addAction(report, "Replace Threads calls with context.scheduler() or managed resources");
        }
        if (report.hasImportsJson) {
            addAction(report, "Shade and relocate private dependencies; remove imports.json");
        }

        for (String reference : internalReferences) {
            if (isMappableLegacyReference(reference)) continue;
            if (reference.startsWith("net/rwhps/server/net/") ||
                reference.startsWith("net/rwhps/server/plugin/internal/") ||
                reference.startsWith("net/rwhps/server/game/") ||
                reference.startsWith("com/corrodinggames/")) {
                if (report.blockingReferences.size() < 128) report.blockingReferences.add(reference.replace('/', '.'));
            }
        }
        for (String reference : report.classes.referencedClasses) {
            if (reference.startsWith("com/corrodinggames/")) {
                if (report.blockingReferences.size() < 128) report.blockingReferences.add(reference.replace('/', '.'));
            }
        }
        report.blockingReferences.replaceAll(String::trim);
        report.blockingReferences.removeIf(String::isEmpty);
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(report.blockingReferences));
        report.blockingReferences.clear();
        report.blockingReferences.addAll(distinct);
        if (!report.blockingReferences.isEmpty()) {
            report.migrationReadiness = "BLOCKED_API_GAP";
            addAction(report, "Keep the plugin on audited V1 or request a stable V2 API for each blocking reference");
        } else {
            report.migrationReadiness = "MAPPABLE";
        }
    }

    private void finalizeMigration(PluginReport report) {
        if (report.issues.stream().anyMatch(issue -> issue.severity == IssueSeverity.ERROR) && "UNKNOWN".equals(report.migrationReadiness)) {
            report.migrationReadiness = "BLOCKED_INVALID_ARCHIVE";
        } else if ("UNKNOWN".equals(report.migrationReadiness)) {
            buildMigrationActions(report, Collections.emptySet());
        }
        report.refreshStatus();
    }

    private static boolean isMappableLegacyReference(String reference) {
        return reference.equals(LEGACY_PLUGIN) ||
            reference.equals("net/rwhps/server/util/log/Log") ||
            reference.equals("net/rwhps/server/util/file/FileUtils") ||
            reference.equals("net/rwhps/server/game/player/PlayerHess") ||
            reference.equals("net/rwhps/server/util/game/command/CommandHandler") ||
            reference.equals("net/rwhps/server/core/thread/Threads") ||
            reference.startsWith("net/rwhps/server/game/event/");
    }

    private static boolean implementsType(
        String className,
        String target,
        ClassScanSummary summary,
        Set<String> visited
    ) {
        if (className == null || !visited.add(className)) return false;
        if (target.equals(className)) return true;
        Set<String> interfaces = summary.interfaces.getOrDefault(className, Collections.emptySet());
        for (String interfaceName : interfaces) {
            if (target.equals(interfaceName) || implementsType(interfaceName, target, summary, visited)) return true;
        }
        return implementsType(summary.superClasses.get(className), target, summary, visited);
    }

    private static boolean referencesAny(PluginReport report, String... candidates) {
        for (String reference : report.classes.referencedClasses) {
            for (String candidate : candidates) {
                if (reference.equals(candidate) || reference.startsWith(candidate + "$")) return true;
            }
        }
        return false;
    }

    private static boolean declaresMethod(PluginReport report, String method) {
        return report.classes.declaredMethods.stream().anyMatch(value -> value.startsWith(method + "("));
    }

    private static void addAction(PluginReport report, String action) {
        if (!report.migrationActions.contains(action)) report.migrationActions.add(action);
    }

    private static String stringValue(JsonObject object, String name, PluginReport report) {
        if (!object.has(name)) return "";
        JsonElement value = object.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            report.addIssue(IssueSeverity.ERROR, "descriptor_field_type", name + " must be a string", "Fix the field type in plugin.json");
            return "";
        }
        return value.getAsString();
    }

    private static void validateLength(PluginReport report, String field, String value, int minimum, int maximum) {
        if (value.length() < minimum || value.length() > maximum) {
            report.addIssue(IssueSeverity.ERROR, "descriptor_field_length", field + " must contain " + minimum + ".." + maximum + " characters", "Adjust the field length");
        }
    }

    private static boolean isJavaScriptMain(String main) {
        String lower = main.toLowerCase(Locale.ROOT);
        return lower.endsWith(".js") || lower.endsWith(".mjs");
    }

    private static boolean containsTraversal(String path) {
        for (String segment : path.split("/", -1)) if ("..".equals(segment)) return true;
        return false;
    }

    private static byte[] readBounded(InputStream input, int limit, String label) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (output.size() + read > limit) throw new IOException(label + " exceeds " + limit + " bytes");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
    }

    public static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return hexadecimal(digest.digest());
    }

    public static String sha256(byte[] bytes) {
        return hexadecimal(sha256Digest().digest(bytes));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String hexadecimal(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : limit(message.replace('\n', ' ').replace('\r', ' '), 240);
    }

    private static String limit(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }
}
