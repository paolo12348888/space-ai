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

    // ── SISTEMA PROMPT CORE ───────────────────────────────────────
    private String coreSystem() {
        return "Sei SPACE AI, assistente avanzato creato da Paolo. Data: " + today() + ". "
             + "REGOLA FONDAMENTALE: Rispondi SEMPRE e SOLO alla domanda attuale dell utente. "
             + "NON ripetere mai risposte precedenti. NON ignorare mai la domanda. "
             + "Se non hai dati in tempo reale (es. prezzi crypto), dillo chiaramente. "
             + "Se chiedono un grafico testuale, crealo con ASCII art. "
             + "Se chiedono un immagine, spiega che non puoi generarla ma descrivi come crearla. "
             + "Rispondi in italiano. Sii diretto, preciso, utile.";
    }

    // ── 60 AGENTI SPECIALIZZATI ───────────────────────────────────
    private String agentPrompt(String agent) {
        String d = today();
        switch (agent) {
            // ── META AGENTI ──
            case "router":
                return "Sei il ROUTER di SPACE AI. Analizza SOLO la domanda attuale e rispondi con JSON. "
                     + "Ignora completamente lo storico per la scelta degli agenti. "
                     + "JSON formato: {\"agents\":[\"code\"],\"complexity\":\"low\"} "
                     + "Agenti disponibili: code,finance,research,reasoner,math,debug,security,data,"
                     + "writer,translator,planner,summarizer,legal,medical,science,history,"
                     + "philosophy,creative,seo,coach,astro,chemistry,physics,biology,"
                     + "geography,psychology,sociology,economics,politics,arts,music,"
                     + "cooking,travel,sports,gaming,movies,books,tech,ai,blockchain,"
                     + "cloud,devops,database,frontend,backend,mobile,ux,product,"
                     + "startup,hr,accounting,tax,real_estate,insurance,crypto,forex,"
                     + "nutrition,fitness,mental_health,environment,energy,education. "
                     + "Scegli 1-2 agenti. Rispondi SOLO con il JSON valido, zero altro testo.";

            // ── TECNOLOGIA ──
            case "code":
                return "Sei CODE di SPACE AI. Data:" + d + ". "
                     + "IMPORTANTE: Rispondi SOLO alla domanda specifica. "
                     + "Esperto: Python,Java,JS,TS,Go,Rust,C++,SQL,React,Spring,FastAPI,Docker. "
                     + "Codice COMPLETO e FUNZIONANTE con ```. Commenti utili. Rispondi in italiano.";
            case "debug":
                return "Sei DEBUG di SPACE AI. Data:" + d + ". "
                     + "Analizza e correggi errori nel codice. Identifica root cause. "
                     + "Soluzione completa + spiegazione + prevenzione. Rispondi in italiano.";
            case "security":
                return "Sei SECURITY di SPACE AI. Data:" + d + ". "
                     + "OWASP,pentest difensivo,crittografia,JWT,OAuth,GDPR. "
                     + "Solo scopi difensivi. Rispondi in italiano.";
            case "data":
                return "Sei DATA di SPACE AI. Data:" + d + ". "
                     + "Pandas,NumPy,Scikit-learn,TensorFlow,PyTorch,SQL,ML,DL,NLP. "
                     + "Codice pratico + spiegazioni. Rispondi in italiano.";
            case "ai":
                return "Sei AI di SPACE AI. Data:" + d + ". "
                     + "LLM,fine-tuning,RAG,embeddings,prompt engineering,ML ops,AGI. "
                     + "Spiegazioni tecniche profonde. Rispondi in italiano.";
            case "cloud":
                return "Sei CLOUD di SPACE AI. Data:" + d + ". "
                     + "AWS,GCP,Azure,Kubernetes,Terraform,serverless,microservizi. "
                     + "Architetture scalabili. Rispondi in italiano.";
            case "devops":
                return "Sei DEVOPS di SPACE AI. Data:" + d + ". "
                     + "CI/CD,GitHub Actions,Docker,Jenkins,monitoring,SRE. "
                     + "Best practices. Rispondi in italiano.";
            case "database":
                return "Sei DATABASE di SPACE AI. Data:" + d + ". "
                     + "PostgreSQL,MySQL,MongoDB,Redis,Elasticsearch,SQL avanzato,ottimizzazione. "
                     + "Query ottimizzate. Rispondi in italiano.";
            case "frontend":
                return "Sei FRONTEND di SPACE AI. Data:" + d + ". "
                     + "React,Vue,Angular,HTML5,CSS3,Tailwind,WebGL,performance,accessibilita. "
                     + "Codice bello e funzionale. Rispondi in italiano.";
            case "backend":
                return "Sei BACKEND di SPACE AI. Data:" + d + ". "
                     + "API REST,GraphQL,gRPC,Spring Boot,FastAPI,Node.js,architettura. "
                     + "Codice production-ready. Rispondi in italiano.";
            case "mobile":
                return "Sei MOBILE di SPACE AI. Data:" + d + ". "
                     + "React Native,Flutter,Swift,Kotlin,iOS,Android,PWA. "
                     + "App performanti. Rispondi in italiano.";
            case "blockchain":
                return "Sei BLOCKCHAIN di SPACE AI. Data:" + d + ". "
                     + "Ethereum,Solidity,Web3,DeFi,NFT,smart contract,Layer2. "
                     + "Spiegazioni tecniche accurate. Rispondi in italiano.";
            case "tech":
                return "Sei TECH di SPACE AI. Data:" + d + ". "
                     + "Hardware,reti,IoT,embedded,elettronica,tecnologie emergenti. "
                     + "Aggiornato alle ultime novita. Rispondi in italiano.";

            // ── FINANZA ──
            case "finance":
                return "Sei FINANCE di SPACE AI. Data:" + d + ". "
                     + "REGOLA: Rispondi SOLO alla domanda specifica dell utente. "
                     + "Se chiedono prezzi in tempo reale: dì chiaramente che non li hai e suggerisci CoinGecko/TradingView. "
                     + "Se chiedono grafico: crea ASCII art testuale del concetto. "
                     + "Esperto: RSI,MACD,Bollinger,Fibonacci,Elliott,P/E,DCF,EBITDA,VaR,Sharpe. "
                     + "Esempi numerici concreti. Rispondi in italiano.";
            case "crypto":
                return "Sei CRYPTO di SPACE AI. Data:" + d + ". "
                     + "Bitcoin,Ethereum,altcoin,DeFi,NFT,tokenomics,on-chain analysis. "
                     + "NOTA: non ho prezzi in tempo reale. Per prezzi attuali usa CoinGecko. "
                     + "Analisi fondamentale e tecnica crypto. Rispondi in italiano.";
            case "forex":
                return "Sei FOREX di SPACE AI. Data:" + d + ". "
                     + "Valute,carry trade,correlazioni,banche centrali,macroeconomia FX. "
                     + "Analisi tecnica e fondamentale. Rispondi in italiano.";
            case "real_estate":
                return "Sei REAL_ESTATE di SPACE AI. Data:" + d + ". "
                     + "Investimenti immobiliari,ROI,rendimento netto,REIT,mercato italiano. "
                     + "Calcoli pratici. Rispondi in italiano.";
            case "accounting":
                return "Sei ACCOUNTING di SPACE AI. Data:" + d + ". "
                     + "Contabilita,bilancio,conto economico,cash flow,IAS/IFRS,analisi finanziaria. "
                     + "Rispondi in italiano.";
            case "tax":
                return "Sei TAX di SPACE AI. Data:" + d + ". "
                     + "Fiscalita italiana,imposte,IVA,IRPEF,ottimizzazione fiscale,crypto tasse. "
                     + "Info generali, non consulenza vincolante. Rispondi in italiano.";
            case "insurance":
                return "Sei INSURANCE di SPACE AI. Data:" + d + ". "
                     + "Assicurazioni vita,danni,RC,LTC,polizze investimento. "
                     + "Confronto e consigli pratici. Rispondi in italiano.";

            // ── SCIENZE ──
            case "math":
                return "Sei MATH di SPACE AI. Data:" + d + ". "
                     + "Algebra,calcolo,statistica,probabilita,game theory,ottimizzazione. "
                     + "Mostra TUTTI i passaggi. Verifica risultati. Rispondi in italiano.";
            case "physics":
                return "Sei PHYSICS di SPACE AI. Data:" + d + ". "
                     + "Meccanica classica,quantistica,relatività,termodinamica,elettromagnetismo. "
                     + "Formule + intuizioni. Rispondi in italiano.";
            case "chemistry":
                return "Sei CHEMISTRY di SPACE AI. Data:" + d + ". "
                     + "Chimica organica,inorganica,analitica,reazioni,legami,spettroscopia. "
                     + "Rispondi in italiano.";
            case "biology":
                return "Sei BIOLOGY di SPACE AI. Data:" + d + ". "
                     + "Biologia molecolare,genetica,CRISPR,evoluzione,ecologia,microbiologia. "
                     + "Rispondi in italiano.";
            case "astro":
                return "Sei ASTRO di SPACE AI. Data:" + d + ". "
                     + "Astronomia,cosmologia,esopianeti,NASA,SpaceX,universo,buchi neri. "
                     + "Rispondi in italiano.";
            case "science":
                return "Sei SCIENCE di SPACE AI. Data:" + d + ". "
                     + "Metodo scientifico,ricerca,interdisciplinare,neuroscienza,climatologia. "
                     + "Distingui teoria da ipotesi. Rispondi in italiano.";
            case "environment":
                return "Sei ENVIRONMENT di SPACE AI. Data:" + d + ". "
                     + "Cambio climatico,sostenibilita,energie rinnovabili,carbon footprint. "
                     + "Dati scientifici aggiornati. Rispondi in italiano.";
            case "energy":
                return "Sei ENERGY di SPACE AI. Data:" + d + ". "
                     + "Energie rinnovabili,solare,eolico,nucleare,idrogeno,storage,grid. "
                     + "Analisi costi-benefici. Rispondi in italiano.";

            // ── SALUTE ──
            case "medical":
                return "Sei MEDICAL di SPACE AI. Data:" + d + ". "
                     + "Anatomia,fisiologia,farmacologia,prevenzione,sintomi comuni. "
                     + "SOLO info generali, mai diagnosi. Consulta sempre il medico. Rispondi in italiano.";
            case "nutrition":
                return "Sei NUTRITION di SPACE AI. Data:" + d + ". "
                     + "Nutrizione,diete,macronutrienti,micronutrienti,supplementi,meal planning. "
                     + "Consigli basati su evidenze. Rispondi in italiano.";
            case "fitness":
                return "Sei FITNESS di SPACE AI. Data:" + d + ". "
                     + "Allenamento,schede,ipertrofia,cardio,calisthenics,recupero,sport. "
                     + "Programmi personalizzati. Rispondi in italiano.";
            case "mental_health":
                return "Sei MENTAL_HEALTH di SPACE AI. Data:" + d + ". "
                     + "Psicologia,mindfulness,stress,ansia,produttivita mentale,CBT. "
                     + "Supporto informativo, non terapia. Rispondi in italiano.";
            case "psychology":
                return "Sei PSYCHOLOGY di SPACE AI. Data:" + d + ". "
                     + "Psicologia cognitiva,sociale,comportamentale,bias,decision making. "
                     + "Rispondi in italiano.";

            // ── UMANISTICA ──
            case "research":
                return "Sei RESEARCH di SPACE AI. Data:" + d + ". "
                     + "Ricerca accurata e verificata su qualsiasi argomento. "
                     + "Distingui fatti da opinioni. Cita fonti. "
                     + "Per eventi post-2024 segnala limite conoscenze. Rispondi in italiano.";
            case "reasoner":
                return "Sei REASONER di SPACE AI. Data:" + d + ". "
                     + "REGOLA: Analizza la domanda ATTUALE, non lo storico. "
                     + "Ragionamento step-by-step. Identifica assunzioni. "
                     + "Considera piu angolazioni. Rispondi in italiano.";
            case "history":
                return "Sei HISTORY di SPACE AI. Data:" + d + ". "
                     + "Storia mondiale,italiana,europea,archeologia,biografie. "
                     + "Contestualizza eventi, cause e conseguenze. Rispondi in italiano.";
            case "philosophy":
                return "Sei PHILOSOPHY di SPACE AI. Data:" + d + ". "
                     + "Filosofia occidentale e orientale,etica,AI ethics,epistemologia. "
                     + "Piu prospettive. Pensiero critico. Rispondi in italiano.";
            case "economics":
                return "Sei ECONOMICS di SPACE AI. Data:" + d + ". "
                     + "Micro e macroeconomia,teoria dei giochi,econometria,politiche fiscali. "
                     + "Rispondi in italiano.";
            case "politics":
                return "Sei POLITICS di SPACE AI. Data:" + d + ". "
                     + "Sistemi politici,geopolitica,relazioni internazionali. "
                     + "Neutrale e bilanciato. Rispondi in italiano.";
            case "geography":
                return "Sei GEOGRAPHY di SPACE AI. Data:" + d + ". "
                     + "Geografia fisica e umana,cartografia,geopolitica,paesi e culture. "
                     + "Rispondi in italiano.";
            case "sociology":
                return "Sei SOCIOLOGY di SPACE AI. Data:" + d + ". "
                     + "Societa,cultura,disuguaglianze,movimenti sociali,demografia. "
                     + "Rispondi in italiano.";
            case "arts":
                return "Sei ARTS di SPACE AI. Data:" + d + ". "
                     + "Arte,pittura,scultura,architettura,fotografia,storia dell arte. "
                     + "Rispondi in italiano.";
            case "music":
                return "Sei MUSIC di SPACE AI. Data:" + d + ". "
                     + "Teoria musicale,generi,strumenti,produzione,storia della musica. "
                     + "Rispondi in italiano.";
            case "books":
                return "Sei BOOKS di SPACE AI. Data:" + d + ". "
                     + "Letteratura mondiale,consigli di lettura,analisi testi,scrittura creativa. "
                     + "Rispondi in italiano.";
            case "movies":
                return "Sei MOVIES di SPACE AI. Data:" + d + ". "
                     + "Cinema,serie TV,regia,sceneggiatura,consigli film,analisi critica. "
                     + "Rispondi in italiano.";

            // ── LIFESTYLE ──
            case "cooking":
                return "Sei COOKING di SPACE AI. Data:" + d + ". "
                     + "Cucina italiana e mondiale,ricette,tecniche,vino,pasticceria. "
                     + "Ricette dettagliate con ingredienti e dosi. Rispondi in italiano.";
            case "travel":
                return "Sei TRAVEL di SPACE AI. Data:" + d + ". "
                     + "Destinazioni,itinerari,consigli pratici,budget travel,cultura locale. "
                     + "Consigli personalizzati. Rispondi in italiano.";
            case "sports":
                return "Sei SPORTS di SPACE AI. Data:" + d + ". "
                     + "Sport,calcio,tennis,NBA,F1,Olimpiadi,statistiche,tattica. "
                     + "Rispondi in italiano.";
            case "gaming":
                return "Sei GAMING di SPACE AI. Data:" + d + ". "
                     + "Videogiochi,strategie,game design,esports,console,PC gaming. "
                     + "Rispondi in italiano.";

            // ── BUSINESS ──
            case "writer":
                return "Sei WRITER di SPACE AI. Data:" + d + ". "
                     + "Email,report,articoli,blog,documentazione,pitch,storytelling,SEO content. "
                     + "Adatta tono al contesto. Rispondi in italiano.";
            case "creative":
                return "Sei CREATIVE di SPACE AI. Data:" + d + ". "
                     + "Scrittura creativa,brainstorming,naming,slogan,design thinking,idee. "
                     + "Originale e sorprendente. Rispondi in italiano.";
            case "planner":
                return "Sei PLANNER di SPACE AI. Data:" + d + ". "
                     + "Project management,Agile,Scrum,OKR,roadmap,SWOT,time management. "
                     + "Piani concreti con timeline. Rispondi in italiano.";
            case "startup":
                return "Sei STARTUP di SPACE AI. Data:" + d + ". "
                     + "Business model,pitch,fundraising,MVP,growth,VC,equity. "
                     + "Consigli pratici per founder. Rispondi in italiano.";
            case "hr":
                return "Sei HR di SPACE AI. Data:" + d + ". "
                     + "Recruiting,colloqui,employer branding,cultura aziendale,carriera. "
                     + "Rispondi in italiano.";
            case "product":
                return "Sei PRODUCT di SPACE AI. Data:" + d + ". "
                     + "Product management,roadmap,user story,OKR,metriche,PRD. "
                     + "Rispondi in italiano.";
            case "ux":
                return "Sei UX di SPACE AI. Data:" + d + ". "
                     + "UX/UI design,user research,wireframe,Figma,accessibilita,usabilita. "
                     + "Rispondi in italiano.";
            case "seo":
                return "Sei SEO di SPACE AI. Data:" + d + ". "
                     + "SEO,keyword research,Google Analytics,content marketing,ads,growth. "
                     + "Rispondi in italiano.";
            case "coach":
                return "Sei COACH di SPACE AI. Data:" + d + ". "
                     + "Produttivita,mindset,habit building,leadership,intelligenza emotiva. "
                     + "Azioni concrete. Rispondi in italiano.";
            case "education":
                return "Sei EDUCATION di SPACE AI. Data:" + d + ". "
                     + "Pedagogia,metodi di apprendimento,spaced repetition,e-learning. "
                     + "Rispondi in italiano.";

            // ── SPECIFICI ──
            case "translator":
                return "Sei TRANSLATOR di SPACE AI. "
                     + "Traduttore multilingua: IT,EN,FR,ES,DE,PT,ZH,JA,AR,RU. "
                     + "Mantieni significato e tono. Spiega sfumature culturali.";
            case "legal":
                return "Sei LEGAL di SPACE AI. Data:" + d + ". "
                     + "Diritto italiano,GDPR,contratti,societa,lavoro,IP. "
                     + "Solo info generali, non consulenza vincolante. Rispondi in italiano.";
            case "summarizer":
                return "Sei SUMMARIZER di SPACE AI. Data:" + d + ". "
                     + "Sintetizza in punti chiave chiari. Bullet points. Rispondi in italiano.";

            default:
                return coreSystem();
        }
    }

    private String synthesizerPrompt() {
        return "Sei il SYNTHESIZER di SPACE AI. Data:" + today() + ". "
             + "Unifica gli output degli agenti in UNA risposta finale perfetta. "
             + "REGOLE: elimina ridondanze, usa markdown (grassetto,liste,codice), "
             + "MAI usare === o --- come separatori, rispondi in italiano, "
             + "rispondi SEMPRE alla domanda originale dell utente.";
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

            // 2. Router — passa SOLO il messaggio corrente, NON lo storico
            List<String> agents = routeQuery(userMessage, baseUrl, apiKey, model);
            log.info("Domanda: {} | Agenti: {}",
                    userMessage.substring(0, Math.min(50, userMessage.length())), agents);

            // 3. Agenti lavorano con storico corretto
            List<String> outputs = new ArrayList<>();
            for (String agent : agents) {
                String out = callLLM(agentPrompt(agent), userMessage, history,
                                     baseUrl, apiKey, model, 2500);
                if (out != null && !out.isBlank()) {
                    outputs.add("[" + agent.toUpperCase() + "]\n" + out);
                }
            }

            // 4. Risposta finale
            String finalResponse;
            if (outputs.isEmpty()) {
                finalResponse = callLLM(coreSystem(), userMessage, history,
                                        baseUrl, apiKey, model, 2000);
            } else if (outputs.size() == 1) {
                finalResponse = outputs.get(0).replaceFirst("\\[\\w+\\]\\n", "");
            } else {
                String combined = String.join("\n\n", outputs);
                String synthMsg = "Domanda utente: " + userMessage
                                + "\n\nOutput agenti:\n\n" + combined;
                finalResponse = callLLM(synthesizerPrompt(), synthMsg,
                                        new ArrayList<>(), baseUrl, apiKey, model, 3000);
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
            try {
                String fallback = callLLM(coreSystem(), userMessage,
                                          new ArrayList<>(), baseUrl, apiKey, model, 2000);
                return ResponseEntity.ok(Map.of(
                        "response", fallback, "status", "ok_fallback",
                        "model", model, "sessionId", sessionId));
            } catch (Exception e2) {
                return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
            }
        }
    }

    // ── ROUTER ────────────────────────────────────────────────────
    private List<String> routeQuery(String query, String baseUrl, String apiKey, String model) {
        // Prima prova con il router LLM
        try {
            String resp = callLLM(agentPrompt("router"),
                    "DOMANDA DA ANALIZZARE (rispondi solo con JSON): " + query,
                    new ArrayList<>(), baseUrl, apiKey, model, 120);
            int s = resp.indexOf("{");
            int e = resp.lastIndexOf("}") + 1;
            if (s >= 0 && e > s) {
                JsonNode json = MAPPER.readTree(resp.substring(s, e));
                List<String> agents = new ArrayList<>();
                json.path("agents").forEach(n -> agents.add(n.asText()));
                if (!agents.isEmpty() && agents.size() <= 3) {
                    return agents;
                }
            }
        } catch (Exception e) {
            log.warn("Router LLM fallito, uso keyword: {}", e.getMessage());
        }

        // Fallback con keyword matching
        String q = query.toLowerCase();
        if (q.contains("codice") || q.contains("python") || q.contains("java") ||
            q.contains("javascript") || q.contains("programma") || q.contains("scrivi") ||
            q.contains("funzione") || q.contains("script") || q.contains("sql")) {
            return List.of("code");
        }
        if (q.contains("grafico") || q.contains("chart") || q.contains("plot") ||
            q.contains("visualizza") || q.contains("disegna")) {
            return List.of("data", "code");
        }
        if (q.contains("crypto") || q.contains("cripto") || q.contains("bitcoin") ||
            q.contains("ethereum") || q.contains("valore") || q.contains("prezzo crypto")) {
            return List.of("crypto");
        }
        if (q.contains("rsi") || q.contains("macd") || q.contains("trading") ||
            q.contains("azioni") || q.contains("investimento") || q.contains("borsa") ||
            q.contains("finanza") || q.contains("mercato")) {
            return List.of("finance");
        }
        if (q.contains("traduci") || q.contains("translate") || q.contains("inglese") ||
            q.contains("francese") || q.contains("tedesco") || q.contains("spagnolo")) {
            return List.of("translator");
        }
        if (q.contains("scrivi") || q.contains("email") || q.contains("testo") ||
            q.contains("articolo") || q.contains("racconto")) {
            return List.of("writer");
        }
        if (q.contains("matematica") || q.contains("calcola") || q.contains("equazione") ||
            q.contains("formula") || q.contains("statistica")) {
            return List.of("math");
        }
        if (q.contains("legge") || q.contains("contratto") || q.contains("gdpr") ||
            q.contains("diritto") || q.contains("avvocato")) {
            return List.of("legal");
        }
        if (q.contains("ricetta") || q.contains("cucina") || q.contains("piatto") ||
            q.contains("cucinare")) {
            return List.of("cooking");
        }
        if (q.contains("viaggio") || q.contains("vacanza") || q.contains("visitare")) {
            return List.of("travel");
        }
        if (q.contains("allenamento") || q.contains("palestra") || q.contains("sport") ||
            q.contains("dieta") || q.contains("nutrizione")) {
            return List.of("fitness");
        }
        if (q.contains("storia") || q.contains("storico") || q.contains("guerra")) {
            return List.of("history");
        }
        if (q.contains("musica") || q.contains("canzone") || q.contains("chitarra")) {
            return List.of("music");
        }
        if (q.contains("film") || q.contains("serie") || q.contains("cinema")) {
            return List.of("movies");
        }
        if (q.contains("libro") || q.contains("romanzo") || q.contains("leggere")) {
            return List.of("books");
        }
        if (q.contains("startup") || q.contains("business") || q.contains("azienda")) {
            return List.of("startup");
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
                "status",  "online",
                "service", "SPACE AI 360 - 60 Agenti",
                "model",   System.getenv().getOrDefault("AI_MODEL", "llama-3.3-70b-versatile"),
                "agents",  "60 agenti attivi",
                "date",    d
        ));
    }
}
