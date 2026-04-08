package com.spaceai.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

/**
 * Tool → Spring AI ToolCallback adattatore。
 * <p>
 * verràpersonalizzato Tool Protocollocome Spring AI  ToolCallback Interfaccia，
 * inchiamataGestisci JSON analisi、controllo permessieeccezioneCattura。
 * <p>
 * Corrisponde a space-ai-learn AgentToolCallback in
 */
public class ToolCallbackAdapter implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ToolCallbackAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Tool tool;
    private final ToolDefinition toolDefinition;
    private final ToolContext context;

    public ToolCallbackAdapter(Tool tool, ToolContext context) {
        this.tool = tool;
        this.context = context;
        this.toolDefinition = DefaultToolDefinition.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(tool.inputSchema())
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String jsonInput) {
        try {
            Map<String, Object> input = MAPPER.readValue(jsonInput, Map.class);

            // Permessocontrollo preliminare
            PermissionResult perm = tool.checkPermission(input, context);
            if (!perm.allowed()) {
                log.warn("[{}] Permission denied: {}", tool.name(), perm.message());
                return "Permission denied: " + perm.message();
            }

            log.debug("[{}] {}", tool.name(), tool.activityDescription(input));
            return tool.execute(input, context);
        } catch (JsonProcessingException e) {
            log.warn("[{}] JSON parse failed: {}", tool.name(), e.getMessage());
            return "Error: Invalid JSON input: " + e.getMessage();
        } catch (Exception e) {
            log.warn("[{}] Execution exception: {}", tool.name(), e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public Tool getTool() {
        return tool;
    }
}
