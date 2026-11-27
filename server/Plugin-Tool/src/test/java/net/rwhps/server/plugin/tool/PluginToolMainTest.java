package net.rwhps.server.plugin.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginToolMainTest {
    @TempDir
    Path tempDir;

    @Test
    void returnsStableExitCodesForValidCorruptAndUsageCases() throws Exception {
        Path valid = TestArchives.v2(tempDir.resolve("valid.jar"), "fixture.cli", "CLI", ">= 1.2.0", false);
        Path corrupt = TestArchives.corrupt(tempDir.resolve("corrupt.jar"));

        Result validResult = run("validate", valid.toString());
        Result corruptResult = run("validate", corrupt.toString());
        Result usageResult = run("validate");

        assertEquals(PluginToolMain.EXIT_OK, validResult.exitCode);
        assertTrue(validResult.output.contains("status=PASS"));
        assertEquals(PluginToolMain.EXIT_VALIDATION, corruptResult.exitCode);
        assertTrue(corruptResult.output.contains("archive_zip"));
        assertEquals(PluginToolMain.EXIT_USAGE, usageResult.exitCode);
    }

    private Result run(String... arguments) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int code;
        try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8.name());
             PrintStream err = new PrintStream(error, true, StandardCharsets.UTF_8.name())) {
            code = PluginToolMain.run(arguments, out, err);
        }
        return new Result(code, output.toString(StandardCharsets.UTF_8.name()) + error.toString(StandardCharsets.UTF_8.name()));
    }

    private static final class Result {
        private final int exitCode;
        private final String output;

        private Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
