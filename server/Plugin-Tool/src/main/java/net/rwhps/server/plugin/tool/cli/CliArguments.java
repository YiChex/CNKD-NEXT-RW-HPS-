package net.rwhps.server.plugin.tool.cli;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CliArguments {
    private static final List<String> FLAGS = List.of("--force", "--help");
    private static final List<String> VALUE_OPTIONS = List.of("--server-api", "--json", "--approved-by", "--output", "--expected-change");

    public final String command;
    public final List<String> positional;
    public final Map<String, String> options;

    private CliArguments(String command, List<String> positional, Map<String, String> options) {
        this.command = command;
        this.positional = Collections.unmodifiableList(positional);
        this.options = Collections.unmodifiableMap(options);
    }

    public static CliArguments parse(String[] args) {
        if (args.length == 0) return new CliArguments("help", new ArrayList<>(), new LinkedHashMap<>());
        String command = args[0].toLowerCase();
        List<String> positional = new ArrayList<>();
        Map<String, String> options = new LinkedHashMap<>();
        for (int index = 1; index < args.length; index++) {
            String argument = args[index];
            if (FLAGS.contains(argument)) {
                if (options.containsKey(argument)) throw new IllegalArgumentException("duplicate option: " + argument);
                options.put(argument, "true");
            } else if (VALUE_OPTIONS.contains(argument)) {
                if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException("missing value for " + argument);
                }
                if (options.containsKey(argument)) throw new IllegalArgumentException("duplicate option: " + argument);
                options.put(argument, args[++index]);
            } else if (argument.startsWith("--")) {
                throw new IllegalArgumentException("unknown option: " + argument);
            } else {
                positional.add(argument);
            }
        }
        return new CliArguments(command, positional, options);
    }

    public boolean flag(String name) {
        return "true".equals(options.get(name));
    }

    public String option(String name, String defaultValue) {
        return options.getOrDefault(name, defaultValue);
    }
}
