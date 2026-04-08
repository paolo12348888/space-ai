package com.spaceai.command.impl;

import com.spaceai.command.CommandContext;
import com.spaceai.command.SlashCommand;
import com.spaceai.console.AnsiStyle;

/**
 * /clear Comando —— Cancella la cronologia della conversazione.
 */
public class ClearCommand implements SlashCommand {

    @Override
    public String name() {
        return "clear";
    }

    @Override
    public String description() {
        return "Clear conversation history";
    }

    @Override
    public String execute(String args, CommandContext context) {
        if (context.agentLoop() != null) {
            context.agentLoop().reset();
        }
        return AnsiStyle.green("  ✓ Conversation history cleared.");
    }
}
