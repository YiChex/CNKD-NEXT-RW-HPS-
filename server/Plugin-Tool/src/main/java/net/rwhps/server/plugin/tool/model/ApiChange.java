package net.rwhps.server.plugin.tool.model;

public final class ApiChange {
    public String severity = "";
    public String code = "";
    public String symbol = "";
    public String message = "";

    public ApiChange(String severity, String code, String symbol, String message) {
        this.severity = severity;
        this.code = code;
        this.symbol = symbol;
        this.message = message;
    }
}
