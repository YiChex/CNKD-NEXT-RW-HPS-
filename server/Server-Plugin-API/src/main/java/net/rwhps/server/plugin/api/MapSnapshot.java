package net.rwhps.server.plugin.api;

import java.util.Objects;

/** Immutable selected-map state. Filesystem source paths are intentionally excluded. */
public final class MapSnapshot {
    private final boolean available;
    private final String displayName;
    private final MapKind kind;
    private final boolean custom;
    private final boolean modProvided;
    private final String runtimeName;

    public MapSnapshot(
        boolean available,
        String displayName,
        MapKind kind,
        boolean custom,
        boolean modProvided,
        String runtimeName
    ) {
        this.available = available;
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.custom = custom;
        this.modProvided = modProvided;
        this.runtimeName = Objects.requireNonNull(runtimeName, "runtimeName");
    }

    public boolean available() { return available; }
    public String displayName() { return displayName; }
    public MapKind kind() { return kind; }
    public boolean custom() { return custom; }
    public boolean modProvided() { return modProvided; }
    public String runtimeName() { return runtimeName; }
}
