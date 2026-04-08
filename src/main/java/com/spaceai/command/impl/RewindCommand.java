package com.spaceai.command.impl;

import com.spaceai.command.CommandContext;
import com.spaceai.command.SlashCommand;
import com.spaceai.console.AnsiStyle;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * /rewind Comando —— Conversazioneaprimaun certoposizione。
 * <p>
 * messaggiosu（Utentemessaggio + messaggio）comebit/posizioneesegue。
 * <ul>
 *   <li>{@code /rewind} —— rimuovedopo 1  coppie di messaggi</li>
 *   <li>{@code /rewind <n>} —— rimuovedopo n  coppie di messaggi</li>
 * </ul>
 * <p>
 * usa {@code agentLoop.getMessageHistory()} per ottenere la cronologia messaggi corrente,
 * dopotramite {@code agentLoop.replaceHistory()} troncadopoListasostituisce。
 */
public class RewindCommand implements SlashCommand {

    /**  coppie di messaggipackagemessaggio（Utentemessaggio + messaggio） */
    private static final int MESSAGES_PER_PAIR = 2;

    @Override
    public String name() {
        return "rewind";
    }

    @Override
    public String description() {
        return "Roll back conversation to a previous point";
    }

    @Override
    public String execute(String args, CommandContext context) {
        if (context.agentLoop() == null) {
            return AnsiStyle.red("  ✗ AgentLoop unavailable.");
        }

        // analisimessaggiosuquantità
        int pairsToRemove = parseRewindCount(args);
        if (pairsToRemove < 0) {
            return AnsiStyle.red("  ✗ Invalid rewind count. Please enter a positive integer.") + "\n"
                    + AnsiStyle.dim("  Usage: /rewind [n]  (n = number of message pairs to remove, default 1)");
        }

        List<Message> currentHistory = context.agentLoop().getMessageHistory();
        int currentSize = currentHistory.size();

        if (currentSize == 0) {
            return AnsiStyle.yellow("  ⚠ Conversation history is empty, cannot rewind.");
        }

        // richiederimuovemessaggioquantità
        int messagesToRemove = pairsToRemove * MESSAGES_PER_PAIR;

        // Serimuovemessaggiomessaggio，cancella tuttimessaggio
        if (messagesToRemove >= currentSize) {
            context.agentLoop().replaceHistory(new ArrayList<>());
            return AnsiStyle.green("  ✓ Cleared all " + currentSize + " messages.") + "\n"
                    + AnsiStyle.dim("  (Requested " + pairsToRemove + " pairs, cleared all messages)");
        }

        // troncacronologia messaggi
        int newSize = currentSize - messagesToRemove;
        List<Message> truncatedHistory = new ArrayList<>(currentHistory.subList(0, newSize));
        context.agentLoop().replaceHistory(truncatedHistory);

        // BuildRisultatoOutput
        StringBuilder sb = new StringBuilder();
        sb.append(AnsiStyle.green("  ✓ Rewound " + pairsToRemove + " message pairs")).append("\n");
        sb.append(AnsiStyle.dim("    Removed: " + messagesToRemove + " messages")).append("\n");
        sb.append(AnsiStyle.dim("    Remaining: " + newSize + " messages")).append("\n");

        return sb.toString();
    }

    /**
     * analisiquantitàParametri。
     *
     * @param args ComandoParametri
     * @return messaggiosuquantità，Predefinitocome 1；analisiFallimentoRestituisce -1
     */
    private int parseRewindCount(String args) {
        String trimmed = args != null ? args.trim() : "";

        if (trimmed.isEmpty()) {
            return 1;  // Predefinito 1  coppie di messaggi
        }

        try {
            int count = Integer.parseInt(trimmed);
            return count > 0 ? count : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
