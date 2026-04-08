package com.spaceai.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * utilizzo Tokentraccia — Registra API chiamata token consumoemonitorafinestra di contesto。
 * <p>
 * Estrae le informazioni statistiche sui token dai metadati usage di ChatResponse,
 * supportaSessione、costoefinestra di contestosogliamonitora。
 */
public class TokenTracker {

    // ── finestra di contestosoglia ──
    /** compressione automaticaattivapercentuale（c'èfinestra 93%） */
    public static final double AUTO_COMPACT_THRESHOLD_PCT = 0.93;
    /** Avvisosogliapercentuale（82%） */
    public static final double WARNING_THRESHOLD_PCT = 0.82;
    /** sogliapercentuale（98%，ObbligatorioCompressionecontinua） */
    public static final double BLOCKING_THRESHOLD_PCT = 0.98;
    /** compressione automaticaBuffer token  */
    public static final long AUTO_COMPACT_BUFFER_TOKENS = 13_000;
    /** ManualmenteCompressioneBuffer token  */
    public static final long MANUAL_COMPACT_BUFFER_TOKENS = 3_000;

    /** finestra di contestoAvvisoStato */
    public enum TokenWarningState {
        NORMAL,   // positivo（）
        WARNING,  // soglia（）
        ERROR,    // aCompressionesoglia（）
        BLOCKING  // ObbligatorioCompressionecontinua（）
    }

    private final AtomicLong totalInputTokens = new AtomicLong(0);
    private final AtomicLong totalOutputTokens = new AtomicLong(0);
    private final AtomicLong totalCacheReadTokens = new AtomicLong(0);
    private final AtomicLong totalCacheCreationTokens = new AtomicLong(0);
    private final AtomicLong apiCallCount = new AtomicLong(0);

    /** una volta API chiamata prompt token （Correntecontestodimensione） */
    private final AtomicLong lastPromptTokens = new AtomicLong(0);

    /** Modello（ token ） */
    private double inputPricePerMillion = 0.27;  // DeepSeek-V3 input ($/M token) — aggiorna se cambi modello
    private double outputPricePerMillion = 1.10; // DeepSeek-V3 output ($/M token) — aggiorna se cambi modello
    private double cacheReadPricePerMillion = 0.3; // prezzo lettura cache ($/M token)
    private String modelName = "deepseek-chat";

    /** finestra di contestodimensione（token） */
    private long contextWindowSize;
    /** Output token  */
    private long reservedTokens = 20_000;

    public TokenTracker() {
        // supportaVariabili d'ambientesovrascrivefinestra di contestodimensione
        String envWindow = System.getenv("SPACE_AI_CONTEXT_WINDOW");
        if (envWindow != null && !envWindow.isBlank()) {
            try {
                this.contextWindowSize = Long.parseLong(envWindow.trim());
            } catch (NumberFormatException e) {
                this.contextWindowSize = 200_000; // Predefinito 200K
            }
        } else {
            this.contextWindowSize = 200_000;
        }
    }

    /** recorduna volta API chiamata token  */
    public void recordUsage(long inputTokens, long outputTokens) {
        totalInputTokens.addAndGet(inputTokens);
        totalOutputTokens.addAndGet(outputTokens);
        lastPromptTokens.set(inputTokens);
        apiCallCount.incrementAndGet();
    }

    /** recorduna voltapackageCache API chiamata */
    public void recordUsage(long inputTokens, long outputTokens, long cacheRead, long cacheCreation) {
        totalInputTokens.addAndGet(inputTokens);
        totalOutputTokens.addAndGet(outputTokens);
        totalCacheReadTokens.addAndGet(cacheRead);
        totalCacheCreationTokens.addAndGet(cacheCreation);
        lastPromptTokens.set(inputTokens);
        apiCallCount.incrementAndGet();
    }

    /** ImpostazioniModelloeCorrisponde a */
    public void setModel(String model) {
        this.modelName = model;
        // in base aModelloImpostazioni
        if (model.contains("opus")) {
            inputPricePerMillion = 15.0;
            outputPricePerMillion = 75.0;
            cacheReadPricePerMillion = 1.5;
        } else if (model.contains("sonnet")) {
            inputPricePerMillion = 3.0;
            outputPricePerMillion = 15.0;
            cacheReadPricePerMillion = 0.3;
        } else if (model.contains("haiku")) {
            inputPricePerMillion = 0.25;
            outputPricePerMillion = 1.25;
            cacheReadPricePerMillion = 0.03;
        }
    }

    public long getInputTokens() { return totalInputTokens.get(); }
    public long getOutputTokens() { return totalOutputTokens.get(); }
    public long getCacheReadTokens() { return totalCacheReadTokens.get(); }
    public long getCacheCreationTokens() { return totalCacheCreationTokens.get(); }
    public long getTotalTokens() { return totalInputTokens.get() + totalOutputTokens.get(); }
    public long getApiCallCount() { return apiCallCount.get(); }
    public String getModelName() { return modelName; }

    /** stima il costo della sessione corrente (USD) */
    public double estimateCost() {
        double inputCost = totalInputTokens.get() * inputPricePerMillion / 1_000_000.0;
        double outputCost = totalOutputTokens.get() * outputPricePerMillion / 1_000_000.0;
        double cacheCost = totalCacheReadTokens.get() * cacheReadPricePerMillion / 1_000_000.0;
        return inputCost + outputCost + cacheCost;
    }

    // ── finestra di contestoMonitora ──

    /** c'èfinestra di contestodimensione（finestra - Output） */
    public long getEffectiveWindow() {
        return contextWindowSize - reservedTokens;
    }

    /** una volta prompt  token （Correntecontestodimensione） */
    public long getLastPromptTokens() {
        return lastPromptTokens.get();
    }

    /** Correntecontestopercentuale di utilizzo */
    public double getUsagePercentage() {
        long effective = getEffectiveWindow();
        if (effective <= 0) return 0;
        return (double) lastPromptTokens.get() / effective;
    }

    /** seattivacompressione automatica */
    public boolean shouldAutoCompact() {
        return getUsagePercentage() >= AUTO_COMPACT_THRESHOLD_PCT;
    }

    /** segiàasoglia（ObbligatorioCompressionecontinua） */
    public boolean isBlocking() {
        return getUsagePercentage() >= BLOCKING_THRESHOLD_PCT;
    }

    /** ottienicompressione automaticaattiva token soglia */
    public long getAutoCompactThreshold() {
        return (long) (getEffectiveWindow() * AUTO_COMPACT_THRESHOLD_PCT);
    }

    /** ottieniCorrente token AvvisoStato */
    public TokenWarningState getTokenWarningState() {
        double pct = getUsagePercentage();
        if (pct >= BLOCKING_THRESHOLD_PCT) return TokenWarningState.BLOCKING;
        if (pct >= AUTO_COMPACT_THRESHOLD_PCT) return TokenWarningState.ERROR;
        if (pct >= WARNING_THRESHOLD_PCT) return TokenWarningState.WARNING;
        return TokenWarningState.NORMAL;
    }

    public long getContextWindowSize() { return contextWindowSize; }

    public void setContextWindowSize(long size) { this.contextWindowSize = size; }

    public long getReservedTokens() { return reservedTokens; }

    public void setReservedTokens(long reserved) { this.reservedTokens = reserved; }

    /** reimposta */
    public void reset() {
        totalInputTokens.set(0);
        totalOutputTokens.set(0);
        totalCacheReadTokens.set(0);
        totalCacheCreationTokens.set(0);
        lastPromptTokens.set(0);
        apiCallCount.set(0);
    }

    /** Formatta token quantità（bit/posizionedividi） */
    public static String formatTokens(long tokens) {
        if (tokens < 1000) return String.valueOf(tokens);
        if (tokens < 1_000_000) return String.format("%.1fK", tokens / 1000.0);
        return String.format("%.2fM", tokens / 1_000_000.0);
    }

    // ── Metodi helper per test e compatibilità ────────────────────────────────

    /** Restituisce il totale token di input */
    public long getTotalInputTokens() { return totalInputTokens.get(); }

    /** Restituisce il totale token di output */
    public long getTotalOutputTokens() { return totalOutputTokens.get(); }

    /** Imposta manualmente i lastPromptTokens (per test) */
    public void setLastPromptTokens(long tokens) { lastPromptTokens.set(tokens); }

    /** Stima costo USD (alias per compatibilità test) */
    public double estimateCostUsd() { return estimateCost(); }

    /** Restituisce lo stato di avviso (alias per compatibilità test) */
    public TokenWarningState getWarningState() { return getTokenWarningState(); }

}
