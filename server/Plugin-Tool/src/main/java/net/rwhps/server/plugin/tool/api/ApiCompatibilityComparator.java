package net.rwhps.server.plugin.tool.api;

import net.rwhps.server.plugin.tool.model.ApiChange;
import net.rwhps.server.plugin.tool.model.ApiClassSurface;
import net.rwhps.server.plugin.tool.model.ApiCompatibilityReport;
import net.rwhps.server.plugin.tool.model.ApiFieldSurface;
import net.rwhps.server.plugin.tool.model.ApiMethodSurface;
import net.rwhps.server.plugin.tool.model.ApiSurfaceSnapshot;
import net.rwhps.server.plugin.tool.version.SemVersion;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Compares the public/protected JVM API surface and applies SemVer release gates. */
public final class ApiCompatibilityComparator {
    public ApiCompatibilityReport compare(ApiSurfaceSnapshot baseline, ApiSurfaceSnapshot candidate, String expectedChange) {
        ApiCompatibilityReport report = new ApiCompatibilityReport();
        report.baselineVersion = baseline.apiVersion;
        report.candidateVersion = candidate.apiVersion;
        report.baselineArtifactSha256 = value(baseline.artifactSha256);
        report.candidateArtifactSha256 = value(candidate.artifactSha256);

        Map<String, ApiClassSurface> baselineClasses = classesByName(baseline);
        Map<String, ApiClassSurface> candidateClasses = classesByName(candidate);
        for (Map.Entry<String, ApiClassSurface> entry : baselineClasses.entrySet()) {
            ApiClassSurface current = candidateClasses.get(entry.getKey());
            if (current == null) {
                change(report, "BREAKING", "CLASS_REMOVED", entry.getKey(), "Public API class was removed");
            } else {
                compareClass(entry.getValue(), current, report);
            }
        }
        for (Map.Entry<String, ApiClassSurface> entry : candidateClasses.entrySet()) {
            if (!baselineClasses.containsKey(entry.getKey())) {
                change(report, "ADDITIVE", "CLASS_ADDED", entry.getKey(), "Public API class was added");
            }
        }

        int surfaceBreakingChanges = report.breakingChanges;
        applyVersionPolicy(report, expectedChange == null ? "auto" : expectedChange);
        report.finalizeCompatibility(surfaceBreakingChanges);
        return report;
    }

    private void compareClass(ApiClassSurface baseline, ApiClassSurface candidate, ApiCompatibilityReport report) {
        String symbol = baseline.name;
        if (!baseline.kind.equals(candidate.kind)) {
            change(report, "BREAKING", "CLASS_KIND_CHANGED", symbol, "Class kind changed from " + baseline.kind + " to " + candidate.kind);
        }
        compareVisibility(baseline.access, candidate.access, report, "CLASS_VISIBILITY_NARROWED", symbol, "class visibility was narrowed");
        if (baseline.superName == null ? candidate.superName != null : !baseline.superName.equals(candidate.superName)) {
            change(report, "BREAKING", "CLASS_SUPER_CHANGED", symbol, "Superclass changed from " + value(baseline.superName) + " to " + value(candidate.superName));
        }
        compareClassModifiers(baseline.access, candidate.access, symbol, report);
        if (!value(baseline.signature).equals(value(candidate.signature))) {
            change(report, "WARN", "CLASS_GENERIC_SIGNATURE_CHANGED", symbol, "Generic class signature changed");
        }

        Set<String> baselineInterfaces = new TreeSet<>(baseline.interfaces);
        Set<String> candidateInterfaces = new TreeSet<>(candidate.interfaces);
        for (String removed : difference(baselineInterfaces, candidateInterfaces)) {
            change(report, "BREAKING", "INTERFACE_REMOVED", symbol, "Implemented/extended interface was removed: " + removed);
        }
        for (String added : difference(candidateInterfaces, baselineInterfaces)) {
            change(report, "ADDITIVE", "INTERFACE_ADDED", symbol, "Implemented/extended interface was added: " + added);
        }

        compareFields(baseline, candidate, report);
        compareMethods(baseline, candidate, report);
    }

    private void compareFields(ApiClassSurface baseline, ApiClassSurface candidate, ApiCompatibilityReport report) {
        Map<String, ApiFieldSurface> oldFields = new TreeMap<>();
        Map<String, ApiFieldSurface> newFields = new TreeMap<>();
        for (ApiFieldSurface field : baseline.fields) oldFields.put(field.key(), field);
        for (ApiFieldSurface field : candidate.fields) newFields.put(field.key(), field);
        for (Map.Entry<String, ApiFieldSurface> entry : oldFields.entrySet()) {
            ApiFieldSurface current = newFields.get(entry.getKey());
            String symbol = baseline.name + "#" + entry.getValue().name;
            if (current == null) {
                change(report, "BREAKING", "FIELD_REMOVED", symbol, "Public/protected field was removed or its descriptor changed");
            } else {
                compareMemberVisibility(entry.getValue().access, current.access, report, "FIELD_VISIBILITY_NARROWED", symbol);
                compareStaticFinal(entry.getValue().access, current.access, report, "FIELD", symbol);
                if (!value(entry.getValue().signature).equals(value(current.signature))) {
                    change(report, "WARN", "FIELD_GENERIC_SIGNATURE_CHANGED", symbol, "Generic field signature changed");
                }
                if (!value(entry.getValue().constantValue).equals(value(current.constantValue)) &&
                    !isApiVersionConstant(baseline, entry.getValue())) {
                    change(report, "WARN", "FIELD_CONSTANT_CHANGED", symbol, "Compile-time field constant changed");
                } else if (!value(entry.getValue().constantValue).equals(value(current.constantValue))) {
                    change(report, "INFO", "API_VERSION_CONSTANT_UPDATED", symbol, "API version constant changed with the candidate API");
                }
            }
        }
        for (Map.Entry<String, ApiFieldSurface> entry : newFields.entrySet()) {
            if (!oldFields.containsKey(entry.getKey())) {
                change(report, "ADDITIVE", "FIELD_ADDED", candidate.name + "#" + entry.getValue().name, "Public/protected field was added");
            }
        }
    }

    private void compareMethods(ApiClassSurface baseline, ApiClassSurface candidate, ApiCompatibilityReport report) {
        Map<String, ApiMethodSurface> oldMethods = new TreeMap<>();
        Map<String, ApiMethodSurface> newMethods = new TreeMap<>();
        for (ApiMethodSurface method : baseline.methods) oldMethods.put(method.key(), method);
        for (ApiMethodSurface method : candidate.methods) newMethods.put(method.key(), method);
        for (Map.Entry<String, ApiMethodSurface> entry : oldMethods.entrySet()) {
            ApiMethodSurface current = newMethods.get(entry.getKey());
            String symbol = baseline.name + "#" + entry.getValue().name + entry.getValue().descriptor;
            if (current == null) {
                change(report, "BREAKING", "METHOD_REMOVED", symbol, "Public/protected method was removed or its descriptor changed");
            } else {
                compareMemberVisibility(entry.getValue().access, current.access, report, "METHOD_VISIBILITY_NARROWED", symbol);
                compareStaticFinal(entry.getValue().access, current.access, report, "METHOD", symbol);
                boolean oldAbstract = has(entry.getValue().access, 0x0400);
                boolean newAbstract = has(current.access, 0x0400);
                if (!oldAbstract && newAbstract) change(report, "BREAKING", "METHOD_BECAME_ABSTRACT", symbol, "Concrete method became abstract");
                if (oldAbstract && !newAbstract) change(report, "ADDITIVE", "METHOD_BECAME_CONCRETE", symbol, "Abstract method gained an implementation");
                if (!value(entry.getValue().signature).equals(value(current.signature))) {
                    change(report, "WARN", "METHOD_GENERIC_SIGNATURE_CHANGED", symbol, "Generic method signature changed");
                }
                if (!entry.getValue().exceptions.equals(current.exceptions)) {
                    change(report, "WARN", "METHOD_EXCEPTIONS_CHANGED", symbol, "Declared exception list changed");
                }
                if (entry.getValue().annotationDefault != current.annotationDefault) {
                    change(report, "WARN", "ANNOTATION_DEFAULT_CHANGED", symbol, "Annotation default presence changed");
                }
            }
        }
        for (Map.Entry<String, ApiMethodSurface> entry : newMethods.entrySet()) {
            if (!oldMethods.containsKey(entry.getKey())) {
                boolean abstractMethod = has(entry.getValue().access, 0x0400);
                if (abstractMethod) {
                    change(report, "BREAKING", "ABSTRACT_METHOD_ADDED", candidate.name + "#" + entry.getValue().name + entry.getValue().descriptor, "Abstract method added to a public API type");
                } else {
                    change(report, "ADDITIVE", "METHOD_ADDED", candidate.name + "#" + entry.getValue().name + entry.getValue().descriptor, "Public/protected method was added");
                }
            }
        }
    }

    private void compareClassModifiers(int oldAccess, int newAccess, String symbol, ApiCompatibilityReport report) {
        if (!has(oldAccess, 0x0010) && has(newAccess, 0x0010)) {
            change(report, "BREAKING", "CLASS_BECAME_FINAL", symbol, "Class became final");
        }
        if (!has(oldAccess, 0x0400) && has(newAccess, 0x0400)) {
            change(report, "BREAKING", "CLASS_BECAME_ABSTRACT", symbol, "Class became abstract");
        }
    }

    private void compareVisibility(int oldAccess, int newAccess, ApiCompatibilityReport report, String code, String symbol, String message) {
        if (visibility(newAccess) < visibility(oldAccess)) change(report, "BREAKING", code, symbol, message);
    }

    private void compareMemberVisibility(int oldAccess, int newAccess, ApiCompatibilityReport report, String code, String symbol) {
        compareVisibility(oldAccess, newAccess, report, code, symbol, "member visibility was narrowed");
    }

    private void compareStaticFinal(int oldAccess, int newAccess, ApiCompatibilityReport report, String type, String symbol) {
        if (has(oldAccess, 0x0008) != has(newAccess, 0x0008)) {
            change(report, "BREAKING", type + "_STATIC_CHANGED", symbol, type + " static modifier changed");
        }
        if (!has(oldAccess, 0x0010) && has(newAccess, 0x0010)) {
            change(report, "BREAKING", type + "_BECAME_FINAL", symbol, type + " became final");
        } else if (has(oldAccess, 0x0010) && !has(newAccess, 0x0010)) {
            change(report, "WARN", type + "_FINAL_REMOVED", symbol, type + " final modifier was removed");
        }
    }

    private void applyVersionPolicy(ApiCompatibilityReport report, String expectedChange) {
        SemVersion baseline;
        SemVersion candidate;
        try {
            baseline = SemVersion.parse(report.baselineVersion);
            candidate = SemVersion.parse(report.candidateVersion);
        } catch (RuntimeException error) {
            change(report, "BREAKING", "VERSION_INVALID", "apiVersion", "Baseline or candidate API version is invalid");
            return;
        }
        if (candidate.compareTo(baseline) < 0) {
            change(report, "BREAKING", "VERSION_REGRESSED", "apiVersion", "Candidate API version is lower than the baseline");
        } else if (candidate.compareTo(baseline) == 0 && report.totalChanges > 0) {
            change(report, "BREAKING", "VERSION_NOT_INCREMENTED", "apiVersion", "API surface changed without increasing the API version");
        }

        int surfaceLevel = report.breakingChanges > 0 ? 3 : report.additiveChanges > 0 ? 2 : report.warnings > 0 ? 1 : 0;
        if (report.breakingChanges > 0 && candidate.major <= baseline.major) {
            change(report, "BREAKING", "BREAKING_REQUIRES_MAJOR", "apiVersion", "Breaking API changes require a major version increment");
        } else if (report.breakingChanges == 0 && report.additiveChanges > 0 &&
            candidate.major == baseline.major && candidate.minor <= baseline.minor) {
            change(report, "BREAKING", "ADDITIVE_REQUIRES_MINOR", "apiVersion", "Added API requires a minor version increment");
        }

        String normalizedExpected = expectedChange.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"auto".equals(normalizedExpected)) {
            int expectedLevel = level(normalizedExpected);
            if (expectedLevel < 0) {
                change(report, "BREAKING", "EXPECTED_CHANGE_INVALID", "expectedChange", "Expected change must be auto, patch, minor or major");
            } else if (surfaceLevel > expectedLevel) {
                change(report, "BREAKING", "EXPECTED_CHANGE_TOO_LOW", "expectedChange", "Observed API changes exceed the declared release level");
            } else if (surfaceLevel < expectedLevel && report.totalChanges > 0) {
                change(report, "WARN", "EXPECTED_CHANGE_HIGHER_THAN_OBSERVED", "expectedChange", "Declared release level is higher than observed API changes");
            }
        }
        if (candidate.major > baseline.major && report.breakingChanges == 0 && report.totalChanges > 0) {
            change(report, "WARN", "MAJOR_WITHOUT_BREAKING_CHANGE", "apiVersion", "Major version increment has no detected breaking surface change");
        }
    }

    private static int level(String value) {
        switch (value) {
            case "patch": return 1;
            case "minor": return 2;
            case "major": return 3;
            default: return -1;
        }
    }

    private static boolean isApiVersionConstant(ApiClassSurface owner, ApiFieldSurface field) {
        return "net.rwhps.server.plugin.api.ApiVersions".equals(owner.name) && "SERVER_API".equals(field.name);
    }

    private static Map<String, ApiClassSurface> classesByName(ApiSurfaceSnapshot snapshot) {
        Map<String, ApiClassSurface> result = new TreeMap<>();
        for (ApiClassSurface surface : snapshot.classes) result.put(surface.name, surface);
        return result;
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static int visibility(int access) {
        if (has(access, 0x0001)) return 3;
        if (has(access, 0x0004)) return 2;
        if (has(access, 0x0002)) return 0;
        return 1;
    }

    private static boolean has(int access, int mask) {
        return (access & mask) != 0;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static void change(ApiCompatibilityReport report, String severity, String code, String symbol, String message) {
        report.addChange(new ApiChange(severity, code, symbol, message));
    }
}
