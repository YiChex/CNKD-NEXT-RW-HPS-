package net.rwhps.server.plugin.tool.model;

import java.util.ArrayList;
import java.util.List;

public final class ApiSurfaceSnapshot {
    public int schemaVersion = 1;
    public String apiVersion = "";
    public String implementationTitle = "";
    public String artifactFileName = "";
    public String artifactSha256 = "";
    public int maxClassMajor;
    public String toolVersion = "";
    public final List<ApiClassSurface> classes = new ArrayList<>();
}
