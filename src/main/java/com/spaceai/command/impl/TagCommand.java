package com.spaceai.command.impl;

import com.spaceai.command.CommandContext;
import com.spaceai.command.SlashCommand;
import com.spaceai.console.AnsiStyle;
import org.springframework.ai.chat.messages.Message;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /tag Comando —— applica un tag alla posizione corrente della conversazione.
 * <p>
 * tagrecordCorrentecronologia messaggidimensione，puòindopoveloceaquestoposizione。
 * tagdatiArchiviazionein Map in，in JVM Ciclo di vitainternopersistenza。
 * <p>
 * Sottocomandi supportati:
 * <ul>
 *   <li>{@code /tag <name>} —— applica tag alla posizione corrente</li>
 *   <li>{@code /tag list} —— elenca tuttitag</li>
 *   <li>{@code /tag goto <name>} —— aspecificatotagposizione</li>
 * </ul>
 */
public class TagCommand implements SlashCommand {

    /** tagArchiviazione：Firma -> tagInformazione */
    private static final Map<String, TagInfo> tags = new LinkedHashMap<>();

    @Override
    public String name() {
        return "tag";
    }

    @Override
    public String description() {
        return "Tag current conversation point with a label";
    }

    @Override
    public String execute(String args, CommandContext context) {
        if (context.agentLoop() == null) {
            return AnsiStyle.red("  ✗ AgentLoop unavailable.");
        }

        String trimmedArgs = args != null ? args.trim() : "";

        if (trimmedArgs.isEmpty()) {
            return showUsage();
        }

        // analisiComando
        String[] parts = trimmedArgs.split("\\s+", 2);
        String firstArg = parts[0].toLowerCase();

        return switch (firstArg) {
            case "list" -> listTags(context);
            case "goto" -> {
                String tagName = parts.length > 1 ? parts[1].trim() : "";
                yield gotoTag(tagName, context);
            }
            default -> {
                // unParametrinonsìComando，comeFirma
                yield createTag(trimmedArgs, context);
            }
        };
    }

    /**
     * creatag，recordCorrentecronologia messaggidimensione。
     *
     * @param tagName Firma
     * @param context Contesto del comando
     * @return Informazioni sul risultato dell'operazione
     */
    private String createTag(String tagName, CommandContext context) {
        if (tagName.isEmpty()) {
            return AnsiStyle.red("  ✗ Please specify tag name.");
        }

        // Firmanon puòeComando（ list/goto giàin switch inGestisce）
        int position = context.agentLoop().getMessageHistory().size();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        boolean isOverwrite = tags.containsKey(tagName);
        tags.put(tagName, new TagInfo(position, timestamp));

        String action = isOverwrite ? "updated" : "created";
        return AnsiStyle.green("  ✓ Tag " + action + ": ") + AnsiStyle.bold(tagName) + "\n"
                + AnsiStyle.dim("    Position: message " + position + "  Time: " + timestamp);
    }

    /**
     * elenca tuttigiàsalvatag。
     *
     * @param context Contesto del comando
     * @return tagListaInformazione
     */
    private String listTags(CommandContext context) {
        if (tags.isEmpty()) {
            return AnsiStyle.dim("  No saved tags.") + "\n"
                    + AnsiStyle.dim("  Use /tag <name> to tag the current position.");
        }

        int currentPosition = context.agentLoop().getMessageHistory().size();

        StringBuilder sb = new StringBuilder();
        sb.append(AnsiStyle.bold("\n  🏷️  Conversation Tags\n\n"));

        for (Map.Entry<String, TagInfo> entry : tags.entrySet()) {
            String name = entry.getKey();
            TagInfo info = entry.getValue();

            // altoevidenziaCorrenteposizionecorrispondetag
            String marker = (info.position() == currentPosition)
                    ? AnsiStyle.green(" ◀ current")
                    : "";

            sb.append("  • ")
                    .append(AnsiStyle.bold(name))
                    .append(AnsiStyle.dim("  (position=" + info.position() + ", " + info.timestamp() + ")"))
                    .append(marker)
                    .append("\n");
        }

        sb.append("\n")
                .append(AnsiStyle.dim("  Current position: " + currentPosition + " messages  |  Total " + tags.size() + " tags"))
                .append("\n");
        return sb.toString();
    }

    /**
     * aspecificatotagCorrisponde aConversazioneposizione。
     * <p>
     * verràcronologia messaggitroncaatagrecordposizione。SeCorrentemessaggiomenointagposizione，
     * nontronca（tagposizionepuòCorrenteConversazionelungo）。
     *
     * @param tagName Firma
     * @param context Contesto del comando
     * @return Informazioni sul risultato dell'operazione
     */
    private String gotoTag(String tagName, CommandContext context) {
        if (tagName.isEmpty()) {
            return AnsiStyle.red("  ✗ Please specify tag name.") + "\n"
                    + AnsiStyle.dim("  Usage: /tag goto <name>");
        }

        TagInfo info = tags.get(tagName);
        if (info == null) {
            return AnsiStyle.red("  ✗ Tag not found: " + tagName) + "\n"
                    + AnsiStyle.dim("  Use /tag list to see all available tags.");
        }

        List<Message> currentHistory = context.agentLoop().getMessageHistory();
        int currentSize = currentHistory.size();
        int targetPosition = info.position();

        if (targetPosition >= currentSize) {
            return AnsiStyle.yellow("  ⚠ Tag position (" + targetPosition + ") is not less than current message count ("
                    + currentSize + "), no rewind needed.");
        }

        // troncaatagposizione
        List<Message> truncated = new ArrayList<>(currentHistory.subList(0, targetPosition));
        context.agentLoop().replaceHistory(truncated);

        int removedCount = currentSize - targetPosition;
        return AnsiStyle.green("  ✓ Rewound to tag: ") + AnsiStyle.bold(tagName) + "\n"
                + AnsiStyle.dim("    Removed " + removedCount + " messages, current count: " + targetPosition);
    }

    /**
     * Mostra le informazioni sull'utilizzo.
     *
     * @return Testo di descrizione dell'utilizzo
     */
    private String showUsage() {
        StringBuilder sb = new StringBuilder();
        sb.append(AnsiStyle.bold("\n  🏷️  Tag — Conversation position tags\n\n"));
        sb.append("  ").append(AnsiStyle.cyan("/tag <name>")).append("         Tag current position\n");
        sb.append("  ").append(AnsiStyle.cyan("/tag list")).append("           List all tags\n");
        sb.append("  ").append(AnsiStyle.cyan("/tag goto <name>")).append("    Rewind to tag position\n");
        return sb.toString();
    }

    /**
     * tagInformazioneRegistra —— salvatagmessaggioposizioneecrea。
     *
     * @param position  cronologia messaggiposizione in (numero di messaggi)
     * @param timestamp Timestamp di creazione
     */
    private record TagInfo(int position, String timestamp) {}
}
