package net.rwhps.server.plugin.tool.model;

import java.util.ArrayList;
import java.util.List;

public final class SdkManifest {
    public int schemaVersion = 1;
    public String generatedAt = "";
    public String apiVersion = "";
    public String toolVersion = "";
    public String status = "UNKNOWN";
    public int assetCount;
    public int missingCount;
    public final List<SdkAsset> assets = new ArrayList<>();
    public final List<String> missing = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
}
