package net.rwhps.server.plugin.tool.api;

import net.rwhps.server.plugin.tool.model.ApiClassSurface;
import net.rwhps.server.plugin.tool.model.ApiChange;
import net.rwhps.server.plugin.tool.model.ApiCompatibilityReport;
import net.rwhps.server.plugin.tool.model.ApiFieldSurface;
import net.rwhps.server.plugin.tool.model.ApiMethodSurface;
import net.rwhps.server.plugin.tool.model.ApiSurfaceSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiCompatibilityComparatorTest {
    @Test
    void acceptsAdditiveMinorChangeAndReportsIntentionalVersionConstantUpdate() {
        ApiSurfaceSnapshot baseline = snapshot("1.1.0", false, false);
        ApiSurfaceSnapshot candidate = snapshot("1.2.0", true, true);

        ApiCompatibilityReport report = new ApiCompatibilityComparator().compare(baseline, candidate, "minor");

        assertTrue(report.compatible);
        assertTrue(report.releaseAllowed);
        assertEquals("PASS", report.status);
        assertEquals(0, report.breakingChanges);
        assertTrue(report.additiveChanges >= 2);
        assertTrue(report.informationalChanges >= 1);
    }

    @Test
    void rejectsRemovedMethodAndMissingVersionBump() {
        ApiSurfaceSnapshot baseline = snapshot("1.1.0", true, false);
        ApiSurfaceSnapshot removed = snapshot("1.2.0", false, false);
        ApiSurfaceSnapshot sameVersion = snapshot("1.1.0", true, true);

        ApiCompatibilityReport removedReport = new ApiCompatibilityComparator().compare(baseline, removed, "auto");
        ApiCompatibilityReport sameVersionReport = new ApiCompatibilityComparator().compare(baseline, sameVersion, "auto");

        assertFalse(removedReport.compatible);
        assertTrue(removedReport.changes.stream().anyMatch(change -> "METHOD_REMOVED".equals(change.code)));
        assertTrue(removedReport.changes.stream().anyMatch(change -> "BREAKING_REQUIRES_MAJOR".equals(change.code)));
        assertTrue(sameVersionReport.compatible);
        assertFalse(sameVersionReport.releaseAllowed);
        assertTrue(sameVersionReport.changes.stream().anyMatch(change -> "VERSION_NOT_INCREMENTED".equals(change.code)));
    }

    @Test
    void permitsBreakingChangeOnlyWhenMajorVersionIncreases() {
        ApiCompatibilityReport report = new ApiCompatibilityComparator().compare(
            snapshot("1.1.0", true, false),
            snapshot("2.0.0", false, false),
            "major"
        );

        assertFalse(report.compatible);
        assertTrue(report.releaseAllowed);
        assertEquals("PASS", report.status);
        assertEquals(1, report.surfaceBreakingChanges);
        assertTrue(report.changes.stream().anyMatch(change -> "METHOD_REMOVED".equals(change.code)));
    }

    @Test
    void rejectsPolicyViolationEvenWhenStoredChangeReportIsFull() {
        ApiCompatibilityReport report = new ApiCompatibilityReport();
        for (int i = 0; i < 4096; i++) {
            report.addChange(new ApiChange("ADDITIVE", "CLASS_ADDED", "Added" + i, "added"));
        }
        report.addChange(new ApiChange("BREAKING", "VERSION_NOT_INCREMENTED", "apiVersion", "invalid"));

        report.finalizeCompatibility(0);

        assertEquals(4097, report.totalChanges);
        assertEquals(4096, report.changes.size());
        assertEquals(1, report.omittedChanges);
        assertEquals(1, report.policyViolations);
        assertFalse(report.releaseAllowed);
        assertEquals("FAIL", report.status);
    }

    private static ApiSurfaceSnapshot snapshot(String version, boolean includeMethod, boolean includeNewClass) {
        ApiSurfaceSnapshot snapshot = new ApiSurfaceSnapshot();
        snapshot.apiVersion = version;
        snapshot.artifactFileName = "api-" + version + ".jar";
        ApiClassSurface versions = new ApiClassSurface();
        versions.name = "net.rwhps.server.plugin.api.ApiVersions";
        versions.access = 0x0011;
        ApiFieldSurface descriptor = new ApiFieldSurface();
        descriptor.name = "SERVER_API";
        descriptor.descriptor = "Ljava/lang/String;";
        descriptor.access = 0x0019;
        descriptor.constantValue = "java.lang.String:" + version;
        versions.fields.add(descriptor);
        snapshot.classes.add(versions);

        ApiClassSurface service = new ApiClassSurface();
        service.name = "net.rwhps.server.plugin.api.Service";
        service.kind = "INTERFACE";
        service.access = 0x0601;
        if (includeMethod) {
            ApiMethodSurface method = new ApiMethodSurface();
            method.name = "stable";
            method.descriptor = "()V";
            method.access = 0x0001;
            service.methods.add(method);
        }
        snapshot.classes.add(service);
        if (includeNewClass) {
            ApiClassSurface added = new ApiClassSurface();
            added.name = "net.rwhps.server.plugin.api.Added";
            added.kind = "CLASS";
            added.access = 0x0011;
            snapshot.classes.add(added);
        }
        return snapshot;
    }
}
