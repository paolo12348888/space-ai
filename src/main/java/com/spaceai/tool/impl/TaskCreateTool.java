package com.spaceai.tool.impl;

import com.spaceai.core.TaskManager;
import com.spaceai.tool.Tool;
import com.spaceai.tool.ToolContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TaskCreate Strumento —— creaunnuovodopoattività（ManualmenteModalità）。
 * <p>
 * Corrisponde a space-ai comando TaskCreate in. Dopo la creazione, l'attività è in stato PENDING,
 * richiedetramite TaskUpdate StrumentoStatostream。
 * </p>
 *
 * <h3>Parametri</h3>
 * <ul>
 *   <li><b>description</b>（obbligatorio)—— descrizione attività</li>
 *   <li><b>metadata</b>（Opzionale）—— JSON formatoaggiuntivometadatistringa</li>
 * </ul>
 *
 * <h3>ritorna</h3>
 * <p>JSON formato，package task_id e status Campo。</p>
 */
public class TaskCreateTool implements Tool {

    /** ToolContext chiave di archiviazione di TaskManager in */
    private static final String TASK_MANAGER_KEY = "TASK_MANAGER";

    @Override
    public String name() {
        return "TaskCreate";
    }

    @Override
    public String description() {
        return "Create a new background task for tracking work items";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "description": {
                      "type": "string",
                      "description": "Task description, what this task should accomplish"
                    },
                    "metadata": {
                      "type": "string",
                      "description": "Optional JSON metadata string, e.g. {\\"priority\\":\\"high\\"}"
                    }
                  },
                  "required": ["description"]
                }""";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        // Ottieni TaskManager istanza
        TaskManager manager = context.get(TASK_MANAGER_KEY);
        if (manager == null) {
            return errorJson("TaskManager not initialized, check context configuration");
        }

        // analisiobbligatorioParametri: description
        String desc = (String) input.get("description");
        if (desc == null || desc.isBlank()) {
            return errorJson("Parameter 'description' is required and cannot be empty");
        }

        // analisiOpzionaleParametri: metadata
        Map<String, String> metadata = parseMetadata((String) input.get("metadata"));

        // creaManualmenteattività
        String taskId;
        if (metadata.isEmpty()) {
            taskId = manager.createManualTask(desc);
        } else {
            taskId = manager.createManualTask(desc, metadata);
        }

        // Restituisce JSON Risultato
        return """
                {
                  "task_id": "%s",
                  "description": "%s",
                  "status": "PENDING",
                  "message": "Task created"
                }""".formatted(escapeJson(taskId), escapeJson(desc));
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "📋 Creating task: " + input.getOrDefault("description", "unnamed");
    }

    /* ------------------------------------------------------------------ */
    /*  ausiliariometodo                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * Analizza metadata JSON stringacome Map。
     * analisi：supporta key:value su JSON su。
     * analisiFallimentoritornavuoto Map noneccezione。
     */
    private Map<String, String> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Collections.emptyMap();
        }

        try {
            // analisi：、divide、 key-value
            String trimmed = metadataJson.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
            }

            if (trimmed.isEmpty()) {
                return Collections.emptyMap();
            }

            Map<String, String> result = new LinkedHashMap<>();
            for (String pair : trimmed.split(",")) {
                String[] kv = pair.split(":", 2);
                if (kv.length == 2) {
                    String key = stripQuotes(kv[0].trim());
                    String value = stripQuotes(kv[1].trim());
                    if (!key.isEmpty()) {
                        result.put(key, value);
                    }
                }
            }
            return Collections.unmodifiableMap(result);
        } catch (Exception e) {
            // analisiFallimento，ritornavuoto Map
            return Collections.emptyMap();
        }
    }

    /**
     * dividistringa。
     */
    private String stripQuotes(String s) {
        if (s.length() >= 2
                && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * escape JSON caratteri speciali.
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * BuildErrore JSON Risposta。
     */
    private String errorJson(String message) {
        return """
                {
                  "error": true,
                  "message": "%s"
                }""".formatted(escapeJson(message));
    }
}
