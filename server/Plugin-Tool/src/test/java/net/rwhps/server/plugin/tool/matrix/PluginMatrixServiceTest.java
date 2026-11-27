package net.rwhps.server.plugin.tool.matrix;

import net.rwhps.server.plugin.tool.TestArchives;
import net.rwhps.server.plugin.tool.model.MatrixReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginMatrixServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void buildsCrossVersionMatrixAndDetectsDuplicateIds() throws Exception {
        TestArchives.v2(tempDir.resolve("one.jar"), "fixture.same", "One", ">= 1.2.0", false);
        TestArchives.v2(tempDir.resolve("two.jar"), "fixture.same", "Two", ">= 1.2.0", false);

        MatrixReport matrix = new PluginMatrixService().inspectDirectory(tempDir, List.of("1.2.0", "1.1.0"));

        assertEquals(2, matrix.failed);
        assertTrue(matrix.plugins.stream().allMatch(report -> report.issues.stream().anyMatch(issue -> "matrix_duplicate_id".equals(issue.code))));
        assertTrue(matrix.plugins.stream().allMatch(report -> report.versionCompatibility.get("1.2.0")));
        assertTrue(matrix.plugins.stream().noneMatch(report -> report.versionCompatibility.get("1.1.0")));
    }

    @Test
    void detectsMissingAndCyclicLegacyDependencies() throws Exception {
        TestArchives.v1(tempDir.resolve("missing.jar"), "fixture.missing", "Missing", "Unknown", false);
        TestArchives.v1(tempDir.resolve("a.jar"), "fixture.a", "A", "B", false);
        TestArchives.v1(tempDir.resolve("b.jar"), "fixture.b", "B", "A", false);

        MatrixReport matrix = new PluginMatrixService().inspectDirectory(tempDir, List.of("1.2.0"));

        assertEquals(3, matrix.failed);
        assertTrue(matrix.plugins.stream().anyMatch(report -> report.issues.stream().anyMatch(issue -> "legacy_dependency_missing".equals(issue.code))));
        assertTrue(matrix.plugins.stream().filter(report -> report.issues.stream().anyMatch(issue -> "legacy_dependency_cycle".equals(issue.code))).count() >= 2);
    }
}
