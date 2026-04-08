package com.spaceai.tool.impl;

import com.spaceai.tool.Tool;
import com.spaceai.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Function;

/**
 * UtenteStrumento —— AI inesegueinUtenteeottieni。
 * <p>
 * Corrisponde a space-ai  AskUserQuestionTool，consente AI inrichiedeInformazione
 * pausaesegueeUtente。UtentecomeStrumentoValore di ritorno AI。
 * <p>
 * Dipendenza ToolContext funzione callback {@code USER_INPUT_CALLBACK} registrata in
 * questoCallbackda ReplSession inavvioImpostazioni，inleggeterminaleUtenteInput。
 */
public class AskUserQuestionTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(AskUserQuestionTool.class);

    /** ToolContext chiave Callback per leggere l'input utente in */
    public static final String USER_INPUT_CALLBACK = "ask_user_input_callback";

    @Override
    public String name() {
        return "AskUserQuestion";
    }

    @Override
    public String description() {
        return "Ask the user a question and wait for their response. Use this when you need clarification, " +
                "confirmation, or additional information from the user to proceed with a task. " +
                "The question should be clear, specific, and actionable.";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "question": {
                      "type": "string",
                      "description": "The question to ask the user. Should be clear and specific."
                    },
                    "options": {
                      "type": "array",
                      "items": { "type": "string" },
                      "description": "Optional list of choices for the user to pick from"
                    }
                  },
                  "required": ["question"]
                }
                """;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "Asking user a question...";
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(Map<String, Object> input, ToolContext context) {
        String question = (String) input.get("question");
        if (question == null || question.isBlank()) {
            return "Error: question parameter is required";
        }

        // ottieniUtenteInputCallback
        Object callback = context.get(USER_INPUT_CALLBACK);
        if (callback == null) {
            log.warn("User input callback not registered (USER_INPUT_CALLBACK), returning default response");
            return "Error: User input not available in current environment";
        }

        if (!(callback instanceof Function<?, ?> inputFn)) {
            return "Error: Invalid user input callback type";
        }

        try {
            Function<String, String> askUser = (Function<String, String>) inputFn;

            // Buildtesto
            StringBuilder prompt = new StringBuilder();
            prompt.append("\n  🤔 AI is asking you a question:\n");
            prompt.append("  ").append("─".repeat(50)).append("\n");
            prompt.append("  ").append(question).append("\n");

            // Sec'èOpzioni
            if (input.containsKey("options")) {
                var options = (java.util.List<String>) input.get("options");
                if (options != null && !options.isEmpty()) {
                    prompt.append("\n  Options:\n");
                    for (int i = 0; i < options.size(); i++) {
                        prompt.append("    ").append(i + 1).append(". ").append(options.get(i)).append("\n");
                    }
                }
            }

            prompt.append("  ").append("─".repeat(50)).append("\n");

            // chiamataCallbackottieniUtenteInput
            String userResponse = askUser.apply(prompt.toString());

            if (userResponse == null || userResponse.isBlank()) {
                return "(User provided no response)";
            }

            log.debug("User response: {}", userResponse);
            return "User response: " + userResponse;

        } catch (Exception e) {
            log.debug("Failed to get user input", e);
            return "Error: Failed to get user input - " + e.getMessage();
        }
    }
}
