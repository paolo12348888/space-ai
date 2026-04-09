package com.spaceai.web;

import com.spaceai.core.AgentLoop;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST Controller per SPACE AI Web API.
 * Espone /api/chat e /api/health per uso web e da client esterni.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatController {

    private final AgentLoop agentLoop;

    public ChatController(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
    }

    /**
     * POST /api/chat
     * Body: { "message": "testo utente" }
     * Response: { "response": "risposta AI", "model": "...", "provider": "..." }
     */
    @PostMapping(value = "/chat",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> chat(@RequestBody Map<String, String> body) {
        String userMessage = body.getOrDefault("message", "").trim();
        if (userMessage.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Il campo 'message' è obbligatorio"));
        }

        try {
            String aiResponse = agentLoop.run(userMessage);
            return ResponseEntity.ok(Map.of(
                    "response", aiResponse,
                    "status", "ok"
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Errore AI: " + e.getMessage()));
        }
    }

    /**
     * GET /api/health
     * Restituisce lo stato del servizio.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        String provider = System.getenv().getOrDefault("SPACE_AI_PROVIDER", "openai");
        String model    = System.getenv().getOrDefault("AI_MODEL", "deepseek-chat");
        String baseUrl  = System.getenv().getOrDefault("AI_BASE_URL", "https://api.deepseek.com");
        return ResponseEntity.ok(Map.of(
                "status",   "online",
                "service",  "SPACE AI",
                "provider", provider,
                "model",    model,
                "baseUrl",  baseUrl
        ));
    }
}
