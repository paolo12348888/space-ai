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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

    // ── WEB SEARCH con Tavily ─────────────────────────────────────
    private String searchWeb(String query) {
        String tavilyKey = System.getenv().getOrDefault("TAVILY_API_KEY", "");
        if (tavilyKey.isEmpty()) {
            log.warn("TAVILY_API_KEY non configurata");
            return null;
        }
        try {
            ObjectNode req = MAPPER.createObjectNode();
            req.put("api_key", tavilyKey);
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

            if (json.has("answer") && !json.path("answer").asText().isBlank()) {
                sb.append("RISPOSTA BREVE: ").append(json.path("answer").asText()).append("\n\n");
            }

            JsonNode results = json.path("results");
            if (results.isArray()) {
                sb.append("FONTI RECENTI:\n");
                int i = 1;
                for (JsonNode r : results) {
                    sb.append(i++).append(". ").append(r.path("title").asText()).append("\n");
                    String content = r.path("content").asText();
                    sb.append("   ").append(content, 0, Math.min(250, content.length())).append("\n");
                    sb.append("   URL: ").append(r.path("url").asText()).append("\n\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Tavily error: {}", e.getMessage());
            return null;
        }
    }

    // ── RILEVAMENTO WEB SEARCH ────────────────────────────────────
    private boolean needsWebSearch(String msg) {
        String q = msg.toLowerCase();
        return q.contains("cerca sul web") || q.contains("cerca online") ||
               q.contains("notizie") || q.contains("oggi") || q.contains("attuale") ||
               q.contains("adesso") || q.contains("aggiornato") || q.contains("ultimo") ||
               q.contains("prezzo") || q.contains("quotazione") || q.contains("mercato oggi") ||
               q.contains("ultime notizie") || q.contains("2026") || q.contains("2025") ||
               q.contains("valore attuale") || q.contains("come sta") || q.contains("quanto vale");
    }

    // ── CORE SYSTEM PROMPT ────────────────────────────────────────
    private String coreSystem() {
        return "Sei SPACE AI, assistente AI avanzato creato da Paolo. Data: " + today() + ". "
             + "REGOLA ASSOLUTA: Rispondi SEMPRE e SOLO alla domanda ATTUALE dell utente. "
             + "NON ripetere mai le stesse risposte. NON ignorare mai la domanda. "
             + "Se ti vengono forniti [DATI WEB IN TEMPO REALE], USALI nella risposta. "
             + "Se non hai dati in tempo reale e non c e ricerca web, dillo chiaramente. "
             + "Rispondi in italiano. Sii diretto, preciso, utile. "
             + "Usa markdown: **grassetto**, liste, ```codice```.";
    }

    // ── 60 AGENTI ─────────────────────────────────────────────────
    private String agentPrompt(String agent) {
        String d = today();
        switch (agent) {
            case "router":
                return "Sei il ROUTER di SPACE AI. Analizza SOLO la domanda attuale. "
                     + "Rispondi SOLO con JSON valido: {\"agents\":[\"code\"],\"complexity\":\"low\"} "
                     + "Agenti: code,finance,research,reasoner,math,debug,security,data,ai,cloud,"
                     + "devops,database,frontend,backend,mobile,blockchain,tech,crypto,forex,"
                     + "real_estate,accounting,tax,physics,chemistry,biology,astro,science,"
                     + "environment,energy,medical,nutrition,fitness,mental_health,psychology,"
                     + "history,philosophy,economics,politics,geography,arts,music,books,movies,"
                     + "cooking,travel,sports,gaming,writer,creative,planner,startup,hr,product,"
                     + "ux,seo,coach,education,translator,legal,summarizer. "
                     + "Scegli 1-2 agenti. SOLO JSON, zero altro testo.";
            case "code":
                return "Sei CODE di SPACE AI. Data:" + d + ". "
                     + "Rispondi SOLO alla domanda specifica. "
                     + "Esperto: Python,Java,JS,TS,Go,Rust,C++,SQL,React,Spring,FastAPI,Docker. "
                     + "Codice COMPLETO con ```. Mai troncare. Rispondi in italiano.";
            case "finance":
                return "Sei FINANCE di SPACE AI. Data:" + d + ". "
                     + "REGOLA: Rispondi SOLO alla domanda attuale dell utente. "
                     + "Se ci sono [DATI WEB IN TEMPO REALE] nel messaggio, usali. "
                     + "RSI,MACD,Bollinger,Fibonacci,P/E,DCF,VaR,Sharpe,portfolio. "
                     + "Esempi numerici. Rispondi in italiano.";
            case "crypto":
                return "Sei CRYPTO di SPACE AI. Data:" + d + ". "
                     + "Se ci sono [DATI WEB IN TEMPO REALE] usali per prezzi aggiornati. "
                     + "Bitcoin,Ethereum,DeFi,NFT,tokenomics,on-chain,Layer2. "
                     + "Analisi tecnica e fondamentale crypto. Rispondi in italiano.";
            case "research":
                return "Sei RESEARCH di SPACE AI. Data:" + d + ". "
                     + "Se ci sono [DATI WEB IN TEMPO REALE] nel messaggio, sintetizzali. "
                     + "Altrimenti fornisci info accurate dalle tue conoscenze. "
                     + "Per eventi post-2024 segnala il limite. Rispondi in italiano.";
            case "reasoner":
                return "Sei REASONER di SPACE AI. Data:" + d + ". "
                     + "Analizza la domanda ATTUALE step-by-step. "
                     + "Identifica assunzioni. Considera piu angolazioni. Rispondi in italiano.";
            case "math":
                return "Sei MATH di SPACE AI. Data:" + d + ". "
                     + "Algebra,calcolo,statistica,probabilita,ottimizzazione. "
                     + "Mostra TUTTI i passaggi. Rispondi in italiano.";
            case "debug":
                return "Sei DEBUG di SPACE AI. Data:" + d + ". "
                     + "Analizza bug, identifica root cause, soluzione completa. Rispondi in italiano.";
            case "security":
                return "Sei SECURITY di SPACE AI. Data:" + d + ". "
                     + "OWASP,crittografia,JWT,OAuth,GDPR. Solo scopi difensivi. Rispondi in italiano.";
            case "data":
                return "Sei DATA di SPACE AI. Data:" + d + ". "
                     + "Pandas,NumPy,Scikit-learn,TensorFlow,ML,DL,NLP,SQL avanzato. Rispondi in italiano.";
            case "ai":
                return "Sei AI-EXPERT di SPACE AI. Data:" + d + ". "
                     + "LLM,fine-tuning,RAG,embeddings,prompt engineering,ML ops. Rispondi in italiano.";
            case "cloud":
                return "Sei CLOUD di SPACE AI. Data:" + d + ". "
                     + "AWS,GCP,Azure,Kubernetes,Terraform,serverless. Rispondi in italiano.";
            case "devops":
                return "Sei DEVOPS di SPACE AI. Data:" + d + ". "
                     + "CI/CD,GitHub Actions,Docker,monitoring,SRE. Rispondi in italiano.";
            case "database":
                return "Sei DATABASE di SPACE AI. Data:" + d + ". "
                     + "PostgreSQL,MySQL,MongoDB,Redis,SQL ottimizzato. Rispondi in italiano.";
            case "frontend":
                return "Sei FRONTEND di SPACE AI. Data:" + d + ". "
                     + "React,Vue,HTML5,CSS3,Tailwind,performance. Rispondi in italiano.";
            case "backend":
                return "Sei BACKEND di SPACE AI. Data:" + d + ". "
                     + "API REST,GraphQL,Spring Boot,FastAPI,Node.js. Rispondi in italiano.";
            case "mobile":
                return "Sei MOBILE di SPACE AI. Data:" + d + ". "
                     + "React Native,Flutter,Swift,Kotlin,iOS,Android. Rispondi in italiano.";
            case "blockchain":
                return "Sei BLOCKCHAIN di SPACE AI. Data:" + d + ". "
                     + "Ethereum,Solidity,Web3,DeFi,smart contract. Rispondi in italiano.";
            case "tech":
                return "Sei TECH di SPACE AI. Data:" + d + ". "
                     + "Hardware,reti,IoT,embedded,tecnologie emergenti. Rispondi in italiano.";
            case "forex":
                return "Sei FOREX di SPACE AI. Data:" + d + ". "
                     + "Valute,carry trade,banche centrali,macroeconomia FX. Rispondi in italiano.";
            case "real_estate":
                return "Sei REAL_ESTATE di SPACE AI. Data:" + d + ". "
                     + "Investimenti immobiliari,ROI,REIT,mercato italiano. Rispondi in italiano.";
            case "accounting":
                return "Sei ACCOUNTING di SPACE AI. Data:" + d + ". "
                     + "Bilancio,conto economico,cash flow,IAS/IFRS. Rispondi in italiano.";
            case "tax":
                return "Sei TAX di SPACE AI. Data:" + d + ". "
                     + "Fiscalita italiana,IVA,IRPEF,crypto tasse. Info generali. Rispondi in italiano.";
            case "physics":
                return "Sei PHYSICS di SPACE AI. Data:" + d + ". "
                     + "Meccanica,quantistica,relativita,termodinamica. Rispondi in italiano.";
            case "chemistry":
                return "Sei CHEMISTRY di SPACE AI. Data:" + d + ". "
                     + "Chimica organica,inorganica,reazioni,legami. Rispondi in italiano.";
            case "biology":
                return "Sei BIOLOGY di SPACE AI. Data:" + d + ". "
                     + "Biologia molecolare,genetica,CRISPR,evoluzione. Rispondi in italiano.";
            case "astro":
                return "Sei ASTRO di SPACE AI. Data:" + d + ". "
                     + "Astronomia,cosmologia,esopianeti,universo. Rispondi in italiano.";
            case "science":
                return "Sei SCIENCE di SPACE AI. Data:" + d + ". "
                     + "Metodo scientifico,neuroscienza,climatologia. Rispondi in italiano.";
            case "environment":
                return "Sei ENVIRONMENT di SPACE AI. Data:" + d + ". "
                     + "Clima,sostenibilita,energie rinnovabili. Rispondi in italiano.";
            case "energy":
                return "Sei ENERGY di SPACE AI. Data:" + d + ". "
                     + "Solare,eolico,nucleare,idrogeno,storage. Rispondi in italiano.";
            case "medical":
                return "Sei MEDICAL di SPACE AI. Data:" + d + ". "
                     + "Anatomia,farmacologia,prevenzione. Solo info generali. Rispondi in italiano.";
            case "nutrition":
                return "Sei NUTRITION di SPACE AI. Data:" + d + ". "
                     + "Diete,macronutrienti,meal planning. Rispondi in italiano.";
            case "fitness":
                return "Sei FITNESS di SPACE AI. Data:" + d + ". "
                     + "Allenamento,schede,ipertrofia,cardio. Rispondi in italiano.";
            case "mental_health":
                return "Sei MENTAL_HEALTH di SPACE AI. Data:" + d + ". "
                     + "Mindfulness,stress,ansia,CBT. Solo supporto informativo. Rispondi in italiano.";
            case "psychology":
                return "Sei PSYCHOLOGY di SPACE AI. Data:" + d + ". "
                     + "Psicologia cognitiva,bias,decision making. Rispondi in italiano.";
            case "history":
                return "Sei HISTORY di SPACE AI. Data:" + d + ". "
                     + "Storia mondiale,italiana,europea. Cause e conseguenze. Rispondi in italiano.";
            case "philosophy":
                return "Sei PHILOSOPHY di SPACE AI. Data:" + d + ". "
                     + "Etica,epistemologia,AI ethics. Piu prospettive. Rispondi in italiano.";
            case "economics":
                return "Sei ECONOMICS di SPACE AI. Data:" + d + ". "
                     + "Micro e macro,teoria dei giochi,politiche fiscali. Rispondi in italiano.";
            case "politics":
                return "Sei POLITICS di SPACE AI. Data:" + d + ". "
                     + "Sistemi politici,geopolitica. Neutrale. Rispondi in italiano.";
            case "geography":
                return "Sei GEOGRAPHY di SPACE AI. Data:" + d + ". "
                     + "Geografia fisica e umana,paesi e culture. Rispondi in italiano.";
            case "arts":
                return "Sei ARTS di SPACE AI. Data:" + d + ". "
                     + "Arte,pittura,architettura,fotografia. Rispondi in italiano.";
            case "music":
                return "Sei MUSIC di SPACE AI. Data:" + d + ". "
                     + "Teoria musicale,strumenti,produzione. Rispondi in italiano.";
            case "books":
                return "Sei BOOKS di SPACE AI. Data:" + d + ". "
                     + "Letteratura,consigli lettura,analisi testi. Rispondi in italiano.";
            case "movies":
                return "Sei MOVIES di SPACE AI. Data:" + d + ". "
                     + "Cinema,serie TV,analisi critica. Rispondi in italiano.";
            case "cooking":
                return "Sei COOKING di SPACE AI. Data:" + d + ". "
                     + "Cucina italiana e mondiale,ricette con dosi precise. Rispondi in italiano.";
            case "travel":
                return "Sei TRAVEL di SPACE AI. Data:" + d + ". "
                     + "Destinazioni,itinerari,budget travel. Rispondi in italiano.";
            case "sports":
                return "Sei SPORTS di SPACE AI. Data:" + d + ". "
                     + "Sport,calcio,tennis,NBA,F1,tattica. Rispondi in italiano.";
            case "gaming":
                return "Sei GAMING di SPACE AI. Data:" + d + ". "
                     + "Videogiochi,strategie,esports,game design. Rispondi in italiano.";
            case "writer":
                return "Sei WRITER di SPACE AI. Data:" + d + ". "
                     + "Email,report,articoli,storytelling,copywriting. Rispondi in italiano.";
            case "creative":
                return "Sei CREATIVE di SPACE AI. Data:" + d + ". "
                     + "Brainstorming,naming,slogan,design thinking. Originale! Rispondi in italiano.";
            case "planner":
                return "Sei PLANNER di SPACE AI. Data:" + d + ". "
                     + "Project management,Agile,OKR,roadmap,SWOT. Rispondi in italiano.";
            case "startup":
                return "Sei STARTUP di SPACE AI. Data:" + d + ". "
                     + "Business model,pitch,fundraising,MVP,growth. Rispondi in italiano.";
            case "hr":
                return "Sei HR di SPACE AI. Data:" + d + ". "
                     + "Recruiting,colloqui,cultura aziendale,carriera. Rispondi in italiano.";
            case "product":
                return "Sei PRODUCT di SPACE AI. Data:" + d + ". "
                     + "Product management,roadmap,user story,metriche. Rispondi in italiano.";
            case "ux":
                return "Sei UX di SPACE AI. Data:" + d + ". "
                     + "UX/UI,user research,Figma,accessibilita. Rispondi in italiano.";
            case "seo":
                return "Sei SEO di SPACE AI. Data:" + d + ". "
                     + "SEO,keyword,Google Analytics,content marketing. Rispondi in italiano.";
            case "coach":
                return "Sei COACH di SPACE AI. Data:" + d + ". "
                     + "Produttivita,mindset,habit,leadership. Rispondi in italiano.";
            case "education":
                return "Sei EDUCATION di SPACE AI. Data:" + d + ". "
                     + "Pedagogia,apprendimento,spaced repetition. Rispondi in italiano.";
            case "translator":
                return "Sei TRANSLATOR di SPACE AI. "
                     + "Multilingua: IT,EN,FR,ES,DE,PT,ZH,JA,AR,RU. Mantieni tono originale.";
            case "legal":
                return "Sei LEGAL di SPACE AI. Data:" + d + ". "
                     + "Diritto italiano,GDPR,contratti,IP. Solo info generali. Rispondi in italiano.";
            case "summarizer":
                return "Sei SUMMARIZER di SPACE AI. Sintetizza in bullet points chiari. Rispondi in italiano.";
            default:
                return coreSystem();
        }
    }

    private String synthesizerPrompt() {
        return "Sei il SYNTHESIZER di SPACE AI. Data:" + today() + ". "
             + "Unifica gli output in UNA risposta finale coerente. "
             + "Elimina ridondanze. Usa markdown. MAI === o ---. "
             + "Rispondi SEMPRE alla domanda originale. Rispondi in italiano.";
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
            // 1. Storico
            List<Map<String, String>> history = new ArrayList<>();
            if (!supabaseUrl.isEmpty() && !supabaseKey.isEmpty()) {
                history = loadHistory(sessionId, supabaseUrl, supabaseKey);
            }

            // 2. Web search se necessario
            String webData = null;
            if (needsWebSearch(userMessage)) {
                log.info("Web search per: {}", userMessage.substring(0, Math.min(40, userMessage.length())));
                webData = searchWeb(userMessage);
            }

            // 3. Arricchisci il messaggio con i dati web
            String enrichedMessage = userMessage;
            if (webData != null && !webData.isBlank()) {
                enrichedMessage = userMessage + "\n\n[DATI WEB IN TEMPO REALE - " + today() + "]:\n" + webData;
            }

            // 4. Router — SOLO messaggio corrente, NO storico
            List<String> agents = routeQuery(userMessage, baseUrl, apiKey, model);
            log.info("Agenti: {} | WebSearch: {}", agents, webData != null);

            // 5. Agenti con messaggio arricchito e storico
            List<String> outputs = new ArrayList<>();
            for (String agent : agents) {
                String out = callLLM(agentPrompt(agent), enrichedMessage, history,
                                     baseUrl, apiKey, model, 2500);
                if (out != null && !out.isBlank()) {
                    outputs.add("[" + agent.toUpperCase() + "]\n" + out);
                }
            }

            // 6. Risposta finale
            String finalResponse;
            if (outputs.isEmpty()) {
                finalResponse = callLLM(coreSystem(), enrichedMessage, history,
                                        baseUrl, apiKey, model, 2000);
            } else if (outputs.size() == 1) {
                finalResponse = outputs.get(0).replaceFirst("\\[\\w+\\]\\n", "");
            } else {
                String combined = String.join("\n\n", outputs);
                String synthMsg = "Domanda: " + userMessage + "\n\nOutput:\n\n" + combined;
                finalResponse = callLLM(synthesizerPrompt(), synthMsg,
                                        new ArrayList<>(), baseUrl, apiKey, model, 3000);
            }

            // 7. Salva
            if (!supabaseUrl.isEmpty()) {
                try {
                    saveMessage(sessionId, "user",      userMessage,   supabaseUrl, supabaseKey);
                    saveMessage(sessionId, "assistant", finalResponse, supabaseUrl, supabaseKey);
                } catch (Exception e) {
                    log.warn("Supabase: {}", e.getMessage());
                }
            }

            return ResponseEntity.ok(Map.of(
                    "response",   finalResponse,
                    "status",     "ok",
                    "model",      model,
                    "agents",     agents.toString(),
                    "webSearch",  webData != null ? "true" : "false",
                    "sessionId",  sessionId
            ));

        } catch (Exception e) {
            log.error("Errore: {}", e.getMessage());
            try {
                String fallback = callLLM(coreSystem(), userMessage,
                                          new ArrayList<>(), baseUrl, apiKey, model, 2000);
                return ResponseEntity.ok(Map.of("response", fallback,
                        "status", "ok_fallback", "sessionId", sessionId));
            } catch (Exception e2) {
                return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
            }
        }
    }

    // ── ROUTER ────────────────────────────────────────────────────
    private List<String> routeQuery(String query, String baseUrl, String apiKey, String model) {
        try {
            String resp = callLLM(agentPrompt("router"),
                    "DOMANDA: " + query,
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
            log.warn("Router LLM fallito: {}", e.getMessage());
        }

        // Keyword fallback
        String q = query.toLowerCase();
        if (q.contains("python") || q.contains("java") || q.contains("codice") ||
            q.contains("javascript") || q.contains("programma") || q.contains("script") ||
            q.contains("funzione") || q.contains("sql") || q.contains("scrivi codice")) {
            return List.of("code");
        }
        if (q.contains("grafico") || q.contains("chart") || q.contains("plot") ||
            q.contains("visualizza") || q.contains("disegna grafic")) {
            return List.of("data", "code");
        }
        if (q.contains("bitcoin") || q.contains("ethereum") || q.contains("crypto") ||
            q.contains("cripto") || q.contains("nft") || q.contains("defi")) {
            return List.of("crypto");
        }
        if (q.contains("borsa") || q.contains("azioni") || q.contains("trading") ||
            q.contains("investimento") || q.contains("rsi") || q.contains("macd") ||
            q.contains("finanza") || q.contains("mercato") || q.contains("portafoglio")) {
            return List.of("finance");
        }
        if (q.contains("traduci") || q.contains("in inglese") || q.contains("in francese") ||
            q.contains("in tedesco") || q.contains("in spagnolo")) {
            return List.of("translator");
        }
        if (q.contains("matematica") || q.contains("calcola") || q.contains("equazione") ||
            q.contains("integrale") || q.contains("statistica") || q.contains("probabilita")) {
            return List.of("math");
        }
        if (q.contains("bug") || q.contains("errore nel codice") || q.contains("non funziona")) {
            return List.of("debug");
        }
        if (q.contains("legge") || q.contains("contratto") || q.contains("gdpr") ||
            q.contains("avvocato") || q.contains("diritto")) {
            return List.of("legal");
        }
        if (q.contains("ricetta") || q.contains("cucina") || q.contains("cucinare")) {
            return List.of("cooking");
        }
        if (q.contains("viaggio") || q.contains("vacanza") || q.contains("dove visitare")) {
            return List.of("travel");
        }
        if (q.contains("allenamento") || q.contains("palestra") || q.contains("muscoli")) {
            return List.of("fitness");
        }
        if (q.contains("dieta") || q.contains("nutrizione") || q.contains("calorie")) {
            return List.of("nutrition");
        }
        if (q.contains("storia") || q.contains("guerra") || q.contains("storico")) {
            return List.of("history");
        }
        if (q.contains("film") || q.contains("serie tv") || q.contains("cinema")) {
            return List.of("movies");
        }
        if (q.contains("musica") || q.contains("canzone") || q.contains("chitarra")) {
            return List.of("music");
        }
        if (q.contains("email") || q.contains("articolo") || q.contains("testo") ||
            q.contains("scrivi un")) {
            return List.of("writer");
        }
        if (q.contains("startup") || q.contains("business plan") || q.contains("azienda")) {
            return List.of("startup");
        }
        if (q.contains("notizie") || q.contains("news") || q.contains("ultime")) {
            return List.of("research");
        }

        return List.of("reasoner");
    }

    // ── CHIAMATA LLM ──────────────────────────────────────────────
    private String callLLM(String systemPrompt, String userMessage,
                            List<Map<String, String>> history,
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
        sys.put("role", "system");
        sys.put("content", systemPrompt);
        messages.add(sys);

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
    private List<Map<String, String>> loadHistory(String sessionId, String url, String key) {
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
        boolean tavily = !System.getenv().getOrDefault("TAVILY_API_KEY", "").isEmpty();
        boolean supabase = !System.getenv().getOrDefault("SUPABASE_URL", "").isEmpty();
        return ResponseEntity.ok(Map.of(
                "status",    "online",
                "service",   "SPACE AI 360",
                "model",     System.getenv().getOrDefault("AI_MODEL", "llama-3.3-70b-versatile"),
                "agents",    "60 agenti",
                "webSearch", tavily ? "enabled" : "disabled - aggiungi TAVILY_API_KEY",
                "supabase",  supabase ? "connected" : "off",
                "date",      today()
        ));
    }
}
