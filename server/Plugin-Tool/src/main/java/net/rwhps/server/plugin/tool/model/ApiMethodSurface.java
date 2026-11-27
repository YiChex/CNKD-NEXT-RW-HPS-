package net.rwhps.server.plugin.tool.model;

import java.util.ArrayList;
import java.util.List;

public final class ApiMethodSurface {
    public String name = "";
    public String descriptor = "";
    public String signature = "";
    public int access;
    public boolean annotationDefault;
    public final List<String> exceptions = new ArrayList<>();

    public String key() {
        return name + descriptor;
    }
}
