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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AgentLoop agentLoop;
    private final RestTemplate restTemplate = new RestTemplate();

    // System prompt avanzato — capacità complete
    private static final String SYSTEM_PROMPT = """
        Sei SPACE AI, un assistente AI avanzato creato da Paolo.
        Data attuale: %s
        
        Le tue capacità:
        - Analisi e scrittura di codice (Python, Java, JavaScript, SQL, ecc.)
        - Finanza, trading, analisi tecnica e fondamentale
        - Ragionamento avanzato e problem solving
        - Analisi di documenti e dati allegati
        - Ricerca e sintesi di informazioni aggiornate
        - Matematica, statistica e data science
        
        Regole:
        - Rispondi SEMPRE in italiano a meno che l'utente non scriva in un'altra lingua
        - Sii preciso, dettagliato e usa esempi pratici
        - Per il codice usa blocchi formattati con il linguaggio
        - Se non sai qualcosa di recente, dillo chiaramente
        - Sei capace di fare analisi approfondite, non solo risposte brevi
        """;

    public ChatController(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
    }

    @PostMapping(value = "/chat",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> body) {
        String userMessage = body.getOrDefault("message", "").trim();
        String sessionId   = body.getOrDefault("sessionId", "default");

        if (userMessage.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Il campo 'message' è obbligatorio"));
        }

        String baseUrl      = System.getenv().getOrDefault("AI_BASE_URL", "https://api.groq.com/openai/v1");
        String apiKey       = System.getenv().getOrDefault("AI_API_KEY", "");
        String model        = System.getenv().getOrDefault("AI_MODEL", "llama-3.3-70b-versatile");
        String supabaseUrl  = System.getenv().getOrDefault("SUPABASE_URL", "");
        String supabaseKey  = System.getenv().getOrDefault("SUPABASE_KEY", "");

        try {
            // 1. Carica storico chat da Supabase
            List<Map<String, String>> history = loadHistory(sessionId, supabaseUrl, supabaseKey);

            // 2. Costruisci i messaggi con system prompt aggiornato
            ObjectNode requestBody = MAPPER.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("max_tokens", 4096);
            requestBody.put("temperature", 0.7);

            ArrayNode messages = MAPPER.createArrayNode();

            // System prompt con data corrente
            String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
            ObjectNode systemMsg = MAPPER.createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("content", String.format(SYSTEM_PROMPT, today));
            messages.add(systemMsg);

            // Aggiungi storico (ultimi 20 messaggi)
            int start = Math.max(0, history.size() - 20);
            for (int i = start; i < history.size(); i++) {
                Map<String, String> msg = history.get(i);
                ObjectNode histMsg = MAPPER.createObjectNode();
                histMsg.put("role", msg.get("role"));
                histMsg.put("content", msg.get("content"));
                messages.add(histMsg);
            }

            // Messaggio utente corrente
            ObjectNode userMsg = MAPPER.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", userMessage);
            messages.add(userMsg);
            requestBody.set("messages", messages);

            // 3. Chiama il modello AI
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (!apiKey.isEmpty()) headers.setBearerAuth(apiKey);
            headers.set("HTTP-Referer", "https://space-ai-940e.onrender.com");
            headers.set("X-Title", "SPACE AI");
            headers.set("ngrok-skip-browser-warning", "true");
            headers.set("User-Agent", "SPACE-AI-Server/1.0");

            String endpoint = baseUrl.endsWith("/") ?
                    baseUrl + "chat/completions" :
                    baseUrl + "/chat/completions";

            HttpEntity<String> request = new HttpEntity<>(
                    MAPPER.writeValueAsString(requestBody), headers);

            log.info("AI call → {} | model: {} | session: {}", endpoint, model, sessionId);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    endpoint, request, String.class);

            JsonNode json = MAPPER.readTree(response.getBody());
            String aiResponse = json.path("choices").get(0)
                    .path("message").path("content").asText();

            // 4. Salva messaggio utente + risposta in Supabase
            saveMessage(sessionId, "user", userMessage, supabaseUrl, supabaseKey);
            saveMessage(sessionId, "assistant", aiResponse, supabaseUrl, supabaseKey);

            return ResponseEntity.ok(Map.of(
                    "response", aiResponse,
                    "status", "ok",
                    "model", model,
                    "sessionId", sessionId
            ));

        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.error("Errore AI: {}", errMsg);
            return ResponseEntity.status(502)
                    .body(Map.of(
                            "error", "Errore AI: " + errMsg,
                            "provider", baseUrl,
                            "model", model
                    ));
        }
    }

    // ── Carica storico da Supabase ──────────────────────────────
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> loadHistory(String sessionId,
                                                   String supabaseUrl,
                                                   String supabaseKey) {
        List<Map<String, String>> history = new ArrayList<>();
        if (supabaseUrl.isEmpty() || supabaseKey.isEmpty()) return history;

        try {
            String url = supabaseUrl + "/rest/v1/messages"
                    + "?session_id=eq." + sessionId
                    + "&order=created_at.asc"
                    + "&limit=50"
                    + "&select=role,content";

            HttpHeaders h = new HttpHeaders();
            h.set("apikey", supabaseKey);
            h.set("Authorization", "Bearer " + supabaseKey);

            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(h), String.class);

            JsonNode arr = MAPPER.readTree(resp.getBody());
            for (JsonNode node : arr) {
                history.add(Map.of(
                        "role",    node.path("role").asText(),
                        "content", node.path("content").asText()
                ));
            }
            log.info("Storico caricato: {} messaggi per sessione {}", history.size(), sessionId);
        } catch (Exception e) {
            log.warn("Errore caricamento storico Supabase: {}", e.getMessage());
        }
        return history;
    }

    // ── Salva messaggio in Supabase ─────────────────────────────
    private void saveMessage(String sessionId, String role,
                              String content, String supabaseUrl, String supabaseKey) {
        if (supabaseUrl.isEmpty() || supabaseKey.isEmpty()) return;
        try {
            String url = supabaseUrl + "/rest/v1/messages";

            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            h.set("apikey", supabaseKey);
            h.set("Authorization", "Bearer " + supabaseKey);
            h.set("Prefer", "return=minimal");

            ObjectNode body = MAPPER.createObjectNode();
            body.put("session_id", sessionId);
            body.put("role", role);
            body.put("content", content);

            restTemplate.postForEntity(url,
                    new HttpEntity<>(MAPPER.writeValueAsString(body), h), String.class);
        } catch (Exception e) {
            log.warn("Errore salvataggio Supabase: {}", e.getMessage());
        }
    }

    // ── Carica storico via API ──────────────────────────────────
    @GetMapping("/history/{sessionId}")
    public ResponseEntity<Object> getHistory(@PathVariable String sessionId) {
        String supabaseUrl = System.getenv().getOrDefault("SUPABASE_URL", "");
        String supabaseKey = System.getenv().getOrDefault("SUPABASE_KEY", "");
        List<Map<String, String>> history = loadHistory(sessionId, supabaseUrl, supabaseKey);
        return ResponseEntity.ok(Map.of("messages", history, "sessionId", sessionId));
    }

    // ── Health check ────────────────────────────────────────────
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        boolean supabaseOk = !System.getenv().getOrDefault("SUPABASE_URL", "").isEmpty();
        return ResponseEntity.ok(Map.of(
                "status",    "online",
                "service",   "SPACE AI",
                "model",     System.getenv().getOrDefault("AI_MODEL", "llama-3.3-70b-versatile"),
                "baseUrl",   System.getenv().getOrDefault("AI_BASE_URL", ""),
                "supabase",  supabaseOk ? "connected" : "not configured",
                "date",      today
        ));
    }
}
