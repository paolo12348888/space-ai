package com.spaceai.core;

import com.spaceai.core.compact.AutoCompactManager;
import com.spaceai.permission.DenialTracker;
import com.spaceai.permission.PermissionRuleEngine;
import com.spaceai.permission.PermissionTypes.PermissionChoice;
import com.spaceai.permission.PermissionTypes.PermissionDecision;
import com.spaceai.tool.ToolCallbackAdapter;
import com.spaceai.tool.ToolContext;
import com.spaceai.tool.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Ciclo Agent — corrisponde all'agent loop di space-ai/src/core/query.ts.
 * <p>
 * Supporta due modalità:
 * <ul>
 *   <li>{@link #run(String)} —— Modalità bloccante, attende la risposta completa prima di restituire</li>
 *   <li>{@link #runStreaming(String, Consumer)} —— Modalità streaming, output token per token in tempo reale</li>
 * </ul>
 * Ciclo esplicito con ChatModel (non ChatClient), controllo completo di ogni iterazione:
 * <ol>
 *   <li>Costruisce il Prompt (cronologia messaggi + sistema di prompt + definizioni strumenti)</li>
 *   <li>Chiama ChatModel.call() o ChatModel.stream()</li>
 *   <li>Controlla le chiamate strumento → conferma permessi → esegue lo strumento → restituisce i risultati</li>
 *   <li>Cicla fino a quando non ci sono più chiamate strumento o si raggiunge il massimo di iterazioni</li>
 * </ol>
 */
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Numero massimo di iterazioni per turno, per prevenire loop infiniti */
    private static final int MAX_ITERATIONS = 50;

    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;
    private final ToolContext toolContext;
    private final String systemPrompt;
    private final TokenTracker tokenTracker;
    private final HookManager hookManager;

    /** Motore di regole dei permessi (opzionale, usa il callback tradizionale se null) */
    private PermissionRuleEngine permissionEngine;

    /** Gestore di compressione automatica (opzionale) */
    private AutoCompactManager autoCompactManager;

    /** Tracker dei rifiuti */
    private final DenialTracker denialTracker = new DenialTracker();

    /** Cronologia messaggi — gestita autonomamente, senza dipendere da Spring AI ChatMemory */
    private final List<Message> messageHistory = new ArrayList<>();

    /** Callback eventi chiamata strumento: notifica la UI prima/dopo ogni chiamata strumento */
    private Consumer<ToolEvent> onToolEvent;

    /** Callback testo assistente: notifica la UI ad ogni risposta dell'assistente (solo modalità bloccante) */
    private Consumer<String> onAssistantMessage;

    /** Callback inizio output streaming: notifica la UI di fermare lo spinner */
    private Runnable onStreamStart;

    /** Callback conferma permessi: richiede conferma all'utente prima di operazioni pericolose (restituisce PermissionChoice) */
    private Function<PermissionRequest, PermissionChoice> onPermissionRequest;

    /** Callback contenuto Thinking: mostra il processo di ragionamento dell'AI */
    private Consumer<String> onThinkingContent;

    public AgentLoop(ChatModel chatModel, ToolRegistry toolRegistry,
                     ToolContext toolContext, String systemPrompt) {
        this(chatModel, toolRegistry, toolContext, systemPrompt, new TokenTracker());
    }

    public AgentLoop(ChatModel chatModel, ToolRegistry toolRegistry,
                     ToolContext toolContext, String systemPrompt, TokenTracker tokenTracker) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.toolContext = toolContext;
        this.systemPrompt = systemPrompt;
        this.tokenTracker = tokenTracker;
        this.hookManager = new HookManager();
        this.messageHistory.add(new SystemMessage(systemPrompt));
    }

    public void setOnToolEvent(Consumer<ToolEvent> onToolEvent) {
        this.onToolEvent = onToolEvent;
    }

    public void setOnAssistantMessage(Consumer<String> onAssistantMessage) {
        this.onAssistantMessage = onAssistantMessage;
    }

    public void setOnStreamStart(Runnable onStreamStart) {
        this.onStreamStart = onStreamStart;
    }

    public void setOnPermissionRequest(Function<PermissionRequest, PermissionChoice> onPermissionRequest) {
        this.onPermissionRequest = onPermissionRequest;
    }

    public void setPermissionEngine(PermissionRuleEngine engine) {
        this.permissionEngine = engine;
    }

    public void setAutoCompactManager(AutoCompactManager manager) {
        this.autoCompactManager = manager;
    }

    public AutoCompactManager getAutoCompactManager() {
        return autoCompactManager;
    }

    public void setOnThinkingContent(Consumer<String> onThinkingContent) {
        this.onThinkingContent = onThinkingContent;
    }

    // ==================== modalità bloccante ====================

    /**
     * Esegue in modalità bloccante un ciclo Agent completo per un input utente.
     * Attende la risposta completa prima di restituire.
     */
    public String run(String userInput) {
        messageHistory.add(new UserMessage(userInput));
        return executeLoop(false, null);
    }

    // ==================== modalità streaming ====================

    /**
     * Esegue in modalità streaming un ciclo Agent completo per un input utente.
     * Il testo viene inviato token per token al terminale tramite il callback onToken in tempo reale.
     *
     * @param userInput Testo di input dell'utente
     * @param onToken   Callback in tempo reale per ogni token di testo (per la visualizzazione carattere per carattere nel terminale)
     * @return Testo completo finale della risposta dell'assistente
     */
    public String runStreaming(String userInput, Consumer<String> onToken) {
        messageHistory.add(new UserMessage(userInput));
        return executeLoop(true, onToken);
    }

    // ==================== Ciclo principale (unificato bloccante/streaming) ====================

    private String executeLoop(boolean streaming, Consumer<String> onToken) {
        List<ToolCallback> callbacks = toolRegistry.toCallbacks(toolContext);
        ChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(callbacks)
                .internalToolExecutionEnabled(false)
                .build();

        int iteration = 0;
        String lastAssistantText = "";

        while (iteration < MAX_ITERATIONS) {
            iteration++;
            log.debug("Agent loop iteration {} ({})", iteration, streaming ? "streaming" : "blocking");

            Prompt prompt = new Prompt(List.copyOf(messageHistory), options);

            // Chiama l'AI e ottiene i risultati
            IterationResult result;
            if (streaming) {
                result = streamIteration(prompt, onToken);
            } else {
                result = blockingIteration(prompt);
            }

            // Registra l'utilizzo dei Token
            if (result.promptTokens > 0 || result.completionTokens > 0) {
                tokenTracker.recordUsage(result.promptTokens, result.completionTokens);
            }

            // Aggiunge il messaggio dell'assistente alla cronologia
            messageHistory.add(result.assistant);

            String text = result.assistant.getText();
            if (text != null && !text.isBlank()) {
                lastAssistantText = text;
                // Notifica la UI in modalità bloccante (la modalità streaming emette già in tempo reale nel callback)
                if (!streaming && onAssistantMessage != null) {
                    onAssistantMessage.accept(text);
                }
            }

            // Nessuna chiamata strumento → fine
            if (!result.assistant.hasToolCalls()) {
                log.debug("No tool calls, loop ended (total {} iterations)", iteration);
                break;
            }

            // Esegue le chiamate strumento
            executeToolCalls(result.assistant.getToolCalls(), callbacks);

            // Controllo compressione automatica (dopo la chiamata strumento, prima della prossima chiamata API)
            if (autoCompactManager != null) {
                autoCompactManager.autoCompactIfNeeded(
                        () -> messageHistory,
                        this::replaceHistory
                );
            }
        }

        if (iteration >= MAX_ITERATIONS) {
            log.warn("Agent loop reached max iterations {}, force stopping", MAX_ITERATIONS);
            lastAssistantText += "\n\n[WARNING: Maximum loop iteration limit reached]";
        }

        return lastAssistantText;
    }

    /** Modalità bloccante: chiama chatModel.call() e analizza il risultato */
    private IterationResult blockingIteration(Prompt prompt) {
        ChatResponse response = chatModel.call(prompt);

        long promptTokens = 0, completionTokens = 0;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            var usage = response.getMetadata().getUsage();
            promptTokens = usage.getPromptTokens();
            completionTokens = usage.getCompletionTokens();
        }

        // Tenta di estrarre il contenuto thinking (Anthropic extended thinking)
        extractThinkingContent(response);

        return new IterationResult(response.getResult().getOutput(), promptTokens, completionTokens);
    }

    /** Modalità streaming: chiama chatModel.stream() per output token per token, accumula la risposta completa */
    private IterationResult streamIteration(Prompt prompt, Consumer<String> onToken) {
        StringBuilder textBuffer = new StringBuilder();
        // Le chiamate strumento sono accumulate con deduplicazione per ID (lo streaming frammentato può inviare la stessa chiamata più volte)
        Map<String, AssistantMessage.ToolCall> toolCallMap = new LinkedHashMap<>();
        long[] tokenUsage = {0, 0};
        boolean[] firstToken = {true};

        try {
            Flux<ChatResponse> flux = chatModel.stream(prompt);

            flux.doOnNext(chunk -> {
                // Registra l'utilizzo dei token (di solito nell'ultimo chunk)
                if (chunk.getMetadata() != null && chunk.getMetadata().getUsage() != null) {
                    var usage = chunk.getMetadata().getUsage();
                    if (usage.getPromptTokens() > 0) tokenUsage[0] = usage.getPromptTokens();
                    if (usage.getCompletionTokens() > 0) tokenUsage[1] = usage.getCompletionTokens();
                }

                if (chunk.getResult() == null || chunk.getResult().getOutput() == null) return;
                AssistantMessage output = chunk.getResult().getOutput();

                // Output in tempo reale dei token di testo
                String text = output.getText();
                if (text != null && !text.isEmpty()) {
                    // Notifica la UI all'arrivo del primo token (ferma lo spinner)
                    if (firstToken[0]) {
                        firstToken[0] = false;
                        if (onStreamStart != null) onStreamStart.run();
                    }
                    textBuffer.append(text);
                    if (onToken != null) onToken.accept(text);
                }

                // Accumula le chiamate strumento (con deduplicazione per ID)
                if (output.hasToolCalls()) {
                    for (var tc : output.getToolCalls()) {
                        if (tc.id() != null) {
                            toolCallMap.putIfAbsent(tc.id(), tc);
                        }
                    }
                }
            }).blockLast();

        } catch (Exception e) {
            // Chiamata streaming fallita → degrada alla modalità bloccante
            log.warn("Streaming call failed, falling back to blocking mode: {}", e.getMessage());
            return blockingIteration(prompt);
        }

        // Usa il Builder per costruire AssistantMessage (il costruttore è protected)
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>(toolCallMap.values());
        AssistantMessage assistant = AssistantMessage.builder()
                .content(textBuffer.toString())
                .toolCalls(toolCalls)
                .build();

        return new IterationResult(assistant, tokenUsage[0], tokenUsage[1]);
    }

    /** Esegue la lista di chiamate strumento e aggiunge i risultati alla cronologia messaggi */
    @SuppressWarnings("unchecked")
    private void executeToolCalls(List<AssistantMessage.ToolCall> toolCalls,
                                  List<ToolCallback> callbacks) {
        List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();

        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.name();
            String toolArgs = toolCall.arguments();
            String callId = toolCall.id();

            // Analizza i parametri per Hook e controllo permessi
            Map<String, Object> parsedArgs = Map.of();
            try {
                parsedArgs = MAPPER.readValue(toolArgs, Map.class);
            } catch (Exception ignored) {}

            // PreToolUse Hook
            var preHookCtx = new HookManager.HookContext(toolName, parsedArgs);
            if (hookManager.execute(HookManager.HookType.PRE_TOOL_USE, preHookCtx) == HookManager.HookResult.ABORT) {
                log.info("[{}] PreToolUse Hook aborted execution", toolName);
                toolResponses.add(new ToolResponseMessage.ToolResponse(callId, toolName, "Aborted by hook"));
                continue;
            }

            if (onToolEvent != null) {
                onToolEvent.accept(new ToolEvent(toolName, ToolEvent.Phase.START, toolArgs, null));
            }

            String result;
            ToolCallbackAdapter adapter = findCallbackByName(callbacks, toolName);
            if (adapter != null) {
                // Controllo permessi: usa prima il motore di regole, poi il callback tradizionale
                boolean permitted = true;
                if (permissionEngine != null) {
                    PermissionDecision decision = permissionEngine.evaluate(
                            toolName, parsedArgs, adapter.getTool().isReadOnly());
                    if (decision.isAllowed()) {
                        permitted = true;
                        denialTracker.recordSuccess();
                    } else if (decision.isDenied()) {
                        permitted = false;
                        denialTracker.recordDenial();
                        log.info("[{}] Denied by rule: {}", toolName, decision.reason());
                    } else if (decision.needsAsk() && onPermissionRequest != null) {
                        // Tracciamento rifiuti: forza il ritorno al prompt manuale dopo troppi rifiuti consecutivi
                        if (denialTracker.shouldFallbackToPrompting()) {
                            log.info("[{}] Denial threshold reached, forcing manual prompt", toolName);
                        }
                        String activity = adapter.getTool().activityDescription(parsedArgs);
                        PermissionRequest req = new PermissionRequest(toolName, toolArgs, activity);
                        req.setDecision(decision);
                        PermissionChoice choice = onPermissionRequest.apply(req);
                        permitted = (choice == PermissionChoice.ALLOW_ONCE || choice == PermissionChoice.ALWAYS_ALLOW);
                        if (permitted) {
                            denialTracker.recordSuccess();
                        } else {
                            denialTracker.recordDenial();
                        }
                        // Persistenza della scelta utente
                        String command = parsedArgs != null ? (String) parsedArgs.get("command") : null;
                        permissionEngine.applyChoice(choice, toolName, command);
                    } else {
                        permitted = false;
                        denialTracker.recordDenial();
                    }
                } else if (!adapter.getTool().isReadOnly() && onPermissionRequest != null) {
                    // Modalità callback tradizionale (compatibilità retroattiva)
                    String activity = adapter.getTool().activityDescription(parsedArgs);
                    PermissionRequest req = new PermissionRequest(toolName, toolArgs, activity);
                    PermissionChoice choice = onPermissionRequest.apply(req);
                    permitted = (choice == PermissionChoice.ALLOW_ONCE || choice == PermissionChoice.ALWAYS_ALLOW);
                }

                if (permitted) {
                    result = adapter.call(toolArgs);
                } else {
                    result = "Permission denied: User rejected this operation";
                    log.info("[{}] User denied tool execution", toolName);
                }
            } else {
                result = "Error: Unknown tool '" + toolName + "'";
                log.warn("Unknown tool: {}", toolName);
            }

            // PostToolUse Hook
            var postHookCtx = new HookManager.HookContext(toolName, parsedArgs);
            postHookCtx.setResult(result);
            hookManager.execute(HookManager.HookType.POST_TOOL_USE, postHookCtx);
            // L'Hook potrebbe aver modificato il risultato
            if (postHookCtx.getResult() != null) {
                result = postHookCtx.getResult();
            }

            if (onToolEvent != null) {
                onToolEvent.accept(new ToolEvent(toolName, ToolEvent.Phase.END, toolArgs, result));
            }

            toolResponses.add(new ToolResponseMessage.ToolResponse(callId, toolName, result));
        }

        messageHistory.add(ToolResponseMessage.builder().responses(toolResponses).build());
    }

    /** trova l'adattatore con il nome corrispondente nella lista ToolCallback */
    private ToolCallbackAdapter findCallbackByName(List<ToolCallback> callbacks, String name) {
        for (ToolCallback cb : callbacks) {
            if (cb instanceof ToolCallbackAdapter adapter && adapter.getTool().name().equals(name)) {
                return adapter;
            }
        }
        return null;
    }

    /** ottienicronologia messaggi（incontestoCompressioneecc.） */
    public List<Message> getMessageHistory() {
        return Collections.unmodifiableList(messageHistory);
    }

    /** Ottieni tracciamento Token */
    public TokenTracker getTokenTracker() {
        return tokenTracker;
    }

    /** ottieniSistemaprompt */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /** Ottieni ChatModel（incontestoCompressioneecc.richiededirettamentechiama il modello） */
    public ChatModel getChatModel() {
        return chatModel;
    }

    /** ottieniStrumentocontesto（inregistraCallback） */
    public ToolContext getToolContext() {
        return toolContext;
    }

    /** Ottieni Hook gestore */
    public HookManager getHookManager() {
        return hookManager;
    }

    /** reimpostacronologia（Sistemaprompt) */
    public void reset() {
        messageHistory.clear();
        messageHistory.add(new SystemMessage(systemPrompt));
    }

    /** sostituiscecronologia messaggi（incontestoCompressionedoposostituisce） */
    public void replaceHistory(List<Message> newHistory) {
        messageHistory.clear();
        messageHistory.addAll(newHistory);
    }

    /** iterazioneRisultato */
    private record IterationResult(AssistantMessage assistant, long promptTokens, long completionTokens) {}

    /**
     * Tenta di estrarre il contenuto thinking da ChatResponse.
     * <p>
     * Anthropic  extended thinking FunzioneinRispostainpackage。
     * Spring AI puòverràil suoin metadata inocomemessaggioattributo。
     */
    private void extractThinkingContent(ChatResponse response) {
        if (onThinkingContent == null) return;

        try {
            // modalità1: Controlla response metadata in thinking Campo
            if (response.getMetadata() != null) {
                var metadata = response.getMetadata();
                // Spring AI puòin metadata inArchiviazione thinking contenuto
                // versioni diversepuòc'èdiverso key
                if (metadata instanceof Map<?, ?> metaMap) {
                    Object thinking = metaMap.get("thinking");
                    if (thinking instanceof String thinkText && !thinkText.isBlank()) {
                        onThinkingContent.accept(thinkText);
                        return;
                    }
                }
            }

            // modalità2: Controlla AssistantMessage  metadata
            if (response.getResult() != null && response.getResult().getOutput() != null) {
                var output = response.getResult().getOutput();
                var msgMeta = output.getMetadata();
                if (msgMeta != null) {
                    // tentaOttieni thinking Correlatometadati
                    Object thinking = msgMeta.get("thinking");
                    if (thinking instanceof String thinkText && !thinkText.isBlank()) {
                        onThinkingContent.accept(thinkText);
                    }
                }
            }
        } catch (Exception e) {
            // thinking Fallimentonon influenza il principalestream
            log.debug("Thinking content extraction exception (can be ignored): {}", e.getMessage());
        }
    }

    /** conferma permessiRichiesta */
    public static class PermissionRequest {
        private final String toolName;
        private final String arguments;
        private final String activityDescription;
        private PermissionDecision decision;

        public PermissionRequest(String toolName, String arguments, String activityDescription) {
            this.toolName = toolName;
            this.arguments = arguments;
            this.activityDescription = activityDescription;
        }

        public String toolName() { return toolName; }
        public String arguments() { return arguments; }
        public String activityDescription() { return activityDescription; }
        public PermissionDecision decision() { return decision; }
        public void setDecision(PermissionDecision decision) { this.decision = decision; }
    }

    /** StrumentoEvento，Usato per UI visualizza */
    public record ToolEvent(String toolName, Phase phase, String arguments, String result) {
        public enum Phase { START, END }
    }
}
