package net.rwhps.server.plugin.tool.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import net.rwhps.server.plugin.tool.inspection.PluginArchiveInspector;
import net.rwhps.server.plugin.tool.model.ApiClassSurface;
import net.rwhps.server.plugin.tool.model.ApiSurfaceSnapshot;
import net.rwhps.server.plugin.tool.version.SemVersion;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Reads API snapshots or scans an API JAR without loading API classes. */
public final class ApiSurfaceStore {
    private static final long MAX_SNAPSHOT_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_CLASSES = 4_096;
    private static final int MAX_MEMBERS_PER_CLASS = 16_384;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public ApiSurfaceSnapshot read(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized) || Files.isSymbolicLink(normalized)) {
            throw new IOException("API baseline must be a regular non-symbolic-link file: " + normalized);
        }
        String lowerName = normalized.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (lowerName.endsWith(".jar")) {
            return new ApiSurfaceScanner().scan(normalized);
        }
        if (!lowerName.endsWith(".json")) {
            throw new IOException("API baseline must be a .json snapshot or .jar artifact: " + normalized);
        }
        long size = Files.size(normalized);
        if (size <= 0L || size > MAX_SNAPSHOT_BYTES) {
            throw new IOException("API surface JSON size must be within 1.." + MAX_SNAPSHOT_BYTES + " bytes");
        }
        try (Reader reader = Files.newBufferedReader(normalized, StandardCharsets.UTF_8)) {
            ApiSurfaceSnapshot snapshot = gson.fromJson(reader, ApiSurfaceSnapshot.class);
            validate(snapshot, normalized);
            return snapshot;
        } catch (JsonSyntaxException | JsonIOException error) {
            throw new IOException("invalid API surface JSON: " + safeMessage(error), error);
        }
    }

    public void validate(ApiSurfaceSnapshot snapshot, Path source) throws IOException {
        if (snapshot == null) throw new IOException("API surface is empty: " + source);
        if (snapshot.schemaVersion != 1) throw new IOException("unsupported API surface schemaVersion: " + snapshot.schemaVersion);
        try {
            SemVersion.parse(snapshot.apiVersion);
        } catch (RuntimeException error) {
            throw new IOException("API surface has invalid apiVersion: " + snapshot.apiVersion, error);
        }
        if (snapshot.classes == null || snapshot.classes.isEmpty()) {
            throw new IOException("API surface contains no public API classes: " + source);
        }
        if (snapshot.classes.size() > MAX_CLASSES) throw new IOException("API surface contains too many classes");
        if (snapshot.maxClassMajor > PluginArchiveInspector.MAX_PLUGIN_CLASS_MAJOR) {
            throw new IOException("API surface requires newer than Java 11: major=" + snapshot.maxClassMajor);
        }
        Set<String> names = new HashSet<>();
        for (ApiClassSurface surface : snapshot.classes) {
            if (surface == null || surface.name == null || surface.name.trim().isEmpty()) {
                throw new IOException("API surface contains an empty class name: " + source);
            }
            if (!names.add(surface.name)) throw new IOException("API surface contains duplicate class: " + surface.name);
            if (surface.fields == null || surface.methods == null || surface.interfaces == null) {
                throw new IOException("API surface class is incomplete: " + surface.name);
            }
            if (surface.fields.size() > MAX_MEMBERS_PER_CLASS || surface.methods.size() > MAX_MEMBERS_PER_CLASS) {
                throw new IOException("API surface class contains too many members: " + surface.name);
            }
            Set<String> fieldKeys = new HashSet<>();
            for (net.rwhps.server.plugin.tool.model.ApiFieldSurface field : surface.fields) {
                if (field == null || field.name == null || field.descriptor == null || field.name.isEmpty() || field.descriptor.isEmpty()) {
                    throw new IOException("API surface contains an invalid field: " + surface.name);
                }
                if (!fieldKeys.add(field.key())) throw new IOException("API surface contains duplicate field: " + surface.name + "#" + field.key());
            }
            Set<String> methodKeys = new HashSet<>();
            for (net.rwhps.server.plugin.tool.model.ApiMethodSurface method : surface.methods) {
                if (method == null || method.name == null || method.descriptor == null || method.name.isEmpty() || method.descriptor.isEmpty()) {
                    throw new IOException("API surface contains an invalid method: " + surface.name);
                }
                if (!methodKeys.add(method.key())) throw new IOException("API surface contains duplicate method: " + surface.name + "#" + method.key());
            }
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
    }
}
