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

    // ── WEB SEARCH TAVILY ────────────────────────────────────────
    private String searchWeb(String query) {
        String key = System.getenv().getOrDefault("TAVILY_API_KEY", "");
        if (key.isEmpty()) return null;
        try {
            ObjectNode req = MAPPER.createObjectNode();
            req.put("api_key", key);
            req.put("query", query);
            req.put("search_depth", "advanced");
            req.put("max_results", 5);
            req.put("include_answer", true);
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> resp = restTemplate.postForEntity(
                    "https://api.tavily.com/search",
                    new HttpEntity<>(MAPPER.writeValueAsString(req), h),
                    String.class);
            JsonNode json = MAPPER.readTree(resp.getBody());
            StringBuilder sb = new StringBuilder();
            if (json.has("answer") && !json.path("answer").asText().isBlank())
                sb.append("RISPOSTA: ").append(json.path("answer").asText()).append("\n\n");
            JsonNode results = json.path("results");
            if (results.isArray()) {
                sb.append("FONTI:\n");
                int i = 1;
                for (JsonNode r : results) {
                    sb.append(i++).append(". ").append(r.path("title").asText()).append("\n");
                    String c = r.path("content").asText();
                    sb.append("   ").append(c, 0, Math.min(200, c.length())).append("\n");
                    sb.append("   URL: ").append(r.path("url").asText()).append("\n\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Tavily: {}", e.getMessage());
            return null;
        }
    }

    private boolean needsSearch(String msg) {
        String q = msg.toLowerCase();
        return q.contains("cerca") || q.contains("notizie") || q.contains("oggi") ||
               q.contains("attuale") || q.contains("2025") || q.contains("2026") ||
               q.contains("prezzo") || q.contains("quotazione") || q.contains("aggiornato") ||
               q.contains("ultimo") || q.contains("recente") || q.contains("adesso");
    }

    // ── GENERAZIONE IMMAGINI HuggingFace ────────────────────────
    private String generateImage(String prompt) {
        String hfKey = System.getenv().getOrDefault("HF_TOKEN", "");
        if (hfKey.isEmpty()) return "Configura HF_TOKEN nelle variabili d ambiente per generare immagini.";
        try {
            String model = "stabilityai/stable-diffusion-xl-base-1.0";
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            h.setBearerAuth(hfKey);
            ObjectNode req = MAPPER.createObjectNode();
            req.put("inputs", prompt);
            ResponseEntity<byte[]> resp = restTemplate.postForEntity(
                    "https://api-inference.huggingface.co/models/" + model,
                    new HttpEntity<>(MAPPER.writeValueAsString(req), h),
                    byte[].class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                String b64 = Base64.getEncoder().encodeToString(resp.getBody());
                return "IMAGE:" + b64;
            }
            return "Errore generazione immagine: " + resp.getStatusCode();
        } catch (Exception e) {
            log.warn("HF image: {}", e.getMessage());
            return "Errore generazione immagine: " + e.getMessage();
        }
    }

    // ── CORE SYSTEM ──────────────────────────────────────────────
    private String coreSystem() {
        return "Sei SPACE AI, assistente AI avanzato creato da Paolo. Data: " + today() + ". "
             + "Rispondi SEMPRE alla domanda ATTUALE. NON ripetere risposte precedenti. "
             + "Se ci sono [DATI WEB] nel messaggio, usali. "
             + "Rispondi in italiano. Usa markdown. Sii preciso e completo.";
    }

    // ── 60+ AGENTI ───────────────────────────────────────────────
    private String agentPrompt(String agent) {
        String d = today();
        switch (agent) {
            case "router":
                return "Sei il ROUTER di SPACE AI. Analizza SOLO la domanda attuale. "
                     + "Rispondi SOLO con JSON: {\"agents\":[\"code\"],\"complexity\":\"low\"} "
                     + "Agenti: code,finance,research,reasoner,math,debug,security,data,ai,cloud,"
                     + "devops,database,frontend,backend,mobile,blockchain,tech,crypto,forex,"
                     + "real_estate,accounting,tax,physics,chemistry,biology,astro,science,"
                     + "environment,energy,medical,nutrition,fitness,mental_health,psychology,"
                     + "history,philosophy,economics,politics,geography,arts,music,books,movies,"
                     + "cooking,travel,sports,gaming,writer,creative,planner,startup,hr,product,"
                     + "ux,seo,coach,education,translator,legal,summarizer,image_gen. "
                     + "Scegli 1-2 agenti. SOLO JSON.";
            case "image_gen":
                return "Sei IMAGE_GEN di SPACE AI. Data:" + d + ". "
                     + "Sei specializzato nella creazione e descrizione di immagini. "
                     + "Quando l utente chiede di generare/creare/disegnare un immagine: "
                     + "1. Rispondi con [GENERA_IMMAGINE: descrizione dettagliata in inglese] "
                     + "2. Poi descrivi cosa hai creato in italiano. "
                     + "Quando descrive un immagine esistente: analizzala dettagliatamente. "
                     + "Per prompt immagini: sii specifico su stile, colori, composizione, illuminazione.";
            case "code":
                return "Sei CODE di SPACE AI. Data:" + d + ". "
                     + "Esperto: Python,Java,JS,TS,Go,Rust,C++,SQL,React,Spring,FastAPI,Docker. "
                     + "Codice COMPLETO con ```. Mai troncare. Rispondi in italiano.";
            case "finance":
                return "Sei FINANCE di SPACE AI. Data:" + d + ". "
                     + "Se ci sono [DATI WEB] nel messaggio usali per prezzi aggiornati. "
                     + "RSI,MACD,Bollinger,Fibonacci,P/E,DCF,VaR,Sharpe,portfolio. "
                     + "Esempi numerici concreti. Rispondi in italiano.";
            case "crypto":
                return "Sei CRYPTO di SPACE AI. Data:" + d + ". "
                     + "Se ci sono [DATI WEB] usali. Bitcoin,Ethereum,DeFi,NFT,tokenomics. "
                     + "Analisi tecnica e fondamentale. Rispondi in italiano.";
            case "research":
                return "Sei RESEARCH di SPACE AI. Data:" + d + ". "
                     + "Se ci sono [DATI WEB] sintetizzali. Info accurate e verificate. "
                     + "Per eventi post-2024 segnala il limite. Rispondi in italiano.";
            case "reasoner":
                return "Sei REASONER di SPACE AI. Data:" + d + ". "
                     + "Analizza step-by-step. Considera piu angolazioni. Rispondi in italiano.";
            case "math":
                return "Sei MATH di SPACE AI. Data:" + d + ". "
                     + "Mostra TUTTI i passaggi. Algebra,calcolo,statistica. Rispondi in italiano.";
            case "debug":
                return "Sei DEBUG di SPACE AI. Data:" + d + ". "
                     + "Root cause + soluzione completa + prevenzione. Rispondi in italiano.";
            case "security":
                return "Sei SECURITY di SPACE AI. Data:" + d + ". "
                     + "OWASP,crittografia,JWT,OAuth,GDPR. Solo difensivo. Rispondi in italiano.";
            case "data":
                return "Sei DATA di SPACE AI. Data:" + d + ". "
                     + "Pandas,NumPy,Scikit-learn,TensorFlow,ML,DL,SQL. Rispondi in italiano.";
            case "ai":
                return "Sei AI-EXPERT di SPACE AI. Data:" + d + ". "
                     + "LLM,fine-tuning,RAG,embeddings,prompt engineering. Rispondi in italiano.";
            case "cloud":
                return "Sei CLOUD di SPACE AI. Data:" + d + ". "
                     + "AWS,GCP,Azure,Kubernetes,Terraform. Rispondi in italiano.";
            case "devops":
                return "Sei DEVOPS di SPACE AI. Data:" + d + ". "
                     + "CI/CD,Docker,GitHub Actions,monitoring. Rispondi in italiano.";
            case "database":
                return "Sei DATABASE di SPACE AI. Data:" + d + ". "
                     + "PostgreSQL,MySQL,MongoDB,Redis,SQL ottimizzato. Rispondi in italiano.";
            case "frontend":
                return "Sei FRONTEND di SPACE AI. Data:" + d + ". "
                     + "React,Vue,HTML5,CSS3,Tailwind. Rispondi in italiano.";
            case "backend":
                return "Sei BACKEND di SPACE AI. Data:" + d + ". "
                     + "API REST,GraphQL,Spring Boot,FastAPI. Rispondi in italiano.";
            case "mobile":
                return "Sei MOBILE di SPACE AI. Data:" + d + ". "
                     + "React Native,Flutter,Swift,Kotlin. Rispondi in italiano.";
            case "blockchain":
                return "Sei BLOCKCHAIN di SPACE AI. Data:" + d + ". "
                     + "Ethereum,Solidity,Web3,DeFi,smart contract. Rispondi in italiano.";
            case "tech":
                return "Sei TECH di SPACE AI. Data:" + d + ". Hardware,reti,IoT. Rispondi in italiano.";
            case "forex":
                return "Sei FOREX di SPACE AI. Data:" + d + ". Valute,carry trade,FX. Rispondi in italiano.";
            case "real_estate":
                return "Sei REAL_ESTATE di SPACE AI. Data:" + d + ". Immobiliare,ROI,REIT. Rispondi in italiano.";
            case "accounting":
                return "Sei ACCOUNTING di SPACE AI. Data:" + d + ". Bilancio,cash flow,IAS. Rispondi in italiano.";
            case "tax":
                return "Sei TAX di SPACE AI. Data:" + d + ". Fiscalita italiana,IVA,IRPEF. Rispondi in italiano.";
            case "physics":
                return "Sei PHYSICS di SPACE AI. Data:" + d + ". Meccanica,quantistica,relativita. Rispondi in italiano.";
            case "chemistry":
                return "Sei CHEMISTRY di SPACE AI. Data:" + d + ". Chimica organica,reazioni. Rispondi in italiano.";
            case "biology":
                return "Sei BIOLOGY di SPACE AI. Data:" + d + ". Genetica,CRISPR,evoluzione. Rispondi in italiano.";
            case "astro":
                return "Sei ASTRO di SPACE AI. Data:" + d + ". Astronomia,cosmologia,universo. Rispondi in italiano.";
            case "science":
                return "Sei SCIENCE di SPACE AI. Data:" + d + ". Metodo scientifico,neuroscienza. Rispondi in italiano.";
            case "environment":
                return "Sei ENVIRONMENT di SPACE AI. Data:" + d + ". Clima,sostenibilita. Rispondi in italiano.";
            case "energy":
                return "Sei ENERGY di SPACE AI. Data:" + d + ". Solare,eolico,nucleare. Rispondi in italiano.";
            case "medical":
                return "Sei MEDICAL di SPACE AI. Data:" + d + ". Solo info generali. Rispondi in italiano.";
            case "nutrition":
                return "Sei NUTRITION di SPACE AI. Data:" + d + ". Diete,macronutrienti. Rispondi in italiano.";
            case "fitness":
                return "Sei FITNESS di SPACE AI. Data:" + d + ". Allenamento,schede. Rispondi in italiano.";
            case "mental_health":
                return "Sei MENTAL_HEALTH di SPACE AI. Data:" + d + ". Solo supporto informativo. Rispondi in italiano.";
            case "psychology":
                return "Sei PSYCHOLOGY di SPACE AI. Data:" + d + ". Psicologia cognitiva,bias. Rispondi in italiano.";
            case "history":
                return "Sei HISTORY di SPACE AI. Data:" + d + ". Storia mondiale. Rispondi in italiano.";
            case "philosophy":
                return "Sei PHILOSOPHY di SPACE AI. Data:" + d + ". Etica,epistemologia. Rispondi in italiano.";
            case "economics":
                return "Sei ECONOMICS di SPACE AI. Data:" + d + ". Micro e macro economia. Rispondi in italiano.";
            case "politics":
                return "Sei POLITICS di SPACE AI. Data:" + d + ". Neutrale e bilanciato. Rispondi in italiano.";
            case "geography":
                return "Sei GEOGRAPHY di SPACE AI. Data:" + d + ". Paesi e culture. Rispondi in italiano.";
            case "arts":
                return "Sei ARTS di SPACE AI. Data:" + d + ". Arte,pittura,architettura. Rispondi in italiano.";
            case "music":
                return "Sei MUSIC di SPACE AI. Data:" + d + ". Teoria,strumenti,produzione. Rispondi in italiano.";
            case "books":
                return "Sei BOOKS di SPACE AI. Data:" + d + ". Letteratura,consigli lettura. Rispondi in italiano.";
            case "movies":
                return "Sei MOVIES di SPACE AI. Data:" + d + ". Cinema,serie TV. Rispondi in italiano.";
            case "cooking":
                return "Sei COOKING di SPACE AI. Data:" + d + ". Ricette con dosi precise. Rispondi in italiano.";
            case "travel":
                return "Sei TRAVEL di SPACE AI. Data:" + d + ". Destinazioni,itinerari. Rispondi in italiano.";
            case "sports":
                return "Sei SPORTS di SPACE AI. Data:" + d + ". Sport,tattica,statistiche. Rispondi in italiano.";
            case "gaming":
                return "Sei GAMING di SPACE AI. Data:" + d + ". Videogiochi,strategie. Rispondi in italiano.";
            case "writer":
                return "Sei WRITER di SPACE AI. Data:" + d + ". Email,report,storytelling. Rispondi in italiano.";
            case "creative":
                return "Sei CREATIVE di SPACE AI. Data:" + d + ". Brainstorming,naming,slogan. Rispondi in italiano.";
            case "planner":
                return "Sei PLANNER di SPACE AI. Data:" + d + ". Agile,OKR,roadmap. Rispondi in italiano.";
            case "startup":
                return "Sei STARTUP di SPACE AI. Data:" + d + ". Pitch,fundraising,MVP. Rispondi in italiano.";
            case "hr":
                return "Sei HR di SPACE AI. Data:" + d + ". Recruiting,carriera. Rispondi in italiano.";
            case "product":
                return "Sei PRODUCT di SPACE AI. Data:" + d + ". Roadmap,user story. Rispondi in italiano.";
            case "ux":
                return "Sei UX di SPACE AI. Data:" + d + ". UX/UI,Figma,usabilita. Rispondi in italiano.";
            case "seo":
                return "Sei SEO di SPACE AI. Data:" + d + ". SEO,content marketing. Rispondi in italiano.";
            case "coach":
                return "Sei COACH di SPACE AI. Data:" + d + ". Mindset,produttivita. Rispondi in italiano.";
            case "education":
                return "Sei EDUCATION di SPACE AI. Data:" + d + ". Apprendimento,pedagogia. Rispondi in italiano.";
            case "translator":
                return "Sei TRANSLATOR di SPACE AI. Multilingua IT,EN,FR,ES,DE,PT,ZH,JA.";
            case "legal":
                return "Sei LEGAL di SPACE AI. Data:" + d + ". Solo info generali. Rispondi in italiano.";
            case "summarizer":
                return "Sei SUMMARIZER di SPACE AI. Bullet points chiari. Rispondi in italiano.";
            default:
                return coreSystem();
        }
    }

    private String synthesizerPrompt() {
        return "Sei il SYNTHESIZER di SPACE AI. Data:" + today() + ". "
             + "Unifica in UNA risposta finale. Elimina ridondanze. "
             + "Usa markdown. MAI === o ---. Rispondi in italiano.";
    }

    // ── ENDPOINT PRINCIPALE ──────────────────────────────────────
    @PostMapping(value = "/chat",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> body) {
        String userMessage = body.getOrDefault("message", "").trim();
        String sessionId   = body.getOrDefault("sessionId", "default");

        if (userMessage.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Messaggio vuoto"));

        String baseUrl     = System.getenv().getOrDefault("AI_BASE_URL", "https://api.groq.com/openai/v1");
        String apiKey      = System.getenv().getOrDefault("AI_API_KEY", "");
        String model       = System.getenv().getOrDefault("AI_MODEL", "llama-3.3-70b-versatile");
        String supabaseUrl = System.getenv().getOrDefault("SUPABASE_URL", "");
        String supabaseKey = System.getenv().getOrDefault("SUPABASE_KEY", "");

        try {
            // 1. Storico
            List<Map<String, String>> history = new ArrayList<>();
            if (!supabaseUrl.isEmpty() && !supabaseKey.isEmpty())
                history = loadHistory(sessionId, supabaseUrl, supabaseKey);

            // 2. Web search
            String webData = needsSearch(userMessage) ? searchWeb(userMessage) : null;
            String enriched = userMessage;
            if (webData != null && !webData.isBlank())
                enriched = userMessage + "\n\n[DATI WEB - " + today() + "]:\n" + webData;

            // 3. Controlla se è richiesta generazione immagine
            String q = userMessage.toLowerCase();
            boolean isImageRequest = q.contains("genera immagine") || q.contains("crea immagine") ||
                    q.contains("disegna") || q.contains("genera un'immagine") ||
                    q.contains("crea un'immagine") || q.contains("generate image") ||
                    q.contains("immagine di") || q.contains("foto di");

            if (isImageRequest) {
                // Prima ottieni il prompt ottimizzato dall'agente image_gen
                String imgPromptSystem = agentPrompt("image_gen");
                String imgAgentResp = callLLM(imgPromptSystem, enriched, history, baseUrl, apiKey, model, 500);

                // Estrai il prompt per HuggingFace
                String hfPrompt = userMessage;
                if (imgAgentResp.contains("[GENERA_IMMAGINE:")) {
                    int s = imgAgentResp.indexOf("[GENERA_IMMAGINE:") + 17;
                    int e = imgAgentResp.indexOf("]", s);
                    if (e > s) hfPrompt = imgAgentResp.substring(s, e).trim();
                }

                // Genera immagine
                String imgResult = generateImage(hfPrompt);

                String finalResp;
                if (imgResult.startsWith("IMAGE:")) {
                    finalResp = imgAgentResp.replace(
                            imgAgentResp.substring(
                                    Math.max(0, imgAgentResp.indexOf("[GENERA_IMMAGINE:")),
                                    Math.min(imgAgentResp.length(),
                                             imgAgentResp.indexOf("]", imgAgentResp.indexOf("[GENERA_IMMAGINE:")) + 1)),
                            "");
                    if (finalResp.isBlank())
                        finalResp = "Ho generato l'immagine richiesta!";

                    if (!supabaseUrl.isEmpty()) {
                        try {
                            saveMessage(sessionId, "user", userMessage, supabaseUrl, supabaseKey);
                            saveMessage(sessionId, "assistant", finalResp, supabaseUrl, supabaseKey);
                        } catch (Exception e) { log.warn("Supabase: {}", e.getMessage()); }
                    }

                    return ResponseEntity.ok(Map.of(
                            "response",   finalResp,
                            "image",      imgResult.substring(6),
                            "imageType",  "image/jpeg",
                            "status",     "ok",
                            "model",      model,
                            "sessionId",  sessionId
                    ));
                } else {
                    finalResp = imgResult;
                    return ResponseEntity.ok(Map.of("response", finalResp, "status", "ok",
                            "model", model, "sessionId", sessionId));
                }
            }

            // 4. Router normale
            List<String> agents = routeQuery(userMessage, baseUrl, apiKey, model);
            log.info("Agenti: {} | Web: {}", agents, webData != null);

            // 5. Agenti
            List<String> outputs = new ArrayList<>();
            for (String agent : agents) {
                String out = callLLM(agentPrompt(agent), enriched, history, baseUrl, apiKey, model, 2500);
                if (out != null && !out.isBlank())
                    outputs.add("[" + agent.toUpperCase() + "]\n" + out);
            }

            // 6. Risposta finale
            String finalResponse;
            if (outputs.isEmpty())
                finalResponse = callLLM(coreSystem(), enriched, history, baseUrl, apiKey, model, 2000);
            else if (outputs.size() == 1)
                finalResponse = outputs.get(0).replaceFirst("\\[\\w+\\]\\n", "");
            else {
                String combined = String.join("\n\n", outputs);
                String synthMsg = "Domanda: " + userMessage + "\n\nOutput:\n\n" + combined;
                finalResponse = callLLM(synthesizerPrompt(), synthMsg, new ArrayList<>(), baseUrl, apiKey, model, 3000);
            }

            // 7. Salva
            if (!supabaseUrl.isEmpty()) {
                try {
                    saveMessage(sessionId, "user",      userMessage,   supabaseUrl, supabaseKey);
                    saveMessage(sessionId, "assistant", finalResponse, supabaseUrl, supabaseKey);
                } catch (Exception e) { log.warn("Supabase: {}", e.getMessage()); }
            }

            return ResponseEntity.ok(Map.of(
                    "response",  finalResponse,
                    "status",    "ok",
                    "model",     model,
                    "agents",    agents.toString(),
                    "webSearch", webData != null ? "true" : "false",
                    "sessionId", sessionId
            ));

        } catch (Exception e) {
            log.error("Errore: {}", e.getMessage());
            try {
                String fallback = callLLM(coreSystem(), userMessage, new ArrayList<>(), baseUrl, apiKey, model, 2000);
                return ResponseEntity.ok(Map.of("response", fallback, "status", "ok_fallback", "sessionId", sessionId));
            } catch (Exception e2) {
                return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
            }
        }
    }

    // ── ROUTER ────────────────────────────────────────────────────
    private List<String> routeQuery(String query, String baseUrl, String apiKey, String model) {
        try {
            String resp = callLLM(agentPrompt("router"), "DOMANDA: " + query,
                    new ArrayList<>(), baseUrl, apiKey, model, 100);
            int s = resp.indexOf("{"), e = resp.lastIndexOf("}") + 1;
            if (s >= 0 && e > s) {
                JsonNode json = MAPPER.readTree(resp.substring(s, e));
                List<String> agents = new ArrayList<>();
                json.path("agents").forEach(n -> agents.add(n.asText()));
                if (!agents.isEmpty() && agents.size() <= 3) return agents;
            }
        } catch (Exception e) { log.warn("Router: {}", e.getMessage()); }

        String q = query.toLowerCase();
        if (q.contains("python")||q.contains("java")||q.contains("codice")||q.contains("funzione")) return List.of("code");
        if (q.contains("grafico")||q.contains("chart")||q.contains("visualizza")) return List.of("data","code");
        if (q.contains("bitcoin")||q.contains("crypto")||q.contains("cripto")) return List.of("crypto");
        if (q.contains("borsa")||q.contains("trading")||q.contains("azioni")) return List.of("finance");
        if (q.contains("traduci")||q.contains("in inglese")||q.contains("traduzione")) return List.of("translator");
        if (q.contains("calcola")||q.contains("matematica")||q.contains("equazione")) return List.of("math");
        if (q.contains("bug")||q.contains("errore nel codice")) return List.of("debug");
        if (q.contains("legge")||q.contains("contratto")||q.contains("gdpr")) return List.of("legal");
        if (q.contains("ricetta")||q.contains("cucina")) return List.of("cooking");
        if (q.contains("viaggio")||q.contains("vacanza")) return List.of("travel");
        if (q.contains("allenamento")||q.contains("palestra")) return List.of("fitness");
        if (q.contains("notizie")||q.contains("news")) return List.of("research");
        if (q.contains("startup")||q.contains("business")) return List.of("startup");
        if (q.contains("email")||q.contains("scrivi un")) return List.of("writer");
        return List.of("reasoner");
    }

    // ── CHIAMATA LLM ──────────────────────────────────────────────
    private String callLLM(String system, String userMessage, List<Map<String, String>> history,
                            String baseUrl, String apiKey, String model, int maxTokens) throws Exception {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("model", model);
        req.put("max_tokens", maxTokens);
        req.put("temperature", 0.8);
        req.put("top_p", 0.95);
        req.put("frequency_penalty", 0.3);
        req.put("presence_penalty", 0.3);

        ArrayNode messages = MAPPER.createArrayNode();
        ObjectNode sys = MAPPER.createObjectNode();
        sys.put("role", "system"); sys.put("content", system);
        messages.add(sys);

        int start = Math.max(0, history.size() - 10);
        for (int i = start; i < history.size(); i++) {
            ObjectNode m = MAPPER.createObjectNode();
            m.put("role", history.get(i).get("role"));
            m.put("content", history.get(i).get("content"));
            messages.add(m);
        }

        ObjectNode usr = MAPPER.createObjectNode();
        usr.put("role", "user"); usr.put("content", userMessage);
        messages.add(usr);
        req.set("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (!apiKey.isEmpty()) headers.setBearerAuth(apiKey);
        headers.set("HTTP-Referer", "https://space-ai-940e.onrender.com");
        headers.set("X-Title", "SPACE AI");
        headers.set("ngrok-skip-browser-warning", "true");
        headers.set("User-Agent", "SPACE-AI-Server/1.0");

        String endpoint = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
        ResponseEntity<String> response = restTemplate.postForEntity(
                endpoint, new HttpEntity<>(MAPPER.writeValueAsString(req), headers), String.class);
        return MAPPER.readTree(response.getBody()).path("choices").get(0).path("message").path("content").asText();
    }

    // ── SUPABASE ──────────────────────────────────────────────────
    private List<Map<String, String>> loadHistory(String sessionId, String url, String key) {
        List<Map<String, String>> history = new ArrayList<>();
        try {
            HttpHeaders h = new HttpHeaders();
            h.set("apikey", key); h.set("Authorization", "Bearer " + key);
            ResponseEntity<String> r = restTemplate.exchange(
                    url + "/rest/v1/messages?session_id=eq." + sessionId + "&order=created_at.asc&limit=30&select=role,content",
                    HttpMethod.GET, new HttpEntity<>(h), String.class);
            JsonNode arr = MAPPER.readTree(r.getBody());
            for (JsonNode n : arr)
                history.add(Map.of("role", n.path("role").asText(), "content", n.path("content").asText()));
        } catch (Exception e) { log.warn("Supabase load: {}", e.getMessage()); }
        return history;
    }

    private void saveMessage(String sessionId, String role, String content, String url, String key) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("apikey", key); h.set("Authorization", "Bearer " + key); h.set("Prefer", "return=minimal");
        ObjectNode b = MAPPER.createObjectNode();
        b.put("session_id", sessionId); b.put("role", role); b.put("content", content);
        restTemplate.postForEntity(url + "/rest/v1/messages", new HttpEntity<>(MAPPER.writeValueAsString(b), h), String.class);
    }

    @GetMapping("/history/{sessionId}")
    public ResponseEntity<Object> getHistory(@PathVariable String sessionId) {
        String url = System.getenv().getOrDefault("SUPABASE_URL", "");
        String key = System.getenv().getOrDefault("SUPABASE_KEY", "");
        if (url.isEmpty()) return ResponseEntity.ok(Map.of("messages", List.of()));
        return ResponseEntity.ok(Map.of("messages", loadHistory(sessionId, url, key)));
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, String>> config() {
        return ResponseEntity.ok(Map.of(
                "supabaseUrl", System.getenv().getOrDefault("SUPABASE_URL", ""),
                "supabaseKey", System.getenv().getOrDefault("SUPABASE_KEY", "")
        ));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        boolean tavily = !System.getenv().getOrDefault("TAVILY_API_KEY", "").isEmpty();
        boolean hf     = !System.getenv().getOrDefault("HF_TOKEN", "").isEmpty();
        boolean supa   = !System.getenv().getOrDefault("SUPABASE_URL", "").isEmpty();
        return ResponseEntity.ok(Map.of(
                "status",    "online",
                "model",     System.getenv().getOrDefault("AI_MODEL", "llama-3.3-70b-versatile"),
                "agents",    "60+ agenti + image_gen",
                "webSearch", tavily ? "enabled" : "disabled",
                "images",    hf     ? "enabled" : "disabled",
                "supabase",  supa   ? "connected" : "off",
                "date",      today()
        ));
    }
}
