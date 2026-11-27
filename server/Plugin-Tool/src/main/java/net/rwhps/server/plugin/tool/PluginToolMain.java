package net.rwhps.server.plugin.tool;

import net.rwhps.server.plugin.api.ApiVersions;
import net.rwhps.server.plugin.tool.api.ApiCompatibilityComparator;
import net.rwhps.server.plugin.tool.api.ApiSurfaceScanner;
import net.rwhps.server.plugin.tool.api.ApiSurfaceStore;
import net.rwhps.server.plugin.tool.cli.CliArguments;
import net.rwhps.server.plugin.tool.inspection.PluginArchiveInspector;
import net.rwhps.server.plugin.tool.lock.PluginLockWriter;
import net.rwhps.server.plugin.tool.matrix.PluginMatrixService;
import net.rwhps.server.plugin.tool.model.MatrixReport;
import net.rwhps.server.plugin.tool.model.PluginReport;
import net.rwhps.server.plugin.tool.model.ApiCompatibilityReport;
import net.rwhps.server.plugin.tool.model.ApiSurfaceSnapshot;
import net.rwhps.server.plugin.tool.output.ToolReportWriter;
import net.rwhps.server.plugin.tool.sdk.SdkManifestService;
import net.rwhps.server.plugin.tool.model.SdkManifest;
import net.rwhps.server.plugin.tool.version.SemVersion;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PluginToolMain {
    public static final String TOOL_VERSION = "1.0.0";
    public static final int EXIT_OK = 0;
    public static final int EXIT_VALIDATION = 2;
    public static final int EXIT_USAGE = 64;
    public static final int EXIT_INTERNAL = 70;

    private PluginToolMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream output, PrintStream error) {
        try {
            CliArguments cli = CliArguments.parse(args);
            if (cli.flag("--help") || "help".equals(cli.command)) {
                printUsage(output);
                return EXIT_OK;
            }
            if ("version".equals(cli.command)) {
                output.println("CNKD Plugin Tool " + TOOL_VERSION + " serverApi=" + ApiVersions.SERVER_API + " java=" + Runtime.version().feature());
                return EXIT_OK;
            }
            switch (cli.command) {
                case "validate": return runSingle(cli, output, false);
                case "inspect": return runSingle(cli, output, true);
                case "migrate": return runSingle(cli, output, true);
                case "matrix": return runMatrix(cli, output);
                case "lock": return runLock(cli, output);
                case "api-scan": return runApiScan(cli, output);
                case "api-compare":
                case "abi": return runApiCompare(cli, output);
                case "sdk-manifest":
                case "sdk-check": return runSdkManifest(cli, output);
                default:
                    error.println("Unknown command: " + cli.command);
                    printUsage(error);
                    return EXIT_USAGE;
            }
        } catch (IllegalArgumentException errorValue) {
            error.println("CNKD_PLUGIN_TOOL_USAGE_ERROR " + safeMessage(errorValue));
            return EXIT_USAGE;
        } catch (Throwable errorValue) {
            if (errorValue instanceof VirtualMachineError) throw (VirtualMachineError) errorValue;
            if (errorValue instanceof ThreadDeath) throw (ThreadDeath) errorValue;
            error.println("CNKD_PLUGIN_TOOL_ERROR " + safeMessage(errorValue));
            return EXIT_INTERNAL;
        }
    }

    private static int runSingle(CliArguments cli, PrintStream output, boolean migrationDetails) throws Exception {
        requirePositionals(cli, 1, "archive path");
        String serverApi = singleServerApi(cli);
        PluginReport report = new PluginArchiveInspector(serverApi).inspect(Path.of(cli.positional.get(0)));
        new ToolReportWriter().printPlugin(report, output, migrationDetails);
        writeOptionalJson(cli, report);
        return "FAIL".equals(report.status) ? EXIT_VALIDATION : EXIT_OK;
    }

    private static int runMatrix(CliArguments cli, PrintStream output) throws Exception {
        requirePositionals(cli, 1, "plugin directory");
        List<String> serverApis = serverApis(cli);
        MatrixReport matrix = new PluginMatrixService().inspectDirectory(Path.of(cli.positional.get(0)), serverApis);
        new ToolReportWriter().printMatrix(matrix, output, true);
        writeOptionalJson(cli, matrix);
        return matrix.failed > 0 ? EXIT_VALIDATION : EXIT_OK;
    }

    private static int runLock(CliArguments cli, PrintStream output) throws Exception {
        requirePositionals(cli, 1, "plugin directory");
        String approvedBy = cli.option("--approved-by", "");
        if (approvedBy.isEmpty()) throw new IllegalArgumentException("lock requires --approved-by <name>");
        Path directory = Path.of(cli.positional.get(0)).toAbsolutePath().normalize();
        List<String> serverApis = serverApis(cli);
        MatrixReport matrix = new PluginMatrixService().inspectDirectory(directory, serverApis);
        ToolReportWriter writer = new ToolReportWriter();
        writer.printMatrix(matrix, output, false);
        writeOptionalJson(cli, matrix);
        if (matrix.failed > 0) return EXIT_VALIDATION;
        String outputValue = cli.option("--output", "plugin-lock.json");
        Path lockPath = Path.of(outputValue);
        if (!lockPath.isAbsolute()) lockPath = directory.resolve(lockPath);
        Path written = new PluginLockWriter().write(matrix, lockPath, approvedBy, cli.flag("--force"));
        output.println("CNKD_PLUGIN_LOCK_WRITTEN path=" + written + " entries=" + matrix.total);
        return EXIT_OK;
    }

    private static int runApiScan(CliArguments cli, PrintStream output) throws Exception {
        requirePositionals(cli, 1, "API JAR");
        ApiSurfaceSnapshot snapshot = new ApiSurfaceScanner().scan(Path.of(cli.positional.get(0)));
        new ToolReportWriter().printApiSurface(snapshot, output);
        writeOptionalJson(cli, snapshot);
        return EXIT_OK;
    }

    private static int runApiCompare(CliArguments cli, PrintStream output) throws Exception {
        if (cli.positional.size() != 2) {
            throw new IllegalArgumentException("api-compare requires baseline JSON/JAR and candidate API JAR");
        }
        ApiSurfaceStore store = new ApiSurfaceStore();
        ApiSurfaceSnapshot baseline = store.read(Path.of(cli.positional.get(0)));
        ApiSurfaceSnapshot candidate = new ApiSurfaceScanner().scan(Path.of(cli.positional.get(1)));
        String expected = cli.option("--expected-change", "auto");
        ApiCompatibilityReport report = new ApiCompatibilityComparator().compare(baseline, candidate, expected);
        new ToolReportWriter().printApiCompatibility(report, output);
        writeOptionalJson(cli, report);
        return report.releaseAllowed ? EXIT_OK : EXIT_VALIDATION;
    }

    private static int runSdkManifest(CliArguments cli, PrintStream output) throws Exception {
        requirePositionals(cli, 1, "SDK directory");
        SdkManifest manifest = new SdkManifestService().inspect(Path.of(cli.positional.get(0)));
        output.println(
            "CNKD_SDK_MANIFEST status=" + manifest.status +
                " apiVersion=" + manifest.apiVersion +
                " toolVersion=" + manifest.toolVersion +
                " assets=" + manifest.assetCount +
                " missing=" + manifest.missingCount +
                " warnings=" + manifest.warnings.size()
        );
        manifest.missing.forEach(item -> output.println("  MISSING " + item));
        manifest.warnings.forEach(item -> output.println("  WARN " + item));
        writeOptionalJson(cli, manifest);
        return "PASS".equals(manifest.status) ? EXIT_OK : EXIT_VALIDATION;
    }

    private static void writeOptionalJson(CliArguments cli, Object report) throws Exception {
        String json = cli.option("--json", "");
        if (!json.isEmpty()) new ToolReportWriter().writeJson(Path.of(json), report, cli.flag("--force"));
    }

    private static String singleServerApi(CliArguments cli) {
        List<String> versions = serverApis(cli);
        if (versions.size() != 1) throw new IllegalArgumentException(cli.command + " accepts exactly one --server-api version");
        return versions.get(0);
    }

    private static List<String> serverApis(CliArguments cli) {
        String raw = cli.option("--server-api", ApiVersions.SERVER_API);
        Set<String> unique = new LinkedHashSet<>();
        for (String value : raw.split(",")) {
            String version = value.trim();
            if (version.isEmpty()) throw new IllegalArgumentException("server-api contains an empty version");
            SemVersion.parse(version);
            unique.add(version);
        }
        return new ArrayList<>(unique);
    }

    private static void requirePositionals(CliArguments cli, int count, String description) {
        if (cli.positional.size() != count) {
            throw new IllegalArgumentException(cli.command + " requires exactly " + count + " " + description);
        }
    }

    private static void printUsage(PrintStream output) {
        output.println("CNKD NEXT Plugin Tool " + TOOL_VERSION);
        output.println("Usage:");
        output.println("  java -jar cnkd-plugin-tool.jar validate <archive> [--server-api 1.2.0] [--json report.json] [--force]");
        output.println("  java -jar cnkd-plugin-tool.jar inspect <archive> [--server-api 1.2.0] [--json report.json] [--force]");
        output.println("  java -jar cnkd-plugin-tool.jar migrate <archive> [--server-api 1.2.0] [--json report.json] [--force]");
        output.println("  java -jar cnkd-plugin-tool.jar matrix <plugin-dir> [--server-api 1.1.0,1.2.0] [--json matrix.json] [--force]");
        output.println("  java -jar cnkd-plugin-tool.jar lock <plugin-dir> --approved-by <name> [--server-api 1.2.0] [--output plugin-lock.json] [--json matrix.json] [--force]");
        output.println("  java -jar cnkd-plugin-tool.jar api-scan <server-plugin-api.jar> [--json api-surface.json] [--force]");
        output.println("  java -jar cnkd-plugin-tool.jar api-compare <baseline.json|baseline.jar> <candidate-api.jar> [--expected-change auto|patch|minor|major] [--json api-compatibility.json] [--force]");
        output.println("  java -jar cnkd-plugin-tool.jar sdk-manifest <plugin-sdk-dir> [--json sdk-manifest.json] [--force]");
        output.println("  java -jar cnkd-plugin-tool.jar version");
        output.println("Exit codes: 0=valid 2=validation/matrix failure 64=usage 70=internal error");
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return error.getClass().getSimpleName();
        return message.replace('\n', ' ').replace('\r', ' ');
    }
}
