package com.spaceai.tool;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Strumentoeseguecontesto —— Corrisponde a space-ai in ToolUseContext。
 * <p>
 * fornisceStrumentoesegueinformazioni sull'ambienteecondivisoStato。
 */
public class ToolContext {

    private final Path workDir;
    private final String model;
    private final ConcurrentHashMap<String, Object> state;

    public ToolContext(Path workDir, String model) {
        this.workDir = workDir;
        this.model = model;
        this.state = new ConcurrentHashMap<>();
    }

    public static ToolContext defaultContext() {
        return new ToolContext(Path.of(System.getProperty("user.dir")), "deepseek-chat");
    }

    public Path getWorkDir() {
        return workDir;
    }

    public String getModel() {
        return model;
    }

    /** ottienivalore di stato condiviso */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) state.get(key);
    }

    /** Impostazionivalore di stato condiviso */
    public void set(String key, Object value) {
        state.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(String key, T defaultValue) {
        return (T) state.getOrDefault(key, defaultValue);
    }

    public boolean has(String key) {
        return state.containsKey(key);
    }
}
