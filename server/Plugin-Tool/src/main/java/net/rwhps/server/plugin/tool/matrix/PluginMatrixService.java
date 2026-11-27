package net.rwhps.server.plugin.tool.matrix;

import net.rwhps.server.plugin.tool.inspection.PluginArchiveInspector;
import net.rwhps.server.plugin.tool.model.IssueSeverity;
import net.rwhps.server.plugin.tool.model.MatrixReport;
import net.rwhps.server.plugin.tool.model.PluginReport;
import net.rwhps.server.plugin.tool.version.SemVersion;
import net.rwhps.server.plugin.tool.version.VersionConstraint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class PluginMatrixService {
    public MatrixReport inspectDirectory(Path directory, List<String> serverApis) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized) || Files.isSymbolicLink(normalized)) {
            throw new IOException("plugin directory must be a regular non-symbolic-link directory: " + normalized);
        }
        if (serverApis.isEmpty()) throw new IllegalArgumentException("at least one server API version is required");
        for (String api : serverApis) SemVersion.parse(api);

        MatrixReport matrix = new MatrixReport();
        matrix.generatedAt = Instant.now().toString();
        matrix.pluginDirectory = normalized.toString();
        matrix.serverApi = serverApis.get(0);
        matrix.serverApis.addAll(serverApis);
        PluginArchiveInspector inspector = new PluginArchiveInspector(matrix.serverApi);
        List<Path> archives;
        try (Stream<Path> children = Files.list(normalized)) {
            archives = children
                .filter(path -> {
                    String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    return lower.endsWith(".jar") || lower.endsWith(".zip");
                })
                .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        for (Path archive : archives) {
            PluginReport report = inspector.inspect(archive);
            addVersionCompatibility(report, serverApis);
            matrix.plugins.add(report);
        }
        validateIdentities(matrix.plugins);
        validateFileNames(matrix.plugins);
        validateLegacyDependencies(matrix.plugins);
        matrix.plugins.forEach(PluginReport::refreshStatus);
        matrix.summarize();
        return matrix;
    }

    private void addVersionCompatibility(PluginReport report, List<String> serverApis) {
        if (report.descriptor.supportedVersions.isEmpty()) return;
        try {
            VersionConstraint constraint = VersionConstraint.parse(report.descriptor.supportedVersions);
            for (String api : serverApis) {
                report.versionCompatibility.put(api, constraint.matches(SemVersion.parse(api)));
            }
        } catch (Exception ignored) {
            for (String api : serverApis) report.versionCompatibility.put(api, false);
        }
    }

    private void validateIdentities(List<PluginReport> reports) {
        Map<String, List<PluginReport>> byId = reports.stream()
            .filter(report -> !report.descriptor.internalName.isEmpty())
            .collect(Collectors.groupingBy(report -> report.descriptor.internalName.toLowerCase(Locale.ROOT)));
        for (Map.Entry<String, List<PluginReport>> duplicate : byId.entrySet()) {
            if (duplicate.getValue().size() <= 1) continue;
            String files = duplicate.getValue().stream().map(report -> report.fileName).collect(Collectors.joining(", "));
            for (PluginReport report : duplicate.getValue()) {
                report.addIssue(IssueSeverity.ERROR, "matrix_duplicate_id", "Duplicate internalName '" + duplicate.getKey() + "' across: " + files, "Keep exactly one archive for each plugin ID");
            }
        }
    }

    private void validateFileNames(List<PluginReport> reports) {
        Map<String, List<PluginReport>> byFile = reports.stream()
            .collect(Collectors.groupingBy(report -> report.fileName.toLowerCase(Locale.ROOT)));
        for (Map.Entry<String, List<PluginReport>> duplicate : byFile.entrySet()) {
            if (duplicate.getValue().size() <= 1) continue;
            String files = duplicate.getValue().stream().map(report -> report.fileName).collect(Collectors.joining(", "));
            for (PluginReport report : duplicate.getValue()) {
                report.addIssue(IssueSeverity.ERROR, "matrix_duplicate_filename", "Duplicate archive file name under case-insensitive deployment rules: " + files, "Keep one archive per file name");
            }
        }
    }

    private void validateLegacyDependencies(List<PluginReport> reports) {
        Map<String, List<PluginReport>> byName = reports.stream()
            .filter(report -> !report.descriptor.name.isEmpty())
            .collect(Collectors.groupingBy(report -> report.descriptor.name.trim().toLowerCase(Locale.ROOT)));
        Map<PluginReport, PluginReport> edges = new HashMap<>();
        for (PluginReport report : reports) {
            if (report.descriptor.apiVersion != 1 || report.descriptor.importName.trim().isEmpty()) continue;
            String dependencyName = report.descriptor.importName.trim().toLowerCase(Locale.ROOT);
            List<PluginReport> matches = byName.getOrDefault(dependencyName, Collections.emptyList());
            if (matches.isEmpty()) {
                report.addIssue(IssueSeverity.ERROR, "legacy_dependency_missing", "Missing V1 dependency named '" + report.descriptor.importName + "'", "Add the approved dependency or remove the legacy import relationship");
            } else if (matches.size() > 1) {
                report.addIssue(IssueSeverity.ERROR, "legacy_dependency_ambiguous", "V1 dependency name matches multiple archives: " + report.descriptor.importName, "Use unique plugin display names before migration");
            } else {
                edges.put(report, matches.get(0));
            }
        }
        Set<PluginReport> fullyVisited = new HashSet<>();
        for (PluginReport report : edges.keySet()) {
            detectCycle(report, edges, new ArrayDeque<>(), new HashSet<>(), fullyVisited);
        }
    }

    private void detectCycle(
        PluginReport current,
        Map<PluginReport, PluginReport> edges,
        Deque<PluginReport> stack,
        Set<PluginReport> visiting,
        Set<PluginReport> fullyVisited
    ) {
        if (fullyVisited.contains(current)) return;
        if (!visiting.add(current)) {
            List<PluginReport> cycle = new ArrayList<>();
            boolean include = false;
            for (PluginReport entry : stack) {
                if (entry == current) include = true;
                if (include) cycle.add(entry);
            }
            cycle.add(current);
            String chain = cycle.stream().map(report -> report.descriptor.name).collect(Collectors.joining(" -> "));
            for (PluginReport report : cycle) {
                if (report.issues.stream().noneMatch(issue -> "legacy_dependency_cycle".equals(issue.code))) {
                    report.addIssue(IssueSeverity.ERROR, "legacy_dependency_cycle", "V1 dependency cycle: " + chain, "Remove the cycle and migrate to independently shaded V2 plugins");
                }
            }
            return;
        }
        stack.addLast(current);
        PluginReport next = edges.get(current);
        if (next != null) detectCycle(next, edges, stack, visiting, fullyVisited);
        stack.removeLast();
        visiting.remove(current);
        fullyVisited.add(current);
    }
}
