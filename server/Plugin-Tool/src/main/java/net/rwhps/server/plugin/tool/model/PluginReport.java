package net.rwhps.server.plugin.tool.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

public final class PluginReport {
    public String path = "";
    public String fileName = "";
    public String status = "FAIL";
    public boolean valid;
    public boolean compatible;
    public String serverApi = "";
    public String archiveSha256 = "";
    public String descriptorSha256 = "";
    public PluginDescriptorData descriptor = new PluginDescriptorData();
    public ClassScanSummary classes = new ClassScanSummary();
    public boolean hasImportsJson;
    public boolean javascript;
    public String migrationReadiness = "UNKNOWN";
    public final Map<String, Boolean> versionCompatibility = new LinkedHashMap<>();
    public final List<String> migrationActions = new ArrayList<>();
    public final List<String> blockingReferences = new ArrayList<>();
    public final List<PluginIssue> issues = new ArrayList<>();
    public int omittedIssueCount;
    private transient boolean observedError;
    private transient boolean observedWarning;

    public void addIssue(IssueSeverity severity, String code, String message, String suggestion) {
        observedError |= severity == IssueSeverity.ERROR;
        observedWarning |= severity == IssueSeverity.WARN;
        if (issues.size() < 256) issues.add(new PluginIssue(severity, code, message, suggestion));
        else omittedIssueCount++;
        refreshStatus();
    }

    public void refreshStatus() {
        boolean errors = observedError || issues.stream().anyMatch(issue -> issue.severity == IssueSeverity.ERROR);
        boolean warnings = observedWarning || issues.stream().anyMatch(issue -> issue.severity == IssueSeverity.WARN);
        valid = !errors;
        status = errors ? "FAIL" : warnings ? "WARN" : "PASS";
    }
}
