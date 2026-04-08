package com.spaceai.command.impl;

import com.spaceai.command.CommandContext;
import com.spaceai.command.SlashCommand;
import com.spaceai.console.AnsiStyle;
import com.spaceai.core.ConversationPersistence;
import com.spaceai.core.ConversationPersistence.ConversationSummary;
import org.springframework.ai.chat.messages.Message;

import java.nio.file.Path;
import java.util.List;

/**
 * /resume Comando —— primasalvaConversazione。
 * <p>
 * carica la cronologia delle conversazioni da ~/.space-ai-java/conversations/,
 * sostituisceCorrentecronologia messaggi，primacontesto。
 * <ul>
 *   <li>/resume —— una voltaConversazione</li>
 *   <li>/resume list —— elencapuòConversazione</li>
 *   <li>/resume [numero sequenziale] —— specificatonumero sequenzialeConversazione</li>
 * </ul>
 */
public class ResumeCommand implements SlashCommand {

    @Override
    public String name() {
        return "resume";
    }

    @Override
    public String description() {
        return "Resume a saved conversation";
    }

    @Override
    public String execute(String args, CommandContext context) {
        ConversationPersistence persistence = new ConversationPersistence();
        List<ConversationSummary> conversations = persistence.listConversations();

        args = args == null ? "" : args.strip();

        if (conversations.isEmpty()) {
            return AnsiStyle.yellow("  ⚠ No saved conversations\n")
                    + AnsiStyle.dim("  Conversations are auto-saved on exit to ~/.space-ai-java/conversations/");
        }

        // /resume list —— elenca tuttiConversazione
        if (args.equals("list")) {
            return formatConversationList(conversations);
        }

        // ConversazioneIndice
        int index = 0; // Predefinitoun
        if (!args.isEmpty()) {
            try {
                index = Integer.parseInt(args) - 1;
                if (index < 0 || index >= conversations.size()) {
                    return AnsiStyle.red("  ✗ Invalid index (range 1-" + conversations.size() + ")");
                }
            } catch (NumberFormatException e) {
                return AnsiStyle.yellow("  ⚠ Usage: /resume [index] or /resume list");
            }
        }

        // caricaeConversazione
        ConversationSummary summary = conversations.get(index);
        Path file = persistence.getConversationsDir().resolve(summary.filename());
        List<Message> messages = persistence.loadFromFile(file);

        if (messages.isEmpty()) {
            return AnsiStyle.red("  ✗ Failed to load conversation: " + summary.filename());
        }

        // sostituisceCorrentecronologia messaggi
        context.agentLoop().replaceHistory(messages);

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.green("  ✓ Conversation restored\n"));
        sb.append("  ").append("─".repeat(50)).append("\n");
        sb.append("  ").append(AnsiStyle.bold("Summary:  ")).append(summary.summary()).append("\n");
        sb.append("  ").append(AnsiStyle.bold("Time:     ")).append(summary.savedAt()).append("\n");
        sb.append("  ").append(AnsiStyle.bold("Messages: ")).append(summary.messageCount()).append("\n");
        sb.append("  ").append(AnsiStyle.bold("Dir:      ")).append(AnsiStyle.dim(summary.workingDir())).append("\n");

        return sb.toString();
    }

    private String formatConversationList(List<ConversationSummary> conversations) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  📂 Saved Conversations\n"));
        sb.append("  ").append("─".repeat(50)).append("\n\n");

        int maxShow = Math.min(conversations.size(), 20);
        for (int i = 0; i < maxShow; i++) {
            ConversationSummary conv = conversations.get(i);
            sb.append("  ").append(AnsiStyle.cyan(String.format("%2d", i + 1))).append(". ");
            sb.append(AnsiStyle.bold(conv.summary())).append("\n");
            sb.append("      ").append(AnsiStyle.dim(conv.savedAt()))
                    .append(AnsiStyle.dim(" | " + conv.messageCount() + " messages"))
                    .append("\n");
        }

        if (conversations.size() > maxShow) {
            sb.append(AnsiStyle.dim("\n  ... and " + (conversations.size() - maxShow) + " more conversations\n"));
        }

        sb.append(AnsiStyle.dim("\n  Use /resume [index] to restore a conversation\n"));

        return sb.toString();
    }
}
