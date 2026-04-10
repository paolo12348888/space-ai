package com.spaceai.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.spaceai.core.AgentLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * REST Controller per SPACE AI Web API.
 * Gestisce chiamate dirette HTTP per compatibilità con OpenRouter,
 * DeepSeek e qualsiasi provider OpenAI-compatible.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentLoop agentLoop;
    private final RestTemplate restTemplate = new RestTemplate();

    public ChatController(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
    }

    /**
     * POST /api/chat
     * Usa chiamata HTTP diretta per supportare tutti i provider
     * incluso OpenRouter che richiede header speciali.
     */
    @PostMapping(value = "/chat",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> body) {
        String userMessage = body.getOrDefault("message", "").trim();
        if (userMessage.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Il campo 'message' è obbligatorio"));
        }

        String baseUrl = System.getenv().getOrDefault("AI_BASE_URL", "https://api.deepseek.com");
        String apiKey  = System.getenv().getOrDefault("AI_API_KEY", "");
        String model   = System.getenv().getOrDefault("AI_MODEL", "deepseek-chat");

        try {
            // Costruisce il body della richiesta OpenAI-compatible
            ObjectNode requestBody = MAPPER.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("max_tokens", 2048);
            requestBody.put("temperature", 0.7);

            ArrayNode messages = MAPPER.createArrayNode();
            ObjectNode systemMsg = MAPPER.createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("content", "Sei SPACE AI, un assistente esperto di programmazione e tecnologia. Rispondi sempre in italiano in modo chiaro e preciso.");
            messages.add(systemMsg);

            ObjectNode userMsg = MAPPER.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", userMessage);
            messages.add(userMsg);
            requestBody.set("messages", messages);

            // Header HTTP — include quelli richiesti da OpenRouter
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);
            headers.set("HTTP-Referer", "https://space-ai-940e.onrender.com");
            headers.set("X-Title", "SPACE AI");

            String endpoint = baseUrl.endsWith("/") ? baseUrl + "chat/completions"
                                                    : baseUrl + "/chat/completions";

            HttpEntity<String> request = new HttpEntity<>(
                MAPPER.writeValueAsString(requestBody), headers
            );

            log.info("Chiamata AI → {} | modello: {}", endpoint, model);

            ResponseEntity<String> response = restTemplate.postForEntity(
                endpoint, request, String.class
            );

            JsonNode json = MAPPER.readTree(response.getBody());
            String aiResponse = json
                .path("choices").get(0)
                .path("message").path("content").asText();

            return ResponseEntity.ok(Map.of(
                "response", aiResponse,
                "status", "ok",
                "model", model
            ));

        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.error("Errore chiamata AI: {}", errMsg);
            return ResponseEntity.status(502)
                    .body(Map.of(
                        "error", "Errore AI: " + errMsg,
                        "provider", baseUrl,
                        "model", model
                    ));
        }
    }

    /**
     * GET /api/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status",   "online",
                "service",  "SPACE AI",
                "provider", System.getenv().getOrDefault("SPACE_AI_PROVIDER", "openai"),
                "model",    System.getenv().getOrDefault("AI_MODEL", "deepseek-chat"),
                "baseUrl",  System.getenv().getOrDefault("AI_BASE_URL", "https://api.deepseek.com")
        ));
    }
}
