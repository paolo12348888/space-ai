package com.spaceai.core.compact;

import com.spaceai.core.compact.CompactionResult.CompactLayer;
import org.springframework.ai.chat.messages.*;

import java.time.Instant;
import java.util.List;

/**
 * Comprimi —— inogni volta API chiamatadopoesegue，clippingvecchio tool_result contenuto。
 * <p>
 * Corrisponde a space-ai  microCompact。nonrichiedeesterno API chiamata，localeoperazione。
 * Strategia：
 * <ul>
 *   <li>conserva i risultati tool degli ultimi N round, i più vecchi conservano solo la riga di riepilogo</li>
 *   <li>：vuoto gapThresholdMinutes dopopulizia attiva</li>
 * </ul>
 */
public class MicroCompact {

    /** conserva il contenuto completo degli ultimi N ToolResponseMessage */
    private static final int KEEP_RECENT_TOOL_RESULTS = 6;

    /** troncasoglia：questolungovecchio tool result tronca */
    private static final int TRUNCATE_THRESHOLD = 200;

    /** troncadopobit/posizionetesto */
    private static final String TRUNCATED_MARKER = "[Tool result truncated — %d chars omitted]";

    /** ：vuotoquestominutidoporimuovimenoquantità */
    private static final int GAP_THRESHOLD_MINUTES = 10;

    /** vuoto tool result quantità（） */
    private static final int KEEP_RECENT_AFTER_GAP = 2;

    /** ultimo tempo attivo */
    private Instant lastActivityTime = Instant.now();

    /** Aggiornamento（ogni volta API chiamatadopochiamata） */
    public void recordActivity() {
        lastActivityTime = Instant.now();
    }

    /**
     * sucronologia messaggieseguemicro-compressione。
     * direttamenteinListasopramodificacon。
     *
     * @param history messaggioLista（direttamentemodifica）
     * @return CompressioneRisultato
     */
    public CompactionResult compact(List<Message> history) {
        int totalToolResponses = 0;
        int truncated = 0;

        // ：vuotoTimeoutdopoStrategia
        long minutesSinceLastActivity = java.time.Duration.between(lastActivityTime, Instant.now()).toMinutes();
        int keepRecent = minutesSinceLastActivity >= GAP_THRESHOLD_MINUTES
                ? KEEP_RECENT_AFTER_GAP
                : KEEP_RECENT_TOOL_RESULTS;

        // Scansione，ac'è ToolResponseMessage posizione
        int recentCount = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof ToolResponseMessage) {
                totalToolResponses++;
                recentCount++;
                if (recentCount > keepRecent) {
                    // richiedetronca
                    ToolResponseMessage trm = (ToolResponseMessage) history.get(i);
                    if (shouldTruncate(trm)) {
                        history.set(i, truncateToolResponse(trm));
                        truncated++;
                    }
                }
            }
        }

        if (truncated == 0) {
            return CompactionResult.noAction(CompactLayer.MICRO, "No tool results to truncate");
        }

        return CompactionResult.success(CompactLayer.MICRO, totalToolResponses,
                totalToolResponses - truncated, null);
    }

    /**  ToolResponseMessage serichiedetronca */
    private boolean shouldTruncate(ToolResponseMessage trm) {
        var responses = trm.getResponses();
        if (responses == null || responses.isEmpty()) return false;
        for (var resp : responses) {
            if (resp.responseData() != null && resp.responseData().toString().length() > TRUNCATE_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /** creatroncadopo ToolResponseMessage */
    private ToolResponseMessage truncateToolResponse(ToolResponseMessage original) {
        var responses = original.getResponses();
        if (responses == null || responses.isEmpty()) return original;

        var truncatedResponses = responses.stream().map(resp -> {
            String data = resp.responseData() != null ? resp.responseData().toString() : "";
            if (data.length() > TRUNCATE_THRESHOLD) {
                String marker = String.format(TRUNCATED_MARKER, data.length());
                return new ToolResponseMessage.ToolResponse(resp.id(), resp.name(), marker);
            }
            return resp;
        }).toList();

        return ToolResponseMessage.builder()
                .responses(truncatedResponses)
                .build();
    }
}
