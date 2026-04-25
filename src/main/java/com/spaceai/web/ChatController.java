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
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE dd MMMM yyyy HH:mm", Locale.ITALIAN));
    }

    // ── ENV HELPERS ──────────────────────────────────────────────
    private String env(String key, String def) {
        return System.getenv().getOrDefault(key, def);
    }

    // ── WEB SEARCH ───────────────────────────────────────────────
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
                sb.append("RISPOSTA: ").append(json.path("answer").asText()).append("\n\n");
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

    // ── GENERAZIONE IMMAGINI ─────────────────────────────────────
    private String generateImage(String prompt) {
        // Prima prova Pollinations.ai (gratis, no key, sempre disponibile)
        try {
            String encoded = java.net.URLEncoder.encode(prompt, "UTF-8").replace("+", "%20");
            String url = "https://image.pollinations.ai/prompt/" + encoded
                       + "?width=768&height=768&nologo=true&enhance=true&model=flux";
            log.info("Generazione immagine con Pollinations: {}", prompt.substring(0, Math.min(50, prompt.length())));
            org.springframework.web.client.RestTemplate rt = new org.springframework.web.client.RestTemplate();
            // Timeout lungo per generazione
            rt.getMessageConverters().add(new org.springframework.http.converter.ByteArrayHttpMessageConverter());
            ResponseEntity<byte[]> resp = rt.getForEntity(url, byte[].class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null && resp.getBody().length > 5000) {
                log.info("Immagine generata con Pollinations ({} bytes)", resp.getBody().length);
                return "IMAGE:" + Base64.getEncoder().encodeToString(resp.getBody());
            }
        } catch (Exception e) {
            log.warn("Pollinations fallito: {}", e.getMessage());
        }

        // Fallback: HuggingFace con i modelli disponibili
        String hfKey = env("HF_TOKEN", "");
        if (!hfKey.isEmpty()) {
            String[] models = {
                "black-forest-labs/FLUX.1-schnell",
                "stabilityai/stable-diffusion-xl-base-1.0",
                "stabilityai/stable-diffusion-2-1"
            };
            for (String model : models) {
                try {
                    log.info("Tentativo HF model: {}", model);
                    HttpHeaders h = new HttpHeaders();
                    h.setContentType(MediaType.APPLICATION_JSON);
                    h.setBearerAuth(hfKey);
                    // Attendi se il modello e in cold start
                    ObjectNode req = MAPPER.createObjectNode();
                    req.put("inputs", prompt);
                    ObjectNode params = MAPPER.createObjectNode();
                    params.put("wait_for_model", true);
                    req.set("parameters", params);
                    ResponseEntity<byte[]> resp = restTemplate.postForEntity(
                            "https://api-inference.huggingface.co/models/" + model,
                            new HttpEntity<>(MAPPER.writeValueAsString(req), h), byte[].class);
                    if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null && resp.getBody().length > 1000) {
                        log.info("Immagine generata con HF {} ({} bytes)", model, resp.getBody().length);
                        return "IMAGE:" + Base64.getEncoder().encodeToString(resp.getBody());
                    }
                } catch (Exception e) {
                    log.warn("HF {} fallito: {}", model, e.getMessage());
                }
            }
        }

        return "Non riesco a generare l'immagine. Riprova tra qualche minuto.";
    }

    // ── SYSTEM PROMPT BASE ───────────────────────────────────────
    private String coreSystem() {
        return "Sei SPACE AI, assistente avanzato creato da Paolo. Data: " + today() + ". " +
               "Rispondi SEMPRE alla domanda attuale. Se ci sono [DATI WEB] usali. " +
               "Rispondi in italiano. Usa markdown. Sii preciso e completo.";
    }

    // ── TUTTI GLI AGENTI ─────────────────────────────────────────
    private String agentPrompt(String agent) {
        String d = today();
        switch (agent) {
            case "router":
                return "Sei il ROUTER di SPACE AI. Analizza SOLO la domanda attuale. " +
                       "Rispondi SOLO con JSON valido: {\"agents\":[\"code\"],\"complexity\":\"low\"} " +
                       "Agenti: code,debug,debug2,security,cybersec,data,ai,cloud,devops,database,frontend,backend," +
                       "mobile,blockchain,tech,optimizer,integrator,automator,autonomous," +
                       "iot,robotics,quantum,ar_vr,gamedev,scraping,devrel,mlops,llm_fine,agent_ai,api_design," +
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
                       "debate,interview,language,mindmap,prompt_eng,video_gen,audio_gen," +
                       "image_gen,spaces. Scegli 1-2 agenti. SOLO JSON.";

            case "spaces":
                return "Sei SPACES, l'assistente vocale personale di SPACE AI. Data:" + d + ". " +
                       "Personalita: professionale, amichevole, proattivo. Voce di SPACE AI. " +
                       "Rispondi in modo CONCISO (max 3 frasi) perche la risposta verra letta ad alta voce. " +
                       "Capacita: pianificazione giornata, notizie aggiornate, analisi dati, ricerca web, " +
                       "promemoria, briefing mattutino, supporto decisionale. " +
                       "Se ci sono [DATI WEB] usali per dare informazioni aggiornate. " +
                       "Inizia sempre con 'SPACES:' seguito dalla risposta.";

            case "image_gen":
                return "Sei IMAGE_GEN di SPACE AI. Data:" + d + ". " +
                       "Sei specializzato nella generazione di immagini. " +
                       "Quando richiesto: rispondi con [GENERA_IMMAGINE: descrizione dettagliata in inglese] " +
                       "poi descrivi cosa hai creato in italiano. Sii specifico su stile, colori, composizione.";

            case "code":
                return "Sei CODE di SPACE AI. Data:" + d + ". " +
                       "Python, Java, JS, TS, Go, Rust, C++, SQL, React, Spring, FastAPI, Docker. " +
                       "Codice COMPLETO con ```. Mai troncare. Rispondi in italiano.";

            case "debug":
                return "Sei DEBUG di SPACE AI. Data:" + d + ". " +
                       "Root cause analysis + soluzione completa + prevenzione. Rispondi in italiano.";

            case "security":
                return "Sei SECURITY di SPACE AI. Data:" + d + ". " +
                       "OWASP, crittografia, JWT, OAuth2, GDPR. Solo difensivo. Rispondi in italiano.";

            case "data":
                return "Sei DATA di SPACE AI. Data:" + d + ". " +
                       "Pandas, NumPy, Scikit-learn, TensorFlow, PyTorch, SQL, ETL. Rispondi in italiano.";

            case "ai":
                return "Sei AI-EXPERT di SPACE AI. Data:" + d + ". " +
                       "LLM, fine-tuning, RAG, embeddings, prompt engineering, MLops. Rispondi in italiano.";

            case "cloud":
                return "Sei CLOUD di SPACE AI. Data:" + d + ". AWS, GCP, Azure, Kubernetes, Terraform. Rispondi in italiano.";

            case "devops":
                return "Sei DEVOPS di SPACE AI. Data:" + d + ". CI/CD, Docker, GitHub Actions, monitoring. Rispondi in italiano.";

            case "database":
                return "Sei DATABASE di SPACE AI. Data:" + d + ". PostgreSQL, MySQL, MongoDB, Redis, query optimization. Rispondi in italiano.";

            case "frontend":
                return "Sei FRONTEND di SPACE AI. Data:" + d + ". React, Vue, HTML5, CSS3, Tailwind. Rispondi in italiano.";

            case "backend":
                return "Sei BACKEND di SPACE AI. Data:" + d + ". API REST, GraphQL, Spring Boot, FastAPI. Rispondi in italiano.";

            case "mobile":
                return "Sei MOBILE di SPACE AI. Data:" + d + ". React Native, Flutter, Swift, Kotlin. Rispondi in italiano.";

            case "blockchain":
                return "Sei BLOCKCHAIN di SPACE AI. Data:" + d + ". Ethereum, Solidity, Web3, DeFi. Rispondi in italiano.";

            case "tech":
                return "Sei TECH di SPACE AI. Data:" + d + ". Hardware, reti, IoT, 5G, quantum computing. Rispondi in italiano.";

            case "optimizer":
                return "Sei OPTIMIZER di SPACE AI. Data:" + d + ". Performance tuning, caching, profiling. Rispondi in italiano.";

            case "integrator":
                return "Sei INTEGRATOR di SPACE AI. Data:" + d + ". API integration, webhooks, ETL, middleware. Rispondi in italiano.";

            case "automator":
                return "Sei AUTOMATOR di SPACE AI. Data:" + d + ". RPA, workflow automation, scripting. Rispondi in italiano.";

            case "autonomous":
                return "Sei AUTONOMOUS di SPACE AI. Data:" + d + ". " +
                       "Agente autonomo multi-step. Per ogni task: 1)Analizza 2)Pianifica 3)Esegui 4)Verifica. Rispondi in italiano.";

            case "finance":
                return "Sei FINANCE di SPACE AI. Data:" + d + ". " +
                       "RSI, MACD, Bollinger, P/E, DCF, VaR, Sharpe. Esempi numerici. Rispondi in italiano.";

            case "crypto":
                return "Sei CRYPTO di SPACE AI. Data:" + d + ". Bitcoin, Ethereum, DeFi, NFT, on-chain analysis. Rispondi in italiano.";

            case "forex":
                return "Sei FOREX di SPACE AI. Data:" + d + ". Valute, carry trade, banche centrali. Rispondi in italiano.";

            case "trader":
                return "Sei TRADER di SPACE AI. Data:" + d + ". Trading algoritmico, backtesting, quant strategies. Rispondi in italiano.";

            case "investor":
                return "Sei INVESTOR di SPACE AI. Data:" + d + ". Value investing, growth, portfolio, ETF. Rispondi in italiano.";

            case "real_estate":
                return "Sei REAL_ESTATE di SPACE AI. Data:" + d + ". Immobiliare, ROI, REIT. Rispondi in italiano.";

            case "accounting":
                return "Sei ACCOUNTING di SPACE AI. Data:" + d + ". Bilancio, cash flow, IAS/IFRS. Rispondi in italiano.";

            case "tax":
                return "Sei TAX di SPACE AI. Data:" + d + ". Fiscalita italiana, IVA, IRPEF, crypto tasse. Rispondi in italiano.";

            case "auditor":
                return "Sei AUDITOR di SPACE AI. Data:" + d + ". Audit, compliance, risk assessment. Rispondi in italiano.";

            case "analyst":
                return "Sei ANALYST di SPACE AI. Data:" + d + ". Business intelligence, KPI, dashboard, reporting. Rispondi in italiano.";

            case "math":
                return "Sei MATH di SPACE AI. Data:" + d + ". Mostra TUTTI i passaggi. Algebra, calcolo, statistica. Rispondi in italiano.";

            case "physics":
                return "Sei PHYSICS di SPACE AI. Data:" + d + ". Meccanica, quantistica, relativita. Rispondi in italiano.";

            case "chemistry":
                return "Sei CHEMISTRY di SPACE AI. Data:" + d + ". Chimica organica, reazioni. Rispondi in italiano.";

            case "biology":
                return "Sei BIOLOGY di SPACE AI. Data:" + d + ". Genetica, CRISPR, evoluzione. Rispondi in italiano.";

            case "astro":
                return "Sei ASTRO di SPACE AI. Data:" + d + ". Astronomia, cosmologia, esopianeti. Rispondi in italiano.";

            case "science":
                return "Sei SCIENCE di SPACE AI. Data:" + d + ". Metodo scientifico, interdisciplinare. Rispondi in italiano.";

            case "environment":
                return "Sei ENVIRONMENT di SPACE AI. Data:" + d + ". Clima, sostenibilita, carbon footprint. Rispondi in italiano.";

            case "energy":
                return "Sei ENERGY di SPACE AI. Data:" + d + ". Solare, eolico, nucleare, idrogeno. Rispondi in italiano.";

            case "researcher2":
                return "Sei RESEARCHER2 di SPACE AI. Data:" + d + ". Ricerca scientifica, paper review, meta-analisi. Rispondi in italiano.";

            case "predictor":
                return "Sei PREDICTOR di SPACE AI. Data:" + d + ". Previsioni, trend analysis, forecasting. Rispondi in italiano.";

            case "medical":
                return "Sei MEDICAL di SPACE AI. Data:" + d + ". Solo info generali, mai diagnosi. Rispondi in italiano.";

            case "nutrition":
                return "Sei NUTRITION di SPACE AI. Data:" + d + ". Diete, macronutrienti, meal planning. Rispondi in italiano.";

            case "fitness":
                return "Sei FITNESS di SPACE AI. Data:" + d + ". Allenamento, schede, sport. Rispondi in italiano.";

            case "mental_health":
                return "Sei MENTAL_HEALTH di SPACE AI. Data:" + d + ". Solo supporto informativo. Rispondi in italiano.";

            case "psychology":
                return "Sei PSYCHOLOGY di SPACE AI. Data:" + d + ". Psicologia cognitiva, bias, decision making. Rispondi in italiano.";

            case "research":
                return "Sei RESEARCH di SPACE AI. Data:" + d + ". Info accurate. Se ci sono [DATI WEB] usali. Rispondi in italiano.";

            case "reasoner":
                return "Sei REASONER di SPACE AI. Data:" + d + ". Analisi step-by-step, logica. Rispondi in italiano.";

            case "history":
                return "Sei HISTORY di SPACE AI. Data:" + d + ". Storia mondiale, cause e conseguenze. Rispondi in italiano.";

            case "philosophy":
                return "Sei PHILOSOPHY di SPACE AI. Data:" + d + ". Etica, epistemologia, AI ethics. Rispondi in italiano.";

            case "economics":
                return "Sei ECONOMICS di SPACE AI. Data:" + d + ". Micro e macro, teoria dei giochi. Rispondi in italiano.";

            case "politics":
                return "Sei POLITICS di SPACE AI. Data:" + d + ". Neutrale e bilanciato. Rispondi in italiano.";

            case "geography":
                return "Sei GEOGRAPHY di SPACE AI. Data:" + d + ". Paesi e culture. Rispondi in italiano.";

            case "journalist":
                return "Sei JOURNALIST di SPACE AI. Data:" + d + ". Fact-checking, analisi media, investigative. Rispondi in italiano.";

            case "ethicist":
                return "Sei ETHICIST di SPACE AI. Data:" + d + ". Etica applicata, AI ethics, bioetica. Rispondi in italiano.";

            case "linguist":
                return "Sei LINGUIST di SPACE AI. Data:" + d + ". Linguistica, semantica, NLP, etimologia. Rispondi in italiano.";

            case "arts":
                return "Sei ARTS di SPACE AI. Data:" + d + ". Arte, pittura, architettura, fotografia. Rispondi in italiano.";

            case "music":
                return "Sei MUSIC di SPACE AI. Data:" + d + ". Teoria, strumenti, produzione musicale. Rispondi in italiano.";

            case "books":
                return "Sei BOOKS di SPACE AI. Data:" + d + ". Letteratura, consigli lettura, analisi. Rispondi in italiano.";

            case "movies":
                return "Sei MOVIES di SPACE AI. Data:" + d + ". Cinema, serie TV, critica. Rispondi in italiano.";

            case "writer":
                return "Sei WRITER di SPACE AI. Data:" + d + ". Email, report, storytelling, copywriting. Rispondi in italiano.";

            case "creative":
                return "Sei CREATIVE di SPACE AI. Data:" + d + ". Brainstorming, naming, design thinking. Rispondi in italiano.";

            case "designer":
                return "Sei DESIGNER di SPACE AI. Data:" + d + ". Graphic design, brand identity, UI. Rispondi in italiano.";

            case "architect":
                return "Sei ARCHITECT di SPACE AI. Data:" + d + ". Software architecture, system design, patterns. Rispondi in italiano.";

            case "innovator":
                return "Sei INNOVATOR di SPACE AI. Data:" + d + ". Innovation, first principles, disruption. Rispondi in italiano.";

            case "planner":
                return "Sei PLANNER di SPACE AI. Data:" + d + ". Agile, OKR, roadmap, SWOT. Rispondi in italiano.";

            case "startup":
                return "Sei STARTUP di SPACE AI. Data:" + d + ". Pitch, fundraising, MVP, growth hacking. Rispondi in italiano.";

            case "hr":
                return "Sei HR di SPACE AI. Data:" + d + ". Recruiting, carriera, employer branding. Rispondi in italiano.";

            case "product":
                return "Sei PRODUCT di SPACE AI. Data:" + d + ". Roadmap, user story, metriche, PRD. Rispondi in italiano.";

            case "ux":
                return "Sei UX di SPACE AI. Data:" + d + ". UX/UI, user research, Figma. Rispondi in italiano.";

            case "seo":
                return "Sei SEO di SPACE AI. Data:" + d + ". SEO, keyword, content marketing. Rispondi in italiano.";

            case "coach":
                return "Sei COACH di SPACE AI. Data:" + d + ". Mindset, produttivita, leadership. Rispondi in italiano.";

            case "education":
                return "Sei EDUCATION di SPACE AI. Data:" + d + ". Pedagogia, apprendimento. Rispondi in italiano.";

            case "negotiator":
                return "Sei NEGOTIATOR di SPACE AI. Data:" + d + ". Tecniche negoziazione, BATNA, win-win. Rispondi in italiano.";

            case "strategist":
                return "Sei STRATEGIST di SPACE AI. Data:" + d + ". Strategic planning, competitive analysis. Rispondi in italiano.";

            case "consultant":
                return "Sei CONSULTANT di SPACE AI. Data:" + d + ". Business consulting, McKinsey frameworks. Rispondi in italiano.";

            case "translator":
                return "Sei TRANSLATOR di SPACE AI. Multilingua: IT, EN, FR, ES, DE, PT, ZH, JA, AR.";

            case "legal":
                return "Sei LEGAL di SPACE AI. Data:" + d + ". Solo info generali, non consulenza. Rispondi in italiano.";

            case "summarizer":
                return "Sei SUMMARIZER di SPACE AI. Bullet points chiari e concisi. Rispondi in italiano.";

            case "cooking":
                return "Sei COOKING di SPACE AI. Data:" + d + ". Ricette con dosi precise. Rispondi in italiano.";

            case "travel":
                return "Sei TRAVEL di SPACE AI. Data:" + d + ". Destinazioni, itinerari, budget. Rispondi in italiano.";

            case "sports":
                return "Sei SPORTS di SPACE AI. Data:" + d + ". Sport, tattica, statistiche. Rispondi in italiano.";

            case "gaming":
                return "Sei GAMING di SPACE AI. Data:" + d + ". Videogiochi, strategie, esports. Rispondi in italiano.";

            case "monitor":
                return "Sei MONITOR di SPACE AI. Data:" + d + ". Monitoring, alerting, incident management. Rispondi in italiano.";

            case "classifier":
                return "Sei CLASSIFIER di SPACE AI. Data:" + d + ". Classificazione testi, sentiment analysis. Rispondi in italiano.";

            case "extractor":
                return "Sei EXTRACTOR di SPACE AI. Data:" + d + ". Estrazione info da testi, NER, parsing. Rispondi in italiano.";


            case "video_gen":
                return "Sei VIDEO_GEN di SPACE AI. Data:" + d + ". "
                     + "Generazione video AI: RunwayML, Sora, Pika, Kling, storyboard. "
                     + "Prompt ottimizzati per video, script, scene. Rispondi in italiano.";
            case "audio_gen":
                return "Sei AUDIO_GEN di SPACE AI. Data:" + d + ". "
                     + "Generazione audio AI: ElevenLabs, Suno, Udio, voice cloning, podcast. "
                     + "Script audio, jingle, voci sintetiche. Rispondi in italiano.";
            case "prompt_eng":
                return "Sei PROMPT_ENGINEER di SPACE AI. Data:" + d + ". "
                     + "Prompt engineering avanzato: chain-of-thought, few-shot, zero-shot, ReAct. "
                     + "Ottimizza prompt per LLM, Midjourney, Stable Diffusion. Rispondi in italiano.";
            case "cybersec":
                return "Sei CYBERSEC di SPACE AI. Data:" + d + ". "
                     + "Cybersecurity: CTF, forensics, malware analysis, threat intelligence, SIEM, SOC. "
                     + "Solo uso difensivo ed educativo. Rispondi in italiano.";
            case "quant":
                return "Sei QUANT di SPACE AI. Data:" + d + ". "
                     + "Quantitative finance: Black-Scholes, Monte Carlo, HFT, alpha generation. "
                     + "Codice Python per backtesting e modelli stocastici. Rispondi in italiano.";
            case "defi":
                return "Sei DEFI di SPACE AI. Data:" + d + ". "
                     + "DeFi: yield farming, AMM, DEX, lending, flash loans, MEV, tokenomics. "
                     + "Analisi rischi e opportunita. Rispondi in italiano.";
            case "nft":
                return "Sei NFT di SPACE AI. Data:" + d + ". "
                     + "NFT: minting, marketplace, rarity, ERC-721/1155, generative art, royalties. Rispondi in italiano.";
            case "web3":
                return "Sei WEB3 di SPACE AI. Data:" + d + ". "
                     + "Web3: dApp, wallet, IPFS, DAO, Layer2, cross-chain, Hardhat, Foundry. Rispondi in italiano.";
            case "iot":
                return "Sei IOT di SPACE AI. Data:" + d + ". "
                     + "IoT: Arduino, Raspberry Pi, ESP32, MQTT, edge computing, home automation. Rispondi in italiano.";
            case "robotics":
                return "Sei ROBOTICS di SPACE AI. Data:" + d + ". "
                     + "Robotica: ROS, SLAM, computer vision, droni, autonomous vehicles, RL. Rispondi in italiano.";
            case "quantum":
                return "Sei QUANTUM di SPACE AI. Data:" + d + ". "
                     + "Quantum computing: Qiskit, qubit, algoritmi Shor/Grover, quantum ML, cryptography. Rispondi in italiano.";
            case "ar_vr":
                return "Sei AR_VR di SPACE AI. Data:" + d + ". "
                     + "AR/VR: Unity, Unreal, WebXR, Apple Vision Pro, ARKit, spatial computing. Rispondi in italiano.";
            case "gamedev":
                return "Sei GAMEDEV di SPACE AI. Data:" + d + ". "
                     + "Game dev: Unity, Unreal, Godot, game design, shaders, monetization. Rispondi in italiano.";
            case "scraping":
                return "Sei SCRAPER di SPACE AI. Data:" + d + ". "
                     + "Web scraping etico: BeautifulSoup, Scrapy, Playwright, pipeline dati. Solo usi legali. Rispondi in italiano.";
            case "mlops":
                return "Sei MLOPS di SPACE AI. Data:" + d + ". "
                     + "MLOps: MLflow, Kubeflow, model serving, A/B testing, data drift, feature store. Rispondi in italiano.";
            case "llm_fine":
                return "Sei LLM_FINETUNER di SPACE AI. Data:" + d + ". "
                     + "Fine-tuning LLM: LoRA, QLoRA, RLHF, DPO, distillazione, dataset curation. Rispondi in italiano.";
            case "agent_ai":
                return "Sei AGENT_AI di SPACE AI. Data:" + d + ". "
                     + "Agentic AI: LangChain, LlamaIndex, AutoGen, CrewAI, tool use, multi-agent. Rispondi in italiano.";
            case "api_design":
                return "Sei API_DESIGNER di SPACE AI. Data:" + d + ". "
                     + "API design: REST, GraphQL, OpenAPI, versioning, rate limiting, API gateway. Rispondi in italiano.";
            case "biomed":
                return "Sei BIOMED di SPACE AI. Data:" + d + ". "
                     + "Biomedicina: genomica, drug discovery AI, AlphaFold, molecular docking. Solo info generali. Rispondi in italiano.";
            case "neuro":
                return "Sei NEURO di SPACE AI. Data:" + d + ". "
                     + "Neuroscienze: BCI, neuromorphic computing, cognitive science, AI e cervello. Rispondi in italiano.";
            case "climate":
                return "Sei CLIMATE di SPACE AI. Data:" + d + ". "
                     + "Climate tech: carbon capture, green hydrogen, ESG, carbon credits, net-zero. Rispondi in italiano.";
            case "space_tech":
                return "Sei SPACE_TECH di SPACE AI. Data:" + d + ". "
                     + "Tecnologia spaziale: SpaceX, NASA, satelliti, orbital mechanics, space economy. Rispondi in italiano.";
            case "fintech":
                return "Sei FINTECH di SPACE AI. Data:" + d + ". "
                     + "Fintech: open banking, BNPL, neobank, payment systems, embedded finance. Rispondi in italiano.";
            case "ecommerce":
                return "Sei ECOMMERCE di SPACE AI. Data:" + d + ". "
                     + "E-commerce: Shopify, Amazon FBA, dropshipping, funnel, conversion. Rispondi in italiano.";
            case "growth":
                return "Sei GROWTH di SPACE AI. Data:" + d + ". "
                     + "Growth hacking: viral loops, A/B testing, retention, LTV, CAC, product-led growth. Rispondi in italiano.";
            case "brand":
                return "Sei BRAND di SPACE AI. Data:" + d + ". "
                     + "Branding: identita visiva, positioning, storytelling, naming, brand voice. Rispondi in italiano.";
            case "pr":
                return "Sei PR di SPACE AI. Data:" + d + ". "
                     + "PR: comunicati stampa, crisis management, media relations, reputation. Rispondi in italiano.";
            case "social":
                return "Sei SOCIAL_MEDIA di SPACE AI. Data:" + d + ". "
                     + "Social: Instagram, TikTok, LinkedIn, viral content, community management. Rispondi in italiano.";
            case "ads":
                return "Sei ADS di SPACE AI. Data:" + d + ". "
                     + "Advertising: Google Ads, Meta Ads, TikTok Ads, ROAS, retargeting. Rispondi in italiano.";
            case "analytics":
                return "Sei ANALYTICS di SPACE AI. Data:" + d + ". "
                     + "Analytics: GA4, Mixpanel, Amplitude, Tableau, attribution modeling. Rispondi in italiano.";
            case "supply_chain":
                return "Sei SUPPLY_CHAIN di SPACE AI. Data:" + d + ". "
                     + "Supply chain: logistica, inventory, demand forecasting, lean, ERP, SAP. Rispondi in italiano.";
            case "pm":
                return "Sei PROJECT_MANAGER di SPACE AI. Data:" + d + ". "
                     + "PM: Scrum, Kanban, Prince2, PMP, risk management, Jira, Asana. Rispondi in italiano.";
            case "bizdev":
                return "Sei BIZDEV di SPACE AI. Data:" + d + ". "
                     + "Business dev: partnership, M&A, deal structuring, market entry, due diligence. Rispondi in italiano.";
            case "vc":
                return "Sei VC di SPACE AI. Data:" + d + ". "
                     + "Venture Capital: valuation, cap table, term sheet, portfolio, exit. Rispondi in italiano.";
            case "insurance":
                return "Sei INSURANCE di SPACE AI. Data:" + d + ". "
                     + "Assicurazioni: prodotti vita/danni, attuariato, Solvency II, insurtech. Rispondi in italiano.";
            case "fashion":
                return "Sei FASHION di SPACE AI. Data:" + d + ". "
                     + "Moda: trend forecasting, sustainable fashion, luxury market, fashion tech. Rispondi in italiano.";
            case "food_tech":
                return "Sei FOOD_TECH di SPACE AI. Data:" + d + ". "
                     + "Food tech: food science, alternative proteins, HACCP, agritech. Rispondi in italiano.";
            case "legal2":
                return "Sei LEGAL2 di SPACE AI. Data:" + d + ". "
                     + "Diritto avanzato: AI regulation, IP/brevetti, privacy law, compliance. Solo info. Rispondi in italiano.";
            case "tax2":
                return "Sei TAX2 di SPACE AI. Data:" + d + ". "
                     + "Fiscalita internazionale: transfer pricing, BEPS, treaty, trust. Solo info. Rispondi in italiano.";
            case "debate":
                return "Sei DEBATE di SPACE AI. Data:" + d + ". "
                     + "Argomentazione: logica formale, fallace, retorica, pro/contro bilanciati. Rispondi in italiano.";
            case "interview":
                return "Sei INTERVIEW di SPACE AI. Data:" + d + ". "
                     + "Colloqui: technical interview, behavioral, case study, salary negotiation. Rispondi in italiano.";
            case "language":
                return "Sei LANGUAGE di SPACE AI. Data:" + d + ". "
                     + "Lingue: metodi accelerati, grammatica, pronuncia, spaced repetition. Rispondi in italiano.";
            case "mindmap":
                return "Sei MINDMAP di SPACE AI. Data:" + d + ". "
                     + "Mappe mentali: struttura idee, knowledge graphs, brainstorming visivo. Rispondi in italiano.";
            case "debug2":
                return "Sei DEBUG2 di SPACE AI. Data:" + d + ". "
                     + "Debug avanzato: profiling memoria/CPU, distributed tracing, OpenTelemetry. Rispondi in italiano.";
            case "accounting2":
                return "Sei ACCOUNTING2 di SPACE AI. Data:" + d + ". "
                     + "Contabilita avanzata: IFRS 16, transfer pricing, M&A accounting, fair value. Rispondi in italiano.";
            case "pharma":
                return "Sei PHARMA di SPACE AI. Data:" + d + ". "
                     + "Farmaceutica: drug pipeline, FDA/EMA, clinical trials, pharmacoeconomics. Solo info. Rispondi in italiano.";
            case "devrel":
                return "Sei DEVREL di SPACE AI. Data:" + d + ". "
                     + "Developer Relations: documentazione, tutorial, SDK design, community. Rispondi in italiano.";

            default:
                return coreSystem();
        }
    }

    private String synthesizerPrompt() {
        return "Sei il SYNTHESIZER di SPACE AI. Data:" + today() + ". " +
               "Unifica in UNA risposta finale perfetta. Elimina ridondanze. " +
               "Usa markdown. Rispondi sempre in italiano.";
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

        String baseUrl     = env("AI_BASE_URL", "https://api.groq.com/openai/v1");
        String apiKey      = env("AI_API_KEY", "");
        String model       = env("AI_MODEL", "llama-3.3-70b-versatile");
        String supabaseUrl = env("SUPABASE_URL", "");
        String supabaseKey = env("SUPABASE_KEY", "");

        try {
            // Storico
            List<Map<String, String>> history = new ArrayList<>();
            if (!supabaseUrl.isEmpty() && !supabaseKey.isEmpty())
                history = loadHistory(sessionId, supabaseUrl, supabaseKey);

            // Web search
            String webData = needsSearch(userMessage) ? searchWeb(userMessage) : null;
            String enriched = userMessage;
            if (webData != null && !webData.isBlank())
                enriched = userMessage + "\n\n[DATI WEB - " + today() + "]:\n" + webData;

            // Gestione immagini
            String q = userMessage.toLowerCase();
            boolean isImg = q.contains("genera immagine") || q.contains("crea immagine") ||
                    q.contains("disegna") || q.contains("image of") ||
                    (q.contains("immagine") && (q.contains("crea") || q.contains("genera") || q.contains("mostra")));

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

                if (!supabaseUrl.isEmpty()) {
                    try { saveMsg(sessionId, "user", userMessage, supabaseUrl, supabaseKey);
                          saveMsg(sessionId, "assistant", textResp, supabaseUrl, supabaseKey); }
                    catch (Exception ex) { log.warn("Supabase: {}", ex.getMessage()); }
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

            // Router
            List<String> agents = routeQuery(userMessage, baseUrl, apiKey, model);
            log.info("Agenti: {} | WebSearch: {}", agents, webData != null);

            // Agenti
            List<String> outputs = new ArrayList<>();
            for (String agent : agents) {
                String out = callLLM(agentPrompt(agent), enriched, history, baseUrl, apiKey, model, 2500);
                if (out != null && !out.isBlank())
                    outputs.add("[" + agent.toUpperCase() + "]\n" + out);
            }

            // Risposta finale
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

            // Salva
            if (!supabaseUrl.isEmpty()) {
                try { saveMsg(sessionId, "user", userMessage, supabaseUrl, supabaseKey);
                      saveMsg(sessionId, "assistant", finalResponse, supabaseUrl, supabaseKey); }
                catch (Exception ex) { log.warn("Supabase: {}", ex.getMessage()); }
            }

            return ResponseEntity.ok(Map.of(
                    "response", finalResponse, "status", "ok", "model", model,
                    "agents", agents.toString(),
                    "webSearch", webData != null ? "true" : "false",
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
                    new ArrayList<>(), baseUrl, apiKey, model, 80);
            int s = resp.indexOf("{"), e = resp.lastIndexOf("}") + 1;
            if (s >= 0 && e > s) {
                JsonNode json = MAPPER.readTree(resp.substring(s, e));
                List<String> agents = new ArrayList<>();
                json.path("agents").forEach(n -> agents.add(n.asText()));
                if (!agents.isEmpty() && agents.size() <= 3) return agents;
            }
        } catch (Exception e) { log.warn("Router: {}", e.getMessage()); }

        String q = query.toLowerCase();
        if (q.contains("spaces") || q.contains("assistente vocale") || q.contains("briefing")) return List.of("spaces");
        if (q.contains("python") || q.contains("java") || q.contains("codice") || q.contains("funzione")) return List.of("code");
        if (q.contains("bitcoin") || q.contains("crypto") || q.contains("cripto")) return List.of("crypto");
        if (q.contains("trading") || q.contains("borsa") || q.contains("azioni")) return List.of("finance");
        if (q.contains("grafico") || q.contains("visualizza") || q.contains("chart")) return List.of("data", "code");
        if (q.contains("traduci") || q.contains("in inglese") || q.contains("traduzione")) return List.of("translator");
        if (q.contains("calcola") || q.contains("matematica") || q.contains("equazione")) return List.of("math");
        if (q.contains("bug") || q.contains("errore nel codice")) return List.of("debug");
        if (q.contains("legge") || q.contains("contratto") || q.contains("gdpr")) return List.of("legal");
        if (q.contains("ricetta") || q.contains("cucina")) return List.of("cooking");
        if (q.contains("viaggio") || q.contains("vacanza")) return List.of("travel");
        if (q.contains("allenamento") || q.contains("palestra")) return List.of("fitness");
        if (q.contains("notizie") || q.contains("news")) return List.of("research");
        if (q.contains("startup") || q.contains("business")) return List.of("startup");
        if (q.contains("automaz") || q.contains("automatizza")) return List.of("automator");
        if (q.contains("video") && (q.contains("genera") || q.contains("crea"))) return List.of("video_gen");
        if (q.contains("podcast") || q.contains("voice cloning") || q.contains("elevenlabs")) return List.of("audio_gen");
        if (q.contains("prompt") && (q.contains("ottimizza") || q.contains("midjourney"))) return List.of("prompt_eng");
        if (q.contains("pentest") || q.contains("vulnerabilit") || q.contains("ctf")) return List.of("cybersec");
        if (q.contains("black-scholes") || q.contains("monte carlo") || q.contains("quant")) return List.of("quant");
        if (q.contains("defi") || q.contains("yield farming") || q.contains("liquidity pool")) return List.of("defi");
        if (q.contains("nft") || q.contains("opensea") || q.contains("erc-721")) return List.of("nft");
        if (q.contains("dapp") || q.contains("web3") || q.contains("hardhat")) return List.of("web3");
        if (q.contains("arduino") || q.contains("raspberry") || q.contains("esp32")) return List.of("iot");
        if (q.contains("robot") || q.contains("drone") || q.contains("ros")) return List.of("robotics");
        if (q.contains("qubit") || q.contains("quantum") || q.contains("qiskit")) return List.of("quantum");
        if (q.contains("unity") || q.contains("unreal") || q.contains("game dev")) return List.of("gamedev");
        if (q.contains("scrapy") || q.contains("beautifulsoup") || q.contains("web scraping")) return List.of("scraping");
        if (q.contains("mlflow") || q.contains("kubeflow") || q.contains("mlops")) return List.of("mlops");
        if (q.contains("lora") || q.contains("qlora") || q.contains("fine-tun")) return List.of("llm_fine");
        if (q.contains("langchain") || q.contains("crewai") || q.contains("autogen")) return List.of("agent_ai");
        if (q.contains("openapi") || q.contains("swagger") || q.contains("api design")) return List.of("api_design");
        if (q.contains("fintech") || q.contains("neobank") || q.contains("open banking")) return List.of("fintech");
        if (q.contains("shopify") || q.contains("amazon fba") || q.contains("dropshipping")) return List.of("ecommerce");
        if (q.contains("growth hack") || q.contains("retention") || q.contains("ltv")) return List.of("growth");
        if (q.contains("naming") || q.contains("rebranding") || q.contains("brand identity")) return List.of("brand");
        if (q.contains("instagram") || q.contains("tiktok") || q.contains("social media")) return List.of("social");
        if (q.contains("google ads") || q.contains("meta ads") || q.contains("roas")) return List.of("ads");
        if (q.contains("venture capital") || q.contains("cap table") || q.contains("term sheet")) return List.of("vc");
        if (q.contains("m&a") || q.contains("due diligence") || q.contains("deal")) return List.of("bizdev");
        if (q.contains("colloquio") || q.contains("intervista lavoro")) return List.of("interview");
        if (q.contains("mappa mentale") || q.contains("knowledge graph")) return List.of("mindmap");
        if (q.contains("clima") || q.contains("carbon") || q.contains("green tech")) return List.of("climate");
        if (q.contains("nasa") || q.contains("spacex") || q.contains("satellite")) return List.of("space_tech");
        if (q.contains("genomica") || q.contains("alphafold") || q.contains("drug discovery")) return List.of("biomed");
        if (q.contains("fashion") || q.contains("moda") || q.contains("lusso")) return List.of("fashion");
        if (q.contains("analisi") || q.contains("analizza")) return List.of("analyst");
        if (q.contains("strategia") || q.contains("piano")) return List.of("strategist");
        return List.of("reasoner");
    }

    // ── CHIAMATA LLM ──────────────────────────────────────────────
    private String callLLM(String system, String userMsg, List<Map<String, String>> history,
                            String baseUrl, String apiKey, String model, int maxTokens) throws Exception {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("model", model); req.put("max_tokens", maxTokens);
        req.put("temperature", 0.8); req.put("top_p", 0.95);
        req.put("frequency_penalty", 0.3); req.put("presence_penalty", 0.3);

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
        usr.put("role", "user"); usr.put("content", userMsg);
        messages.add(usr);
        req.set("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (!apiKey.isEmpty()) headers.setBearerAuth(apiKey);
        headers.set("HTTP-Referer", "https://space-ai-940e.onrender.com");
        headers.set("X-Title", "SPACE AI");
        headers.set("ngrok-skip-browser-warning", "true");
        headers.set("User-Agent", "SPACE-AI/1.0");

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
                    url + "/rest/v1/messages?session_id=eq." + sessionId + "&order=created_at.asc&limit=20&select=role,content",
                    HttpMethod.GET, new HttpEntity<>(h), String.class);
            JsonNode arr = MAPPER.readTree(r.getBody());
            for (JsonNode n : arr)
                history.add(Map.of("role", n.path("role").asText(), "content", n.path("content").asText()));
        } catch (Exception e) { log.warn("Supabase load: {}", e.getMessage()); }
        return history;
    }

    private void saveMsg(String sessionId, String role, String content, String url, String key) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("apikey", key); h.set("Authorization", "Bearer " + key); h.set("Prefer", "return=minimal");
        ObjectNode b = MAPPER.createObjectNode();
        b.put("session_id", sessionId); b.put("role", role); b.put("content", content);
        restTemplate.postForEntity(url + "/rest/v1/messages",
                new HttpEntity<>(MAPPER.writeValueAsString(b), h), String.class);
    }

    @GetMapping("/history/{sessionId}")
    public ResponseEntity<Object> getHistory(@PathVariable String sessionId) {
        String url = env("SUPABASE_URL", ""); String key = env("SUPABASE_KEY", "");
        if (url.isEmpty()) return ResponseEntity.ok(Map.of("messages", List.of()));
        return ResponseEntity.ok(Map.of("messages", loadHistory(sessionId, url, key)));
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, String>> config() {
        return ResponseEntity.ok(Map.of(
                "supabaseUrl", env("SUPABASE_URL", ""),
                "supabaseKey", env("SUPABASE_KEY", "")));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status",    "online",
                "model",     env("AI_MODEL", "llama-3.3-70b-versatile"),
                "agents",    "131 agenti specializzati + SPACES voice",
                "webSearch", !env("TAVILY_API_KEY", "").isEmpty() ? "enabled" : "disabled",
                "images",    !env("HF_TOKEN", "").isEmpty() ? "enabled" : "disabled",
                "supabase",  !env("SUPABASE_URL", "").isEmpty() ? "connected" : "off",
                "date",      today()
        ));
    }
}
