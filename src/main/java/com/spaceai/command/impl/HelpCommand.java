package com.spaceai.command.impl;

import com.spaceai.command.CommandContext;
import com.spaceai.command.SlashCommand;
import com.spaceai.console.AnsiStyle;

import java.util.List;

/**
 * /help Comando —— visualizzac'èregistratocomando slash。
 */
public class HelpCommand implements SlashCommand {

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "Show available commands";
    }

    @Override
    public List<String> aliases() {
        return List.of("?");
    }

    @Override
    public String execute(String args, CommandContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append(AnsiStyle.bold("\n  Available Commands:\n\n"));

        // ottieni dinamicamente tutti i comandi registrati da CommandRegistry
        var commands = context.commandRegistry().getCommands();

        // lungoComandonomeinsu
        int maxNameLen = commands.stream()
                .mapToInt(cmd -> cmd.name().length())
                .max().orElse(12);
        maxNameLen = Math.max(maxNameLen, 12);

        for (SlashCommand cmd : commands) {
            String nameStr = "/" + cmd.name();
            // Sec'èAlias，aggiuntivovisualizza
            String aliasStr = "";
            if (!cmd.aliases().isEmpty()) {
                aliasStr = AnsiStyle.DIM + " (also: "
                        + String.join(", ", cmd.aliases().stream().map(a -> "/" + a).toList())
                        + ")" + AnsiStyle.RESET;
            }
            sb.append(String.format("  %s%-" + (maxNameLen + 2) + "s%s %s%s%n",
                    AnsiStyle.CYAN, nameStr, AnsiStyle.RESET, cmd.description(), aliasStr));
        }

        sb.append("\n");
        sb.append(AnsiStyle.dim("  Shortcuts: Tab to autocomplete, ↑↓ to browse history, Ctrl+D to exit\n"));

        return sb.toString();
    }
}
