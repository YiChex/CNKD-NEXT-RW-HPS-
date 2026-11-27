package net.rwhps.server.plugin.tool.model;

import java.util.ArrayList;
import java.util.List;

public final class ApiCompatibilityReport {
    public int schemaVersion = 1;
    public String baselineVersion = "";
    public String candidateVersion = "";
    public String baselineArtifactSha256 = "";
    public String candidateArtifactSha256 = "";
    public String status = "UNKNOWN";
    public boolean compatible;
    public boolean binaryCompatible;
    public boolean releaseAllowed;
    public int breakingChanges;
    public int surfaceBreakingChanges;
    public int policyViolations;
    public int warnings;
    public int additiveChanges;
    public int informationalChanges;
    public int totalChanges;
    public int omittedChanges;
    public final List<ApiChange> changes = new ArrayList<>();

    public void addChange(ApiChange change) {
        totalChanges++;
        if ("BREAKING".equals(change.severity)) breakingChanges++;
        else if ("WARN".equals(change.severity)) warnings++;
        else if ("ADDITIVE".equals(change.severity)) additiveChanges++;
        else if ("INFO".equals(change.severity)) informationalChanges++;
        if (isPolicyViolation(change.code)) policyViolations++;
        if (changes.size() < 4096) changes.add(change);
        else omittedChanges++;
    }

    public void finalizeCompatibility(int surfaceBreakingChanges) {
        this.surfaceBreakingChanges = surfaceBreakingChanges;
        this.binaryCompatible = surfaceBreakingChanges == 0;
        this.releaseAllowed = policyViolations == 0;
        this.compatible = binaryCompatible;
        this.status = policyViolations > 0 ? "FAIL" : warnings > 0 ? "WARN" : "PASS";
    }

    private static boolean isPolicyViolation(String code) {
        return "VERSION_INVALID".equals(code) ||
            "VERSION_REGRESSED".equals(code) ||
            "VERSION_NOT_INCREMENTED".equals(code) ||
            "BREAKING_REQUIRES_MAJOR".equals(code) ||
            "ADDITIVE_REQUIRES_MINOR".equals(code) ||
            "EXPECTED_CHANGE_INVALID".equals(code) ||
            "EXPECTED_CHANGE_TOO_LOW".equals(code);
    }

    public void summarize() {
        if (totalChanges == 0 && !changes.isEmpty()) {
            totalChanges = changes.size();
            breakingChanges = (int) changes.stream().filter(change -> "BREAKING".equals(change.severity)).count();
            warnings = (int) changes.stream().filter(change -> "WARN".equals(change.severity)).count();
            additiveChanges = (int) changes.stream().filter(change -> "ADDITIVE".equals(change.severity)).count();
            informationalChanges = (int) changes.stream().filter(change -> "INFO".equals(change.severity)).count();
        }
    }
}
