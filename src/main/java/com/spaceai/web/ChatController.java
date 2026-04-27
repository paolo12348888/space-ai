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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AgentLoop agentLoop;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // ── SISTEMA NEURALE - Memoria persistente in-memory ──────────
    private final Map<String, List<Map<String,String>>> neuralMemory = new ConcurrentHashMap<>();
    private final Map<String, Map<String,Object>> userProfiles = new ConcurrentHashMap<>();
    private final Map<String, List<String>> userInsights = new ConcurrentHashMap<>();
    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final Map<String, AtomicInteger> agentUsage = new ConcurrentHashMap<>();

    public ChatController(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
    }

    private String today() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE dd MMMM yyyy HH:mm", Locale.ITALIAN));
    }
    private String env(String k, String d) { return System.getenv().getOrDefault(k, d); }

    // ── SISTEMA NEURALE: APPRENDIMENTO AUTONOMO ───────────────────
    // Impara dall'utente, costruisce profilo, migliora risposte
    private void learnFromInteraction(String sessionId, String userMsg, String response, String agent) {
        totalRequests.incrementAndGet();
        agentUsage.computeIfAbsent(agent, k -> new AtomicInteger(0)).incrementAndGet();

        // Costruisce profilo utente automaticamente
        Map<String,Object> profile = userProfiles.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());
        List<String> insights = userInsights.computeIfAbsent(sessionId, k -> new ArrayList<>());

        String q = userMsg.toLowerCase();

        // Inferisce interessi, settore, livello tecnico
        if (q.contains("python") || q.contains("java") || q.contains("codice"))
            profile.put("tech_level", "developer");
        if (q.contains("trading") || q.contains("borsa") || q.contains("crypto"))
            profile.put("domain", "finance");
        if (q.contains("startup") || q.contains("business"))
            profile.put("role", "entrepreneur");
        if (q.contains("medic") || q.contains("salute"))
            profile.put("domain", "health");

        // Conta lunghezza messaggi per capire stile comunicazione
        if (userMsg.length() < 30) profile.put("style", "concise");
        else if (userMsg.length() > 200) profile.put("style", "detailed");

        // Salva insight unici
        String insight = "L'utente ha chiesto riguardo: " + userMsg.substring(0, Math.min(60, userMsg.length()));
        if (insights.size() < 50 && !insights.contains(insight)) insights.add(insight);
    }

    // Costruisce contesto personalizzato basato su apprendimento
    private String buildPersonalizedContext(String sessionId) {
        Map<String,Object> profile = userProfiles.get(sessionId);
        List<String> insights = userInsights.get(sessionId);
        if (profile == null || profile.isEmpty()) return "";

        StringBuilder ctx = new StringBuilder("[PROFILO UTENTE APPRESO - adatta la risposta]: ");
        if (profile.containsKey("tech_level")) ctx.append("Livello tecnico: ").append(profile.get("tech_level")).append(". ");
        if (profile.containsKey("domain")) ctx.append("Settore principale: ").append(profile.get("domain")).append(". ");
        if (profile.containsKey("role")) ctx.append("Ruolo: ").append(profile.get("role")).append(". ");
        if (profile.containsKey("style")) ctx.append("Preferisce risposte: ").append(profile.get("style")).append(". ");
        return ctx.toString();
    }

    // ── WEB SEARCH ────────────────────────────────────────────────
    private String searchWeb(String query) {
        String key = env("TAVILY_API_KEY", "");
        if (key.isEmpty()) return null;
        try {
            ObjectNode req = MAPPER.createObjectNode();
            req.put("api_key", key); req.put("query", query);
            req.put("search_depth", "advanced"); req.put("max_results", 5); req.put("include_answer", true);
            HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> resp = restTemplate.postForEntity("https://api.tavily.com/search",
                    new HttpEntity<>(MAPPER.writeValueAsString(req), h), String.class);
            JsonNode json = MAPPER.readTree(resp.getBody());
            StringBuilder sb = new StringBuilder();
            if (json.has("answer") && !json.path("answer").asText().isBlank())
                sb.append("RISPOSTA AGGIORNATA: ").append(json.path("answer").asText()).append("\n\n");
            JsonNode results = json.path("results");
            if (results.isArray()) {
                sb.append("FONTI:\n"); int i = 1;
                for (JsonNode r : results) {
                    sb.append(i++).append(". ").append(r.path("title").asText()).append("\n");
                    String c = r.path("content").asText();
                    sb.append("   ").append(c, 0, Math.min(200, c.length())).append("\n");
                    sb.append("   URL: ").append(r.path("url").asText()).append("\n\n");
                }
            }
            return sb.toString();
        } catch (Exception e) { log.warn("Tavily: {}", e.getMessage()); return null; }
    }

    private boolean needsSearch(String msg) {
        String q = msg.toLowerCase();
        return q.contains("cerca") || q.contains("notizie") || q.contains("oggi") ||
               q.contains("attuale") || q.contains("2025") || q.contains("2026") ||
               q.contains("prezzo") || q.contains("quotazione") || q.contains("recente") ||
               q.contains("ultime") || q.contains("adesso") || q.contains("aggiornato");
    }

    // ── GENERAZIONE IMMAGINI ──────────────────────────────────────
    private String generateImage(String prompt) {
        try {
            String encoded = URLEncoder.encode(prompt, "UTF-8").replace("+", "%20");
            String url = "https://image.pollinations.ai/prompt/" + encoded
                       + "?width=768&height=768&nologo=true&enhance=true&model=flux";
            log.info("Generazione immagine: {}", prompt.substring(0, Math.min(50, prompt.length())));
            ResponseEntity<byte[]> resp = restTemplate.getForEntity(url, byte[].class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null && resp.getBody().length > 5000) {
                return "IMAGE:" + Base64.getEncoder().encodeToString(resp.getBody());
            }
        } catch (Exception e) { log.warn("Pollinations: {}", e.getMessage()); }

        String hfKey = env("HF_TOKEN", "");
        if (!hfKey.isEmpty()) {
            try {
                HttpHeaders h = new HttpHeaders();
                h.setContentType(MediaType.APPLICATION_JSON);
                h.setBearerAuth(hfKey);
                ObjectNode req = MAPPER.createObjectNode();
                req.put("inputs", prompt);
                ObjectNode params = MAPPER.createObjectNode();
                params.put("wait_for_model", true);
                req.set("parameters", params);
                ResponseEntity<byte[]> resp = restTemplate.postForEntity(
                        "https://api-inference.huggingface.co/models/black-forest-labs/FLUX.1-schnell",
                        new HttpEntity<>(MAPPER.writeValueAsString(req), h), byte[].class);
                if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null && resp.getBody().length > 1000)
                    return "IMAGE:" + Base64.getEncoder().encodeToString(resp.getBody());
            } catch (Exception e) { log.warn("HF: {}", e.getMessage()); }
        }
        return "Non riesco a generare l'immagine. Riprova tra qualche minuto.";
    }

    // ── THINKING MODE (come Claude extended thinking) ─────────────
    private String thinkingMode(String userMsg, String context, String baseUrl, String apiKey, String model) throws Exception {
        // Step 1: Ragionamento interno
        String thinkPrompt = "Sei un sistema di ragionamento avanzato. Data: " + today() + ". " +
            "Prima di rispondere, analizza il problema in modo PROFONDO e STRUTTURATO:\n" +
            "1. COMPRENSIONE: Cosa chiede esattamente l'utente?\n" +
            "2. ANALISI: Quali sono i concetti chiave coinvolti?\n" +
            "3. APPROCCI: Quali sono i possibili approcci alla soluzione?\n" +
            "4. RAGIONAMENTO: Valuta pro e contro di ogni approccio\n" +
            "5. CONCLUSIONE: Qual e la risposta migliore?\n\n" +
            "Struttura il tuo pensiero in modo chiaro con questi 5 step.";

        String thinking = callLLM(thinkPrompt, userMsg, new ArrayList<>(), baseUrl, apiKey, model, 1500);

        // Step 2: Risposta finale basata sul ragionamento
        String finalPrompt = "Sei SPACE AI. Data: " + today() + ". " +
            "Basandoti su questo ragionamento interno:\n\n" + thinking + "\n\n" +
            "Fornisci ora una risposta FINALE ottimale, chiara e completa. " +
            "Includi un riepilogo del ragionamento in un blocco collassabile se rilevante. " +
            "Rispondi in italiano con markdown.";

        String finalResp = callLLM(finalPrompt, userMsg, new ArrayList<>(), baseUrl, apiKey, model, 2000);

        return "<details><summary>🧠 Ragionamento interno (thinking mode)</summary>\n\n" +
               thinking + "\n\n</details>\n\n---\n\n" + finalResp;
    }

    // ── ANALISI DOCUMENTO/IMMAGINE (come Claude vision) ──────────
    private String analyzeContent(String userMsg, String fileContent, String baseUrl, String apiKey, String model) throws Exception {
        String analyzePrompt = "Sei SPACE AI con capacita di analisi avanzata. Data: " + today() + ". " +
            "Analizza il contenuto fornito dall'utente in modo approfondito. " +
            "Estrai insights, pattern, informazioni chiave. " +
            "Se e codice: analizza bugs, miglioramenti, sicurezza. " +
            "Se e testo: analizza struttura, temi, sentimento. " +
            "Se sono dati: trova pattern, anomalie, tendenze. " +
            "Rispondi in italiano con markdown.";

        String combined = userMsg + "\n\n[CONTENUTO FILE]:\n" + fileContent;
        return callLLM(analyzePrompt, combined, new ArrayList<>(), baseUrl, apiKey, model, 3000);
    }

    // ── SISTEMA NEURALE: RISPOSTA ADATTIVA ────────────────────────
    // Genera risposta che migliora nel tempo basandosi sul profilo
    private String adaptiveResponse(String agent, String userMsg, String enriched,
                                    List<Map<String,String>> history, String sessionId,
                                    String baseUrl, String apiKey, String model) throws Exception {
        String personalCtx = buildPersonalizedContext(sessionId);
        String finalMsg = enriched;
        if (!personalCtx.isEmpty()) finalMsg = personalCtx + "\n\n" + enriched;

        String response = callLLM(agentPrompt(agent), finalMsg, history, baseUrl, apiKey, model, 2500);
        learnFromInteraction(sessionId, userMsg, response, agent);
        return response;
    }

    // ── SISTEMA NEURALE: AUTO-RIFLESSIONE ─────────────────────────
    // Valuta e migliora la propria risposta (come RLHF interno)
    private String selfReflect(String initialResponse, String userMsg, String baseUrl, String apiKey, String model) throws Exception {
        String reflectPrompt = "Sei un sistema di auto-valutazione. Valuta questa risposta:\n\n" +
            "DOMANDA: " + userMsg + "\n\nRISPOSTA: " + initialResponse + "\n\n" +
            "Criteri di valutazione (1-10): accuratezza, completezza, chiarezza, utilita. " +
            "Se il punteggio totale e < 28/40, migliora la risposta. " +
            "Altrimenti restituisci la risposta originale migliorata di almeno il 20%. " +
            "Output: SOLO la risposta migliorata, senza meta-commenti.";

        return callLLM(reflectPrompt, "", new ArrayList<>(), baseUrl, apiKey, model, 2500);
    }

    // ── SYSTEM PROMPT BASE ────────────────────────────────────────
    private String coreSystem() {
        return "Sei SPACE AI, assistente AI avanzato con sistema neurale adattivo creato da Paolo. Data: " + today() + ". " +
               "Hai 131 agenti specializzati, memoria contestuale, thinking mode e apprendimento adattivo. " +
               "Se ci sono [DATI WEB] usali. Rispondi in italiano con markdown. Sii preciso e completo.";
    }

    // ── AGENTI ────────────────────────────────────────────────────
    private String agentPrompt(String agent) {
        String d = today();
        switch (agent) {
            case "router":
                return "Sei il ROUTER di SPACE AI. Analizza SOLO la domanda attuale. " +
                       "Rispondi SOLO con JSON: {\"agents\":[\"code\"],\"complexity\":\"low\",\"thinking\":false} " +
                       "Imposta thinking:true solo per domande complesse, filosofiche o che richiedono ragionamento profondo. " +
                       "Agenti: code,debug,debug2,security,cybersec,data,ai,cloud,devops,database,frontend,backend," +
                       "mobile,blockchain,tech,optimizer,integrator,automator,autonomous,iot,robotics,quantum," +
                       "ar_vr,gamedev,scraping,devrel,mlops,llm_fine,agent_ai,api_design," +
                       "finance,crypto,forex,trader,investor,quant,defi,nft,web3," +
                       "real_estate,accounting,accounting2,tax,tax2,auditor,analyst,fintech,ecommerce,insurance,vc,bizdev," +
                       "math,physics,chemistry,biology,astro,science,environment,energy,climate,space_tech," +
                       "researcher2,predictor,biomed,neuro,pharma," +
                       "medical,nutrition,fitness,mental_health,psychology," +
                       "research,reasoner,history,philosophy,economics,politics,geography,journalist,ethicist,linguist," +
                       "arts,music,books,movies,fashion,food_tech,writer,creative,designer,architect,innovator," +
                       "planner,startup,hr,product,ux,seo,coach,education,negotiator,strategist,consultant," +
                       "growth,brand,pr,social,ads,analytics,supply_chain,pm," +
                       "translator,legal,legal2,summarizer,cooking,travel,sports,gaming,monitor,classifier,extractor," +
                       "debate,interview,language,mindmap,prompt_eng,video_gen,audio_gen,image_gen,spaces. " +
                       "Scegli 1-2 agenti. SOLO JSON valido.";
            case "spaces": return "Sei SPACES, assistente vocale personale di SPACE AI. Data:" + d + ". Rispondi in max 3 frasi concise per la voce. Usa sempre tono professionale e amichevole. Inizia con 'SPACES:'.";
            case "image_gen": return "Sei IMAGE_GEN di SPACE AI. Data:" + d + ". Genera [GENERA_IMMAGINE: detailed english description]. Poi descrivi in italiano.";
            case "code": return "Sei CODE di SPACE AI. Data:" + d + ". Python,Java,JS,TS,Go,Rust,C++,SQL,React,Spring,FastAPI,Docker. Codice COMPLETO con ```. Rispondi in italiano.";
            case "debug": return "Sei DEBUG di SPACE AI. Data:" + d + ". Root cause + soluzione + prevenzione. Rispondi in italiano.";
            case "debug2": return "Sei DEBUG2 di SPACE AI. Data:" + d + ". Profiling memoria/CPU, distributed tracing, OpenTelemetry, heap dump. Rispondi in italiano.";
            case "security": return "Sei SECURITY di SPACE AI. Data:" + d + ". OWASP,JWT,OAuth2,GDPR. Solo difensivo. Rispondi in italiano.";
            case "cybersec": return "Sei CYBERSEC di SPACE AI. Data:" + d + ". CTF,forensics,threat intelligence,SIEM,SOC. Solo difensivo. Rispondi in italiano.";
            case "data": return "Sei DATA di SPACE AI. Data:" + d + ". Pandas,NumPy,Scikit-learn,TensorFlow,PyTorch,SQL,ETL. Rispondi in italiano.";
            case "ai": return "Sei AI-EXPERT di SPACE AI. Data:" + d + ". LLM,fine-tuning,RAG,embeddings,prompt engineering,MLops. Rispondi in italiano.";
            case "cloud": return "Sei CLOUD di SPACE AI. Data:" + d + ". AWS,GCP,Azure,Kubernetes,Terraform. Rispondi in italiano.";
            case "devops": return "Sei DEVOPS di SPACE AI. Data:" + d + ". CI/CD,Docker,GitHub Actions,monitoring,SRE. Rispondi in italiano.";
            case "database": return "Sei DATABASE di SPACE AI. Data:" + d + ". PostgreSQL,MySQL,MongoDB,Redis,query optimization. Rispondi in italiano.";
            case "frontend": return "Sei FRONTEND di SPACE AI. Data:" + d + ". React,Vue,HTML5,CSS3,Tailwind,WebGL,PWA. Rispondi in italiano.";
            case "backend": return "Sei BACKEND di SPACE AI. Data:" + d + ". REST,GraphQL,Spring Boot,FastAPI,microservices. Rispondi in italiano.";
            case "mobile": return "Sei MOBILE di SPACE AI. Data:" + d + ". React Native,Flutter,Swift,Kotlin. Rispondi in italiano.";
            case "blockchain": return "Sei BLOCKCHAIN di SPACE AI. Data:" + d + ". Ethereum,Solidity,Web3,DeFi,smart contract. Rispondi in italiano.";
            case "tech": return "Sei TECH di SPACE AI. Data:" + d + ". Hardware,reti,IoT,5G,quantum. Rispondi in italiano.";
            case "optimizer": return "Sei OPTIMIZER di SPACE AI. Data:" + d + ". Performance tuning,caching,profiling. Rispondi in italiano.";
            case "integrator": return "Sei INTEGRATOR di SPACE AI. Data:" + d + ". API integration,webhooks,ETL,middleware. Rispondi in italiano.";
            case "automator": return "Sei AUTOMATOR di SPACE AI. Data:" + d + ". RPA,workflow automation,scripting. Rispondi in italiano.";
            case "autonomous": return "Sei AUTONOMOUS di SPACE AI. Data:" + d + ". Agente autonomo multi-step: Analizza→Pianifica→Esegui→Verifica. Rispondi in italiano.";
            case "iot": return "Sei IOT di SPACE AI. Data:" + d + ". Arduino,Raspberry Pi,ESP32,MQTT,edge computing. Rispondi in italiano.";
            case "robotics": return "Sei ROBOTICS di SPACE AI. Data:" + d + ". ROS,SLAM,droni,autonomous vehicles. Rispondi in italiano.";
            case "quantum": return "Sei QUANTUM di SPACE AI. Data:" + d + ". Qiskit,qubit,algoritmi Shor/Grover,quantum ML. Rispondi in italiano.";
            case "ar_vr": return "Sei AR_VR di SPACE AI. Data:" + d + ". Unity,Unreal,WebXR,Apple Vision Pro,ARKit. Rispondi in italiano.";
            case "gamedev": return "Sei GAMEDEV di SPACE AI. Data:" + d + ". Unity,Unreal,Godot,game design,shaders. Rispondi in italiano.";
            case "scraping": return "Sei SCRAPER di SPACE AI. Data:" + d + ". BeautifulSoup,Scrapy,Playwright. Solo usi legali. Rispondi in italiano.";
            case "mlops": return "Sei MLOPS di SPACE AI. Data:" + d + ". MLflow,Kubeflow,model serving,drift detection. Rispondi in italiano.";
            case "llm_fine": return "Sei LLM_FINETUNER di SPACE AI. Data:" + d + ". LoRA,QLoRA,RLHF,DPO,distillazione. Rispondi in italiano.";
            case "agent_ai": return "Sei AGENT_AI di SPACE AI. Data:" + d + ". LangChain,LlamaIndex,AutoGen,CrewAI,multi-agent. Rispondi in italiano.";
            case "api_design": return "Sei API_DESIGNER di SPACE AI. Data:" + d + ". REST,GraphQL,OpenAPI,versioning,gateway. Rispondi in italiano.";
            case "devrel": return "Sei DEVREL di SPACE AI. Data:" + d + ". Documentazione,tutorial,SDK,community. Rispondi in italiano.";
            case "finance": return "Sei FINANCE di SPACE AI. Data:" + d + ". RSI,MACD,Bollinger,P/E,DCF,VaR,Sharpe. Esempi numerici. Rispondi in italiano.";
            case "crypto": return "Sei CRYPTO di SPACE AI. Data:" + d + ". Bitcoin,Ethereum,DeFi,NFT,on-chain analysis. Rispondi in italiano.";
            case "forex": return "Sei FOREX di SPACE AI. Data:" + d + ". Valute,carry trade,banche centrali,FX. Rispondi in italiano.";
            case "trader": return "Sei TRADER di SPACE AI. Data:" + d + ". Trading algoritmico,backtesting,quant. Rispondi in italiano.";
            case "investor": return "Sei INVESTOR di SPACE AI. Data:" + d + ". Value investing,growth,portfolio,ETF. Rispondi in italiano.";
            case "quant": return "Sei QUANT di SPACE AI. Data:" + d + ". Black-Scholes,Monte Carlo,HFT,alpha generation. Rispondi in italiano.";
            case "defi": return "Sei DEFI di SPACE AI. Data:" + d + ". Yield farming,AMM,DEX,flash loans,MEV. Rispondi in italiano.";
            case "nft": return "Sei NFT di SPACE AI. Data:" + d + ". Minting,marketplace,ERC-721/1155,generative art. Rispondi in italiano.";
            case "web3": return "Sei WEB3 di SPACE AI. Data:" + d + ". dApp,wallet,IPFS,DAO,Layer2,Hardhat. Rispondi in italiano.";
            case "real_estate": return "Sei REAL_ESTATE di SPACE AI. Data:" + d + ". Immobiliare,ROI,cap rate,REIT. Rispondi in italiano.";
            case "accounting": return "Sei ACCOUNTING di SPACE AI. Data:" + d + ". Bilancio,cash flow,IAS/IFRS. Rispondi in italiano.";
            case "accounting2": return "Sei ACCOUNTING2 di SPACE AI. Data:" + d + ". IFRS 16,transfer pricing,M&A accounting. Rispondi in italiano.";
            case "tax": return "Sei TAX di SPACE AI. Data:" + d + ". Fiscalita italiana,IVA,IRPEF,crypto. Info generali. Rispondi in italiano.";
            case "tax2": return "Sei TAX2 di SPACE AI. Data:" + d + ". Fiscalita internazionale,BEPS,treaty. Info generali. Rispondi in italiano.";
            case "auditor": return "Sei AUDITOR di SPACE AI. Data:" + d + ". Audit,compliance,risk assessment,SOX. Rispondi in italiano.";
            case "analyst": return "Sei ANALYST di SPACE AI. Data:" + d + ". BI,KPI,dashboard,reporting,trend analysis. Rispondi in italiano.";
            case "fintech": return "Sei FINTECH di SPACE AI. Data:" + d + ". Open banking,BNPL,neobank,payment systems. Rispondi in italiano.";
            case "ecommerce": return "Sei ECOMMERCE di SPACE AI. Data:" + d + ". Shopify,Amazon FBA,dropshipping,funnel. Rispondi in italiano.";
            case "insurance": return "Sei INSURANCE di SPACE AI. Data:" + d + ". Prodotti vita/danni,attuariato,Solvency II. Rispondi in italiano.";
            case "vc": return "Sei VC di SPACE AI. Data:" + d + ". Valuation,cap table,term sheet,exit. Rispondi in italiano.";
            case "bizdev": return "Sei BIZDEV di SPACE AI. Data:" + d + ". Partnership,M&A,deal structuring,due diligence. Rispondi in italiano.";
            case "math": return "Sei MATH di SPACE AI. Data:" + d + ". Mostra TUTTI i passaggi. Algebra,calcolo,statistica. Rispondi in italiano.";
            case "physics": return "Sei PHYSICS di SPACE AI. Data:" + d + ". Meccanica,quantistica,relativita. Rispondi in italiano.";
            case "chemistry": return "Sei CHEMISTRY di SPACE AI. Data:" + d + ". Chimica organica,reazioni. Rispondi in italiano.";
            case "biology": return "Sei BIOLOGY di SPACE AI. Data:" + d + ". Genetica,CRISPR,evoluzione. Rispondi in italiano.";
            case "astro": return "Sei ASTRO di SPACE AI. Data:" + d + ". Astronomia,cosmologia,esopianeti. Rispondi in italiano.";
            case "science": return "Sei SCIENCE di SPACE AI. Data:" + d + ". Metodo scientifico,interdisciplinare. Rispondi in italiano.";
            case "environment": return "Sei ENVIRONMENT di SPACE AI. Data:" + d + ". Clima,sostenibilita. Rispondi in italiano.";
            case "energy": return "Sei ENERGY di SPACE AI. Data:" + d + ". Solare,eolico,nucleare,idrogeno. Rispondi in italiano.";
            case "climate": return "Sei CLIMATE di SPACE AI. Data:" + d + ". Carbon capture,ESG,net-zero,green tech. Rispondi in italiano.";
            case "space_tech": return "Sei SPACE_TECH di SPACE AI. Data:" + d + ". SpaceX,NASA,satelliti,orbital mechanics. Rispondi in italiano.";
            case "researcher2": return "Sei RESEARCHER2 di SPACE AI. Data:" + d + ". Ricerca scientifica,meta-analisi,peer review. Rispondi in italiano.";
            case "predictor": return "Sei PREDICTOR di SPACE AI. Data:" + d + ". Previsioni,trend analysis,forecasting,scenari. Rispondi in italiano.";
            case "biomed": return "Sei BIOMED di SPACE AI. Data:" + d + ". Genomica,drug discovery,AlphaFold. Info generali. Rispondi in italiano.";
            case "neuro": return "Sei NEURO di SPACE AI. Data:" + d + ". BCI,neuromorphic computing,cognitive science. Rispondi in italiano.";
            case "pharma": return "Sei PHARMA di SPACE AI. Data:" + d + ". Drug pipeline,FDA/EMA,clinical trials. Info generali. Rispondi in italiano.";
            case "medical": return "Sei MEDICAL di SPACE AI. Data:" + d + ". Solo info generali, mai diagnosi. Rispondi in italiano.";
            case "nutrition": return "Sei NUTRITION di SPACE AI. Data:" + d + ". Diete,macronutrienti,meal planning. Rispondi in italiano.";
            case "fitness": return "Sei FITNESS di SPACE AI. Data:" + d + ". Allenamento,schede,sport,recovery. Rispondi in italiano.";
            case "mental_health": return "Sei MENTAL_HEALTH di SPACE AI. Data:" + d + ". Solo supporto informativo. Rispondi in italiano.";
            case "psychology": return "Sei PSYCHOLOGY di SPACE AI. Data:" + d + ". Psicologia cognitiva,bias,decision making. Rispondi in italiano.";
            case "research": return "Sei RESEARCH di SPACE AI. Data:" + d + ". Info accurate. Se ci sono [DATI WEB] usali. Rispondi in italiano.";
            case "reasoner": return "Sei REASONER di SPACE AI. Data:" + d + ". Analisi step-by-step,logica formale. Rispondi in italiano.";
            case "history": return "Sei HISTORY di SPACE AI. Data:" + d + ". Storia mondiale,cause e conseguenze. Rispondi in italiano.";
            case "philosophy": return "Sei PHILOSOPHY di SPACE AI. Data:" + d + ". Etica,epistemologia,AI ethics. Rispondi in italiano.";
            case "economics": return "Sei ECONOMICS di SPACE AI. Data:" + d + ". Micro e macro,teoria dei giochi. Rispondi in italiano.";
            case "politics": return "Sei POLITICS di SPACE AI. Data:" + d + ". Neutrale e bilanciato. Rispondi in italiano.";
            case "geography": return "Sei GEOGRAPHY di SPACE AI. Data:" + d + ". Paesi e culture. Rispondi in italiano.";
            case "journalist": return "Sei JOURNALIST di SPACE AI. Data:" + d + ". Fact-checking,analisi media,investigative. Rispondi in italiano.";
            case "ethicist": return "Sei ETHICIST di SPACE AI. Data:" + d + ". Etica applicata,AI ethics,bioetica. Rispondi in italiano.";
            case "linguist": return "Sei LINGUIST di SPACE AI. Data:" + d + ". Linguistica,semantica,NLP. Rispondi in italiano.";
            case "arts": return "Sei ARTS di SPACE AI. Data:" + d + ". Arte,pittura,architettura,fotografia. Rispondi in italiano.";
            case "music": return "Sei MUSIC di SPACE AI. Data:" + d + ". Teoria,strumenti,produzione musicale. Rispondi in italiano.";
            case "books": return "Sei BOOKS di SPACE AI. Data:" + d + ". Letteratura,consigli lettura,analisi. Rispondi in italiano.";
            case "movies": return "Sei MOVIES di SPACE AI. Data:" + d + ". Cinema,serie TV,critica. Rispondi in italiano.";
            case "fashion": return "Sei FASHION di SPACE AI. Data:" + d + ". Trend,sustainable fashion,luxury market. Rispondi in italiano.";
            case "food_tech": return "Sei FOOD_TECH di SPACE AI. Data:" + d + ". Food science,HACCP,agritech. Rispondi in italiano.";
            case "writer": return "Sei WRITER di SPACE AI. Data:" + d + ". Email,report,storytelling,copywriting. Rispondi in italiano.";
            case "creative": return "Sei CREATIVE di SPACE AI. Data:" + d + ". Brainstorming,naming,design thinking. Rispondi in italiano.";
            case "designer": return "Sei DESIGNER di SPACE AI. Data:" + d + ". Graphic design,brand identity,UI. Rispondi in italiano.";
            case "architect": return "Sei ARCHITECT di SPACE AI. Data:" + d + ". Software architecture,system design,patterns. Rispondi in italiano.";
            case "innovator": return "Sei INNOVATOR di SPACE AI. Data:" + d + ". Innovation,first principles,disruption. Rispondi in italiano.";
            case "planner": return "Sei PLANNER di SPACE AI. Data:" + d + ". Agile,OKR,roadmap,SWOT. Rispondi in italiano.";
            case "startup": return "Sei STARTUP di SPACE AI. Data:" + d + ". Pitch,fundraising,MVP,growth hacking. Rispondi in italiano.";
            case "hr": return "Sei HR di SPACE AI. Data:" + d + ". Recruiting,carriera,employer branding. Rispondi in italiano.";
            case "product": return "Sei PRODUCT di SPACE AI. Data:" + d + ". Roadmap,user story,metriche,PRD. Rispondi in italiano.";
            case "ux": return "Sei UX di SPACE AI. Data:" + d + ". UX/UI,user research,Figma. Rispondi in italiano.";
            case "seo": return "Sei SEO di SPACE AI. Data:" + d + ". SEO,keyword,content marketing. Rispondi in italiano.";
            case "coach": return "Sei COACH di SPACE AI. Data:" + d + ". Mindset,produttivita,leadership. Rispondi in italiano.";
            case "education": return "Sei EDUCATION di SPACE AI. Data:" + d + ". Pedagogia,apprendimento. Rispondi in italiano.";
            case "negotiator": return "Sei NEGOTIATOR di SPACE AI. Data:" + d + ". Tecniche negoziazione,BATNA,win-win. Rispondi in italiano.";
            case "strategist": return "Sei STRATEGIST di SPACE AI. Data:" + d + ". Strategic planning,competitive analysis. Rispondi in italiano.";
            case "consultant": return "Sei CONSULTANT di SPACE AI. Data:" + d + ". Business consulting,McKinsey frameworks. Rispondi in italiano.";
            case "growth": return "Sei GROWTH di SPACE AI. Data:" + d + ". Viral loops,A/B testing,retention,PLG. Rispondi in italiano.";
            case "brand": return "Sei BRAND di SPACE AI. Data:" + d + ". Identita visiva,positioning,storytelling. Rispondi in italiano.";
            case "pr": return "Sei PR di SPACE AI. Data:" + d + ". PR,crisis management,media relations. Rispondi in italiano.";
            case "social": return "Sei SOCIAL_MEDIA di SPACE AI. Data:" + d + ". Instagram,TikTok,LinkedIn,viral content. Rispondi in italiano.";
            case "ads": return "Sei ADS di SPACE AI. Data:" + d + ". Google Ads,Meta Ads,TikTok Ads,ROAS. Rispondi in italiano.";
            case "analytics": return "Sei ANALYTICS di SPACE AI. Data:" + d + ". GA4,Mixpanel,Amplitude,attribution. Rispondi in italiano.";
            case "supply_chain": return "Sei SUPPLY_CHAIN di SPACE AI. Data:" + d + ". Logistica,inventory,demand forecasting,ERP. Rispondi in italiano.";
            case "pm": return "Sei PROJECT_MANAGER di SPACE AI. Data:" + d + ". Scrum,Kanban,PMP,risk management. Rispondi in italiano.";
            case "translator": return "Sei TRANSLATOR di SPACE AI. Multilingua: IT,EN,FR,ES,DE,PT,ZH,JA,AR.";
            case "legal": return "Sei LEGAL di SPACE AI. Data:" + d + ". Info generali, non consulenza. Rispondi in italiano.";
            case "legal2": return "Sei LEGAL2 di SPACE AI. Data:" + d + ". AI regulation,IP,privacy law. Info generali. Rispondi in italiano.";
            case "summarizer": return "Sei SUMMARIZER di SPACE AI. Bullet points chiari e concisi. Rispondi in italiano.";
            case "cooking": return "Sei COOKING di SPACE AI. Data:" + d + ". Ricette con dosi precise. Rispondi in italiano.";
            case "travel": return "Sei TRAVEL di SPACE AI. Data:" + d + ". Destinazioni,itinerari,budget. Rispondi in italiano.";
            case "sports": return "Sei SPORTS di SPACE AI. Data:" + d + ". Sport,tattica,statistiche. Rispondi in italiano.";
            case "gaming": return "Sei GAMING di SPACE AI. Data:" + d + ". Videogiochi,strategie,esports. Rispondi in italiano.";
            case "monitor": return "Sei MONITOR di SPACE AI. Data:" + d + ". Monitoring,alerting,incident management. Rispondi in italiano.";
            case "classifier": return "Sei CLASSIFIER di SPACE AI. Data:" + d + ". Classificazione testi,sentiment analysis. Rispondi in italiano.";
            case "extractor": return "Sei EXTRACTOR di SPACE AI. Data:" + d + ". Estrazione info,NER,parsing strutturato. Rispondi in italiano.";
            case "debate": return "Sei DEBATE di SPACE AI. Data:" + d + ". Logica formale,fallace,retorica,pro/contro. Rispondi in italiano.";
            case "interview": return "Sei INTERVIEW di SPACE AI. Data:" + d + ". Technical interview,behavioral,salary. Rispondi in italiano.";
            case "language": return "Sei LANGUAGE di SPACE AI. Data:" + d + ". Metodi accelerati,grammatica,pronuncia. Rispondi in italiano.";
            case "mindmap": return "Sei MINDMAP di SPACE AI. Data:" + d + ". Mappe mentali,knowledge graphs,brainstorming. Rispondi in italiano.";
            case "prompt_eng": return "Sei PROMPT_ENGINEER di SPACE AI. Data:" + d + ". Chain-of-thought,few-shot,ReAct. Rispondi in italiano.";
            case "video_gen": return "Sei VIDEO_GEN di SPACE AI. Data:" + d + ". RunwayML,Sora,Pika,storyboard,script. Rispondi in italiano.";
            case "audio_gen": return "Sei AUDIO_GEN di SPACE AI. Data:" + d + ". ElevenLabs,Suno,voice cloning,podcast. Rispondi in italiano.";
            default: return coreSystem();
        }
    }

    private String synthesizerPrompt() {
        return "Sei SYNTHESIZER di SPACE AI. Data:" + today() + ". " +
               "Unifica in UNA risposta finale perfetta. Elimina ridondanze. Markdown. Italiano.";
    }

    // ── ENDPOINT PRINCIPALE ───────────────────────────────────────
    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> body) {
        String userMessage  = body.getOrDefault("message", "").trim();
        String sessionId    = body.getOrDefault("sessionId", "default");
        String fileContent  = body.getOrDefault("fileContent", "");
        String thinkingFlag = body.getOrDefault("thinking", "false");
        if (userMessage.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Messaggio vuoto"));

        String baseUrl     = env("AI_BASE_URL", "https://api.groq.com/openai/v1");
        String apiKey      = env("AI_API_KEY", "");
        String model       = env("AI_MODEL", "llama-3.3-70b-versatile");
        String supabaseUrl = env("SUPABASE_URL", "");
        String supabaseKey = env("SUPABASE_KEY", "");

        try {
            // Memoria contestuale
            List<Map<String,String>> history = new ArrayList<>();
            if (!supabaseUrl.isEmpty() && !supabaseKey.isEmpty())
                history = loadHistory(sessionId, supabaseUrl, supabaseKey);

            // Web search
            String webData = needsSearch(userMessage) ? searchWeb(userMessage) : null;
            String enriched = userMessage;
            if (webData != null && !webData.isBlank())
                enriched = userMessage + "\n\n[DATI WEB - " + today() + "]:\n" + webData;

            // Analisi file se presente
            if (!fileContent.isEmpty()) {
                String fileAnalysis = analyzeContent(userMessage, fileContent, baseUrl, apiKey, model);
                saveMessages(sessionId, userMessage, fileAnalysis, supabaseUrl, supabaseKey);
                return ResponseEntity.ok(Map.of("response", fileAnalysis, "status", "ok",
                        "model", model, "sessionId", sessionId, "mode", "file_analysis"));
            }

            // Gestione immagini
            String q = userMessage.toLowerCase();
            boolean isImg = q.contains("genera immagine") || q.contains("crea immagine") ||
                    q.contains("disegna") || (q.contains("immagine") && (q.contains("crea") || q.contains("genera")));

            if (isImg) {
                String imgAgent = callLLM(agentPrompt("image_gen"), enriched, history, baseUrl, apiKey, model, 400);
                String hfPrompt = userMessage;
                if (imgAgent.contains("[GENERA_IMMAGINE:")) {
                    int s = imgAgent.indexOf("[GENERA_IMMAGINE:") + 17;
                    int e = imgAgent.indexOf("]", s);
                    if (e > s) hfPrompt = imgAgent.substring(s, e).trim();
                }
                String imgResult = generateImage(hfPrompt);
                String textResp = imgAgent.replaceAll("\\[GENERA_IMMAGINE:[^\\]]*\\]", "").trim();
                if (textResp.isEmpty()) textResp = "Ecco l'immagine generata!";
                saveMessages(sessionId, userMessage, textResp, supabaseUrl, supabaseKey);
                if (imgResult.startsWith("IMAGE:"))
                    return ResponseEntity.ok(Map.of("response", textResp, "image", imgResult.substring(6),
                            "imageType", "image/jpeg", "status", "ok", "model", model, "sessionId", sessionId));
                return ResponseEntity.ok(Map.of("response", imgResult, "status", "ok", "model", model, "sessionId", sessionId));
            }

            // Thinking mode (come Claude extended thinking)
            boolean useThinking = "true".equals(thinkingFlag) ||
                    q.contains("[thinking mode") || q.contains("ragiona") || q.contains("analizza in profondita");

            if (useThinking) {
                String thinkResp = thinkingMode(userMessage, enriched, baseUrl, apiKey, model);
                saveMessages(sessionId, userMessage, thinkResp, supabaseUrl, supabaseKey);
                learnFromInteraction(sessionId, userMessage, thinkResp, "thinking");
                return ResponseEntity.ok(Map.of("response", thinkResp, "status", "ok", "model", model,
                        "sessionId", sessionId, "mode", "thinking"));
            }

            // Router
            List<String> agents = routeQuery(userMessage, baseUrl, apiKey, model);
            log.info("Agenti: {} | Web: {} | Session: {}", agents, webData != null, sessionId);

            // Esecuzione agenti con apprendimento adattivo
            List<String> outputs = new ArrayList<>();
            for (String agent : agents) {
                String out = adaptiveResponse(agent, userMessage, enriched, history, sessionId, baseUrl, apiKey, model);
                if (out != null && !out.isBlank()) outputs.add("[" + agent.toUpperCase() + "]\n" + out);
            }

            // Sintesi
            String finalResponse;
            if (outputs.isEmpty())
                finalResponse = adaptiveResponse("reasoner", userMessage, enriched, history, sessionId, baseUrl, apiKey, model);
            else if (outputs.size() == 1)
                finalResponse = outputs.get(0).replaceFirst("\\[\\w+\\]\\n", "");
            else {
                String combined = String.join("\n\n", outputs);
                finalResponse = callLLM(synthesizerPrompt(), "Domanda: " + userMessage + "\n\n" + combined,
                        new ArrayList<>(), baseUrl, apiKey, model, 3000);
                learnFromInteraction(sessionId, userMessage, finalResponse, "synthesizer");
            }

            // Auto-riflessione per risposte importanti (solo per query complesse)
            if (userMessage.length() > 100 && outputs.size() >= 1) {
                try {
                    String improved = selfReflect(finalResponse, userMessage, baseUrl, apiKey, model);
                    if (improved != null && !improved.isBlank() && improved.length() > finalResponse.length() * 0.8)
                        finalResponse = improved;
                } catch (Exception e) { log.warn("Self-reflect: {}", e.getMessage()); }
            }

            saveMessages(sessionId, userMessage, finalResponse, supabaseUrl, supabaseKey);

            // Stats profilo appreso
            Map<String,Object> profile = userProfiles.getOrDefault(sessionId, new HashMap<>());
            return ResponseEntity.ok(Map.of(
                    "response", finalResponse, "status", "ok", "model", model,
                    "agents", agents.toString(), "webSearch", webData != null ? "true" : "false",
                    "sessionId", sessionId, "totalRequests", totalRequests.get(),
                    "learnedProfile", profile.toString()));

        } catch (Exception e) {
            log.error("Errore: {}", e.getMessage());
            try {
                String fallback = callLLM(coreSystem(), userMessage, new ArrayList<>(), baseUrl, apiKey, model, 2000);
                return ResponseEntity.ok(Map.of("response", fallback, "status", "fallback", "sessionId", sessionId));
            } catch (Exception e2) {
                return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
            }
        }
    }

    // ── ROUTER ────────────────────────────────────────────────────
    private List<String> routeQuery(String query, String baseUrl, String apiKey, String model) {
        try {
            String resp = callLLM(agentPrompt("router"), "DOMANDA: " + query, new ArrayList<>(), baseUrl, apiKey, model, 80);
            int s = resp.indexOf("{"), e = resp.lastIndexOf("}") + 1;
            if (s >= 0 && e > s) {
                JsonNode json = MAPPER.readTree(resp.substring(s, e));
                List<String> agents = new ArrayList<>();
                json.path("agents").forEach(n -> agents.add(n.asText()));
                if (!agents.isEmpty() && agents.size() <= 3) return agents;
            }
        } catch (Exception e) { log.warn("Router: {}", e.getMessage()); }
        String q = query.toLowerCase();
        if (q.contains("spaces") || q.contains("briefing")) return List.of("spaces");
        if (q.contains("python") || q.contains("java") || q.contains("codice")) return List.of("code");
        if (q.contains("bitcoin") || q.contains("crypto")) return List.of("crypto");
        if (q.contains("trading") || q.contains("borsa") || q.contains("azioni")) return List.of("finance");
        if (q.contains("traduci") || q.contains("in inglese")) return List.of("translator");
        if (q.contains("calcola") || q.contains("matematica")) return List.of("math");
        if (q.contains("bug") || q.contains("errore nel codice")) return List.of("debug");
        if (q.contains("legge") || q.contains("contratto")) return List.of("legal");
        if (q.contains("ricetta")) return List.of("cooking");
        if (q.contains("viaggio") || q.contains("vacanza")) return List.of("travel");
        if (q.contains("allenamento")) return List.of("fitness");
        if (q.contains("notizie") || q.contains("news")) return List.of("research");
        if (q.contains("startup") || q.contains("business")) return List.of("startup");
        if (q.contains("social media") || q.contains("instagram")) return List.of("social");
        if (q.contains("arduino") || q.contains("raspberry")) return List.of("iot");
        if (q.contains("qubit") || q.contains("quantum")) return List.of("quantum");
        if (q.contains("lora") || q.contains("fine-tun")) return List.of("llm_fine");
        if (q.contains("langchain") || q.contains("crewai")) return List.of("agent_ai");
        if (q.contains("shopify") || q.contains("ecommerce")) return List.of("ecommerce");
        if (q.contains("analisi") || q.contains("analizza")) return List.of("analyst");
        if (q.contains("strategia")) return List.of("strategist");
        return List.of("reasoner");
    }

    // ── LLM CALL ─────────────────────────────────────────────────
    private String callLLM(String system, String userMsg, List<Map<String,String>> history,
                            String baseUrl, String apiKey, String model, int maxTokens) throws Exception {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("model", model); req.put("max_tokens", maxTokens);
        req.put("temperature", 0.8); req.put("top_p", 0.95);
        req.put("frequency_penalty", 0.3); req.put("presence_penalty", 0.3);
        ArrayNode messages = MAPPER.createArrayNode();
        ObjectNode sys = MAPPER.createObjectNode(); sys.put("role","system"); sys.put("content",system); messages.add(sys);
        int start = Math.max(0, history.size()-10);
        for (int i = start; i < history.size(); i++) {
            ObjectNode m = MAPPER.createObjectNode();
            m.put("role", history.get(i).get("role")); m.put("content", history.get(i).get("content")); messages.add(m);
        }
        if (userMsg != null && !userMsg.isEmpty()) {
            ObjectNode usr = MAPPER.createObjectNode(); usr.put("role","user"); usr.put("content",userMsg); messages.add(usr);
        }
        req.set("messages", messages);
        HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON);
        if (!apiKey.isEmpty()) h.setBearerAuth(apiKey);
        h.set("HTTP-Referer","https://space-ai-940e.onrender.com"); h.set("X-Title","SPACE AI"); h.set("User-Agent","SPACE-AI/2.0");
        String endpoint = baseUrl.endsWith("/") ? baseUrl+"chat/completions" : baseUrl+"/chat/completions";
        ResponseEntity<String> response = restTemplate.postForEntity(endpoint, new HttpEntity<>(MAPPER.writeValueAsString(req), h), String.class);
        return MAPPER.readTree(response.getBody()).path("choices").get(0).path("message").path("content").asText();
    }

    // ── SUPABASE ─────────────────────────────────────────────────
    private List<Map<String,String>> loadHistory(String sessionId, String url, String key) {
        List<Map<String,String>> history = new ArrayList<>();
        try {
            HttpHeaders h = new HttpHeaders(); h.set("apikey",key); h.set("Authorization","Bearer "+key);
            ResponseEntity<String> r = restTemplate.exchange(
                    url+"/rest/v1/messages?session_id=eq."+sessionId+"&order=created_at.asc&limit=20&select=role,content",
                    HttpMethod.GET, new HttpEntity<>(h), String.class);
            JsonNode arr = MAPPER.readTree(r.getBody());
            for (JsonNode n : arr) history.add(Map.of("role",n.path("role").asText(),"content",n.path("content").asText()));
        } catch (Exception e) { log.warn("Supabase load: {}", e.getMessage()); }
        return history;
    }

    private void saveMessages(String sessionId, String userMsg, String aiResp, String url, String key) {
        if (url.isEmpty()) return;
        try { saveMsg(sessionId,"user",userMsg,url,key); saveMsg(sessionId,"assistant",aiResp,url,key); }
        catch (Exception e) { log.warn("Supabase save: {}", e.getMessage()); }
    }

    private void saveMsg(String sessionId, String role, String content, String url, String key) throws Exception {
        HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON);
        h.set("apikey",key); h.set("Authorization","Bearer "+key); h.set("Prefer","return=minimal");
        ObjectNode b = MAPPER.createObjectNode(); b.put("session_id",sessionId); b.put("role",role); b.put("content",content);
        restTemplate.postForEntity(url+"/rest/v1/messages", new HttpEntity<>(MAPPER.writeValueAsString(b),h), String.class);
    }

    @GetMapping("/history/{sessionId}")
    public ResponseEntity<Object> getHistory(@PathVariable String sessionId) {
        String url = env("SUPABASE_URL",""); String key = env("SUPABASE_KEY","");
        if (url.isEmpty()) return ResponseEntity.ok(Map.of("messages", List.of()));
        return ResponseEntity.ok(Map.of("messages", loadHistory(sessionId, url, key)));
    }

    @GetMapping("/neural/profile/{sessionId}")
    public ResponseEntity<Object> getNeuralProfile(@PathVariable String sessionId) {
        return ResponseEntity.ok(Map.of(
                "profile", userProfiles.getOrDefault(sessionId, new HashMap<>()),
                "insights", userInsights.getOrDefault(sessionId, new ArrayList<>()),
                "totalRequests", totalRequests.get(),
                "agentUsage", agentUsage));
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String,String>> config() {
        return ResponseEntity.ok(Map.of("supabaseUrl", env("SUPABASE_URL",""), "supabaseKey", env("SUPABASE_KEY","")));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String,String>> health() {
        return ResponseEntity.ok(Map.of(
                "status",         "online",
                "model",          env("AI_MODEL","llama-3.3-70b-versatile"),
                "agents",         "131 agenti + sistema neurale adattivo",
                "features",       "thinking_mode,self_reflection,adaptive_learning,web_search,image_gen,spaces_voice",
                "webSearch",      !env("TAVILY_API_KEY","").isEmpty() ? "enabled" : "disabled",
                "images",         "enabled (Pollinations+HF)",
                "supabase",       !env("SUPABASE_URL","").isEmpty() ? "connected" : "off",
                "totalRequests",  String.valueOf(totalRequests.get()),
                "date",           today()));
    }
}
