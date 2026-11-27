package net.rwhps.server.plugin.tool.model;

import java.util.ArrayList;
import java.util.List;

public final class MatrixReport {
    public String generatedAt = "";
    public String pluginDirectory = "";
    public String serverApi = "";
    public final List<String> serverApis = new ArrayList<>();
    public int total;
    public int passed;
    public int warnings;
    public int failed;
    public int legacyV1;
    public int apiV2;
    public final List<PluginReport> plugins = new ArrayList<>();

    public void summarize() {
        total = plugins.size();
        passed = (int) plugins.stream().filter(report -> "PASS".equals(report.status)).count();
        warnings = (int) plugins.stream().filter(report -> "WARN".equals(report.status)).count();
        failed = (int) plugins.stream().filter(report -> "FAIL".equals(report.status)).count();
        legacyV1 = (int) plugins.stream().filter(report -> !report.descriptor.internalName.isEmpty() && report.descriptor.apiVersion == 1).count();
        apiV2 = (int) plugins.stream().filter(report -> !report.descriptor.internalName.isEmpty() && report.descriptor.apiVersion == 2).count();
    }
}
