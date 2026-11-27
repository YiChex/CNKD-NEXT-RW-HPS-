package net.rwhps.server.plugin.tool.lock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.rwhps.server.plugin.tool.TestArchives;
import net.rwhps.server.plugin.tool.matrix.PluginMatrixService;
import net.rwhps.server.plugin.tool.model.MatrixReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginLockWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void writesCompleteLockAndRechecksArchiveHash() throws Exception {
        Path archive = TestArchives.v2(tempDir.resolve("valid.jar"), "fixture.valid", "Valid", ">= 1.2.0", false);
        MatrixReport matrix = new PluginMatrixService().inspectDirectory(tempDir, List.of("1.2.0"));
        Path lock = tempDir.resolve("plugin-lock.json");

        new PluginLockWriter().write(matrix, lock, "test-reviewer", false);
        JsonObject root = JsonParser.parseString(Files.readString(lock)).getAsJsonObject();
        assertEquals(1, root.get("lockVersion").getAsInt());
        assertEquals(1, root.getAsJsonArray("plugins").size());
        assertEquals("fixture.valid", root.getAsJsonArray("plugins").get(0).getAsJsonObject().get("internalName").getAsString());

        Files.write(archive, new byte[] {1}, StandardOpenOption.APPEND);
        Path changedLock = tempDir.resolve("changed-lock.json");
        assertThrows(IllegalStateException.class, () -> new PluginLockWriter().write(matrix, changedLock, "test-reviewer", false));
        assertFalse(Files.exists(changedLock));
    }
}
