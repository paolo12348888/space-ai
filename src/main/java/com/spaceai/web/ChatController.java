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

    // Sistema neurale ispirato a Claude: curiosita, onesta, precisione, utilita
    private static final String SYSTEM_PROMPT =
        "Sei SPACE AI, un assistente AI avanzato creato da Paolo. " +
        "Data e ora attuale: %s. " +
        "\n\n" +
        "## La tua identita\n" +
        "Sei un assistente intelligente, curioso e preciso. " +
        "Hai una personalita autentica: sei diretto, onesto e ti importa davvero aiutare. " +
        "Non sei un semplice chatbot - sei un partner intellettuale. " +
        "\n\n" +
        "## Le tue capacita complete\n" +
        "- **Programmazione**: Python, Java, JavaScript, TypeScript, SQL, Bash, e molto altro. Scrivi codice funzionante, debug, spiega algoritmi.\n" +
        "- **Finanza e Trading**: analisi tecnica (RSI, MACD, Bande di Bollinger), analisi fondamentale (P/E, DCF, bilanci), strategie di trading, gestione del rischio, crypto, forex.\n" +
        "- **Ragionamento avanzato**: problem solving complesso, logica, matematica, statistica, data science.\n" +
        "- **Analisi documenti**: leggi e analizza file allegati, CSV, codice, testi.\n" +
        "- **Scrittura**: testi, email, report, documentazione tecnica.\n" +
        "- **Conoscenza generale**: scienza, storia, tecnologia, arte - rispondi con profondita.\n" +
        "\n\n" +
        "## Come ragioni (ispirato ai migliori modelli)\n" +
        "1. **Comprendi prima di rispondere**: analizza cosa vuole davvero l utente\n" +
        "2. **Pensa step-by-step**: per problemi complessi, mostra il ragionamento\n" +
        "3. **Sii preciso**: dai risposte accurate, cita i limiti quando esistono\n" +
        "4. **Sii utile**: non solo rispondi alla domanda, anticipa i bisogni successivi\n" +
        "5. **Sii onesto**: se non sai qualcosa, dillo. Non inventare.\n" +
        "\n\n" +
        "## Regole di risposta\n" +
        "- Rispondi SEMPRE in italiano a meno che l utente non scriva in un altra lingua\n" +
        "- Usa markdown per formattare (grassetto, liste, codice)\n" +
        "- Per il codice usa sempre i backtick con il linguaggio specificato\n" +
        "- Risposte complete e dettagliate, non troncare mai\n" +
        "- La data attuale e %s - hai conoscenza fino a inizio 2024, per eventi dopo segnalalo\n";

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
                    .body(Map.of("error", "Il campo message e obbligatorio"));
        }

        String baseUrl     = System.getenv().getOrDefault("AI_BASE_URL", "https://api.groq.com/openai/v1");
        String apiKey      = System.getenv().getOrDefault("AI_API_KEY", "");
        String model       = System.getenv().getOrDefault("AI_MODEL", "llama-3.3-70b-versatile");
        String supabaseUrl = System.getenv().getOrDefault("SUPABASE_URL", "");
        String supabaseKey = System.getenv().getOrDefault("SUPABASE_KEY", "");

        try {
            // 1. Carica storico (con fallback sicuro)
            List<Map<String, String>> history = new ArrayList<>();
            if (!supabaseUrl.isEmpty() && !supabaseKey.isEmpty()) {
                history = loadHistory(sessionId, supabaseUrl, supabaseKey);
            }

            // 2. Costruisci prompt
            String today = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("EEEE dd MMMM yyyy HH:mm", Locale.ITALIAN));

            ObjectNode requestBody = MAPPER.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("max_tokens", 4096);
            requestBody.put("temperature", 0.7);

            ArrayNode messages = MAPPER.createArrayNode();

            // System message
            ObjectNode sysMsg = MAPPER.createObjectNode();
            sysMsg.put("role", "system");
            sysMsg.put("content", String.format(SYSTEM_PROMPT, today, today));
            messages.add(sysMsg);

            // Storico ultimi 20 messaggi
            int start = Math.max(0, history.size() - 20);
            for (int i = start; i < history.size(); i++) {
                ObjectNode hMsg = MAPPER.createObjectNode();
                hMsg.put("role",    history.get(i).get("role"));
                hMsg.put("content", history.get(i).get("content"));
                messages.add(hMsg);
            }

            // Messaggio utente
            ObjectNode uMsg = MAPPER.createObjectNode();
            uMsg.put("role", "user");
            uMsg.put("content", userMessage);
            messages.add(uMsg);
            requestBody.set("messages", messages);

            // 3. Headers
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

            log.info("AI call -> {} | model: {} | history: {} msg", endpoint, model, history.size());

            ResponseEntity<String> response = restTemplate.postForEntity(
                    endpoint,
                    new HttpEntity<>(MAPPER.writeValueAsString(requestBody), headers),
                    String.class
            );

            JsonNode json = MAPPER.readTree(response.getBody());
            String aiResponse = json.path("choices").get(0)
                    .path("message").path("content").asText();

            // 4. Salva in Supabase (non blocca la risposta se fallisce)
            if (!supabaseUrl.isEmpty() && !supabaseKey.isEmpty()) {
                try {
                    saveMessage(sessionId, "user",      userMessage, supabaseUrl, supabaseKey);
                    saveMessage(sessionId, "assistant", aiResponse,  supabaseUrl, supabaseKey);
                } catch (Exception e) {
                    log.warn("Supabase save failed (non-blocking): {}", e.getMessage());
                }
            }

            return ResponseEntity.ok(Map.of(
                    "response",  aiResponse,
                    "status",    "ok",
                    "model",     model,
                    "sessionId", sessionId,
                    "history",   history.size()
            ));

        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.error("Errore AI: {}", errMsg);
            return ResponseEntity.status(502)
                    .body(Map.of(
                            "error",    "Errore AI: " + errMsg,
                            "provider", baseUrl,
                            "model",    model
                    ));
        }
    }

    private List<Map<String, String>> loadHistory(String sessionId,
                                                    String supabaseUrl,
                                                    String supabaseKey) {
        List<Map<String, String>> history = new ArrayList<>();
        try {
            String url = supabaseUrl + "/rest/v1/messages"
                    + "?session_id=eq." + sessionId
                    + "&order=created_at.asc"
                    + "&limit=40"
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
        } catch (Exception e) {
            log.warn("Supabase load failed: {}", e.getMessage());
        }
        return history;
    }

    private void saveMessage(String sessionId, String role, String content,
                              String supabaseUrl, String supabaseKey) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("apikey", supabaseKey);
        h.set("Authorization", "Bearer " + supabaseKey);
        h.set("Prefer", "return=minimal");

        ObjectNode b = MAPPER.createObjectNode();
        b.put("session_id", sessionId);
        b.put("role",       role);
        b.put("content",    content);

        restTemplate.postForEntity(
                supabaseUrl + "/rest/v1/messages",
                new HttpEntity<>(MAPPER.writeValueAsString(b), h),
                String.class
        );
    }

    @GetMapping("/history/{sessionId}")
    public ResponseEntity<Object> getHistory(@PathVariable String sessionId) {
        String supabaseUrl = System.getenv().getOrDefault("SUPABASE_URL", "");
        String supabaseKey = System.getenv().getOrDefault("SUPABASE_KEY", "");
        if (supabaseUrl.isEmpty()) {
            return ResponseEntity.ok(Map.of("messages", List.of(), "sessionId", sessionId));
        }
        return ResponseEntity.ok(Map.of(
                "messages",  loadHistory(sessionId, supabaseUrl, supabaseKey),
                "sessionId", sessionId
        ));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        String today = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        boolean supabase = !System.getenv().getOrDefault("SUPABASE_URL", "").isEmpty();
        return ResponseEntity.ok(Map.of(
                "status",   "online",
                "service",  "SPACE AI",
                "model",    System.getenv().getOrDefault("AI_MODEL", "llama-3.3-70b-versatile"),
                "baseUrl",  System.getenv().getOrDefault("AI_BASE_URL", ""),
                "supabase", supabase ? "connected" : "not configured",
                "date",     today
        ));
    }
}
