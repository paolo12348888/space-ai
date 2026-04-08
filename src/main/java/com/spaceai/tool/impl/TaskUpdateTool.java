package com.spaceai.tool.impl;

import com.spaceai.core.TaskManager;
import com.spaceai.core.TaskManager.TaskInfo;
import com.spaceai.core.TaskManager.TaskStatus;
import com.spaceai.tool.Tool;
import com.spaceai.tool.ToolContext;

import java.util.Map;
import java.util.Optional;

/**
 * TaskUpdate Strumento —— Aggiornamentospecificatostato e risultato dell'attività.
 * <p>
 * Corrisponde a space-ai comando TaskUpdate in. Utilizzato per gestire manualmente le transizioni di stato delle attività,
 * ad esempio da PENDING → RUNNING → COMPLETED.
 * </p>
 *
 * <h3>Parametri</h3>
 * <ul>
 *   <li><b>task_id</b>（obbligatorio)—— AggiornamentoID attività</li>
 *   <li><b>status</b>（obbligatorio)—— nuovoStato：PENDING / RUNNING / COMPLETED / FAILED / CANCELLED</li>
 *   <li><b>result</b>（Opzionale）—— risultato di esecuzione dell'attività o informazioni aggiuntive</li>
 * </ul>
 *
 * <h3>ritorna</h3>
 * <p>JSON formatoAggiornamentoconferma，packageAggiornamentodopoattivitàInformazione。</p>
 *
 * <h3>Stato</h3>
 * <p>giàin（COMPLETED / FAILED / CANCELLED）attivitàNon consentitoAggiornamento。</p>
 */
public class TaskUpdateTool implements Tool {

    /** ToolContext chiave di archiviazione di TaskManager in */
    private static final String TASK_MANAGER_KEY = "TASK_MANAGER";

    @Override
    public String name() {
        return "TaskUpdate";
    }

    @Override
    public String description() {
        return "Update a task's status and optional result";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "task_id": {
                      "type": "string",
                      "description": "Task ID to update"
                    },
                    "status": {
                      "type": "string",
                      "description": "New status: PENDING / RUNNING / COMPLETED / FAILED / CANCELLED",
                      "enum": ["PENDING", "RUNNING", "COMPLETED", "FAILED", "CANCELLED"]
                    },
                    "result": {
                      "type": "string",
                      "description": "Task execution result or additional info (optional)"
                    }
                  },
                  "required": ["task_id", "status"]
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

        // analisiobbligatorioParametri: task_id
        String taskId = (String) input.get("task_id");
        if (taskId == null || taskId.isBlank()) {
            return errorJson("Parameter 'task_id' is required and cannot be empty");
        }

        // analisiobbligatorioParametri: status
        String statusStr = (String) input.get("status");
        if (statusStr == null || statusStr.isBlank()) {
            return errorJson("Parameter 'status' is required and cannot be empty");
        }

        TaskStatus newStatus;
        try {
            newStatus = TaskStatus.valueOf(statusStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return errorJson("Invalid status value: '" + statusStr
                    + "'. Valid values: PENDING, RUNNING, COMPLETED, FAILED, CANCELLED");
        }

        // analisiOpzionaleParametri: result
        String result = (String) input.get("result");

        // inAggiornamentoprimaottienivecchioStato（inritornaInformazione）
        Optional<TaskInfo> beforeOpt = manager.getTask(taskId);
        if (beforeOpt.isEmpty()) {
            return errorJson("Task with ID '" + taskId + "' not found");
        }

        TaskInfo before = beforeOpt.get();
        String oldStatus = before.status().name();

        // esegueAggiornamento
        boolean success = manager.updateTask(taskId, newStatus, result);
        if (!success) {
            return errorJson("Update failed: task '" + taskId + "' current status is "
                    + oldStatus + ", may be in terminal state and cannot be updated");
        }

        // ottieniAggiornamentodopoattivitàInformazione
        Optional<TaskInfo> afterOpt = manager.getTask(taskId);
        if (afterOpt.isEmpty()) {
            // sopranon，
            return errorJson("Failed to get task info after update");
        }

        TaskInfo after = afterOpt.get();

        // ritornaAggiornamentoconferma JSON
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"success\": true,\n");
        sb.append("  \"task_id\": \"").append(escapeJson(after.id())).append("\",\n");
        sb.append("  \"previous_status\": \"").append(oldStatus).append("\",\n");
        sb.append("  \"current_status\": \"").append(after.status().name()).append("\",\n");

        if (after.result() != null) {
            sb.append("  \"result\": \"").append(escapeJson(after.result())).append("\",\n");
        } else {
            sb.append("  \"result\": null,\n");
        }

        sb.append("  \"updated_at\": \"").append(after.updatedAt()).append("\",\n");
        sb.append("  \"message\": \"Task status updated from ").append(oldStatus)
                .append(" to ").append(after.status().name()).append("\"\n");
        sb.append("}");

        return sb.toString();
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        String taskId = (String) input.getOrDefault("task_id", "unknown");
        String status = (String) input.getOrDefault("status", "?");
        return "✏️ Updating task " + taskId + " → " + status;
    }

    /* ------------------------------------------------------------------ */
    /*  ausiliariometodo                                                           */
    /* ------------------------------------------------------------------ */

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
