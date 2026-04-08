package com.spaceai.core.compact;

import com.spaceai.core.compact.CompactionResult.CompactLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * compressione completa —— AI riepilogoTuttocronologia conversazione， PTL（Prompt Too Long）Riprova。
 * <p>
 * Corrisponde a space-ai  fullCompact。 SessionMemoryCompact non puòc'èCompressionecome。
 * PTL RiprovaStrategia： API Round（user→assistant→tool_result comeun gruppo）vecchio。
 */
public class FullCompact {

    private static final Logger log = LoggerFactory.getLogger(FullCompact.class);

    /** PTL RiprovaMassimoconteggio */
    private static final int MAX_PTL_RETRIES = 5;

    /** conserva gli ultimi N messaggi (senza compressione) */
    private static final int KEEP_RECENT_MESSAGES = 2;

    private static final String FULL_COMPACT_PROMPT = """
            Please compress the following conversation history into a thorough summary. Requirements:
            1. Preserve ALL key decisions, code changes, and technical details
            2. Keep file paths, function names, class names, and specific identifiers
            3. Preserve user preferences, requirements, and constraints
            4. Record the current state of work: what was completed, what remains, what's blocked
            5. Note any errors encountered and their resolutions
            6. Keep important context about the project structure and architecture
            7. Output within 1000 words, using structured bullet points
            
            Conversation history:
            """;

    private final ChatModel chatModel;

    public FullCompact(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * eseguecompressione completa。
     *
     * @param history Correntecronologia messaggi
     * @return Compressionedoponuovocronologia；SeFallimentoRestituisce null
     */
    public List<Message> compact(List<Message> history) {
        if (history.size() <= KEEP_RECENT_MESSAGES + 2) {
            return null;
        }

        int before = history.size();
        Message systemMsg = history.getFirst();

        //  API Round raggruppamento
        List<ApiRound> rounds = groupByRounds(history);

        // PTL Riprovaciclo：vecchio round
        int dropCount = 0;
        while (dropCount < rounds.size() - 1 && dropCount < MAX_PTL_RETRIES) {
            List<ApiRound> remaining = rounds.subList(dropCount, rounds.size());

            try {
                String summary = generateFullSummary(remaining);
                if (summary != null && !summary.isBlank()) {
                    // Buildnuovocronologia
                    List<Message> newHistory = new ArrayList<>();
                    newHistory.add(systemMsg);
                    newHistory.add(new SystemMessage("[Conversation Summary]\n" + summary));

                    // conserva gli ultimi messaggi
                    for (int i = Math.max(1, before - KEEP_RECENT_MESSAGES); i < before; i++) {
                        newHistory.add(history.get(i));
                    }

                    log.info("Full compact succeeded: {} → {} messages (dropped {} rounds)",
                            before, newHistory.size(), dropCount);
                    return newHistory;
                }
            } catch (Exception e) {
                log.warn("Full compact attempt failed (drop={}): {}", dropCount, e.getMessage());
                // tentaAnalizza PTL gap conrichiede round 
                int gapDrop = parsePtlGap(e, remaining);
                if (gapDrop > 1) {
                    dropCount += gapDrop;
                    log.info("PTL gap parsed: dropping {} additional rounds", gapDrop);
                    continue;
                }
            }

            dropCount++;
        }

        log.error("Full compact failed after {} PTL retries", dropCount);
        return null;
    }

    /**
     * eseguecompressione completaeRestituisce CompactionResult。
     */
    public CompactionResult compactWithResult(List<Message> history) {
        int before = history.size();
        List<Message> result = compact(history);
        if (result == null) {
            return CompactionResult.failure(CompactLayer.FULL, "Full compact failed");
        }
        return CompactionResult.success(CompactLayer.FULL, before, result.size(), null);
    }

    // ── Internometodo ──

    /**  API Round raggruppamento：un round = [UserMessage] + [AssistantMessage + ToolResponseMessages...] */
    private List<ApiRound> groupByRounds(List<Message> history) {
        List<ApiRound> rounds = new ArrayList<>();
        List<Message> currentRound = new ArrayList<>();

        for (int i = 1; i < history.size(); i++) { // saltaSistemamessaggio
            Message msg = history.get(i);
            if (msg instanceof UserMessage && !currentRound.isEmpty()) {
                rounds.add(new ApiRound(List.copyOf(currentRound)));
                currentRound.clear();
            }
            currentRound.add(msg);
        }

        if (!currentRound.isEmpty()) {
            rounds.add(new ApiRound(List.copyOf(currentRound)));
        }

        return rounds;
    }

    /** generariepilogo */
    private String generateFullSummary(List<ApiRound> rounds) {
        StringBuilder dialogText = new StringBuilder();

        for (ApiRound round : rounds) {
            for (Message msg : round.messages()) {
                switch (msg) {
                    case UserMessage um -> dialogText.append("[User] ").append(um.getText()).append("\n");
                    case AssistantMessage am -> {
                        if (am.getText() != null && !am.getText().isBlank()) {
                            String text = am.getText();
                            if (text.length() > 600) text = text.substring(0, 600) + "...";
                            dialogText.append("[Assistant] ").append(text).append("\n");
                        }
                        if (am.hasToolCalls()) {
                            for (var tc : am.getToolCalls()) {
                                dialogText.append("[Tool Call] ").append(tc.name()).append("\n");
                            }
                        }
                    }
                    case ToolResponseMessage trm -> {
                        for (var resp : trm.getResponses()) {
                            dialogText.append("[Tool Result: ").append(resp.name()).append("]\n");
                        }
                    }
                    default -> {}
                }
            }
            dialogText.append("---\n");
        }

        if (dialogText.isEmpty()) return null;

        Prompt prompt = new Prompt(List.of(new UserMessage(FULL_COMPACT_PROMPT + dialogText)));
        ChatResponse response = chatModel.call(prompt);
        return response.getResult().getOutput().getText();
    }

    /** API Round：unUtenteRichiesta + AI Risposta + chiamata strumentociclo completo */
    private record ApiRound(List<Message> messages) {}

    /**
     * tentada PTL ErroreinAnalizza token gap，richiede round 。
     * API Erroremessaggioformatoclasse: "prompt is too long: 250000 tokens > 200000 token limit"
     * ritorna round ，Senon puòanalisiRestituisce 0。
     */
    private int parsePtlGap(Exception e, List<ApiRound> rounds) {
        String msg = e.getMessage();
        if (msg == null) return 0;

        // tentadaErroremessaggioinEstrai token carattere
        // formato: "NNN tokens > NNN token limit" oclasse
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)\\s*tokens?\\s*>\\s*(\\d+)")
                .matcher(msg);
        if (!m.find()) return 0;

        try {
            long actual = Long.parseLong(m.group(1));
            long limit = Long.parseLong(m.group(2));
            long gap = actual - limit;
            if (gap <= 0) return 0;

            // stima il numero di token per round (media approssimativa)
            long avgTokensPerRound = actual / Math.max(rounds.size(), 1);
            if (avgTokensPerRound <= 0) return 0;

            int roundsToDrop = (int) Math.ceil((double) gap / avgTokensPerRound);
            // conservativo: scarta ~20% dei round (strategia di fallback coerente con TS)
            int fallbackDrop = Math.max(1, (int) Math.floor(rounds.size() * 0.2));
            return Math.min(roundsToDrop, fallbackDrop);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
