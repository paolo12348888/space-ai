package com.spaceai.command.impl;

import com.spaceai.command.CommandContext;
import com.spaceai.command.SlashCommand;
import com.spaceai.console.AnsiStyle;
import com.spaceai.core.TokenTracker;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;

/**
 * /status Comando —— Mostra stato sessionedashboard.
 * <p>
 * visualizzaCorrenteModello、Token 、Strumento、messaggio、memoriaeRuntimeecc.Informazione。
 */
public class StatusCommand implements SlashCommand {

    private final Instant startTime = Instant.now();

    @Override
    public String name() {
        return "status";
    }

    @Override
    public String description() {
        return "Show session status dashboard";
    }

    @Override
    public String execute(String args, CommandContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  📊 Session Status\n"));
        sb.append("  ").append("─".repeat(40)).append("\n\n");

        // ModelloInformazione
        TokenTracker tracker = context.agentLoop().getTokenTracker();
        sb.append("  ").append(AnsiStyle.bold("Model:    ")).append(AnsiStyle.cyan(tracker.getModelName())).append("\n");

        // Token 
        sb.append("  ").append(AnsiStyle.bold("Tokens:   "))
                .append("↑ ").append(TokenTracker.formatTokens(tracker.getInputTokens()))
                .append(" input, ↓ ").append(TokenTracker.formatTokens(tracker.getOutputTokens()))
                .append(" output")
                .append(AnsiStyle.dim(" ($" + String.format("%.4f", tracker.estimateCost()) + ")"))
                .append("\n");

        // API chiamataconteggio
        sb.append("  ").append(AnsiStyle.bold("API Calls:")).append(" ").append(tracker.getApiCallCount()).append("\n");

        // cronologia messaggi
        int msgCount = context.agentLoop().getMessageHistory().size();
        sb.append("  ").append(AnsiStyle.bold("Messages: ")).append(msgCount).append("\n");

        // Strumento
        sb.append("  ").append(AnsiStyle.bold("Tools:    ")).append(context.toolRegistry().size()).append(" registered\n");

        // lavoroDirectory
        sb.append("  ").append(AnsiStyle.bold("Work Dir: ")).append(AnsiStyle.dim(System.getProperty("user.dir"))).append("\n");

        // JVM memoria
        Runtime rt = Runtime.getRuntime();
        long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMB = rt.maxMemory() / (1024 * 1024);
        sb.append("  ").append(AnsiStyle.bold("Memory:   ")).append(usedMB).append("MB / ").append(maxMB).append("MB\n");

        // Runtime
        Duration uptime = Duration.between(startTime, Instant.now());
        sb.append("  ").append(AnsiStyle.bold("Uptime:   ")).append(formatDuration(uptime)).append("\n");

        // Java Versione
        sb.append("  ").append(AnsiStyle.bold("JDK:      ")).append(System.getProperty("java.version")).append("\n");

        return sb.toString();
    }

    private String formatDuration(Duration d) {
        long hours = d.toHours();
        long minutes = d.toMinutesPart();
        long seconds = d.toSecondsPart();
        if (hours > 0) return String.format("%dh %dm %ds", hours, minutes, seconds);
        if (minutes > 0) return String.format("%dm %ds", minutes, seconds);
        return String.format("%ds", seconds);
    }
}
