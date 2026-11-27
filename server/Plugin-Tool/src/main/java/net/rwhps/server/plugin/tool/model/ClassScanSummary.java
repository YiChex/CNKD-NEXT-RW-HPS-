package net.rwhps.server.plugin.tool.model;

import java.util.Set;
import java.util.TreeSet;
import java.util.Map;
import java.util.TreeMap;

public final class ClassScanSummary {
    public int classCount;
    public int maxClassMajor;
    public boolean extendsLegacyPlugin;
    public final Set<String> referencedClasses = new TreeSet<>();
    public final Set<String> referencedMembers = new TreeSet<>();
    public final Set<String> declaredMethods = new TreeSet<>();
    public final Set<String> reflectiveClassNames = new TreeSet<>();
    public final Map<String, String> superClasses = new TreeMap<>();
    public final Map<String, Set<String>> interfaces = new TreeMap<>();
}
