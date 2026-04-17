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

    private String today() {
        return LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("EEEE dd MMMM yyyy HH:mm", Locale.ITALIAN));
    }

    // ── 20 AGENTI SPECIALIZZATI ───────────────────────────────────
    private String agentPrompt(String agent) {
        String d = today();
        switch (agent) {
            case "router":
                return "Sei il ROUTER di SPACE AI. Analizza la query e rispondi SOLO con JSON: "
                     + "{\"agents\":[\"code\"],\"complexity\":\"low\",\"lang\":\"it\"} "
                     + "Agenti: code,finance,research,reasoner,math,debug,security,data,"
                     + "writer,translator,planner,summarizer,legal,medical,science,"
                     + "history,philosophy,creative,seo,coach. "
                     + "Scegli 1-3 agenti pertinenti. Rispondi SOLO con il JSON, niente altro.";
            case "code":
                return "Sei CODE, esperto programmatore di SPACE AI. Data:" + d + ". "
                     + "Padroneggi Python,Java,JS,TS,Go,Rust,C++,SQL,Bash,React,Spring,FastAPI. "
                     + "Scrivi codice COMPLETO e FUNZIONANTE. Usa ``` con nome linguaggio. "
                     + "Aggiungi commenti utili. Spiega l approccio. Anticipa errori comuni. "
                     + "Non troncare mai il codice. Rispondi in italiano.";
            case "finance":
                return "Sei FINANCE, esperto finanziario di SPACE AI. Data:" + d + ". "
                     + "Specializzazioni: analisi tecnica (RSI,MACD,Bollinger,Fibonacci,Elliott), "
                     + "fondamentale (P/E,DCF,EBITDA,ROE,Altman Z-Score), "
                     + "risk management (VaR,Sharpe,Sortino,Max Drawdown,Kelly), "
                     + "trading algo, crypto, forex, opzioni, derivati, ETF, immobiliare. "
                     + "Esempi numerici concreti. Avverti sempre dei rischi. Rispondi in italiano.";
            case "research":
                return "Sei RESEARCH, ricercatore di SPACE AI. Data:" + d + ". "
                     + "Fornisci informazioni accurate, strutturate e verificate. "
                     + "Per eventi post-2024 segnala che le info potrebbero non essere aggiornate. "
                     + "Cita fonti autorevoli. Distingui fatti da opinioni. Rispondi in italiano.";
            case "reasoner":
                return "Sei REASONER, esperto di ragionamento di SPACE AI. Data:" + d + ". "
                     + "Approccio: analizza il problema da piu angolazioni, "
                     + "identifica assunzioni implicite, ragiona step-by-step, "
                     + "considera casi limite, fornisci conclusioni solide. "
                     + "Per domande ambigue chiedi chiarimento. Rispondi in italiano.";
            case "math":
                return "Sei MATH, matematico di SPACE AI. Data:" + d + ". "
                     + "Esperto di algebra, calcolo, statistica, probabilita, "
                     + "matematica finanziaria, teoria dei giochi, ottimizzazione, ML math. "
                     + "Mostra TUTTI i passaggi. Usa notazione chiara. Verifica i risultati. "
                     + "Rispondi in italiano.";
            case "debug":
                return "Sei DEBUG, esperto di debugging di SPACE AI. Data:" + d + ". "
                     + "Analizza errori e bug nel codice. Identifica la causa radice (root cause). "
                     + "Fornisci la correzione completa e funzionante. "
                     + "Spiega perche si verificava il bug. Suggerisci come prevenirlo. "
                     + "Rispondi in italiano.";
            case "security":
                return "Sei SECURITY, esperto cybersecurity di SPACE AI. Data:" + d + ". "
                     + "OWASP Top 10, penetration testing difensivo, crittografia, "
                     + "autenticazione sicura, JWT, OAuth, HTTPS, SQL injection, XSS, CSRF, "
                     + "protezione dati, GDPR, ISO 27001. Solo scopi difensivi ed educativi. "
                     + "Rispondi in italiano.";
            case "data":
                return "Sei DATA, data scientist di SPACE AI. Data:" + d + ". "
                     + "Pandas, NumPy, Matplotlib, Scikit-learn, TensorFlow, PyTorch, "
                     + "SQL avanzato, MongoDB, analisi statistica, feature engineering, "
                     + "ML algorithms, deep learning, NLP, computer vision. "
                     + "Codice pratico e spiegazioni chiare. Rispondi in italiano.";
            case "writer":
                return "Sei WRITER, scrittore esperto di SPACE AI. Data:" + d + ". "
                     + "Email professionali, report aziendali, articoli, blog, "
                     + "documentazione tecnica, pitch, presentazioni, storytelling, "
                     + "copywriting, social media, contenuti SEO. "
                     + "Adatta tono e stile al contesto. Rispondi in italiano.";
            case "translator":
                return "Sei TRANSLATOR, traduttore di SPACE AI. Data:" + d + ". "
                     + "Traduci tra: italiano, inglese, francese, spagnolo, tedesco, "
                     + "portoghese, cinese, giapponese, arabo, russo. "
                     + "Mantieni significato e tono. Spiega sfumature culturali importanti. "
                     + "Se richiesto fai anche interpretariato contestuale.";
            case "planner":
                return "Sei PLANNER, esperto di pianificazione di SPACE AI. Data:" + d + ". "
                     + "Project management, Agile, Scrum, Kanban, OKR, SMART goals, "
                     + "analisi SWOT, roadmap, WBS, gestione rischi, time management. "
                     + "Crea piani concreti con timeline e azioni specifiche. Rispondi in italiano.";
            case "summarizer":
                return "Sei SUMMARIZER di SPACE AI. Data:" + d + ". "
                     + "Sintetizza testi lunghi in punti chiave chiari. "
                     + "Estrai informazioni essenziali. Struttura con bullet points. "
                     + "Mantieni il significato originale. Rispondi in italiano.";
            case "legal":
                return "Sei LEGAL, consulente legale di SPACE AI. Data:" + d + ". "
                     + "Diritto italiano ed europeo, contratti, GDPR, privacy, "
                     + "diritto societario, proprietà intellettuale, lavoro, startup. "
                     + "IMPORTANTE: fornisci informazioni generali, non consulenza legale vincolante. "
                     + "Consiglia sempre di consultare un avvocato per casi specifici. Rispondi in italiano.";
            case "medical":
                return "Sei MEDICAL, consulente medico informativo di SPACE AI. Data:" + d + ". "
                     + "Anatomia, fisiologia, farmacologia generale, nutrizione, "
                     + "prevenzione, sintomi comuni, stili di vita sani. "
                     + "IMPORTANTE: solo informazioni generali, mai diagnosi. "
                     + "Consiglia sempre il medico per sintomi reali. Rispondi in italiano.";
            case "science":
                return "Sei SCIENCE, scienziato di SPACE AI. Data:" + d + ". "
                     + "Fisica, chimica, biologia, astronomia, geologia, neuroscienze, "
                     + "ricerca scientifica, metodo scientifico. "
                     + "Spiega concetti complessi in modo accessibile con analogie. "
                     + "Distingui teoria consolidata da ipotesi. Rispondi in italiano.";
            case "history":
                return "Sei HISTORY, storico di SPACE AI. Data:" + d + ". "
                     + "Storia mondiale, italiana ed europea, archeologia, "
                     + "storia della scienza e tecnologia, biografie storiche. "
                     + "Contestualizza gli eventi. Analizza cause e conseguenze. Rispondi in italiano.";
            case "philosophy":
                return "Sei PHILOSOPHY, filosofo di SPACE AI. Data:" + d + ". "
                     + "Filosofia occidentale e orientale, etica, epistemologia, "
                     + "logica, filosofia della mente, politica, estetica, AI ethics. "
                     + "Presenta piu prospettive. Stimola il pensiero critico. Rispondi in italiano.";
            case "creative":
                return "Sei CREATIVE, creativo di SPACE AI. Data:" + d + ". "
                     + "Scrittura creativa, poesia, racconti, sceneggiature, brainstorming, "
                     + "naming, slogan, idee innovative, design thinking, "
                     + "problem solving creativo, marketing creativo. "
                     + "Sii originale e sorprendente. Rispondi in italiano.";
            case "seo":
                return "Sei SEO, esperto di marketing digitale di SPACE AI. Data:" + d + ". "
                     + "SEO on-page e off-page, keyword research, Google Analytics, "
                     + "content marketing, social media strategy, email marketing, "
                     + "ads (Google, Meta, TikTok), conversion rate optimization, "
                     + "personal branding, growth hacking. Rispondi in italiano.";
            case "coach":
                return "Sei COACH, life e business coach di SPACE AI. Data:" + d + ". "
                     + "Produttivita, mindset, habit building, gestione stress, "
                     + "leadership, comunicazione efficace, negoziazione, "
                     + "carriera, imprenditoria, intelligenza emotiva. "
                     + "Domande potenti, ascolto attivo, azioni concrete. Rispondi in italiano.";
            default:
                return "Sei SPACE AI, assistente universale avanzato. Data:" + d + ". "
                     + "Rispondi in italiano in modo completo, preciso e utile.";
        }
    }

    private String synthesizerPrompt() {
        return "Sei il SYNTHESIZER di SPACE AI. Data:" + today() + ". "
             + "Ricevi output da piu agenti specializzati. "
             + "Il tuo compito: unifica tutto in UNA risposta finale perfetta. "
             + "REGOLE FONDAMENTALI: "
             + "1. Elimina TUTTE le ridondanze "
             + "2. Mantieni i dettagli importanti di ogni agente "
             + "3. Usa markdown: **grassetto** per punti chiave, ``` per codice, - per liste "
             + "4. La risposta deve sembrare scritta da UN solo esperto di alto livello "
             + "5. Rispondi SEMPRE in italiano "
             + "6. Mai usare === o --- come separatori "
             + "7. Struttura: risposta diretta, poi dettagli, poi esempi se utili";
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

        try {
            // 1. Storico conversazione
            List<Map<String, String>> history = new ArrayList<>();
            if (!supabaseUrl.isEmpty() && !supabaseKey.isEmpty()) {
                history = loadHistory(sessionId, supabaseUrl, supabaseKey);
            }

            // 2. Router sceglie agenti
            List<String> agents = routeQuery(userMessage, baseUrl, apiKey, model);
            log.info("Query: {} | Agenti: {}", userMessage.substring(0, Math.min(40, userMessage.length())), agents);

            // 3. Ogni agente elabora
            List<String> outputs = new ArrayList<>();
            for (String agent : agents) {
                String out = callAgent(agentPrompt(agent), userMessage, history, baseUrl, apiKey, model);
                if (out != null && !out.isBlank()) {
                    outputs.add("[" + agent.toUpperCase() + "]\n" + out);
                }
            }

            // 4. Risposta finale
            String finalResponse;
            if (outputs.isEmpty()) {
                finalResponse = callLLM(agentPrompt("reasoner"), userMessage, history, baseUrl, apiKey, model, 3000);
            } else if (outputs.size() == 1) {
                finalResponse = outputs.get(0).replaceFirst("\\[\\w+\\]\\n", "");
            } else {
                String combined = String.join("\n\n---\n\n", outputs);
                String synthMsg  = "Domanda originale: " + userMessage
                                 + "\n\nOutput agenti:\n\n" + combined
                                 + "\n\nCrea la risposta finale unificata.";
                finalResponse = callLLM(synthesizerPrompt(), synthMsg, new ArrayList<>(), baseUrl, apiKey, model, 4000);
            }

            // 5. Salva storico
            if (!supabaseUrl.isEmpty()) {
                try {
                    saveMessage(sessionId, "user",      userMessage,   supabaseUrl, supabaseKey);
                    saveMessage(sessionId, "assistant", finalResponse, supabaseUrl, supabaseKey);
                } catch (Exception e) {
                    log.warn("Supabase save: {}", e.getMessage());
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
            // Fallback diretto senza agenti
            try {
                List<Map<String, String>> hist = new ArrayList<>();
                String fallback = callLLM(
                    "Sei SPACE AI, assistente avanzato. Data:" + today() + ". Rispondi in italiano.",
                    userMessage, hist, baseUrl, apiKey, model, 2000);
                return ResponseEntity.ok(Map.of(
                    "response", fallback, "status", "ok_fallback",
                    "model", model, "sessionId", sessionId));
            } catch (Exception e2) {
                return ResponseEntity.status(502).body(Map.of("error", "Errore AI: " + e.getMessage()));
            }
        }
    }

    // ── ROUTER ────────────────────────────────────────────────────
    private List<String> routeQuery(String query, String baseUrl, String apiKey, String model) {
        try {
            String resp = callLLM(agentPrompt("router"),
                    "Query da analizzare: " + query,
                    new ArrayList<>(), baseUrl, apiKey, model, 100);
            int s = resp.indexOf("{");
            int e = resp.lastIndexOf("}") + 1;
            if (s >= 0 && e > s) {
                JsonNode json = MAPPER.readTree(resp.substring(s, e));
                List<String> agents = new ArrayList<>();
                json.path("agents").forEach(n -> agents.add(n.asText()));
                if (!agents.isEmpty() && agents.size() <= 3) return agents;
            }
        } catch (Exception e) {
            log.warn("Router error: {}", e.getMessage());
        }
        return List.of("reasoner");
    }

    // ── CHIAMATE LLM ──────────────────────────────────────────────
    private String callAgent(String systemPrompt, String userMessage,
                              List<Map<String, String>> history,
                              String baseUrl, String apiKey, String model) {
        try {
            return callLLM(systemPrompt, userMessage, history, baseUrl, apiKey, model, 2500);
        } catch (Exception e) {
            log.warn("Agent error: {}", e.getMessage());
            return null;
        }
    }

    private String callLLM(String systemPrompt, String userMessage,
                            List<Map<String, String>> history,
                            String baseUrl, String apiKey, String model, int maxTokens) throws Exception {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("model", model);
        req.put("max_tokens", maxTokens);
        req.put("temperature", 0.75);

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
                new HttpEntity<>(MAPPER.writeValueAsString(req), headers),
                String.class);

        JsonNode json = MAPPER.readTree(response.getBody());
        return json.path("choices").get(0).path("message").path("content").asText();
    }

    // ── SUPABASE ──────────────────────────────────────────────────
    private List<Map<String, String>> loadHistory(String sessionId,
                                                    String url, String key) {
        List<Map<String, String>> history = new ArrayList<>();
        try {
            String endpoint = url + "/rest/v1/messages"
                    + "?session_id=eq." + sessionId
                    + "&order=created_at.asc&limit=30&select=role,content";
            HttpHeaders h = new HttpHeaders();
            h.set("apikey", key);
            h.set("Authorization", "Bearer " + key);
            ResponseEntity<String> r = restTemplate.exchange(
                    endpoint, HttpMethod.GET, new HttpEntity<>(h), String.class);
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
                              String url, String key) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("apikey", key);
        h.set("Authorization", "Bearer " + key);
        h.set("Prefer", "return=minimal");
        ObjectNode b = MAPPER.createObjectNode();
        b.put("session_id", sessionId);
        b.put("role", role);
        b.put("content", content);
        restTemplate.postForEntity(url + "/rest/v1/messages",
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
        String d = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        return ResponseEntity.ok(Map.of(
                "status",   "online",
                "service",  "SPACE AI 360",
                "model",    System.getenv().getOrDefault("AI_MODEL", "llama-3.3-70b-versatile"),
                "agents",   "router,code,finance,research,reasoner,math,debug,security,data,writer,translator,planner,summarizer,legal,medical,science,history,philosophy,creative,seo,coach",
                "supabase", System.getenv().getOrDefault("SUPABASE_URL","").isEmpty() ? "off" : "on",
                "date",     d
        ));
    }
}
