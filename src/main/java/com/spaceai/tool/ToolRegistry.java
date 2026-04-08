package com.spaceai.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

import java.util.*;

/**
 * registrazione strumenticentro —— Corrisponde a space-ai/src/tools.ts inStrumentoInsieme。
 * <p>
 * Gestisci Tool registra、cercaea Spring AI ToolCallback Conversione。
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /**
     * registraStrumento。seStrumento isEnabled() Restituisce false salta。
     */
    public void register(Tool tool) {
        if (!tool.isEnabled()) {
            log.debug("Tool [{}] not enabled, skipping registration", tool.name());
            return;
        }
        if (tools.containsKey(tool.name())) {
            log.warn("Tool [{}] already registered, will be overridden", tool.name());
        }
        tools.put(tool.name(), tool);
        log.debug("Registered tool: [{}]", tool.name());
    }

    /** Registrazione multipla */
    public void registerAll(Tool... toolArray) {
        for (Tool t : toolArray) {
            register(t);
        }
    }

    /** nomeCerca */
    public Optional<Tool> findByName(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** ottieni tuttiregistratoStrumento */
    public List<Tool> getTools() {
        return List.copyOf(tools.values());
    }

    /** ottieni tuttiNome dello strumento */
    public Set<String> getToolNames() {
        return Set.copyOf(tools.keySet());
    }

    /** Conversionecome lista ToolCallback di Spring AI */
    public List<ToolCallback> toCallbacks(ToolContext context) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Tool tool : tools.values()) {
            callbacks.add(new ToolCallbackAdapter(tool, context));
        }
        return callbacks;
    }

    public int size() {
        return tools.size();
    }
}
