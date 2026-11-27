package net.rwhps.server.plugin.api;

import java.util.Objects;

/** Immutable engine-loaded mod identity. Version data is not exposed when the engine does not provide it reliably. */
public final class ModSnapshot {
    private final String name;
    private final boolean engineLoaded;

    public ModSnapshot(String name, boolean engineLoaded) {
        this.name = Objects.requireNonNull(name, "name");
        this.engineLoaded = engineLoaded;
    }

    public String name() { return name; }
    public boolean engineLoaded() { return engineLoaded; }
}
