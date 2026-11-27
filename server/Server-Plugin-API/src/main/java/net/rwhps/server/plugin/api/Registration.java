package net.rwhps.server.plugin.api;

/** A plugin-owned registration that can be released early and exactly once. */
public interface Registration extends AutoCloseable {
    /** Returns false after this handle or its owning plugin scope is closed. */
    boolean isActive();

    @Override
    void close();
}
