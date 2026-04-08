package com.spaceai.command.impl;

import com.spaceai.command.CommandContext;
import com.spaceai.command.SlashCommand;
import com.spaceai.console.AnsiStyle;
import com.spaceai.core.ConversationPersistence;

/**
 * /history Comando —— elencasalvacronologia conversazione。
 * <p>
 * visualizzaConversazionerecord，package、riepilogoemessaggioquantità。
 */
public class HistoryCommand implements SlashCommand {

    @Override
    public String name() {
        return "history";
    }

    @Override
    public String description() {
        return "List saved conversation history";
    }

    @Override
    public String execute(String args, CommandContext context) {
        ConversationPersistence persistence = new ConversationPersistence();
        var conversations = persistence.listConversations();

        if (conversations.isEmpty()) {
            return AnsiStyle.dim("  📂 No saved conversation history");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(AnsiStyle.bold("  📂 Conversation History") + AnsiStyle.dim(" (")
                + conversations.size() + AnsiStyle.dim(" records)\n"));
        sb.append(AnsiStyle.dim("  " + "─".repeat(50)) + "\n");

        int shown = Math.min(conversations.size(), 10);
        for (int i = 0; i < shown; i++) {
            var conv = conversations.get(i);
            sb.append("  " + AnsiStyle.cyan((i + 1) + ".") + " ");
            sb.append(AnsiStyle.bold(conv.summary()));
            sb.append(AnsiStyle.dim(" (" + conv.messageCount() + " messages)") + "\n");
            sb.append("     " + AnsiStyle.dim(conv.savedAt() + " | " + conv.workingDir()) + "\n");
        }

        if (conversations.size() > 10) {
            sb.append(AnsiStyle.dim("  ... and " + (conversations.size() - 10) + " older records\n"));
        }

        sb.append(AnsiStyle.dim("\n  Storage location: " + persistence.getConversationsDir()));

        return sb.toString();
    }
}
