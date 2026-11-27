/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.util.game.command;

import net.rwhps.server.func.ConsSeq;
import net.rwhps.server.struct.map.ObjectMap;
import net.rwhps.server.struct.list.Seq;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @author Dr (dr@der.kim)
 */
@SuppressWarnings("unchecked")
public class CommandHandler {
    private final ObjectMap<String, Command> commands = new ObjectMap<>(16);
    private final Seq<Command> orderedCommands = new Seq<>();
    private String prefix;

    /**
     * Creates a command handler with a specific command prefix.
     */
    public CommandHandler(String prefix) {
        this.prefix = prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    /**
     * Handles a message with no additional parameters.
     */
    public CommandResponse handleMessage(String message) {
        return handleMessage(message, null);
    }

    public CommandResponse handleMessage(String message, Object params) {
        if (message == null || (!message.startsWith(prefix))) {
            return new CommandResponse(ResponseType.noCommand, null, null);
        }

        message = message.substring(prefix.length()).trim();
        if (message.isEmpty()) {
            return new CommandResponse(ResponseType.noCommand, null, null);
        }

        int firstWhitespace = -1;
        for (int i = 0; i < message.length(); i++) {
            if (Character.isWhitespace(message.charAt(i))) {
                firstWhitespace = i;
                break;
            }
        }

        String commandstr = firstWhitespace == -1 ? message : message.substring(0, firstWhitespace);
        String argstr = firstWhitespace == -1 ? "" : message.substring(firstWhitespace + 1).trim();

        Seq<String> result = new Seq<>();

        Command command = commands.get(normalizeCommandName(commandstr));

        if (command != null) {
            int index = 0;
            boolean satisfied = false;

            while (true) {
                if (index >= command.params.length && !argstr.isEmpty()) {
                    return new CommandResponse(ResponseType.manyArguments, command, commandstr);
                } else if (argstr.isEmpty()) {
                    break;
                }

                if (command.params[index].optional || index >= command.params.length - 1 || command.params[index + 1].optional) {
                    satisfied = true;
                }

                if (command.params[index].variadic) {
                    result.add(stripWrappingQuotes(argstr));
                    break;
                }

                ParsedArgument parsedArgument = readNextArgument(argstr);
                if (parsedArgument.remaining.isEmpty()) {
                    if (!satisfied) {
                        return new CommandResponse(ResponseType.fewArguments, command, commandstr);
                    }
                    result.add(parsedArgument.value);
                    break;
                } else {
                    argstr = parsedArgument.remaining;
                    result.add(parsedArgument.value);
                }
                index++;
            }

            if (!satisfied && command.params.length > 0 && !command.params[0].optional) {
                return new CommandResponse(ResponseType.fewArguments, command, commandstr);
            }

            command.runner.accept(result.toArray(String.class), params);

            return new CommandResponse(ResponseType.valid, command, commandstr);
        } else {
            return new CommandResponse(ResponseType.unknownCommand, null, commandstr);
        }
    }

    private static ParsedArgument readNextArgument(String argstr) {
        String source = argstr.trim();
        if (source.isEmpty()) {
            return new ParsedArgument("", "");
        }

        if (source.charAt(0) == '"') {
            StringBuilder value = new StringBuilder();
            boolean escaped = false;
            for (int i = 1; i < source.length(); i++) {
                char c = source.charAt(i);
                if (escaped) {
                    value.append(c);
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    return new ParsedArgument(value.toString(), source.substring(i + 1).trim());
                }
                value.append(c);
            }
            return new ParsedArgument(value.toString(), "");
        }

        int nextWhitespace = findFirstWhitespaceIndex(source);
        if (nextWhitespace == -1) {
            return new ParsedArgument(stripWrappingQuotes(source), "");
        }
        return new ParsedArgument(
            stripWrappingQuotes(source.substring(0, nextWhitespace)),
            source.substring(nextWhitespace + 1).trim()
        );
    }

    private static int findFirstWhitespaceIndex(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String stripWrappingQuotes(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    public void removeCommand(String text) {
        String key = normalizeCommandName(text);
        Command c = commands.get(key);
        if (c == null) {
            return;
        }
        commands.remove(key);
        orderedCommands.remove(c);
    }

    public boolean removeCommand(String text, Command expected) {
        String key = normalizeCommandName(text);
        Command current = commands.get(key);
        if (current != expected) {
            return false;
        }
        commands.remove(key);
        orderedCommands.remove(current);
        return true;
    }

    public boolean containsCommand(String text) {
        return commands.containsKey(normalizeCommandName(text));
    }

    /** Rolls back all command mutations if one plugin registration callback fails. */
    public void runRegistrationTransaction(Runnable registration) {
        Map<String, Command> commandSnapshot = new HashMap<>();
        commandSnapshot.putAll(commands);
        List<Command> orderedSnapshot = new ArrayList<>(orderedCommands);
        try {
            registration.run();
        } catch (RuntimeException | Error error) {
            commands.clear();
            commands.putAll(commandSnapshot);
            orderedCommands.clear();
            orderedCommands.addAll(orderedSnapshot);
            throw error;
        }
    }

    /**
     * Register a command which handles a zero-sized list of arguments and one parameter.
     */
    public <T> Command register(String text, String description, CommandRunner<T> runner) {
        Command cmd = new Command(text, "", description, runner);
        putCommand(text, cmd);
        return cmd;
    }

    public <T> Command register(String text, String params, String description, CommandRunner<T> runner) {
        Command cmd = new Command(text, params, description, runner);
        putCommand(text, cmd);
        return cmd;
    }

    private void putCommand(String text, Command command) {
        String key = normalizeCommandName(text);
        Command previous = commands.get(key);
        if (previous != null) {
            orderedCommands.remove(previous);
        }
        commands.put(key, command);
        orderedCommands.add(command);
    }

    private static String normalizeCommandName(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Command name cannot be blank");
        }
        return text.toLowerCase(Locale.ROOT);
    }

    public Command register(String text, String description, ConsSeq<String[]> runner) {
        return register(text, description, (args, p) -> runner.invoke(args));
    }

    public Command register(String text, String params, String description, ConsSeq<String[]> runner) {
        return register(text, params, description, (args, p) -> runner.invoke(args));
    }

    public Seq<Command> getCommandList() {
        return orderedCommands;
    }

        public enum ResponseType {
        /**
         * 命令状态
         */
        noCommand, unknownCommand, fewArguments, manyArguments, valid
    }

    public static class Command {
        public final String text;
        public final String paramText;
        public final String description;
        public final CommandParam[] params;
        private final CommandRunner runner;

        public Command(String text, String paramText, String description, CommandRunner runner) {
            this.text = text;
            this.paramText = paramText;
            this.runner = runner;
            this.description = description;

            String[] psplit = paramText.split(" ");
            if (paramText.isEmpty()) {
                params = new CommandParam[0];
            } else {
                params = new CommandParam[psplit.length];

                boolean hadOptional = false;

                for (int i = 0; i < params.length; i++) {
                    String param = psplit[i];

                    if (param.length() <= 2) {
                        throw new IllegalArgumentException("Malformed param '" + param + "'");
                    }

                    char l = param.charAt(0), r = param.charAt(param.length() - 1);
                    boolean optional, variadic = false;

                    if (l == '<' && r == '>') {
                        if (hadOptional) {
                            throw new IllegalArgumentException("Can't have non-optional param after optional param!");
                        }
                        optional = false;
                    } else if (l == '[' && r == ']') {
                        optional = true;
                    } else {
                        throw new IllegalArgumentException("Malformed param '" + param + "'");
                    }

                    if (optional) {
                        hadOptional = true;
                    }

                    String fname = param.substring(1, param.length() - 1);
                    if (fname.endsWith("...")) {
                        if (i != params.length - 1) {
                            throw new IllegalArgumentException("A variadic parameter should be the last parameter!");
                        }

                        fname = fname.substring(0, fname.length() - 3);
                        variadic = true;
                    }

                    params[i] = new CommandParam(fname, optional, variadic);

                }
            }
        }
    }

    public interface CommandRunner<T> {
        /**
         * 接收参数
         *
         * @param args      命令分片组
         * @param parameter Run
         */
        void accept(String[] args, T parameter);
    }

    public static class CommandParam {
        public final String name;
        public final boolean optional;
        public final boolean variadic;

        public CommandParam(String name, boolean optional, boolean variadic) {
            this.name = name;
            this.optional = optional;
            this.variadic = variadic;
        }
    }

    public static class CommandResponse {
        public final ResponseType type;
        public final Command command;
        public final String runCommand;

        public CommandResponse(ResponseType type, Command command, String runCommand) {
            this.type = type;
            this.command = command;
            this.runCommand = runCommand;
        }
    }

    private static class ParsedArgument {
        final String value;
        final String remaining;

        ParsedArgument(String value, String remaining) {
            this.value = value;
            this.remaining = remaining;
        }
    }
}
