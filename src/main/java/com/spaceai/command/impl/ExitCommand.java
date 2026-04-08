package com.spaceai.command.impl;

import com.spaceai.command.CommandContext;
import com.spaceai.command.SlashCommand;

import java.util.List;

/**
 * /exit Comando —— Esciapplicazione。
 */
public class ExitCommand implements SlashCommand {

    @Override
    public String name() {
        return "exit";
    }

    @Override
    public String description() {
        return "Exit the application";
    }

    @Override
    public List<String> aliases() {
        return List.of("quit", "q");
    }

    @Override
    public String execute(String args, CommandContext context) {
        if (context.exitCallback() != null) {
            context.exitCallback().run();
        }
        return "";
    }
}
