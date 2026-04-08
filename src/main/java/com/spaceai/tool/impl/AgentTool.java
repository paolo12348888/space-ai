package com.spaceai.tool.impl;

import com.spaceai.tool.Tool;
import com.spaceai.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 *  Agent Strumento —— Corrisponde a space-ai/src/tools/agent/AgentTool.ts。
 * <p>
 * creaun Agent Gestisceattività。 Agent c'ècronologia messaggi，
 * ma condividono l'insieme di strumenti e l'ambiente di contesto. Adatto per:
 * <ul>
 *   <li>richiedecontestoattività（analisiunFile）</li>
 *   <li>eGestiscepiùattività</li>
 *   <li>operazione</li>
 * </ul>
 * <p>
 * Attenzione： Agent  Agent  ChatModel eStrumento，
 * tramite ToolContext in "agentLoop.factory" Ottieni AgentLoop Metodo factory。
 */
public class AgentTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(AgentTool.class);

    /** ToolContext nome chiave per archiviare la factory AgentLoop in */
    public static final String AGENT_FACTORY_KEY = "__agent_factory__";

    @Override
    public String name() {
        return "Agent";
    }

    @Override
    public String description() {
        return """
            Launch a sub-agent to handle a complex task independently. \
            The sub-agent has its own conversation context but shares tools \
            and environment. Use this for tasks that require focused attention \
            or when you want to isolate a subtask. \
            The sub-agent will execute the given prompt and return its final response.""";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "prompt": {
                  "type": "string",
                  "description": "The task description / prompt for the sub-agent"
                },
                "context": {
                  "type": "string",
                  "description": "Additional context or instructions (optional)"
                }
              },
              "required": ["prompt"]
            }""";
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String prompt = (String) input.get("prompt");
        String additionalContext = (String) input.getOrDefault("context", "");

        if (prompt == null || prompt.isBlank()) {
            return "Error: 'prompt' is required";
        }

        // ottieni il metodo factory AgentLoop da ToolContext
        @SuppressWarnings("unchecked")
        java.util.function.Function<String, String> agentFactory =
                context.getOrDefault(AGENT_FACTORY_KEY, null);

        if (agentFactory == null) {
            log.warn("AgentTool: Agent factory not configured, cannot create sub-agent");
            return "Error: Sub-agent capability is not configured. "
                   + "The Agent tool requires an agent factory to be registered in the ToolContext.";
        }

        // Costruisce il completo Agent suggerimento
        String fullPrompt = buildSubAgentPrompt(prompt, additionalContext);

        log.info("Starting sub-agent, task: {}", truncate(prompt, 80));

        try {
            String result = agentFactory.apply(fullPrompt);
            log.info("Sub-agent completed, result length: {} chars", result.length());
            return result;
        } catch (Exception e) {
            log.debug("Sub-agent execution failed", e);
            return "Error: Sub-agent failed: " + e.getMessage();
        }
    }

    /**
     * Build Agent completoprompt
     */
    private String buildSubAgentPrompt(String prompt, String additionalContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a sub-agent tasked with a specific job. ");
        sb.append("Complete the following task thoroughly and return your findings/results:\n\n");
        sb.append("## Task\n");
        sb.append(prompt);

        if (additionalContext != null && !additionalContext.isBlank()) {
            sb.append("\n\n## Additional Context\n");
            sb.append(additionalContext);
        }

        sb.append("\n\n## Instructions\n");
        sb.append("- Focus only on the given task\n");
        sb.append("- Use available tools as needed\n");
        sb.append("- Provide a clear, concise result\n");
        sb.append("- If the task cannot be completed, explain why\n");

        return sb.toString();
    }

    private String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        String prompt = (String) input.getOrDefault("prompt", "");
        if (prompt.length() > 40) {
            prompt = prompt.substring(0, 37) + "...";
        }
        return "🤖 Sub-agent: " + prompt;
    }
}
