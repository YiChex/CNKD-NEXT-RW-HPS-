package net.rwhps.server.plugin.tool.output;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.rwhps.server.plugin.tool.io.AtomicFiles;
import net.rwhps.server.plugin.tool.model.MatrixReport;
import net.rwhps.server.plugin.tool.model.ApiChange;
import net.rwhps.server.plugin.tool.model.ApiCompatibilityReport;
import net.rwhps.server.plugin.tool.model.ApiClassSurface;
import net.rwhps.server.plugin.tool.model.ApiSurfaceSnapshot;
import net.rwhps.server.plugin.tool.model.PluginIssue;
import net.rwhps.server.plugin.tool.model.PluginReport;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

public final class ToolReportWriter {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public void printPlugin(PluginReport report, PrintStream output, boolean migrationDetails) {
        output.println(
            "CNKD_PLUGIN_REPORT status=" + report.status +
                " file=" + report.fileName +
                " id=" + valueOrDash(report.descriptor.internalName) +
                " api=V" + report.descriptor.apiVersion +
                " compatible=" + report.compatible +
                " serverApi=" + report.serverApi
        );
        output.println(
            "  version=" + valueOrDash(report.descriptor.version) +
                " supported=" + valueOrDash(report.descriptor.supportedVersions) +
                " classes=" + report.classes.classCount +
                " maxClassMajor=" + report.classes.maxClassMajor +
                " sha256=" + valueOrDash(report.archiveSha256)
        );
        if (!report.versionCompatibility.isEmpty()) {
            output.println("  versionCompatibility=" + renderCompatibility(report.versionCompatibility));
        }
        for (PluginIssue issue : report.issues) {
            output.println("  ISSUE " + issue.severity + " " + issue.code + " | " + issue.message);
            if (!issue.suggestion.isEmpty()) output.println("    action=" + issue.suggestion);
        }
        if (report.omittedIssueCount > 0) {
            output.println("  ISSUE_TRUNCATED omitted=" + report.omittedIssueCount);
        }
        if (migrationDetails) {
            output.println("  migrationReadiness=" + report.migrationReadiness);
            for (String action : report.migrationActions) output.println("  MIGRATE " + action);
            for (String reference : report.blockingReferences) output.println("  BLOCKED_REF " + reference);
        }
    }

    public void printMatrix(MatrixReport matrix, PrintStream output, boolean migrationDetails) {
        output.println(
            "CNKD_PLUGIN_MATRIX directory=" + matrix.pluginDirectory +
                " serverApis=" + String.join(",", matrix.serverApis) +
                " total=" + matrix.total +
                " pass=" + matrix.passed +
                " warn=" + matrix.warnings +
                " fail=" + matrix.failed +
                " v1=" + matrix.legacyV1 +
                " v2=" + matrix.apiV2
        );
        for (PluginReport report : matrix.plugins) printPlugin(report, output, migrationDetails);
    }

    public void writeJson(Path target, Object report, boolean force) throws IOException {
        AtomicFiles.writeUtf8(target, gson.toJson(report), force, false);
    }

    public void printApiSurface(ApiSurfaceSnapshot snapshot, PrintStream output) {
        output.println(
            "CNKD_API_SURFACE version=" + snapshot.apiVersion +
                " classes=" + snapshot.classes.size() +
                " maxClassMajor=" + snapshot.maxClassMajor +
                " file=" + snapshot.artifactFileName +
                " sha256=" + snapshot.artifactSha256
        );
        for (ApiClassSurface surface : snapshot.classes) {
            output.println(
                "  CLASS " + surface.kind + " " + surface.name +
                    " fields=" + surface.fields.size() +
                    " methods=" + surface.methods.size()
            );
        }
    }

    public void printApiCompatibility(ApiCompatibilityReport report, PrintStream output) {
        output.println(
            "CNKD_API_COMPATIBILITY status=" + report.status +
                " compatible=" + report.compatible +
                " binaryCompatible=" + report.binaryCompatible +
                " releaseAllowed=" + report.releaseAllowed +
                " baseline=" + report.baselineVersion +
                " candidate=" + report.candidateVersion +
                " total=" + report.totalChanges +
                " breaking=" + report.breakingChanges +
                " surfaceBreaking=" + report.surfaceBreakingChanges +
                " policyViolations=" + report.policyViolations +
                " additive=" + report.additiveChanges +
                " warnings=" + report.warnings +
                " info=" + report.informationalChanges
        );
        for (ApiChange change : report.changes) {
            output.println("  " + change.severity + " " + change.code + " " + change.symbol + " | " + change.message);
        }
        if (report.omittedChanges > 0) output.println("  CHANGES_TRUNCATED omitted=" + report.omittedChanges);
    }

    private static String renderCompatibility(Map<String, Boolean> compatibility) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, Boolean> entry : compatibility.entrySet()) {
            if (result.length() > 0) result.append(',');
            result.append(entry.getKey()).append(':').append(entry.getValue());
        }
        return result.toString();
    }

    private static String valueOrDash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }
}
