package net.rwhps.server.plugin.tool.inspection;

import net.rwhps.server.plugin.tool.TestArchives;
import net.rwhps.server.plugin.tool.model.PluginReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginArchiveInspectorTest {
    @TempDir
    Path tempDir;

    @Test
    void validatesDirectAndIndirectV2MainContracts() throws Exception {
        PluginArchiveInspector inspector = new PluginArchiveInspector("1.2.0");
        PluginReport direct = inspector.inspect(TestArchives.v2(tempDir.resolve("direct.jar"), "fixture.direct", "Direct", ">= 1.2.0", false));
        PluginReport indirect = inspector.inspect(TestArchives.indirectV2(tempDir.resolve("indirect.jar")));

        assertEquals("PASS", direct.status);
        assertEquals("PASS", indirect.status);
        assertTrue(direct.compatible);
        assertEquals("ALREADY_V2", indirect.migrationReadiness);
    }

    @Test
    void rejectsV2InternalReferencesAndUnsafeArchives() throws Exception {
        PluginArchiveInspector inspector = new PluginArchiveInspector("1.2.0");
        PluginReport internal = inspector.inspect(TestArchives.v2(tempDir.resolve("internal.jar"), "fixture.internal", "Internal", ">= 1.2.0", true));
        PluginReport unsafe = inspector.inspect(TestArchives.unsafe(tempDir.resolve("unsafe.jar")));

        assertEquals("FAIL", internal.status);
        assertTrue(internal.issues.stream().anyMatch(issue -> "v2_internal_reference".equals(issue.code)));
        assertEquals("FAIL", unsafe.status);
        assertTrue(unsafe.issues.stream().anyMatch(issue -> "archive_unsafe_path".equals(issue.code)));
    }

    @Test
    void producesActionableV1MigrationReport() throws Exception {
        PluginReport legacy = new PluginArchiveInspector("1.2.0").inspect(
            TestArchives.v1(tempDir.resolve("legacy.jar"), "fixture.legacy", "Legacy", "", true)
        );

        assertEquals("WARN", legacy.status);
        assertEquals("MAPPABLE", legacy.migrationReadiness);
        assertTrue(legacy.migrationActions.stream().anyMatch(action -> action.contains("context.logger")));
        assertTrue(legacy.migrationActions.stream().anyMatch(action -> action.contains("registerConsole")));
    }

    @Test
    void rejectsDuplicateFieldsStringApiVersionAndNewerBytecode() throws Exception {
        String main = "fixture.strict.Main";
        byte[] java11 = TestArchives.pluginClass("fixture/strict/Main", "java/lang/Object", new String[] {"net/rwhps/server/plugin/api/CnkdPlugin"}, false);
        String duplicate = "{\"apiVersion\":2,\"name\":\"Strict\",\"internalName\":\"fixture.strict\",\"internalName\":\"fixture.other\",\"author\":\"test\",\"main\":\"" + main + "\",\"description\":\"fixture\",\"version\":\"1.0.0\",\"supportedVersions\":\">= 1.2.0\",\"capabilities\":[]}";
        String stringApi = "{\"apiVersion\":\"2\",\"name\":\"Strict\",\"internalName\":\"fixture.strict\",\"author\":\"test\",\"main\":\"" + main + "\",\"description\":\"fixture\",\"version\":\"1.0.0\",\"supportedVersions\":\">= 1.2.0\",\"capabilities\":[]}";
        String java17Descriptor = "{\"apiVersion\":2,\"name\":\"Strict\",\"internalName\":\"fixture.strict\",\"author\":\"test\",\"main\":\"" + main + "\",\"description\":\"fixture\",\"version\":\"1.0.0\",\"supportedVersions\":\">= 1.2.0\",\"capabilities\":[]}";
        PluginArchiveInspector inspector = new PluginArchiveInspector("1.2.0");

        PluginReport duplicateReport = inspector.inspect(TestArchives.customDescriptor(tempDir.resolve("duplicate.jar"), duplicate, java11, "fixture/strict/Main.class"));
        PluginReport stringApiReport = inspector.inspect(TestArchives.customDescriptor(tempDir.resolve("string-api.jar"), stringApi, java11, "fixture/strict/Main.class"));
        byte[] java17 = TestArchives.pluginClass(org.objectweb.asm.Opcodes.V17, "fixture/strict/Main", "java/lang/Object", new String[] {"net/rwhps/server/plugin/api/CnkdPlugin"}, false);
        PluginReport java17Report = inspector.inspect(TestArchives.customDescriptor(tempDir.resolve("java17.jar"), java17Descriptor, java17, "fixture/strict/Main.class"));

        assertTrue(duplicateReport.issues.stream().anyMatch(issue -> "descriptor_duplicate_field".equals(issue.code)));
        assertTrue(stringApiReport.issues.stream().anyMatch(issue -> "descriptor_api_type".equals(issue.code)));
        assertTrue(java17Report.issues.stream().anyMatch(issue -> "class_java_target".equals(issue.code)));
        assertEquals("FAIL", duplicateReport.status);
        assertEquals("FAIL", stringApiReport.status);
        assertEquals("FAIL", java17Report.status);
    }
}
