package net.rwhps.server.plugin.api.command;

import net.rwhps.server.plugin.api.PlayerSnapshot;

import java.util.List;
import java.util.Optional;

public interface CommandInvocation {
    /** Immutable parsed argument list. */
    List<String> arguments();

    /** Empty for console commands; populated with an immutable player snapshot otherwise. */
    Optional<PlayerSnapshot> player();

    /** Replies with at most 2000 characters. */
    void reply(String message);
}
