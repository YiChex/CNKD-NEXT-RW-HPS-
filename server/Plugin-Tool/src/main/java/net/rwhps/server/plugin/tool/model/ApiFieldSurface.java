package net.rwhps.server.plugin.tool.model;

public final class ApiFieldSurface {
    public String name = "";
    public String descriptor = "";
    public String signature = "";
    public int access;
    public String constantValue = "";

    public String key() {
        return name + descriptor;
    }
}
