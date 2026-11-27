package net.rwhps.server.plugin.tool.model;

import java.util.ArrayList;
import java.util.List;

public final class ApiClassSurface {
    public String name = "";
    public String kind = "CLASS";
    public int access;
    public String signature = "";
    public String superName = "";
    public final List<String> interfaces = new ArrayList<>();
    public final List<ApiFieldSurface> fields = new ArrayList<>();
    public final List<ApiMethodSurface> methods = new ArrayList<>();
}
