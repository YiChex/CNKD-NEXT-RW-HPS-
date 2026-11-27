package net.rwhps.server.plugin.api;

import java.util.Objects;

/** Immutable player data. It never exposes the mutable engine player object. */
public final class PlayerSnapshot {
    private final String id;
    private final String name;
    private final int slot;
    private final int team;
    private final boolean connected;
    private final boolean alive;
    private final boolean adminAuthority;
    private final boolean hostAuthority;
    private final boolean superAdmin;

    public PlayerSnapshot(
        String id,
        String name,
        int slot,
        int team,
        boolean connected,
        boolean alive,
        boolean adminAuthority,
        boolean hostAuthority,
        boolean superAdmin
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.slot = slot;
        this.team = team;
        this.connected = connected;
        this.alive = alive;
        this.adminAuthority = adminAuthority;
        this.hostAuthority = hostAuthority;
        this.superAdmin = superAdmin;
    }

    public String id() { return id; }
    public String name() { return name; }
    public int slot() { return slot; }
    public int team() { return team; }
    public boolean connected() { return connected; }
    public boolean alive() { return alive; }
    public boolean adminAuthority() { return adminAuthority; }
    public boolean hostAuthority() { return hostAuthority; }
    public boolean superAdmin() { return superAdmin; }

    @Override
    public String toString() {
        return "PlayerSnapshot{id='" + id + "', name='" + name + "', slot=" + slot + ", team=" + team + "}";
    }
}
