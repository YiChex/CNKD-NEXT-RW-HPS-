package net.rwhps.server.plugin.tool.model;

import java.util.ArrayList;
import java.util.List;

public final class PluginDescriptorData {
    public String name = "";
    public String internalName = "";
    public String author = "";
    public String main = "";
    public String description = "";
    public String version = "";
    public String supportedVersions = "";
    public String importName = "";
    public int apiVersion = 1;
    public List<String> capabilities = new ArrayList<>();
}
