package com.spaceai.core.compact;

import com.spaceai.core.TokenTracker;
import com.spaceai.core.compact.CompactionResult.CompactLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Orchestratore di compressione automatica — seleziona ed esegue automaticamente la strategia di compressione in base all'utilizzo dei token.
 * <p>
 * Corrisponde alla logica di orchestrazione della compressione automatica di space-ai. Chiamato dopo ogni risposta API nell'AgentLoop.
 * Flusso: controlla soglia → micro-compressione → compressione Memoria di sessione → compressione completa (fallback)
 * Circuit breaker: fallimenti consecutivi {@value MAX_CONSECUTIVE_FAILURES}  volte prima di sospendere la compressione automatica.
 */
public class AutoCompactManager {

    private static final Logger log = LoggerFactory.getLogger(AutoCompactManager.class);

    /** Soglia di fallimenti consecutivi, oltre la quale si sospende la compressione automatica */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final MicroCompact microCompact;
    private final SessionMemoryCompact sessionMemoryCompact;
    private final FullCompact fullCompact;
    private final TokenTracker tokenTracker;

    /** Numero di fallimenti consecutivi della compressione */
    private int consecutiveFailures = 0;

    /** Se il circuit breaker è già stato attivato */
    private boolean circuitBroken = false;

    /** Callback evento di compressione (per notificare la UI) */
    private Consumer<CompactionResult> onCompactionEvent;

    public AutoCompactManager(ChatModel chatModel, TokenTracker tokenTracker) {
        this.tokenTracker = tokenTracker;
        this.microCompact = new MicroCompact();
        this.sessionMemoryCompact = new SessionMemoryCompact(chatModel);
        this.fullCompact = new FullCompact(chatModel);
    }

    public void setOnCompactionEvent(Consumer<CompactionResult> onCompactionEvent) {
        this.onCompactionEvent = onCompactionEvent;
    }

    /**
     * Chiamato dopo ogni risposta API, esegue automaticamente la compressione in base allo stato di utilizzo dei token.
     *
     * @param historySupplier  Funzione per ottenere la cronologia messaggi corrente
     * @param historyReplacer  Funzione per sostituire la cronologia messaggi
     * @return Restituisce il risultato se la compressione è stata eseguita, altrimenti null
     */
    public CompactionResult autoCompactIfNeeded(
            Supplier<List<Message>> historySupplier,
            Consumer<List<Message>> historyReplacer) {

        // Controllo circuit breaker
        if (circuitBroken) {
            return null;
        }

        // Controlla se è necessaria la compressione
        if (!tokenTracker.shouldAutoCompact()) {
            // Esegue la micro-compressione anche se la compressione automatica non è necessaria (costo minimo)
            List<Message> history = historySupplier.get();
            if (history instanceof java.util.ArrayList<Message> mutableHistory) {
                microCompact.compact(mutableHistory);
            }
            return null;
        }

        log.info("Auto-compact triggered at {}% token usage",
                String.format("%.1f", tokenTracker.getUsagePercentage() * 100));

        List<Message> history = historySupplier.get();

        // Fase 1: micro-compressione
        if (history instanceof java.util.ArrayList<Message> mutableHistory) {
            CompactionResult microResult = microCompact.compact(mutableHistory);
            if (microResult.success()) {
                notifyEvent(microResult);
                // Controlla nuovamente dopo la micro-compressione se è ancora necessaria la compressione profonda
                if (!tokenTracker.shouldAutoCompact()) {
                    consecutiveFailures = 0;
                    return microResult;
                }
            }
        }

        // Fase 2: compressione Memoria di sessione
        try {
            List<Message> compacted = sessionMemoryCompact.getCompactedHistory(history);
            if (compacted != null) {
                historyReplacer.accept(compacted);
                CompactionResult result = CompactionResult.success(
                        CompactLayer.SESSION_MEMORY,
                        history.size(), compacted.size(),
                        "Auto session memory compact");
                consecutiveFailures = 0;
                notifyEvent(result);
                log.info("Session memory compact: {} → {} messages", history.size(), compacted.size());
                return result;
            }
        } catch (Exception e) {
            log.warn("Session memory compact failed: {}", e.getMessage());
        }

        // Fase 3: compressione completa (fallback)
        try {
            List<Message> compacted = fullCompact.compact(history);
            if (compacted != null) {
                historyReplacer.accept(compacted);
                CompactionResult result = CompactionResult.success(
                        CompactLayer.FULL,
                        history.size(), compacted.size(),
                        "Auto full compact (fallback)");
                consecutiveFailures = 0;
                notifyEvent(result);
                log.info("Full compact fallback: {} → {} messages", history.size(), compacted.size());
                return result;
            }
        } catch (Exception e) {
            log.warn("Full compact failed: {}", e.getMessage());
        }

        // Tutte le strategie di compressione sono fallite
        consecutiveFailures++;
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            circuitBroken = true;
            log.error("Auto-compact circuit breaker triggered after {} consecutive failures",
                    consecutiveFailures);
            CompactionResult result = CompactionResult.failure(CompactLayer.FULL,
                    "Circuit breaker: auto-compact disabled after " + consecutiveFailures + " failures");
            notifyEvent(result);
            return result;
        }

        return CompactionResult.failure(CompactLayer.SESSION_MEMORY,
                "All compression strategies failed");
    }

    /** Reset manuale del circuit breaker */
    public void resetCircuitBreaker() {
        circuitBroken = false;
        consecutiveFailures = 0;
        log.info("Auto-compact circuit breaker reset");
    }

    public boolean isCircuitBroken() {
        return circuitBroken;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    /** Ottieni l'istanza FullCompact (per delega di CompactCommand) */
    public FullCompact getFullCompact() {
        return fullCompact;
    }

    private void notifyEvent(CompactionResult result) {
        if (onCompactionEvent != null) {
            try {
                onCompactionEvent.accept(result);
            } catch (Exception e) {
                log.debug("Compaction event notification failed", e);
            }
        }
    }
}
