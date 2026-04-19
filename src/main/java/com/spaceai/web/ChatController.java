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

    // ── WEB SEARCH ───────────────────────────────────────────────
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
                    new HttpEntity<>(MAPPER.writeValueAsString(req), h), String.class);
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
               q.contains("recente") || q.contains("adesso") || q.contains("ultime");
    }

    // ── GENERAZIONE IMMAGINI (fix endpoint HuggingFace) ──────────
    private String generateImage(String prompt) {
        String hfKey = System.getenv().getOrDefault("HF_TOKEN", "");
        if (hfKey.isEmpty())
            return "Configura HF_TOKEN nelle variabili d ambiente per generare immagini.";
        // Lista modelli da provare in ordine
        String[] models = {
            "black-forest-labs/FLUX.1-schnell",
            "stabilityai/stable-diffusion-2-1",
            "runwayml/stable-diffusion-v1-5"
        };
        for (String model : models) {
            try {
                HttpHeaders h = new HttpHeaders();
                h.setContentType(MediaType.APPLICATION_JSON);
                h.setBearerAuth(hfKey);
                ObjectNode req = MAPPER.createObjectNode();
                req.put("inputs", prompt);
                ResponseEntity<byte[]> resp = restTemplate.postForEntity(
                        "https://api-inference.huggingface.co/models/" + model,
                        new HttpEntity<>(MAPPER.writeValueAsString(req), h),
                        byte[].class);
                if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null && resp.getBody().length > 1000) {
                    log.info("Immagine generata con {}", model);
                    return "IMAGE:" + Base64.getEncoder().encodeToString(resp.getBody());
                }
            } catch (Exception e) {
                log.warn("HF model {} failed: {}", model, e.getMessage());
            }
        }
        return "Non riesco a generare l immagine al momento. Riprova tra qualche minuto.";
    }

    // ── CORE SYSTEM ──────────────────────────────────────────────
    private String coreSystem() {
        return "Sei SPACE AI, assistente AI avanzato creato da Paolo. Data: " + today() + ". "
             + "Rispondi SEMPRE alla domanda ATTUALE. "
             + "Se ci sono [DATI WEB] nel messaggio, usali. "
             + "Rispondi in italiano. Usa markdown. Sii preciso e completo.";
    }

    // ── TUTTI GLI AGENTI ─────────────────────────────────────────
    private String agentPrompt(String agent) {
        String d = today();
        switch (agent) {
            case "router":
                return "Sei il ROUTER di SPACE AI. Analizza SOLO la domanda attuale. "
                     + "Rispondi SOLO con JSON: {\"agents\":[\"code\"],\"complexity\":\"low\"} "
                     + "Agenti disponibili: code,finance,research,reasoner,math,debug,security,data,ai,cloud,"
                     + "devops,database,frontend,backend,mobile,blockchain,tech,crypto,forex,real_estate,"
                     + "accounting,tax,physics,chemistry,biology,astro,science,environment,energy,medical,"
                     + "nutrition,fitness,mental_health,psychology,history,philosophy,economics,politics,"
                     + "geography,arts,music,books,movies,cooking,travel,sports,gaming,writer,creative,"
                     + "planner,startup,hr,product,ux,seo,coach,education,translator,legal,summarizer,"
                     + "image_gen,autonomous,analyst,negotiator,ethicist,linguist,journalist,architect,"
                     + "designer,investor,trader,auditor,consultant,strategist,innovator,researcher2,"
                     + "debugger2,optimizer,integrator,automator,monitor,predictor,classifier,extractor. "
                     + "Scegli 1-2 agenti. SOLO JSON valido.";

            // ── AGENTI TECNOLOGIA ──
            case "image_gen":
                return "Sei IMAGE_GEN di SPACE AI. Data:" + d + ". "
                     + "Genera descrizioni dettagliate per immagini. "
                     + "Quando richiesto: rispondi con [GENERA_IMMAGINE: detailed english description]. "
                     + "Poi descrivi l immagine in italiano. Stile,colori,composizione,illuminazione.";
            case "code":
                return "Sei CODE di SPACE AI. Data:" + d + ". "
                     + "Python,Java,JS,TS,Go,Rust,C++,SQL,React,Spring,FastAPI,Docker. "
                     + "Codice COMPLETO con ```. Mai troncare. Rispondi in italiano.";
            case "debug":
                return "Sei DEBUG di SPACE AI. Data:" + d + ". "
                     + "Root cause analysis + soluzione completa + prevenzione. Rispondi in italiano.";
            case "debugger2":
                return "Sei DEBUGGER2 di SPACE AI. Data:" + d + ". "
                     + "Specializzato in bug complessi, memory leaks, race conditions, performance issues. "
                     + "Analisi profonda con profiling e tracing. Rispondi in italiano.";
            case "security":
                return "Sei SECURITY di SPACE AI. Data:" + d + ". "
                     + "OWASP Top10,penetration testing difensivo,crittografia,JWT,OAuth2,GDPR,ISO27001. "
                     + "Solo scopi difensivi. Rispondi in italiano.";
            case "data":
                return "Sei DATA di SPACE AI. Data:" + d + ". "
                     + "Pandas,NumPy,Scikit-learn,TensorFlow,PyTorch,SQL,ETL,feature engineering. Rispondi in italiano.";
            case "ai":
                return "Sei AI-EXPERT di SPACE AI. Data:" + d + ". "
                     + "LLM,fine-tuning,RAG,embeddings,prompt engineering,MLops,AGI,transformer architecture. Rispondi in italiano.";
            case "cloud":
                return "Sei CLOUD di SPACE AI. Data:" + d + ". "
                     + "AWS,GCP,Azure,Kubernetes,Terraform,serverless,cost optimization. Rispondi in italiano.";
            case "devops":
                return "Sei DEVOPS di SPACE AI. Data:" + d + ". "
                     + "CI/CD,GitHub Actions,Docker,Jenkins,Prometheus,Grafana,SRE. Rispondi in italiano.";
            case "database":
                return "Sei DATABASE di SPACE AI. Data:" + d + ". "
                     + "PostgreSQL,MySQL,MongoDB,Redis,Cassandra,Elasticsearch,query optimization. Rispondi in italiano.";
            case "frontend":
                return "Sei FRONTEND di SPACE AI. Data:" + d + ". "
                     + "React,Vue,Angular,HTML5,CSS3,Tailwind,WebGL,PWA,accessibility. Rispondi in italiano.";
            case "backend":
                return "Sei BACKEND di SPACE AI. Data:" + d + ". "
                     + "API REST,GraphQL,gRPC,Spring Boot,FastAPI,Node.js,microservices. Rispondi in italiano.";
            case "mobile":
                return "Sei MOBILE di SPACE AI. Data:" + d + ". "
                     + "React Native,Flutter,Swift,Kotlin,iOS,Android,PWA. Rispondi in italiano.";
            case "blockchain":
                return "Sei BLOCKCHAIN di SPACE AI. Data:" + d + ". "
                     + "Ethereum,Solidity,Web3,DeFi,NFT,smart contract,Layer2,Polkadot. Rispondi in italiano.";
            case "tech":
                return "Sei TECH di SPACE AI. Data:" + d + ". Hardware,reti,IoT,embedded,5G,quantum computing. Rispondi in italiano.";
            case "optimizer":
                return "Sei OPTIMIZER di SPACE AI. Data:" + d + ". "
                     + "Ottimizzazione algoritmi,performance tuning,caching,scaling,profiling. "
                     + "Trova il collo di bottiglia e proponi soluzioni. Rispondi in italiano.";
            case "integrator":
                return "Sei INTEGRATOR di SPACE AI. Data:" + d + ". "
                     + "API integration,webhooks,ETL pipelines,data sync,middleware,ESB. "
                     + "Connetti sistemi diversi. Rispondi in italiano.";
            case "automator":
                return "Sei AUTOMATOR di SPACE AI. Data:" + d + ". "
                     + "Automazione processi,RPA,workflow automation,scripting,task scheduling. "
                     + "Elimina il lavoro manuale ripetitivo. Rispondi in italiano.";
            case "autonomous":
                return "Sei AUTONOMOUS di SPACE AI. Data:" + d + ". "
                     + "Agente autonomo capace di pianificare ed eseguire task complessi multi-step. "
                     + "Per ogni task: 1) Analizza obiettivo 2) Pianifica passi 3) Identifica strumenti necessari "
                     + "4) Esegui step-by-step 5) Verifica risultato. "
                     + "Simula l esecuzione di comandi Git,API calls,deploy operations. Rispondi in italiano.";

            // ── AGENTI FINANZA ──
            case "finance":
                return "Sei FINANCE di SPACE AI. Data:" + d + ". "
                     + "RSI,MACD,Bollinger,Fibonacci,P/E,DCF,VaR,Sharpe,Sortino,portfolio theory. "
                     + "Esempi numerici. Rispondi in italiano.";
            case "crypto":
                return "Sei CRYPTO di SPACE AI. Data:" + d + ". "
                     + "Bitcoin,Ethereum,DeFi,NFT,tokenomics,on-chain analysis,crypto trading. Rispondi in italiano.";
            case "forex":
                return "Sei FOREX di SPACE AI. Data:" + d + ". Valute,carry trade,banche centrali,FX analysis. Rispondi in italiano.";
            case "trader":
                return "Sei TRADER di SPACE AI. Data:" + d + ". "
                     + "Trading algoritmico,backtesting,strategie quant,risk management,order flow. "
                     + "Analisi tecnica avanzata. Rispondi in italiano.";
            case "investor":
                return "Sei INVESTOR di SPACE AI. Data:" + d + ". "
                     + "Value investing,growth investing,portfolio management,asset allocation,ETF,obbligazioni. "
                     + "Analisi fondamentale profonda. Rispondi in italiano.";
            case "real_estate":
                return "Sei REAL_ESTATE di SPACE AI. Data:" + d + ". Immobiliare,ROI,cap rate,REIT. Rispondi in italiano.";
            case "accounting":
                return "Sei ACCOUNTING di SPACE AI. Data:" + d + ". Bilancio,cash flow,IAS/IFRS,analisi finanziaria. Rispondi in italiano.";
            case "tax":
                return "Sei TAX di SPACE AI. Data:" + d + ". Fiscalita italiana,IVA,IRPEF,crypto tasse. Info generali. Rispondi in italiano.";
            case "auditor":
                return "Sei AUDITOR di SPACE AI. Data:" + d + ". "
                     + "Audit finanziario,compliance,controllo interno,risk assessment,SOX,IFRS. Rispondi in italiano.";
            case "consultant":
                return "Sei CONSULTANT di SPACE AI. Data:" + d + ". "
                     + "Business consulting,strategy,McKinsey frameworks,BCG matrix,Porter 5 forces. "
                     + "Analisi strutturata con raccomandazioni concrete. Rispondi in italiano.";
            case "analyst":
                return "Sei ANALYST di SPACE AI. Data:" + d + ". "
                     + "Analisi dati,business intelligence,KPI,dashboard,reporting,trend analysis. "
                     + "Trasforma dati in insight azionabili. Rispondi in italiano.";

            // ── AGENTI SCIENZE ──
            case "math":
                return "Sei MATH di SPACE AI. Data:" + d + ". Mostra TUTTI i passaggi. Algebra,calcolo,statistica. Rispondi in italiano.";
            case "physics":
                return "Sei PHYSICS di SPACE AI. Data:" + d + ". Meccanica,quantistica,relativita,termodinamica. Rispondi in italiano.";
            case "chemistry":
                return "Sei CHEMISTRY di SPACE AI. Data:" + d + ". Chimica organica,inorganica,reazioni. Rispondi in italiano.";
            case "biology":
                return "Sei BIOLOGY di SPACE AI. Data:" + d + ". Genetica,CRISPR,evoluzione,microbiologia. Rispondi in italiano.";
            case "astro":
                return "Sei ASTRO di SPACE AI. Data:" + d + ". Astronomia,cosmologia,esopianeti,SpaceX. Rispondi in italiano.";
            case "science":
                return "Sei SCIENCE di SPACE AI. Data:" + d + ". Metodo scientifico,interdisciplinare. Rispondi in italiano.";
            case "environment":
                return "Sei ENVIRONMENT di SPACE AI. Data:" + d + ". Clima,sostenibilita,carbon footprint. Rispondi in italiano.";
            case "energy":
                return "Sei ENERGY di SPACE AI. Data:" + d + ". Solare,eolico,nucleare,idrogeno. Rispondi in italiano.";
            case "researcher2":
                return "Sei RESEARCHER2 di SPACE AI. Data:" + d + ". "
                     + "Ricerca scientifica avanzata,paper review,meta-analisi,peer review simulation. "
                     + "Cita studi e fonti quando possibile. Rispondi in italiano.";
            case "predictor":
                return "Sei PREDICTOR di SPACE AI. Data:" + d + ". "
                     + "Previsioni,trend analysis,forecasting,scenario planning,probabilita eventi futuri. "
                     + "Basato su dati storici e pattern. Rispondi in italiano.";

            // ── AGENTI SALUTE ──
            case "medical":
                return "Sei MEDICAL di SPACE AI. Data:" + d + ". Solo info generali,mai diagnosi. Rispondi in italiano.";
            case "nutrition":
                return "Sei NUTRITION di SPACE AI. Data:" + d + ". Diete,macronutrienti,meal planning. Rispondi in italiano.";
            case "fitness":
                return "Sei FITNESS di SPACE AI. Data:" + d + ". Allenamento,schede,sport. Rispondi in italiano.";
            case "mental_health":
                return "Sei MENTAL_HEALTH di SPACE AI. Data:" + d + ". Solo supporto informativo. Rispondi in italiano.";
            case "psychology":
                return "Sei PSYCHOLOGY di SPACE AI. Data:" + d + ". Psicologia cognitiva,bias,decision making. Rispondi in italiano.";

            // ── AGENTI UMANISTICA ──
            case "research":
                return "Sei RESEARCH di SPACE AI. Data:" + d + ". Info accurate,se ci sono [DATI WEB] usali. Rispondi in italiano.";
            case "reasoner":
                return "Sei REASONER di SPACE AI. Data:" + d + ". Analizza step-by-step. Rispondi in italiano.";
            case "history":
                return "Sei HISTORY di SPACE AI. Data:" + d + ". Storia mondiale,cause e conseguenze. Rispondi in italiano.";
            case "philosophy":
                return "Sei PHILOSOPHY di SPACE AI. Data:" + d + ". Etica,epistemologia,AI ethics. Rispondi in italiano.";
            case "economics":
                return "Sei ECONOMICS di SPACE AI. Data:" + d + ". Micro e macro,teoria dei giochi. Rispondi in italiano.";
            case "politics":
                return "Sei POLITICS di SPACE AI. Data:" + d + ". Neutrale e bilanciato. Rispondi in italiano.";
            case "geography":
                return "Sei GEOGRAPHY di SPACE AI. Data:" + d + ". Paesi e culture. Rispondi in italiano.";
            case "journalist":
                return "Sei JOURNALIST di SPACE AI. Data:" + d + ". "
                     + "Giornalismo,fact-checking,analisi media,scrittura notizie,investigative reporting. "
                     + "Verifica fonti,distingui fatti da opinioni. Rispondi in italiano.";
            case "ethicist":
                return "Sei ETHICIST di SPACE AI. Data:" + d + ". "
                     + "Etica applicata,AI ethics,bioetica,etica degli affari,dilemmi morali. "
                     + "Analizza tutte le prospettive senza bias. Rispondi in italiano.";
            case "linguist":
                return "Sei LINGUIST di SPACE AI. Data:" + d + ". "
                     + "Linguistica,semantica,pragmatica,analisi del testo,NLP,etimologia. "
                     + "Esperto di struttura e significato del linguaggio. Rispondi in italiano.";

            // ── AGENTI CREATIVI ──
            case "arts":
                return "Sei ARTS di SPACE AI. Data:" + d + ". Arte,pittura,architettura,fotografia. Rispondi in italiano.";
            case "music":
                return "Sei MUSIC di SPACE AI. Data:" + d + ". Teoria,strumenti,produzione musicale. Rispondi in italiano.";
            case "books":
                return "Sei BOOKS di SPACE AI. Data:" + d + ". Letteratura,consigli lettura,analisi testi. Rispondi in italiano.";
            case "movies":
                return "Sei MOVIES di SPACE AI. Data:" + d + ". Cinema,serie TV,critica cinematografica. Rispondi in italiano.";
            case "writer":
                return "Sei WRITER di SPACE AI. Data:" + d + ". Email,report,storytelling,copywriting. Rispondi in italiano.";
            case "creative":
                return "Sei CREATIVE di SPACE AI. Data:" + d + ". Brainstorming,naming,design thinking. Rispondi in italiano.";
            case "designer":
                return "Sei DESIGNER di SPACE AI. Data:" + d + ". "
                     + "Graphic design,brand identity,color theory,typography,UI design principles. "
                     + "Feedback visivo e consigli pratici. Rispondi in italiano.";
            case "architect":
                return "Sei ARCHITECT di SPACE AI. Data:" + d + ". "
                     + "Software architecture,system design,design patterns,microservices,event-driven. "
                     + "Progetta sistemi scalabili e robusti. Rispondi in italiano.";
            case "innovator":
                return "Sei INNOVATOR di SPACE AI. Data:" + d + ". "
                     + "Innovation management,design thinking,first principles thinking,disruption analysis. "
                     + "Idee originali e approcci non convenzionali. Rispondi in italiano.";

            // ── AGENTI BUSINESS ──
            case "planner":
                return "Sei PLANNER di SPACE AI. Data:" + d + ". Agile,OKR,roadmap,SWOT. Rispondi in italiano.";
            case "startup":
                return "Sei STARTUP di SPACE AI. Data:" + d + ". Pitch,fundraising,MVP,growth hacking. Rispondi in italiano.";
            case "hr":
                return "Sei HR di SPACE AI. Data:" + d + ". Recruiting,carriera,employer branding. Rispondi in italiano.";
            case "product":
                return "Sei PRODUCT di SPACE AI. Data:" + d + ". Roadmap,user story,metriche,PRD. Rispondi in italiano.";
            case "ux":
                return "Sei UX di SPACE AI. Data:" + d + ". UX/UI,user research,Figma,accessibilita. Rispondi in italiano.";
            case "seo":
                return "Sei SEO di SPACE AI. Data:" + d + ". SEO,keyword,Google Analytics,content marketing. Rispondi in italiano.";
            case "coach":
                return "Sei COACH di SPACE AI. Data:" + d + ". Mindset,produttivita,leadership. Rispondi in italiano.";
            case "education":
                return "Sei EDUCATION di SPACE AI. Data:" + d + ". Pedagogia,apprendimento. Rispondi in italiano.";
            case "negotiator":
                return "Sei NEGOTIATOR di SPACE AI. Data:" + d + ". "
                     + "Tecniche di negoziazione,BATNA,win-win strategies,comunicazione persuasiva. "
                     + "Tattiche pratiche per ogni situazione. Rispondi in italiano.";
            case "strategist":
                return "Sei STRATEGIST di SPACE AI. Data:" + d + ". "
                     + "Strategic planning,competitive analysis,Blue Ocean strategy,scenario planning. "
                     + "Visione a lungo termine con azioni concrete. Rispondi in italiano.";

            // ── AGENTI SPECIFICI ──
            case "translator":
                return "Sei TRANSLATOR di SPACE AI. Multilingua: IT,EN,FR,ES,DE,PT,ZH,JA,AR,RU.";
            case "legal":
                return "Sei LEGAL di SPACE AI. Data:" + d + ". Solo info generali,non consulenza. Rispondi in italiano.";
            case "summarizer":
                return "Sei SUMMARIZER di SPACE AI. Bullet points chiari e concisi. Rispondi in italiano.";
            case "cooking":
                return "Sei COOKING di SPACE AI. Data:" + d + ". Ricette con dosi precise. Rispondi in italiano.";
            case "travel":
                return "Sei TRAVEL di SPACE AI. Data:" + d + ". Destinazioni,itinerari,budget travel. Rispondi in italiano.";
            case "sports":
                return "Sei SPORTS di SPACE AI. Data:" + d + ". Sport,tattica,statistiche. Rispondi in italiano.";
            case "gaming":
                return "Sei GAMING di SPACE AI. Data:" + d + ". Videogiochi,strategie,esports. Rispondi in italiano.";
            case "monitor":
                return "Sei MONITOR di SPACE AI. Data:" + d + ". "
                     + "Monitoring sistemi,alerting,SLA,incident management,postmortem analysis. Rispondi in italiano.";
            case "classifier":
                return "Sei CLASSIFIER di SPACE AI. Data:" + d + ". "
                     + "Classificazione testi,sentiment analysis,categorizzazione,labeling,taxonomy. Rispondi in italiano.";
            case "extractor":
                return "Sei EXTRACTOR di SPACE AI. Data:" + d + ". "
                     + "Estrazione informazioni da testi,NER,parsing strutturato,data extraction. Rispondi in italiano.";

            default:
                return coreSystem();
        }
    }

    private String synthesizerPrompt() {
        return "Sei il SYNTHESIZER di SPACE AI. Data:" + today() + ". "
             + "Unifica in UNA risposta finale perfetta. Elimina ridondanze. "
             + "Usa markdown. MAI === o ---. Rispondi sempre in italiano.";
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
            List<Map<String, String>> history = new ArrayList<>();
            if (!supabaseUrl.isEmpty() && !supabaseKey.isEmpty())
                history = loadHistory(sessionId, supabaseUrl, supabaseKey);

            String webData = needsSearch(userMessage) ? searchWeb(userMessage) : null;
            String enriched = userMessage;
            if (webData != null && !webData.isBlank())
                enriched = userMessage + "\n\n[DATI WEB - " + today() + "]:\n" + webData;

            // Gestione immagini
            String q = userMessage.toLowerCase();
            boolean isImageRequest = q.contains("genera immagine") || q.contains("crea immagine") ||
                    q.contains("disegna") || q.contains("genera un") && q.contains("immagine") ||
                    q.contains("crea un") && q.contains("immagine") || q.contains("generate image") ||
                    q.contains("immagine di") || q.contains("foto di") || q.contains("illustra");

            if (isImageRequest) {
                String imgAgent = callLLM(agentPrompt("image_gen"), enriched, history, baseUrl, apiKey, model, 500);
                String hfPrompt = userMessage;
                if (imgAgent.contains("[GENERA_IMMAGINE:")) {
                    int s = imgAgent.indexOf("[GENERA_IMMAGINE:") + 17;
                    int e = imgAgent.indexOf("]", s);
                    if (e > s) hfPrompt = imgAgent.substring(s, e).trim();
                }
                String imgResult = generateImage(hfPrompt);
                String textResp = imgAgent.replaceAll("\\[GENERA_IMMAGINE:[^\\]]*\\]", "").trim();
                if (textResp.isEmpty()) textResp = "Ecco l immagine generata!";

                if (!supabaseUrl.isEmpty()) {
                    try {
                        saveMessage(sessionId, "user", userMessage, supabaseUrl, supabaseKey);
                        saveMessage(sessionId, "assistant", textResp, supabaseUrl, supabaseKey);
                    } catch (Exception e) { log.warn("Supabase: {}", e.getMessage()); }
                }

                if (imgResult.startsWith("IMAGE:")) {
                    return ResponseEntity.ok(Map.of(
                            "response", textResp, "image", imgResult.substring(6),
                            "imageType", "image/jpeg", "status", "ok",
                            "model", model, "sessionId", sessionId));
                }
                return ResponseEntity.ok(Map.of("response", imgResult, "status", "ok",
                        "model", model, "sessionId", sessionId));
            }

            // Router + agenti
            List<String> agents = routeQuery(userMessage, baseUrl, apiKey, model);
            log.info("Agenti: {} | Web: {}", agents, webData != null);

            List<String> outputs = new ArrayList<>();
            for (String agent : agents) {
                String out = callLLM(agentPrompt(agent), enriched, history, baseUrl, apiKey, model, 2500);
                if (out != null && !out.isBlank())
                    outputs.add("[" + agent.toUpperCase() + "]\n" + out);
            }

            String finalResponse;
            if (outputs.isEmpty())
                finalResponse = callLLM(coreSystem(), enriched, history, baseUrl, apiKey, model, 2000);
            else if (outputs.size() == 1)
                finalResponse = outputs.get(0).replaceFirst("\\[\\w+\\]\\n", "");
            else {
                String combined = String.join("\n\n", outputs);
                finalResponse = callLLM(synthesizerPrompt(),
                        "Domanda: " + userMessage + "\n\nOutput:\n\n" + combined,
                        new ArrayList<>(), baseUrl, apiKey, model, 3000);
            }

            if (!supabaseUrl.isEmpty()) {
                try {
                    saveMessage(sessionId, "user", userMessage, supabaseUrl, supabaseKey);
                    saveMessage(sessionId, "assistant", finalResponse, supabaseUrl, supabaseKey);
                } catch (Exception e) { log.warn("Supabase: {}", e.getMessage()); }
            }

            return ResponseEntity.ok(Map.of(
                    "response", finalResponse, "status", "ok", "model", model,
                    "agents", agents.toString(), "webSearch", webData != null ? "true" : "false",
                    "sessionId", sessionId));

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
        if (q.contains("grafico")||q.contains("visualizza")||q.contains("chart")) return List.of("data","code");
        if (q.contains("bitcoin")||q.contains("crypto")||q.contains("cripto")) return List.of("crypto");
        if (q.contains("trading")||q.contains("borsa")||q.contains("azioni")) return List.of("finance");
        if (q.contains("traduci")||q.contains("in inglese")) return List.of("translator");
        if (q.contains("calcola")||q.contains("matematica")) return List.of("math");
        if (q.contains("bug")||q.contains("errore nel codice")) return List.of("debug");
        if (q.contains("legge")||q.contains("contratto")) return List.of("legal");
        if (q.contains("ricetta")||q.contains("cucina")) return List.of("cooking");
        if (q.contains("viaggio")||q.contains("vacanza")) return List.of("travel");
        if (q.contains("allenamento")||q.contains("palestra")) return List.of("fitness");
        if (q.contains("notizie")||q.contains("news")) return List.of("research");
        if (q.contains("startup")||q.contains("business")) return List.of("startup");
        if (q.contains("automaz")||q.contains("automatizza")) return List.of("automator");
        if (q.contains("analisi")||q.contains("analizza")) return List.of("analyst");
        if (q.contains("strategia")||q.contains("piano")) return List.of("strategist");
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
                "agents",    "120+ agenti specializzati",
                "webSearch", tavily ? "enabled" : "disabled",
                "images",    hf ? "enabled (FLUX+SDXL+SD2)" : "disabled",
                "supabase",  supa ? "connected" : "off",
                "date",      today()
        ));
    }
}
