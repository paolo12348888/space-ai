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
 * Session Memory Comprimi —— conserva i segmenti di messaggi recenti, usa l'AI per riassumere i messaggi più vecchi.
 * <p>
 * Corrisponde a space-ai  sessionMemoryCompact。sìcompressione automaticamodalità。
 * ：
 * <ol>
 *   <li>asopraCompressioneconfine（tramiterileva [Conversation Summary] ）</li>
 *   <li>richiedemessaggio（meno MIN_KEEP_TOKENS token  + MIN_KEEP_TEXT_MSGS testomessaggio）</li>
 *   <li>verràconfinedopo、primamessaggiotramite AI generariepilogo</li>
 *   <li> [Sistemasuggerimento] + [cronologiariepilogo] + [nuovoriepilogo] + [] sostituiscecronologia</li>
 * </ol>
 */
public class SessionMemoryCompact {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryCompact.class);

    /** menotestomessaggio（Utente + ） */
    private static final int MIN_KEEP_TEXT_MSGS = 5;

    /** stima il numero minimo di token da conservare */
    private static final int MIN_KEEP_TOKENS = 10_000;

    /** stima il numero massimo di token da conservare */
    private static final int MAX_KEEP_TOKENS = 40_000;

    /** carattere token （） */
    private static final double CHARS_PER_TOKEN = 4.0;

    /** token fattore di sicurezza stimato (conservativo, corrisponde al moltiplicatore 4/3 di TS) */
    private static final double ESTIMATION_SAFETY_FACTOR = 4.0 / 3.0;

    private static final String SUMMARY_PROMPT = """
            Summarize the following conversation segment concisely but thoroughly.
            Preserve:
            - All key technical decisions and their rationale
            - File paths, function names, class names, and specific code identifiers
            - User requirements and preferences
            - Current state of work (what was done, what remains)
            - Any errors encountered and their resolutions
            
            Keep the summary under 800 words. Use bullet points for clarity.
            
            Conversation segment to summarize:
            """;

    private final ChatModel chatModel;

    public SessionMemoryCompact(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * Esegui Session Memory Compressione。
     *
     * @param history Correntecronologia messaggi（non modifica direttamente, restituisce nuova lista）
     * @return CompressioneRisultato；Senon puòCompressioneRestituisce noAction
     */
    public CompactionResult compact(List<Message> history) {
        if (history.size() <= MIN_KEEP_TEXT_MSGS + 2) {
            return CompactionResult.noAction(CompactLayer.SESSION_MEMORY,
                    "Too few messages to compact");
        }

        int before = history.size();

        // aSistemaprompt（）
        Message systemMsg = history.getFirst();

        // asoprauna voltariepilogoposizione（Sec'è）
        int lastSummaryIndex = findLastSummaryIndex(history);

        // calcola l'area comprimibile a partire dal riepilogo
        int compressibleStart = lastSummaryIndex + 1;

        // trova la posizione iniziale del segmento da conservare procedendo dalla fine
        int keepStart = findKeepStart(history, compressibleStart);

        // SepuòCompressioneareapiccolo，nonCompressione
        if (keepStart - compressibleStart < 4) {
            return CompactionResult.noAction(CompactLayer.SESSION_MEMORY,
                    "Not enough messages to compress (only " + (keepStart - compressibleStart) + " in range)");
        }

        // richiedeCompressionemessaggio
        List<Message> toCompress = history.subList(compressibleStart, keepStart);

        // generariepilogo
        String summary;
        try {
            summary = generateSummary(toCompress);
        } catch (Exception e) {
            log.warn("Session memory compression failed: {}", e.getMessage());
            return CompactionResult.failure(CompactLayer.SESSION_MEMORY,
                    "Summary generation failed: " + e.getMessage());
        }

        if (summary == null || summary.isBlank()) {
            return CompactionResult.failure(CompactLayer.SESSION_MEMORY,
                    "Empty summary generated");
        }

        // Buildnuovocronologia
        List<Message> newHistory = new ArrayList<>();
        newHistory.add(systemMsg);

        // conserva il vecchio riepilogo (se esiste, uniscilo al nuovo)
        String previousSummary = extractPreviousSummary(history, lastSummaryIndex);
        if (previousSummary != null) {
            summary = "=== Earlier Context ===\n" + previousSummary + "\n\n=== Recent Activity ===\n" + summary;
        }

        // aggiungenuovoriepilogomessaggio
        newHistory.add(new SystemMessage("[Conversation Summary]\n" + summary));

        // aggiunge
        for (int i = keepStart; i < history.size(); i++) {
            newHistory.add(history.get(i));
        }

        int after = newHistory.size();
        return new CompactionResult(true, CompactLayer.SESSION_MEMORY, before, after, summary,
                "Session memory compacted: " + before + " → " + after + " messages");
    }

    /**
     * ottieniCompressionedoponuovocronologia。chiamatarichiedechiamata compact() confermaSuccesso，dopochiamataquestometodoottieniRisultato。
     * per evitare logica duplicata, questo metodo ri-esegue la compressione e restituisce la nuova cronologia.
     */
    public List<Message> getCompactedHistory(List<Message> history) {
        if (history.size() <= MIN_KEEP_TEXT_MSGS + 2) return null;

        Message systemMsg = history.getFirst();
        int lastSummaryIndex = findLastSummaryIndex(history);
        int compressibleStart = lastSummaryIndex + 1;
        int keepStart = findKeepStart(history, compressibleStart);

        if (keepStart - compressibleStart < 4) return null;

        List<Message> toCompress = history.subList(compressibleStart, keepStart);
        String summary;
        try {
            summary = generateSummary(toCompress);
        } catch (Exception e) {
            return null;
        }
        if (summary == null || summary.isBlank()) return null;

        List<Message> newHistory = new ArrayList<>();
        newHistory.add(systemMsg);

        String previousSummary = extractPreviousSummary(history, lastSummaryIndex);
        if (previousSummary != null) {
            summary = "=== Earlier Context ===\n" + previousSummary + "\n\n=== Recent Activity ===\n" + summary;
        }

        newHistory.add(new SystemMessage("[Conversation Summary]\n" + summary));
        for (int i = keepStart; i < history.size(); i++) {
            newHistory.add(history.get(i));
        }

        return newHistory;
    }

    // ── Internometodo ──

    /** acronologiaindopoun [Conversation Summary] SistemamessaggioIndice */
    private int findLastSummaryIndex(List<Message> history) {
        for (int i = history.size() - 1; i >= 1; i--) {
            if (history.get(i) instanceof SystemMessage sm
                    && sm.getText() != null
                    && sm.getText().startsWith("[Conversation Summary]")) {
                return i;
            }
        }
        return 0; // c'èriepilogo，daSistemasuggerimentodopoinizia
    }

    /** trova la posizione iniziale del segmento da conservare procedendo dalla fine */
    private int findKeepStart(List<Message> history, int minStart) {
        int textMsgCount = 0;
        long estimatedTokens = 0;

        for (int i = history.size() - 1; i >= minStart; i--) {
            Message msg = history.get(i);

            // Stima token 
            long msgTokens = estimateTokens(msg);
            estimatedTokens += msgTokens;

            if (msg instanceof UserMessage || msg instanceof AssistantMessage) {
                textMsgCount++;
            }

            // nondividi tool_use / tool_result su
            // SeCorrentesì ToolResponseMessage， AssistantMessage（ tool_calls）inprima
            if (msg instanceof ToolResponseMessage && i > minStart) {
                continue; // continuaprimapackageCorrisponde a AssistantMessage
            }

            // pienoMinimocondizione，giàasopraferma
            if (textMsgCount >= MIN_KEEP_TEXT_MSGS && estimatedTokens >= MIN_KEEP_TOKENS) {
                // controllasea token sopra
                if (estimatedTokens >= MAX_KEEP_TOKENS) {
                    return i;
                }
            }
        }

        // Seda minStart in poi tutto è nell'intervallo di conservazione, restituisce minStart
        // Descrizionemessaggiononpiù，nonrichiedeCompressione
        return minStart;
    }

    /** stima il numero di token del messaggio */
    private long estimateTokens(Message msg) {
        String text = switch (msg) {
            case UserMessage um -> um.getText();
            case AssistantMessage am -> am.getText();
            case SystemMessage sm -> sm.getText();
            case ToolResponseMessage trm -> {
                StringBuilder sb = new StringBuilder();
                for (var resp : trm.getResponses()) {
                    if (resp.responseData() != null) {
                        sb.append(resp.responseData().toString());
                    }
                }
                yield sb.toString();
            }
            default -> "";
        };
        if (text == null || text.isEmpty()) return 10; // Minimo
        return (long) (text.length() / CHARS_PER_TOKEN * ESTIMATION_SAFETY_FACTOR);
    }

    /** soprauna voltariepilogotesto */
    private String extractPreviousSummary(List<Message> history, int summaryIndex) {
        if (summaryIndex <= 0) return null;
        Message msg = history.get(summaryIndex);
        if (msg instanceof SystemMessage sm && sm.getText() != null) {
            String text = sm.getText();
            if (text.startsWith("[Conversation Summary]\n")) {
                return text.substring("[Conversation Summary]\n".length());
            }
            if (text.startsWith("[Conversation Summary] ")) {
                return text.substring("[Conversation Summary] ".length());
            }
        }
        return null;
    }

    /** chiamata AI generaConversazioneriepilogo */
    private String generateSummary(List<Message> segment) {
        StringBuilder dialogText = new StringBuilder();
        for (Message msg : segment) {
            switch (msg) {
                case UserMessage um -> dialogText.append("[User] ").append(um.getText()).append("\n");
                case AssistantMessage am -> {
                    if (am.getText() != null && !am.getText().isBlank()) {
                        String text = am.getText();
                        if (text.length() > 800) text = text.substring(0, 800) + "...";
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
                        String data = resp.responseData() != null ? resp.responseData().toString() : "";
                        if (data.length() > 200) data = data.substring(0, 200) + "...";
                        dialogText.append("[Tool Result: ").append(resp.name()).append("] ")
                                .append(data).append("\n");
                    }
                }
                default -> {}
            }
        }

        if (dialogText.isEmpty()) return null;

        Prompt prompt = new Prompt(List.of(new UserMessage(SUMMARY_PROMPT + dialogText)));
        ChatResponse response = chatModel.call(prompt);
        return response.getResult().getOutput().getText();
    }
}
