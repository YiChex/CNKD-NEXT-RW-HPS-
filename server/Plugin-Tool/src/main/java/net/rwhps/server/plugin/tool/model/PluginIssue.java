package net.rwhps.server.plugin.tool.model;

import java.util.Objects;

public final class PluginIssue {
    public final IssueSeverity severity;
    public final String code;
    public final String message;
    public final String suggestion;

    public PluginIssue(IssueSeverity severity, String code, String message, String suggestion) {
        this.severity = Objects.requireNonNull(severity, "severity");
        this.code = Objects.requireNonNull(code, "code");
        this.message = Objects.requireNonNull(message, "message");
        this.suggestion = Objects.requireNonNull(suggestion, "suggestion");
    }
}
