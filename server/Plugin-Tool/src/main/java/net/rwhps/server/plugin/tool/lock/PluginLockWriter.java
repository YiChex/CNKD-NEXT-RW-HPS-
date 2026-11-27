package net.rwhps.server.plugin.tool.lock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.rwhps.server.plugin.tool.inspection.PluginArchiveInspector;
import net.rwhps.server.plugin.tool.io.AtomicFiles;
import net.rwhps.server.plugin.tool.model.MatrixReport;
import net.rwhps.server.plugin.tool.model.PluginReport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class PluginLockWriter {
    private static final int MAX_LOCK_ENTRIES = 512;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public Path write(
        MatrixReport matrix,
        Path output,
        String approvedBy,
        boolean force
    ) throws IOException {
        validateApprovedBy(approvedBy);
        if (matrix.failed > 0) throw new IOException("compatibility matrix contains " + matrix.failed + " failed plugin(s)");
        if (matrix.plugins.size() > MAX_LOCK_ENTRIES) throw new IOException("plugin lock cannot contain more than " + MAX_LOCK_ENTRIES + " entries");

        Path pluginDirectory = Path.of(matrix.pluginDirectory).toRealPath();
        Path normalizedOutput = output.toAbsolutePath().normalize();
        if (normalizedOutput.getParent() == null || !pluginDirectory.equals(normalizedOutput.getParent().toRealPath())) {
            throw new IOException("plugin lock must be written directly inside the inspected plugin directory");
        }
        String approvedAt = Instant.now().toString();
        List<Map<String, Object>> entries = new ArrayList<>();
        matrix.plugins.stream()
            .sorted(Comparator.comparing(report -> report.fileName.toLowerCase(java.util.Locale.ROOT)))
            .forEach(report -> entries.add(lockEntry(report, approvedAt, approvedBy)));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("lockVersion", 1);
        root.put("plugins", entries);
        AtomicFiles.writeUtf8(normalizedOutput, gson.toJson(root), force, true);
        return normalizedOutput;
    }

    private Map<String, Object> lockEntry(PluginReport report, String approvedAt, String approvedBy) {
        Path archive = Path.of(report.path).toAbsolutePath().normalize();
        try {
            String currentArchiveHash = PluginArchiveInspector.sha256(archive);
            String currentDescriptorHash = descriptorHash(archive);
            if (!currentArchiveHash.equalsIgnoreCase(report.archiveSha256) ||
                !currentDescriptorHash.equalsIgnoreCase(report.descriptorSha256)) {
                throw new IllegalStateException("archive changed after compatibility inspection: " + report.fileName);
            }
        } catch (IOException error) {
            throw new IllegalStateException("failed to recheck archive before lock write: " + report.fileName, error);
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("internalName", report.descriptor.internalName);
        entry.put("apiVersion", report.descriptor.apiVersion);
        entry.put("version", report.descriptor.version);
        entry.put("fileName", report.fileName);
        entry.put("sha256", report.archiveSha256.toLowerCase());
        entry.put("descriptorSha256", report.descriptorSha256.toLowerCase());
        entry.put("approvedAt", approvedAt);
        entry.put("approvedBy", approvedBy);
        return entry;
    }

    private static String descriptorHash(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry descriptor = zip.getEntry("plugin.json");
            if (descriptor == null) throw new IOException("plugin.json is missing");
            byte[] bytes;
            try (InputStream input = zip.getInputStream(descriptor)) {
                bytes = input.readNBytes(PluginArchiveInspector.MAX_DESCRIPTOR_BYTES + 1);
            }
            if (bytes.length > PluginArchiveInspector.MAX_DESCRIPTOR_BYTES) throw new IOException("plugin.json is too large");
            return PluginArchiveInspector.sha256(bytes);
        }
    }

    private static void validateApprovedBy(String value) {
        if (value == null || value.length() < 1 || value.length() > 128 || !value.equals(value.trim())) {
            throw new IllegalArgumentException("approved-by must contain 1..128 characters without surrounding whitespace");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character == 0x7f) throw new IllegalArgumentException("approved-by cannot contain control characters");
        }
    }
}
