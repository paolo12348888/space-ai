package com.spaceai.tool.impl;

import com.spaceai.core.TaskManager;
import com.spaceai.core.TaskManager.TaskInfo;
import com.spaceai.tool.Tool;
import com.spaceai.tool.ToolContext;

import java.util.Map;
import java.util.Optional;

/**
 * TaskGet Strumento —— QueryspecificatoattivitàInformazione。
 * <p>
 * Corrisponde a space-ai comando TaskGet in. Restituisce uno snapshot completo dell'attività in base al task_id,
 * packageStato、Risultato、emetadati。
 * </p>
 *
 * <h3>Parametri</h3>
 * <ul>
 *   <li><b>task_id</b>（obbligatorio)—— QueryID attività</li>
 * </ul>
 *
 * <h3>ritorna</h3>
 * <p>JSON formatoattività，oErroreInformazione。</p>
 */
public class TaskGetTool implements Tool {

    /** ToolContext chiave di archiviazione di TaskManager in */
    private static final String TASK_MANAGER_KEY = "TASK_MANAGER";

    @Override
    public String name() {
        return "TaskGet";
    }

    @Override
    public String description() {
        return "Get information about a specific task";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "task_id": {
                      "type": "string",
                      "description": "Task ID to query"
                    }
                  },
                  "required": ["task_id"]
                }""";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        // Ottieni TaskManager istanza
        TaskManager manager = context.get(TASK_MANAGER_KEY);
        if (manager == null) {
            return errorJson("TaskManager not initialized, check context configuration");
        }

        // analisiobbligatorioParametri: task_id
        String taskId = (String) input.get("task_id");
        if (taskId == null || taskId.isBlank()) {
            return errorJson("Parameter 'task_id' is required and cannot be empty");
        }

        // Queryattività
        Optional<TaskInfo> taskOpt = manager.getTask(taskId);
        if (taskOpt.isEmpty()) {
            return errorJson("Task with ID '" + taskId + "' not found");
        }

        // ritornaattività JSON
        return taskInfoToJson(taskOpt.get());
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "🔍 Getting task: " + input.getOrDefault("task_id", "unknown");
    }

    /* ------------------------------------------------------------------ */
    /*  ausiliariometodo                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * verrà TaskInfo Conversionecome JSON stringa。
     */
    private String taskInfoToJson(TaskInfo task) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"task_id\": \"").append(escapeJson(task.id())).append("\",\n");
        sb.append("  \"description\": \"").append(escapeJson(task.description())).append("\",\n");
        sb.append("  \"status\": \"").append(task.status().name()).append("\",\n");

        if (task.result() != null) {
            sb.append("  \"result\": \"").append(escapeJson(task.result())).append("\",\n");
        } else {
            sb.append("  \"result\": null,\n");
        }

        sb.append("  \"created_at\": \"").append(task.createdAt()).append("\",\n");
        sb.append("  \"updated_at\": \"").append(task.updatedAt()).append("\"");

        // Outputmetadati
        if (task.metadata() != null && !task.metadata().isEmpty()) {
            sb.append(",\n  \"metadata\": {");
            boolean first = true;
            for (Map.Entry<String, String> entry : task.metadata().entrySet()) {
                if (!first) sb.append(",");
                sb.append("\n    \"").append(escapeJson(entry.getKey()))
                        .append("\": \"").append(escapeJson(entry.getValue())).append("\"");
                first = false;
            }
            sb.append("\n  }");
        }

        sb.append("\n}");
        return sb.toString();
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
