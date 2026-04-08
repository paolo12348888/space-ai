package com.spaceai.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hook Sistema —— Corrisponde a space-ai/src/hooks/ modulo。
 * <p>
 * forniscechiamata strumentoprimadopomeccanismo，consenteUtentetramiteFile di configurazione
 * omodalitàregistraInterceptor，inStrumentoeseguefase。
 * <p>
 * supporta Hook Tipo：
 * <ul>
 *   <li>{@link HookType#PRE_TOOL_USE} —— Strumentoesegueprima，puòmodificaParametrioesegue</li>
 *   <li>{@link HookType#POST_TOOL_USE} —— Strumentoeseguedopo，puòmodificaRisultatooattivadopooperazione</li>
 *   <li>{@link HookType#PRE_PROMPT} —— Invia prompt prima，puòmodificamessaggiocontenuto</li>
 *   <li>{@link HookType#POST_RESPONSE} —— aRispostadopo，puòeseguedopoGestisce</li>
 * </ul>
 */
public class HookManager {

    private static final Logger log = LoggerFactory.getLogger(HookManager.class);

    /** c'èregistrato Hook Lista（Threadsicurezza） */
    private final List<HookRegistration> hooks = new CopyOnWriteArrayList<>();

    /**
     * registraun Hook。
     *
     * @param type    Hook Tipo
     * @param name    Hook nome（inLog/Debug）
     * @param handler Hook Gestisce
     */
    public void register(HookType type, String name, HookHandler handler) {
        hooks.add(new HookRegistration(type, name, handler, 0));
        log.debug("Registered Hook: {} [{}]", name, type);
    }

    /**
     * registraunpriorità Hook（caratterepiccoloprioritàalto）。
     */
    public void register(HookType type, String name, HookHandler handler, int priority) {
        hooks.add(new HookRegistration(type, name, handler, priority));
        log.debug("Registered Hook: {} [{}] priority={}", name, type, priority);
    }

    /**
     * eseguespecificatoTipoc'è Hook。
     * <p>
     * Hook prioritàesegue。Se Hook Restituisce {@link HookResult#ABORT}，
     * dopo Hook verrànonesegue，eRestituisce ABORT Risultato。
     *
     * @param type    Hook Tipo
     * @param context Hook eseguecontesto
     * @return Aggregazione Hook Risultato
     */
    public HookResult execute(HookType type, HookContext context) {
        List<HookRegistration> matching = hooks.stream()
                .filter(h -> h.type() == type)
                .sorted((a, b) -> Integer.compare(a.priority(), b.priority()))
                .toList();

        if (matching.isEmpty()) {
            return HookResult.CONTINUE;
        }

        for (HookRegistration reg : matching) {
            try {
                log.debug("Executing Hook: {} [{}]", reg.name(), type);
                HookResult result = reg.handler().handle(context);

                if (result == HookResult.ABORT) {
                    log.info("Hook [{}] aborted the operation", reg.name());
                    return HookResult.ABORT;
                }
            } catch (Exception e) {
                log.warn("Hook [{}] execution exception: {}", reg.name(), e.getMessage());
                // Hook eccezionenon influenza il principalestream
            }
        }

        return HookResult.CONTINUE;
    }

    /** rimuovespecificatonome Hook */
    public void unregister(String name) {
        hooks.removeIf(h -> h.name().equals(name));
    }

    /** ottieni tuttiregistrato Hook */
    public List<HookRegistration> getHooks() {
        return Collections.unmodifiableList(hooks);
    }

    /** cancella tutti Hook */
    public void clear() {
        hooks.clear();
    }

    // ==================== InternoTipo ====================

    /** Hook Tipo */
    public enum HookType {
        /** Strumentoesegueprima —— puòesegueomodificaParametri */
        PRE_TOOL_USE,
        /** Strumentoeseguedopo —— puòmodificaRisultato */
        POST_TOOL_USE,
        /** Invia prompt prima */
        PRE_PROMPT,
        /** aRispostadopo */
        POST_RESPONSE
    }

    /** Hook esegueRisultato */
    public enum HookResult {
        /** continuaEsegui */
        CONTINUE,
        /** operazione interrotta */
        ABORT
    }

    /** Hook GestisceInterfaccia */
    @FunctionalInterface
    public interface HookHandler {
        HookResult handle(HookContext context);
    }

    /** Hook eseguecontesto —— CorrenteoperazioneCorrelatoInformazione */
    public static class HookContext {
        private final String toolName;
        private final Map<String, Object> arguments;
        private String result;
        private final Map<String, Object> metadata;

        public HookContext(String toolName, Map<String, Object> arguments) {
            this.toolName = toolName;
            this.arguments = arguments != null ? arguments : Map.of();
            this.metadata = new java.util.HashMap<>();
        }

        public String getToolName() { return toolName; }
        public Map<String, Object> getArguments() { return arguments; }
        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }

        /** personalizzatometadati */
        public void put(String key, Object value) { metadata.put(key, value); }
        @SuppressWarnings("unchecked")
        public <T> T get(String key) { return (T) metadata.get(key); }
    }

    /** Hook registraRegistra */
    public record HookRegistration(HookType type, String name, HookHandler handler, int priority) {}
}
