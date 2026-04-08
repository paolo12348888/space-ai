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
 * /branch Comando —— Gestisce i branch di conversazione.
 * <p>
 * Consente all'utente di salvare lo stato della conversazione corrente come branch nominato e di passare tra branch diversi.
 * I dati dei branch sono memorizzati in una Map statica, persistenti durante il ciclo di vita della JVM.
 * <p>
 * Sottocomandi supportati:
 * <ul>
 *   <li>{@code /branch save <name>} —— Salva la conversazione corrente come branch</li>
 *   <li>{@code /branch load <name>} —— Ripristina lo stato della conversazione del branch specificato</li>
 *   <li>{@code /branch list} —— Elenca tutti i branch salvati</li>
 *   <li>{@code /branch delete <name>} —— Elimina il branch specificato</li>
 * </ul>
 */
public class BranchCommand implements SlashCommand {

    /** Archivio branch statico: nome branch -> snapshot messaggi */
    private static final Map<String, BranchSnapshot> branches = new LinkedHashMap<>();

    @Override
    public String name() {
        return "branch";
    }

    @Override
    public String description() {
        return "Manage conversation branches";
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

        // Analizza il sottocomando e i parametri
        String[] parts = trimmedArgs.split("\\s+", 2);
        String subCommand = parts[0].toLowerCase();
        String branchName = parts.length > 1 ? parts[1].trim() : "";

        return switch (subCommand) {
            case "save" -> saveBranch(branchName, context);
            case "load" -> loadBranch(branchName, context);
            case "list" -> listBranches(context);
            case "delete" -> deleteBranch(branchName);
            default -> AnsiStyle.red("  ✗ Unknown subcommand: " + subCommand) + "\n" + showUsage();
        };
    }

    /**
     * Salva la conversazione corrente come branch nominato.
     *
     * @param branchName Nome del branch
     * @param context    Contesto del comando
     * @return Informazioni sul risultato dell'operazione
     */
    private String saveBranch(String branchName, CommandContext context) {
        if (branchName.isEmpty()) {
            return AnsiStyle.red("  ✗ Please specify branch name.") + "\n"
                    + AnsiStyle.dim("  Usage: /branch save <name>");
        }

        List<Message> currentHistory = context.agentLoop().getMessageHistory();

        // Crea uno snapshot dei messaggi (copia profonda del riferimento alla lista)
        List<Message> snapshot = new ArrayList<>(currentHistory);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        branches.put(branchName, new BranchSnapshot(snapshot, timestamp));

        return AnsiStyle.green("  ✓ Branch saved: ") + AnsiStyle.bold(branchName) + "\n"
                + AnsiStyle.dim("    Messages: " + snapshot.size() + "  Time: " + timestamp);
    }

    /**
     * Ripristina lo stato della conversazione del branch specificato.
     *
     * @param branchName Nome del branch
     * @param context    Contesto del comando
     * @return Informazioni sul risultato dell'operazione
     */
    private String loadBranch(String branchName, CommandContext context) {
        if (branchName.isEmpty()) {
            return AnsiStyle.red("  ✗ Please specify branch name.") + "\n"
                    + AnsiStyle.dim("  Usage: /branch load <name>");
        }

        BranchSnapshot snapshot = branches.get(branchName);
        if (snapshot == null) {
            return AnsiStyle.red("  ✗ Branch not found: " + branchName) + "\n"
                    + AnsiStyle.dim("  Use /branch list to see all available branches.");
        }

        // Ripristina la cronologia della conversazione
        context.agentLoop().replaceHistory(new ArrayList<>(snapshot.messages()));

        return AnsiStyle.green("  ✓ Restored to branch: ") + AnsiStyle.bold(branchName) + "\n"
                + AnsiStyle.dim("    Loaded " + snapshot.messages().size() + " messages (saved at " + snapshot.timestamp() + ")");
    }

    /**
     * Elenca tutti i branch salvati.
     *
     * @param context Contesto del comando
     * @return Informazioni sull'elenco dei branch
     */
    private String listBranches(CommandContext context) {
        if (branches.isEmpty()) {
            return AnsiStyle.dim("  No saved branches.") + "\n"
                    + AnsiStyle.dim("  Use /branch save <name> to save current conversation.");
        }

        int currentSize = context.agentLoop().getMessageHistory().size();

        StringBuilder sb = new StringBuilder();
        sb.append(AnsiStyle.bold("\n  🌿 Conversation Branches\n\n"));

        for (Map.Entry<String, BranchSnapshot> entry : branches.entrySet()) {
            String name = entry.getKey();
            BranchSnapshot snapshot = entry.getValue();
            int msgCount = snapshot.messages().size();

            // Segna il branch con la stessa dimensione della conversazione corrente
            String marker = (msgCount == currentSize) ? AnsiStyle.green(" ◀ current size") : "";

            sb.append("  • ")
                    .append(AnsiStyle.bold(name))
                    .append(AnsiStyle.dim("  (" + msgCount + " messages, " + snapshot.timestamp() + ")"))
                    .append(marker)
                    .append("\n");
        }

        sb.append("\n").append(AnsiStyle.dim("  Total " + branches.size() + " branches.")).append("\n");
        return sb.toString();
    }

    /**
     * Elimina il branch specificato.
     *
     * @param branchName Nome del branch
     * @return Informazioni sul risultato dell'operazione
     */
    private String deleteBranch(String branchName) {
        if (branchName.isEmpty()) {
            return AnsiStyle.red("  ✗ Please specify branch name.") + "\n"
                    + AnsiStyle.dim("  Usage: /branch delete <name>");
        }

        BranchSnapshot removed = branches.remove(branchName);
        if (removed == null) {
            return AnsiStyle.red("  ✗ Branch not found: " + branchName);
        }

        return AnsiStyle.green("  ✓ Branch deleted: ") + AnsiStyle.bold(branchName);
    }

    /**
     * Mostra le informazioni sull'utilizzo.
     *
     * @return Testo di descrizione dell'utilizzo
     */
    private String showUsage() {
        StringBuilder sb = new StringBuilder();
        sb.append(AnsiStyle.bold("\n  🌿 Branch — Conversation branch management\n\n"));
        sb.append("  ").append(AnsiStyle.cyan("/branch save <name>")).append("    Save current conversation as branch\n");
        sb.append("  ").append(AnsiStyle.cyan("/branch load <name>")).append("    Restore to specified branch\n");
        sb.append("  ").append(AnsiStyle.cyan("/branch list")).append("            List all branches\n");
        sb.append("  ").append(AnsiStyle.cyan("/branch delete <name>")).append("  Delete specified branch\n");
        return sb.toString();
    }

    /**
     * Record snapshot del branch — salva la lista dei messaggi e il timestamp di creazione.
     *
     * @param messages  Snapshot dei messaggi
     * @param timestamp Timestamp di creazione
     */
    private record BranchSnapshot(List<Message> messages, String timestamp) {}
}
