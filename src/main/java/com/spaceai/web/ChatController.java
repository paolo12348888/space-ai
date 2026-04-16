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

    public ChatController(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
    }

    // ── 12 AGENTI SPECIALIZZATI ───────────────────────────────────

    private String getSystemPrompt(String agent, String today) {
        switch (agent) {
            case "router":
                return "Sei il ROUTER di SPACE AI. Analizza la query e rispondi SOLO con JSON valido: "
                     + "{\"agents\": [\"code\"], \"complexity\": \"low\"} "
                     + "Agenti disponibili: code, finance, research, reasoner, math, debug, "
                     + "security, data, writer, translator, planner, summarizer. "
                     + "Scegli max 3 agenti. Per domande semplici usa 1 solo agente.";

            case "code":
                return "Sei l agente CODE di SPACE AI. Data: " + today + ". "
                     + "Esperto di: Python, Java, JavaScript, TypeScript, Go, Rust, SQL, Bash, "
                     + "algoritmi, design patterns, architettura software, API REST, microservizi. "
                     + "Scrivi codice COMPLETO e FUNZIONANTE con blocchi ```linguaggio. "
                     + "Spiega ogni parte. Anticipa errori comuni. Rispondi in italiano.";

            case "finance":
                return "Sei l agente FINANCE di SPACE AI. Data: " + today + ". "
                     + "Esperto di: RSI, MACD, Bande di Bollinger, Fibonacci, candele giapponesi, "
                     + "P/E ratio, DCF, bilanci aziendali, ROE, EV/EBITDA, "
                     + "position sizing, stop loss, drawdown, Sharpe Ratio, "
                     + "backtesting, crypto, forex, ETF, obbligazioni. "
                     + "Dai consigli pratici con esempi numerici. Rispondi in italiano.";

            case "research":
                return "Sei l agente RESEARCH di SPACE AI. Data: " + today + ". "
                     + "Fornisci informazioni accurate e verificate. "
                     + "Per eventi dopo gennaio 2024 segnala che le info potrebbero non essere aggiornate. "
                     + "Struttura con fatti e dati concreti. Rispondi in italiano.";

            case "reasoner":
                return "Sei l agente REASONER di SPACE AI. Data: " + today + ". "
                     + "Specializzato in ragionamento profondo e problem solving complesso. "
                     + "Pensa step-by-step, analizza tutti i lati, identifica assunzioni implicite. "
                     + "Mostra il percorso di ragionamento. Rispondi in italiano.";

            case "math":
                return "Sei l agente MATH di SPACE AI. Data: " + today + ". "
                     + "Esperto di: algebra, calcolo, statistica, probabilita, matematica finanziaria, "
                     + "geometria, trigonometria, analisi numerica, ottimizzazione. "
                     + "Mostra tutti i passaggi. Usa notazione chiara. Rispondi in italiano.";

            case "debug":
                return "Sei l agente DEBUG di SPACE AI. Data: " + today + ". "
                     + "Specializzato nell analisi e correzione di errori nel codice. "
                     + "Identifica il problema, spiega la causa, fornisci la soluzione corretta "
                     + "con codice funzionante. Suggerisci best practices. Rispondi in italiano.";

            case "security":
                return "Sei l agente SECURITY di SPACE AI. Data: " + today + ". "
                     + "Esperto di: cybersecurity, vulnerabilita comuni (OWASP Top 10), "
                     + "crittografia, autenticazione, autorizzazione, sicurezza delle API, "
                     + "protezione dati, GDPR. Solo per scopi difensivi e educativi. Rispondi in italiano.";

            case "data":
                return "Sei l agente DATA di SPACE AI. Data: " + today + ". "
                     + "Esperto di: analisi dati, pandas, numpy, SQL avanzato, "
                     + "machine learning (sklearn, tensorflow, pytorch), "
                     + "visualizzazione dati, ETL, database design. Rispondi in italiano.";

            case "writer":
                return "Sei l agente WRITER di SPACE AI. Data: " + today + ". "
                     + "Esperto di: scrittura tecnica, documentazione, email professionali, "
                     + "report, presentazioni, storytelling, contenuti marketing. "
                     + "Adatta il tono al contesto. Rispondi in italiano.";

            case "translator":
                return "Sei l agente TRANSLATOR di SPACE AI. Data: " + today + ". "
                     + "Esperto traduttore multilingua: italiano, inglese, francese, "
                     + "spagnolo, tedesco, portoghese, cinese, giapponese. "
                     + "Mantieni il significato e il tono originale. Spiega sfumature culturali.";

            case "planner":
                return "Sei l agente PLANNER di SPACE AI. Data: " + today + ". "
                     + "Esperto di: pianificazione progetti, gestione del tempo, metodologie agili, "
                     + "Scrum, Kanban, OKR, analisi SWOT, roadmap, breakdown di task complessi. "
                     + "Crea piani concreti e actionable. Rispondi in italiano.";

            case "summarizer":
                return "Sei l agente SUMMARIZER di SPACE AI. Data: " + today + ". "
                     + "Esperto di sintesi e riassunti. Estrai i punti chiave, "
                     + "mantieni le informazioni essenziali, elimina il superfluo. "
                     + "Struttura con bullet points chiari. Rispondi in italiano.";

            default:
                return "Sei SPACE AI, assistente avanzato. Data: " + today + ". Rispondi in italiano.";
        }
    }

    private String getSynthesizerPrompt(String today) {
        return "Sei il SYNTHESIZER di SPACE AI. Data: " + today + ". "
             + "Combina gli output degli agenti specializzati in una risposta UNICA, COERENTE e BEN FORMATTATA. "
             + "Elimina ridondanze. Usa markdown (grassetto, liste, blocchi codice). "
             + "La risposta deve sembrare scritta da un singolo esperto. "
             + "Rispondi SEMPRE in italiano.";
    }

    // ── ENDPOINT PRINCIPALE ──────────────────────────────────────
    @PostMapping(value = "/chat",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> body) {
        String userMessage = body.getOrDefault("message", "").trim();
        String sessionId   = body.getOrDefault("sessionId", "default");

        if (userMessage.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Messaggio vuoto"));
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

            // 2. Router
            List<String> agents = routeQuery(userMessage, baseUrl, apiKey, model);
            log.info("Agenti attivati: {}", agents);

            // 3. Agenti lavorano
            List<String> outputs = new ArrayList<>();
            for (String agent : agents) {
                String prompt = getSystemPrompt(agent, today);
                String out = callAgent(prompt, userMessage, history, baseUrl, apiKey, model, 2000);
                if (out != null && !out.isBlank()) {
                    outputs.add("[" + agent.toUpperCase() + "]\n" + out);
                }
            }

            // 4. Sintetizza
            String finalResponse;
            if (outputs.isEmpty()) {
                finalResponse = callLLM(getSystemPrompt("reasoner", today),
                        userMessage, baseUrl, apiKey, model, 2000);
            } else if (outputs.size() == 1) {
                finalResponse = outputs.get(0).replaceFirst("\\[\\w+\\]\\n", "");
            } else {
                String combined = String.join("\n\n---\n\n", outputs);
                String synthPrompt = getSynthesizerPrompt(today);
                String synthMsg = "Domanda: " + userMessage + "\n\nOutput agenti:\n\n" + combined;
                finalResponse = callLLM(synthPrompt, synthMsg, baseUrl, apiKey, model, 3000);
            }

            // 5. Salva
            if (!supabaseUrl.isEmpty()) {
                try {
                    saveMessage(sessionId, "user",      userMessage,   supabaseUrl, supabaseKey);
                    saveMessage(sessionId, "assistant", finalResponse, supabaseUrl, supabaseKey);
                } catch (Exception e) {
                    log.warn("Supabase: {}", e.getMessage());
                }
            }

            return ResponseEntity.ok(Map.of(
                    "response",  finalResponse,
                    "status",    "ok",
                    "model",     model,
                    "agents",    agents.toString(),
                    "sessionId", sessionId
            ));

        } catch (Exception e) {
            log.error("Errore: {}", e.getMessage());
            return ResponseEntity.status(502).body(Map.of(
                    "error", "Errore AI: " + e.getMessage(),
                    "model", model
            ));
        }
    }

    // ── ROUTER ────────────────────────────────────────────────────
    private List<String> routeQuery(String query, String baseUrl, String apiKey, String model) {
        try {
            String routerPrompt = getSystemPrompt("router", "");
            String resp = callLLM(routerPrompt,
                    "Analizza e rispondi SOLO con JSON: " + query,
                    baseUrl, apiKey, model, 150);
            int s = resp.indexOf("{");
            int e = resp.lastIndexOf("}") + 1;
            if (s >= 0 && e > s) {
                JsonNode json = MAPPER.readTree(resp.substring(s, e));
                List<String> agents = new ArrayList<>();
                json.path("agents").forEach(n -> agents.add(n.asText()));
                if (!agents.isEmpty()) return agents;
            }
        } catch (Exception e) {
            log.warn("Router fallback: {}", e.getMessage());
        }
        return List.of("reasoner");
    }

    // ── CHIAMATE AI ───────────────────────────────────────────────
    private String callAgent(String systemPrompt, String userMessage,
                              List<Map<String, String>> history,
                              String baseUrl, String apiKey, String model, int maxTokens) {
        try {
            ObjectNode req = MAPPER.createObjectNode();
            req.put("model", model);
            req.put("max_tokens", maxTokens);
            req.put("temperature", 0.7);

            ArrayNode messages = MAPPER.createArrayNode();
            ObjectNode sys = MAPPER.createObjectNode();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            messages.add(sys);

            int start = Math.max(0, history.size() - 8);
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
            log.warn("Agent error: {}", e.getMessage());
            return null;
        }
    }

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
                String.class);

        JsonNode json = MAPPER.readTree(response.getBody());
        return json.path("choices").get(0).path("message").path("content").asText();
    }

    // ── SUPABASE ──────────────────────────────────────────────────
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
                history.add(Map.of(
                        "role",    n.path("role").asText(),
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
                "service",  "SPACE AI - 12 Agenti",
                "model",    System.getenv().getOrDefault("AI_MODEL", "llama-3.3-70b-versatile"),
                "agents",   "router,code,finance,research,reasoner,math,debug,security,data,writer,translator,planner,summarizer",
                "supabase", System.getenv().getOrDefault("SUPABASE_URL","").isEmpty() ? "off" : "on",
                "date",     today
        ));
    }
}
