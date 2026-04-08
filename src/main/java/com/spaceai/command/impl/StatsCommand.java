package com.spaceai.command.impl;

import com.spaceai.command.CommandContext;
import com.spaceai.command.SlashCommand;
import com.spaceai.console.AnsiStyle;
import com.spaceai.core.TokenTracker;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.concurrent.TimeUnit;

/**
 * /stats Comando —— visualizzaCorrenteSessioneInformazione。
 * <p>
 * visualizzacontenutopackage：
 * <ul>
 *   <li>Conversazione（cronologia messaggidimensione / 2）</li>
 *   <li>API chiamataconteggio</li>
 *   <li>utilizzo Token（Input/Output/totale）</li>
 *   <li>stima del costo (USD)</li>
 *   <li>ogni voltachiamatamedia Token </li>
 *   <li>Correntenome del modello usato</li>
 *   <li>JVM Runtimelungo</li>
 * </ul>
 */
public class StatsCommand implements SlashCommand {

    @Override
    public String name() {
        return "stats";
    }

    @Override
    public String description() {
        return "Show usage statistics";
    }

    @Override
    public String execute(String args, CommandContext context) {
        if (context.agentLoop() == null) {
            return AnsiStyle.red("  ✗ AgentLoop unavailable.");
        }

        TokenTracker tracker = context.agentLoop().getTokenTracker();
        if (tracker == null) {
            return AnsiStyle.yellow("  ⚠ Token tracker unavailable.");
        }

        // raccoltadati
        int messageCount = context.agentLoop().getMessageHistory().size();
        int conversationRounds = messageCount / 2;  // Conversazione
        long apiCalls = tracker.getApiCallCount();
        long inputTokens = tracker.getInputTokens();
        long outputTokens = tracker.getOutputTokens();
        long totalTokens = tracker.getTotalTokens();
        double estimatedCost = tracker.estimateCost();
        String modelName = tracker.getModelName();

        // ogni voltachiamatamedia Token 
        long avgTokensPerCall = apiCalls > 0 ? totalTokens / apiCalls : 0;

        // Calcola JVM Runtimelungo
        String uptime = formatUptime();

        // Output della build
        StringBuilder sb = new StringBuilder();
        sb.append(AnsiStyle.bold("\n  📊 Session Statistics\n"));
        sb.append("\n");

        // ModelloInformazione
        sb.append(formatRow("Model", AnsiStyle.cyan(modelName)));
        sb.append(formatRow("Uptime", uptime));
        sb.append("\n");

        // Conversazione
        sb.append(AnsiStyle.bold("  ── Conversation ──\n"));
        sb.append(formatRow("Messages", String.valueOf(messageCount)));
        sb.append(formatRow("Conversations (approx)", String.valueOf(conversationRounds)));
        sb.append(formatRow("API Calls", String.valueOf(apiCalls)));
        sb.append("\n");

        // statistiche Token
        sb.append(AnsiStyle.bold("  ── Token Usage ──\n"));
        sb.append(formatRow("Input Tokens", TokenTracker.formatTokens(inputTokens)));
        sb.append(formatRow("Output Tokens", TokenTracker.formatTokens(outputTokens)));
        sb.append(formatRow("Total Tokens", AnsiStyle.bold(TokenTracker.formatTokens(totalTokens))));
        sb.append(formatRow("Avg Tokens/Call", TokenTracker.formatTokens(avgTokensPerCall)));
        sb.append("\n");

        // costo
        sb.append(AnsiStyle.bold("  ── Cost ──\n"));
        sb.append(formatRow("Estimated Cost", formatCost(estimatedCost)));
        sb.append("\n");

        return sb.toString();
    }

    /**
     * formatoTabella（sutag + su）。
     *
     * @param label Firma
     * @param value 
     * @return formato -izzatostringa
     */
    private String formatRow(String label, String value) {
        return String.format("  %-24s %s%n", AnsiStyle.dim(label), value);
    }

    /**
     * formatocosto（ 4 bit/posizionepiccolo）。
     * <p>
     * in base acostoaltobassodiverso：
     * <ul>
     *   <li>$0 e inferiore (gratuito): verde</li>
     *   <li>$0.01 ~ $1.00：</li>
     *   <li>$1.00 e superiore: rosso</li>
     * </ul>
     *
     * @param cost costo（）
     * @return costostringa
     */
    private String formatCost(double cost) {
        String formatted = String.format("$%.4f", cost);
        if (cost < 0.01) {
            return AnsiStyle.green(formatted);
        } else if (cost < 1.0) {
            return AnsiStyle.yellow(formatted);
        } else {
            return AnsiStyle.red(formatted);
        }
    }

    /**
     * Formatta JVM Runtimelungo。
     * <p>
     * usa {@link ManagementFactory#getRuntimeMXBean()} per ottenere il tempo di avvio JVM,
     * eRuntimelungo，formatocome "Xh Ym Zs"。
     *
     * @return formato -izzatoRuntimelungostringa
     */
    private String formatUptime() {
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        long uptimeMillis = runtimeMXBean.getUptime();

        long hours = TimeUnit.MILLISECONDS.toHours(uptimeMillis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(uptimeMillis) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(uptimeMillis) % 60;

        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }
}
