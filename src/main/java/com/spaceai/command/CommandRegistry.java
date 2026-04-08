package com.spaceai.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Registro dei comandi — corrisponde alla gestione della raccolta di comandi in space-ai/src/commands.ts.
 */
public class CommandRegistry {

    private static final Logger log = LoggerFactory.getLogger(CommandRegistry.class);

    private final Map<String, SlashCommand> commands = new LinkedHashMap<>();

    /** Registra il comando (inclusi gli alias) */
    public void register(SlashCommand command) {
        commands.put(command.name().toLowerCase(), command);
        for (String alias : command.aliases()) {
            commands.put(alias.toLowerCase(), command);
        }
        log.debug("Registered command: /{}", command.name());
    }

    /** Registrazione multipla */
    public void registerAll(SlashCommand... cmds) {
        for (SlashCommand cmd : cmds) {
            register(cmd);
        }
    }

    /** Analizza ed esegue il comando */
    public Optional<String> dispatch(String input, CommandContext context) {
        if (!input.startsWith("/")) {
            return Optional.empty();
        }

        String stripped = input.substring(1).strip();
        String[] parts = stripped.split("\\s+", 2);
        String cmdName = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        SlashCommand cmd = commands.get(cmdName);
        if (cmd == null) {
            return Optional.of("Unknown command: /" + cmdName + ". Type /help for available commands.");
        }

        return Optional.of(cmd.execute(args, context));
    }

    /** Verifica se l'input è un comando slash */
    public boolean isCommand(String input) {
        return input != null && input.startsWith("/");
    }

    /** Ottieni tutti i comandi univoci (per /help) */
    public List<SlashCommand> getCommands() {
        return commands.values().stream().distinct().toList();
    }

    /** Ottieni i nomi dei comandi (per il completamento Tab) */
    public Set<String> getCommandNames() {
        return Set.copyOf(commands.keySet());
    }
}
