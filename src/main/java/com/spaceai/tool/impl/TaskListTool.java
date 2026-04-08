package com.spaceai.tool.impl;

import com.spaceai.core.TaskManager;
import com.spaceai.core.TaskManager.TaskInfo;
import com.spaceai.core.TaskManager.TaskStatus;
import com.spaceai.tool.Tool;
import com.spaceai.tool.ToolContext;

import java.util.List;
import java.util.Map;

/**
 * TaskList Strumento —— elenca tuttiattività，supportaStatoFiltraggio。
 * <p>
 * Corrisponde a space-ai comando TaskList in. Restituisce un array JSON della lista attività,
 * ognipackageID attività、descrizioneeCorrenteStato。
 * </p>
 *
 * <h3>Parametri</h3>
 * <ul>
 *   <li><b>status</b>（Opzionale）—— StatoFiltraggio：PENDING / RUNNING / COMPLETED / FAILED / CANCELLED</li>
 * </ul>
 *
 * <h3>ritorna</h3>
 * <p>JSON formatolista attività。</p>
 */
public class TaskListTool implements Tool {

    /** ToolContext chiave di archiviazione di TaskManager in */
    private static final String TASK_MANAGER_KEY = "TASK_MANAGER";

    @Override
    public String name() {
        return "TaskList";
    }

    @Override
    public String description() {
        return "List all tasks, optionally filtered by status";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "status": {
                      "type": "string",
                      "description": "Filter by status: PENDING / RUNNING / COMPLETED / FAILED / CANCELLED",
                      "enum": ["PENDING", "RUNNING", "COMPLETED", "FAILED", "CANCELLED"]
                    }
                  },
                  "required": []
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

        // analisiOpzionaleParametri: status
        TaskStatus statusFilter = null;
        String statusStr = (String) input.get("status");
        if (statusStr != null && !statusStr.isBlank()) {
            try {
                statusFilter = TaskStatus.valueOf(statusStr.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return errorJson("Invalid status value: '" + statusStr
                        + "'. Valid values: PENDING, RUNNING, COMPLETED, FAILED, CANCELLED");
            }
        }

        // Querylista attività
        List<TaskInfo> taskList = manager.listTasks(statusFilter);

        // Costruisci JSON Risposta
        return buildListJson(taskList, statusFilter);
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        String status = (String) input.get("status");
        if (status != null && !status.isBlank()) {
            return "📋 Listing tasks [" + status + "]";
        }
        return "📋 Listing all tasks";
    }

    /* ------------------------------------------------------------------ */
    /*  ausiliariometodo                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * verràlista attivitàBuildcome JSON Risposta。
     *
     * @param taskList     lista attività
     * @param statusFilter Correntecondizione di filtro usata (per la visualizzazione delle informazioni), può essere null
     * @return JSON stringa
     */
    private String buildListJson(List<TaskInfo> taskList, TaskStatus statusFilter) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"total\": ").append(taskList.size()).append(",\n");

        if (statusFilter != null) {
            sb.append("  \"filter\": \"").append(statusFilter.name()).append("\",\n");
        }

        sb.append("  \"tasks\": [");

        if (taskList.isEmpty()) {
            sb.append("]\n}");
            return sb.toString();
        }

        sb.append('\n');
        for (int i = 0; i < taskList.size(); i++) {
            TaskInfo task = taskList.get(i);
            sb.append("    {\n");
            sb.append("      \"task_id\": \"").append(escapeJson(task.id())).append("\",\n");
            sb.append("      \"description\": \"").append(escapeJson(task.description())).append("\",\n");
            sb.append("      \"status\": \"").append(task.status().name()).append("\",\n");

            if (task.result() != null) {
                sb.append("      \"result\": \"").append(escapeJson(task.result())).append("\",\n");
            } else {
                sb.append("      \"result\": null,\n");
            }

            sb.append("      \"created_at\": \"").append(task.createdAt()).append("\",\n");
            sb.append("      \"updated_at\": \"").append(task.updatedAt()).append("\"");

            // Outputmetadati
            if (task.metadata() != null && !task.metadata().isEmpty()) {
                sb.append(",\n      \"metadata\": {");
                boolean first = true;
                for (Map.Entry<String, String> entry : task.metadata().entrySet()) {
                    if (!first) sb.append(",");
                    sb.append("\n        \"").append(escapeJson(entry.getKey()))
                            .append("\": \"").append(escapeJson(entry.getValue())).append("\"");
                    first = false;
                }
                sb.append("\n      }");
            }

            sb.append("\n    }");
            if (i < taskList.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }

        sb.append("  ]\n}");
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
