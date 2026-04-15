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

/**
 * SPACE AI - Sistema Multi-Agente
 *
 * Agenti specializzati:
 * 1. ROUTER    - analizza la query e instrada agli agenti giusti
 * 2. RESEARCH  - cerca e sintetizza informazioni aggiornate
 * 3. CODE      - genera e analizza codice
 * 4. FINANCE   - analisi finanziaria e trading
 * 5. REASONER  - ragionamento profondo e problem solving
 * 6. SYNTHESIZER - combina i risultati in risposta finale
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AgentLoop agentLoop;
    private final RestTemplate restTemplate = new RestTemplate();

    // ── AGENTI SPECIALIZZATI ─────────────────────────────────────

    private static final String AGENT_ROUTER =
        "Sei il ROUTER di SPACE AI. Il tuo unico compito e analizzare la query dell utente " +
        "e decidere quali agenti specializzati attivare. " +
        "Rispondi SOLO con un JSON nel formato: " +
        "{\"agents\": [\"code\", \"finance\", \"research\", \"reasoner\"], \"complexity\": \"low|medium|high\", \"summary\": \"breve descrizione query\"} " +
        "Agenti disponibili: code (per codice/programmazione), finance (per finanza/trading/investimenti), " +
        "research (per fatti recenti/notizie/dati aggiornati), reasoner (per logica/matematica/analisi complessa). " +
        "Scegli solo gli agenti necessari. Per domande semplici basta 1 agente.";

    private static final String AGENT_CODE =
        "Sei l agente CODE di SPACE AI, esperto assoluto di programmazione. " +
        "Data: %s. " +
        "Specializzazioni: Python, Java, JavaScript, TypeScript, SQL, Bash, algoritmi, design patterns, debugging, ottimizzazione. " +
        "Scrivi codice COMPLETO e FUNZIONANTE. Usa sempre i blocchi ```linguaggio. " +
        "Spiega ogni parte importante. Anticipa possibili errori. " +
        "Rispondi in italiano.";

    private static final String AGENT_FINANCE =
        "Sei l agente FINANCE di SPACE AI, esperto di mercati finanziari. " +
        "Data: %s. " +
        "Specializzazioni: analisi tecnica (RSI, MACD, Bande di Bollinger, Fibonacci, candele giapponesi), " +
        "analisi fondamentale (P/E, DCF, bilanci, ROE, EV/EBITDA), " +
        "gestione del rischio (position sizing, stop loss, drawdown), " +
        "trading algoritmico (backtesting, strategie, Sharpe Ratio), " +
        "crypto, forex, ETF, obbligazioni, immobiliare. " +
        "Dai consigli pratici con esempi numerici. Rispondi in italiano.";

    private static final String AGENT_RESEARCH =
        "Sei l agente RESEARCH di SPACE AI, specializzato in ricerca e sintesi di informazioni. " +
        "Data attuale: %s. " +
        "Il tuo compito: fornire informazioni accurate, aggiornate e verificate. " +
        "Se una domanda riguarda eventi dopo gennaio 2024, segnala chiaramente che le tue informazioni " +
        "potrebbero non essere aggiornate e suggerisci di verificare su fonti recenti. " +
        "Struttura le risposte con fatti, dati e fonti quando possibile. " +
        "Rispondi in italiano.";

    private static final String AGENT_REASONER =
        "Sei l agente REASONER di SPACE AI, specializzato nel ragionamento profondo. " +
        "Data: %s. " +
        "Approccio: pensa step-by-step, analizza tutti i lati del problema, " +
        "identifica assunzioni implicite, valuta pro e contro, " +
        "fornisci ragionamenti rigorosi per matematica, logica, filosofia, scienza. " +
        "Mostra il percorso di ragionamento, non solo la conclusione. " +
        "Rispondi in italiano.";

    private static final String AGENT_SYNTHESIZER =
        "Sei il SYNTHESIZER di SPACE AI. Ricevi l output di uno o piu agenti specializzati " +
        "e il tuo compito e combinare tutto in una risposta finale COERENTE, COMPLETA e BEN FORMATTATA. " +
        "Data: %s. " +
        "Regole: " +
        "1. Elimina ridondanze e ripetizioni " +
        "2. Mantieni tutti i dettagli importanti " +
        "3. Usa markdown per formattare (**grassetto**, liste, blocchi codice) " +
        "4. La risposta deve sembrare scritta da un singolo esperto, non da piu agenti " +
        "5. Rispondi SEMPRE in italiano " +
        "6. Sii completo ma conciso";

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
                    .body(Map.of("error", "Messaggio vuoto"));
        }

        String baseUrl     = System.getenv().getOrDefault("AI_BASE_URL", "https://api.groq.com/openai/v1");
        String apiKey      = System.getenv().getOrDefault("AI_API_KEY", "");
        String model       = System.getenv().getOrDefault("AI_MODEL", "llama-3.3-70b-versatile");
        String supabaseUrl = System.getenv().getOrDefault("SUPABASE_URL", "");
        String supabaseKey = System.getenv().getOrDefault("SUPABASE_KEY", "");

        String today = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("EEEE dd MMMM yyyy HH:mm", Locale.ITALIAN));

        try {
            // 1. Carica storico
            List<Map<String, String>> history = new ArrayList<>();
            if (!supabaseUrl.isEmpty() && !supabaseKey.isEmpty()) {
                history = loadHistory(sessionId, supabaseUrl, supabaseKey);
            }

            // 2. FASE 1: Router analizza la query
            log.info("ROUTER analizza: {}", userMessage.substring(0, Math.min(50, userMessage.length())));
            RouterResult routing = routeQuery(userMessage, baseUrl, apiKey, model);
            log.info("Agenti attivati: {} | Complessita: {}", routing.agents, routing.complexity);

            // 3. FASE 2: Agenti specializzati lavorano in parallelo (sequenziale per ora)
            List<String> agentOutputs = new ArrayList<>();

            for (String agent : routing.agents) {
                log.info("Agente {} elabora...", agent.toUpperCase());
                String agentPrompt = getAgentPrompt(agent, today);
                String agentOutput = callAgent(agentPrompt, userMessage, history, baseUrl, apiKey, model);
                if (agentOutput != null && !agentOutput.isEmpty()) {
                    agentOutputs.add("[Agente " + agent.toUpperCase() + "]:\n" + agentOutput);
                }
            }

            // 4. FASE 3: Synthesizer combina i risultati
            String finalResponse;
            if (agentOutputs.size() == 1) {
                // Un solo agente - usa direttamente l output
                finalResponse = agentOutputs.get(0)
                        .replaceFirst("\[Agente [A-Z]+\]:\n", "");
            } else {
                // Piu agenti - sintetizza
                log.info("SYNTHESIZER combina {} output...", agentOutputs.size());
                String combinedOutputs = String.join("\n\n---\n\n", agentOutputs);
                finalResponse = synthesize(combinedOutputs, userMessage, today, baseUrl, apiKey, model);
            }

            // 5. Salva in Supabase
            if (!supabaseUrl.isEmpty() && !supabaseKey.isEmpty()) {
                try {
                    saveMessage(sessionId, "user",      userMessage,   supabaseUrl, supabaseKey);
                    saveMessage(sessionId, "assistant", finalResponse, supabaseUrl, supabaseKey);
                } catch (Exception e) {
                    log.warn("Supabase save failed: {}", e.getMessage());
                }
            }

            return ResponseEntity.ok(Map.of(
                    "response",  finalResponse,
                    "status",    "ok",
                    "model",     model,
                    "agents",    routing.agents,
                    "complexity", routing.complexity,
                    "sessionId", sessionId
            ));

        } catch (Exception e) {
            log.error("Errore sistema multi-agente: {}", e.getMessage());
            // Fallback: chiamata diretta senza agenti
            try {
                String fallback = directCall(userMessage, history(sessionId, supabaseUrl, supabaseKey, ""),
                        today, baseUrl, apiKey, model);
                return ResponseEntity.ok(Map.of(
                        "response",  fallback,
                        "status",    "ok_fallback",
                        "model",     model,
                        "sessionId", sessionId
                ));
            } catch (Exception e2) {
                return ResponseEntity.status(502).body(Map.of(
                        "error", "Errore AI: " + e.getMessage()
                ));
            }
        }
    }

    // ── ROUTER ────────────────────────────────────────────────────
    private RouterResult routeQuery(String query, String baseUrl, String apiKey, String model) {
        try {
            String response = callLLM(AGENT_ROUTER,
                    "Analizza questa query e rispondi SOLO con JSON: " + query,
                    baseUrl, apiKey, model, 200);

            // Estrai JSON dalla risposta
            int start = response.indexOf("{");
            int end   = response.lastIndexOf("}") + 1;
            if (start >= 0 && end > start) {
                JsonNode json = MAPPER.readTree(response.substring(start, end));
                List<String> agents = new ArrayList<>();
                json.path("agents").forEach(n -> agents.add(n.asText()));
                if (agents.isEmpty()) agents.add("reasoner");
                return new RouterResult(agents, json.path("complexity").asText("medium"));
            }
        } catch (Exception e) {
            log.warn("Router fallback: {}", e.getMessage());
        }
        // Fallback: usa reasoner
        return new RouterResult(List.of("reasoner"), "medium");
    }

    private String getAgentPrompt(String agent, String today) {
        return switch (agent.toLowerCase()) {
            case "code"     -> String.format(AGENT_CODE,     today);
            case "finance"  -> String.format(AGENT_FINANCE,  today);
            case "research" -> String.format(AGENT_RESEARCH, today);
            default         -> String.format(AGENT_REASONER, today);
        };
    }

    private String callAgent(String systemPrompt, String userMessage,
                              List<Map<String, String>> history,
                              String baseUrl, String apiKey, String model) {
        try {
            ObjectNode req = MAPPER.createObjectNode();
            req.put("model", model);
            req.put("max_tokens", 2048);
            req.put("temperature", 0.7);

            ArrayNode messages = MAPPER.createArrayNode();
            ObjectNode sys = MAPPER.createObjectNode();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            messages.add(sys);

            // Ultimi 10 messaggi di storico
            int start = Math.max(0, history.size() - 10);
            for (int i = start; i < history.size(); i++) {
                ObjectNode m = MAPPER.createObjectNode();
                m.put("role",    history.get(i).get("role"));
                m.put("content", history.get(i).get("content"));
                messages.add(m);
            }

            ObjectNode usr = MAPPER.createObjectNode();
            usr.put("role", "user");
            usr.put("content", userMessage);
            messages.add(usr);
            req.set("messages", messages);

            return callAPI(req, baseUrl, apiKey);
        } catch (Exception e) {
            log.warn("Agent call failed: {}", e.getMessage());
            return null;
        }
    }

    private String synthesize(String combined, String originalQuery,
                               String today, String baseUrl, String apiKey, String model) throws Exception {
        String sysPrompt = String.format(AGENT_SYNTHESIZER, today);
        String userContent = "Domanda originale: " + originalQuery +
                "\n\nOutput degli agenti specializzati:\n\n" + combined +
                "\n\nSintetizza in una risposta finale coerente.";
        return callLLM(sysPrompt, userContent, baseUrl, apiKey, model, 3000);
    }

    private String directCall(String message, List<Map<String, String>> history,
                               String today, String baseUrl, String apiKey, String model) throws Exception {
        String sys = "Sei SPACE AI, assistente avanzato. Data: " + today + ". Rispondi in italiano.";
        return callLLM(sys, message, baseUrl, apiKey, model, 3000);
    }

    private List<Map<String, String>> history(String sessionId, String url, String key, String ignored) {
        if (url.isEmpty()) return new ArrayList<>();
        return loadHistory(sessionId, url, key);
    }

    // ── CHIAMATA API ──────────────────────────────────────────────
    private String callLLM(String systemPrompt, String userMessage,
                            String baseUrl, String apiKey, String model, int maxTokens) throws Exception {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("model", model);
        req.put("max_tokens", maxTokens);
        req.put("temperature", 0.7);

        ArrayNode messages = MAPPER.createArrayNode();
        ObjectNode sys = MAPPER.createObjectNode();
        sys.put("role", "system");
        sys.put("content", systemPrompt);
        messages.add(sys);

        ObjectNode usr = MAPPER.createObjectNode();
        usr.put("role", "user");
        usr.put("content", userMessage);
        messages.add(usr);
        req.set("messages", messages);

        return callAPI(req, baseUrl, apiKey);
    }

    private String callAPI(ObjectNode requestBody, String baseUrl, String apiKey) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (!apiKey.isEmpty()) headers.setBearerAuth(apiKey);
        headers.set("HTTP-Referer", "https://space-ai-940e.onrender.com");
        headers.set("X-Title", "SPACE AI");
        headers.set("ngrok-skip-browser-warning", "true");
        headers.set("User-Agent", "SPACE-AI-Server/1.0");

        String endpoint = baseUrl.endsWith("/") ?
                baseUrl + "chat/completions" : baseUrl + "/chat/completions";

        ResponseEntity<String> response = restTemplate.postForEntity(
                endpoint,
                new HttpEntity<>(MAPPER.writeValueAsString(requestBody), headers),
                String.class
        );

        JsonNode json = MAPPER.readTree(response.getBody());
        return json.path("choices").get(0).path("message").path("content").asText();
    }

    // ── SUPABASE ─────────────────────────────────────────────────
    private List<Map<String, String>> loadHistory(String sessionId,
                                                    String supabaseUrl, String supabaseKey) {
        List<Map<String, String>> history = new ArrayList<>();
        try {
            String url = supabaseUrl + "/rest/v1/messages"
                    + "?session_id=eq." + sessionId
                    + "&order=created_at.asc&limit=30&select=role,content";
            HttpHeaders h = new HttpHeaders();
            h.set("apikey", supabaseKey);
            h.set("Authorization", "Bearer " + supabaseKey);
            ResponseEntity<String> r = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(h), String.class);
            JsonNode arr = MAPPER.readTree(r.getBody());
            for (JsonNode n : arr) {
                history.add(Map.of("role", n.path("role").asText(),
                        "content", n.path("content").asText()));
            }
        } catch (Exception e) {
            log.warn("Supabase load: {}", e.getMessage());
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
        b.put("role", role);
        b.put("content", content);
        restTemplate.postForEntity(supabaseUrl + "/rest/v1/messages",
                new HttpEntity<>(MAPPER.writeValueAsString(b), h), String.class);
    }

    // ── HEALTH ────────────────────────────────────────────────────
    @GetMapping("/history/{sessionId}")
    public ResponseEntity<Object> getHistory(@PathVariable String sessionId) {
        String url = System.getenv().getOrDefault("SUPABASE_URL", "");
        String key = System.getenv().getOrDefault("SUPABASE_KEY", "");
        if (url.isEmpty()) return ResponseEntity.ok(Map.of("messages", List.of()));
        return ResponseEntity.ok(Map.of("messages", loadHistory(sessionId, url, key)));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        return ResponseEntity.ok(Map.of(
                "status",   "online",
                "service",  "SPACE AI Multi-Agent",
                "model",    System.getenv().getOrDefault("AI_MODEL", "llama-3.3-70b-versatile"),
                "agents",   "router,code,finance,research,reasoner,synthesizer",
                "supabase", System.getenv().getOrDefault("SUPABASE_URL","").isEmpty() ? "off" : "on",
                "date",     today
        ));
    }

    // ── RECORD INTERNI ────────────────────────────────────────────
    private record RouterResult(List<String> agents, String complexity) {}
}
