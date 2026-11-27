package net.rwhps.server.plugin.api.command;

@FunctionalInterface
public interface CommandExecutor {
    void execute(CommandInvocation invocation) throws Exception;
}
