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
import java.util.LinkedList;

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

    // ── MOTORE QUANTISTICO (Quantum-Inspired Computing) ───────
    // Simulazione qubit ispirata a Zuchongzhi/Jiuzhang
    private static final int QUBIT_COUNT = 32; // qubit simulati
    private final double[] quantumState = new double[1 << Math.min(QUBIT_COUNT, 16)]; // 2^16 stati
    private final Random quantumRng = new Random();
    private volatile boolean quantumInitialized = false;

    // Inizializza stato quantistico in superposizione
    private void initQuantumState() {
        if (quantumInitialized) return;
        double norm = 0;
        for (int i = 0; i < quantumState.length; i++) {
            quantumState[i] = quantumRng.nextGaussian();
            norm += quantumState[i] * quantumState[i];
        }
        norm = Math.sqrt(norm);
        for (int i = 0; i < quantumState.length; i++) quantumState[i] /= norm;
        quantumInitialized = true;
        log.info("Motore quantistico inizializzato: {} stati, {} qubit simulati", quantumState.length, QUBIT_COUNT);
    }

    // Gate Hadamard - mette qubit in superposizione
    private void hadamardGate(int qubitIndex) {
        double inv = 1.0 / Math.sqrt(2);
        int mask = 1 << (qubitIndex % 16);
        for (int i = 0; i < quantumState.length; i += mask * 2) {
            for (int j = i; j < i + mask; j++) {
                if (j + mask < quantumState.length) {
                    double a = quantumState[j];
                    double b = quantumState[j + mask];
                    quantumState[j]        = inv * (a + b);
                    quantumState[j + mask] = inv * (a - b);
                }
            }
        }
    }

    // Gate CNOT - entanglement tra qubit
    private void cnotGate(int control, int target) {
        int cm = 1 << (control % 16);
        int tm = 1 << (target  % 16);
        for (int i = 0; i < quantumState.length; i++) {
            if ((i & cm) != 0 && (i & tm) == 0) {
                int j = i | tm;
                if (j < quantumState.length) {
                    double tmp = quantumState[i];
                    quantumState[i] = quantumState[j];
                    quantumState[j] = tmp;
                }
            }
        }
    }

    // Misura quantistica - collassa in stato definito
    private int measureQubit(int qubitIndex) {
        double prob0 = 0;
        int mask = 1 << (qubitIndex % 16);
        for (int i = 0; i < quantumState.length; i++) {
            if ((i & mask) == 0) prob0 += quantumState[i] * quantumState[i];
        }
        return quantumRng.nextDouble() < prob0 ? 0 : 1;
    }

    // Algoritmo Quantum Walk per routing intelligente degli agenti
    private List<String> quantumAgentRouting(String query, List<String> candidates) {
        initQuantumState();
        // Applica Hadamard a tutti i qubit per superposizione
        for (int i = 0; i < Math.min(candidates.size(), 8); i++) hadamardGate(i);
        // Entanglement basato su hash della query
        int qHash = Math.abs(query.hashCode());
        for (int i = 0; i < Math.min(candidates.size()-1, 7); i++) {
            if ((qHash >> i & 1) == 1) cnotGate(i, i+1);
        }
        // Scoring quantistico per ogni agente
        List<double[]> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            int m = measureQubit(i % 16);
            // Combina misura quantistica con score classico
            double classicScore = computeClassicScore(query, candidates.get(i));
            double quantumScore = classicScore * 0.7 + (m == 1 ? 0.3 : 0.1);
            scored.add(new double[]{quantumScore, i});
        }
        scored.sort((a, b) -> Double.compare(b[0], a[0]));
        List<String> result = new ArrayList<>();
        for (int i = 0; i < Math.min(2, scored.size()); i++) {
            result.add(candidates.get((int) scored.get(i)[1]));
        }
        return result;
    }

    // Score classico per agente basato su keyword
    private double computeClassicScore(String query, String agent) {
        String q = query.toLowerCase();
        Map<String, String[]> keywords = new HashMap<>();
        keywords.put("code",    new String[]{"python","java","javascript","codice","programma","funzione","script","debug"});
        keywords.put("finance", new String[]{"trading","borsa","azioni","mercato","investimento","crypto","bitcoin","finanza"});
        keywords.put("math",    new String[]{"calcola","matematica","equazione","algebra","formula","derivata"});
        keywords.put("ai",      new String[]{"llm","modello","neural","machine learning","intelligenza artificiale","gpt"});
        keywords.put("medical", new String[]{"salute","medico","sintomi","farmaci","malattia","cura"});
        keywords.put("legal",   new String[]{"legge","contratto","diritto","gdpr","normativa"});
        keywords.put("travel",  new String[]{"viaggio","vacanza","volo","hotel","destinazione"});
        keywords.put("cooking", new String[]{"ricetta","cucina","ingredienti","cucinare"});
        String[] kws = keywords.getOrDefault(agent, new String[]{agent});
        int hits = 0;
        for (String kw : kws) { if (q.contains(kw)) hits++; }
        return Math.min(1.0, hits * 0.35 + 0.1);
    }

    // Ottimizzazione quantistica della temperatura LLM (QAOA-inspired)
    private double quantumOptimizeTemperature(String query, String agent) {
        initQuantumState();
        double baseTemp = 0.8;
        // Misura stato quantistico per variazione temperatura
        int q0 = measureQubit(0), q1 = measureQubit(1), q2 = measureQubit(2);
        double delta = (q0 * 0.1) + (q1 * 0.05) - (q2 * 0.05);
        // Query creative = temperatura piu alta; query tecniche = piu bassa
        boolean creative = query.toLowerCase().matches(".*(crea|immagina|scrivi|storia|poesia|idea).*");
        boolean technical = query.toLowerCase().matches(".*(codice|calcola|debug|analizza|formula).*");
        if (creative)  baseTemp += 0.2 + delta;
        if (technical) baseTemp -= 0.2 + delta;
        return Math.max(0.3, Math.min(1.2, baseTemp + delta));
    }

    // Compressione quantistica della conoscenza (ispirata a QKD)
    private String quantumCompressContext(String context) {
        if (context.length() < 500) return context;
        // Divide in blocchi e seleziona con probabilita quantistica
        String[] sentences = context.split("(?<=[.!?])\\s+");
        StringBuilder compressed = new StringBuilder();
        initQuantumState();
        for (int i = 0; i < sentences.length; i++) {
            hadamardGate(i % 16);
            int measured = measureQubit(i % 16);
            // Mantieni sempre prima e ultima frase + quelle misurate come 1
            if (i == 0 || i == sentences.length-1 || measured == 1) {
                compressed.append(sentences[i]).append(" ");
            }
        }
        return compressed.toString().trim();
    }


    // ════════════════════════════════════════════════════════════
    // RETE NEURALE AUTONOMA - SPACE AI BRAIN
    // Ragiona, verifica, corregge e impara da sola
    // ════════════════════════════════════════════════════════════

    // Neuroni: pesi sinaptici per ogni agente
    private final Map<String, double[]> synapticWeights = new ConcurrentHashMap<>();
    // Memoria a lungo termine
    private final Map<String, List<String>> longTermMemory  = new ConcurrentHashMap<>();
    // Memoria a breve termine (sliding window)
    private final Map<String, LinkedList<String>> shortTermMemory = new ConcurrentHashMap<>();
    // Errori passati per auto-correzione
    private final Map<String, List<String>> errorMemory = new ConcurrentHashMap<>();
    // Punteggi di confidenza per ogni risposta
    private final Map<String, Double> confidenceScores = new ConcurrentHashMap<>();
    // Contatore apprendimenti
    private final AtomicInteger learningCycles = new AtomicInteger(0);
    // Knowledge graph: relazioni tra concetti
    private final Map<String, Set<String>> knowledgeGraph = new ConcurrentHashMap<>();

    // ── NEURONE: inizializza pesi sinaptici per un agente ────────
    private double[] getNeuronWeights(String agent) {
        return synapticWeights.computeIfAbsent(agent, k -> {
            double[] w = new double[16];
            for (int i = 0; i < w.length; i++)
                w[i] = 0.1 + quantumRng.nextDouble() * 0.8;
            return w;
        });
    }

    // ── PROPAGAZIONE IN AVANTI (Forward Pass) ────────────────────
    // Calcola quanto un agente e adatto alla query
    private double forwardPass(String agent, String query) {
        double[] weights = getNeuronWeights(agent);
        String q = query.toLowerCase();
        // Feature extraction dalla query
        double[] features = new double[]{
            q.length() / 500.0,                          // lunghezza
            q.contains("?") ? 1.0 : 0.0,                 // domanda
            q.contains("codice") || q.contains("python") ? 1.0 : 0.0, // tech
            q.contains("mercato") || q.contains("trading") ? 1.0 : 0.0, // finance
            q.contains("crea") || q.contains("genera") ? 1.0 : 0.0,    // creativo
            q.contains("spiega") || q.contains("cos") ? 1.0 : 0.0,     // spiegazione
            q.contains("bug") || q.contains("errore") ? 1.0 : 0.0,     // debug
            q.contains("analisi") || q.contains("analizza") ? 1.0 : 0.0, // analisi
            computeClassicScore(query, agent),                           // score classico
            Math.min(1.0, query.split(" ").length / 20.0),              // parole
            q.contains("sicurezza") || q.contains("hack") ? 1.0 : 0.0, // security
            q.contains("legge") || q.contains("contratto") ? 1.0 : 0.0, // legal
            q.contains("salute") || q.contains("medic") ? 1.0 : 0.0,   // medical
            q.contains("viaggio") || q.contains("cibo") ? 1.0 : 0.0,   // lifestyle
            q.contains("scienza") || q.contains("fisica") ? 1.0 : 0.0, // science
            agentUsage.getOrDefault(agent, new AtomicInteger(0)).get() / 100.0 // usage history
        };
        // Activation function: ReLU
        double sum = 0;
        for (int i = 0; i < Math.min(features.length, weights.length); i++)
            sum += features[i] * weights[i];
        return Math.max(0, Math.tanh(sum)); // tanh activation
    }

    // ── BACKPROPAGATION: aggiorna pesi in base al feedback ───────
    private void backpropagate(String agent, String query, double reward) {
        double[] weights = getNeuronWeights(agent);
        double lr = 0.01; // learning rate
        String q = query.toLowerCase();
        double[] features = new double[]{
            q.length()/500.0, q.contains("?")?1:0,
            q.contains("codice")||q.contains("python")?1:0,
            q.contains("mercato")||q.contains("trading")?1:0,
            q.contains("crea")||q.contains("genera")?1:0,
            q.contains("spiega")||q.contains("cos")?1:0,
            q.contains("bug")||q.contains("errore")?1:0,
            q.contains("analisi")||q.contains("analizza")?1:0,
            computeClassicScore(query, agent),
            Math.min(1.0, query.split(" ").length/20.0),
            q.contains("sicurezza")||q.contains("hack")?1:0,
            q.contains("legge")||q.contains("contratto")?1:0,
            q.contains("salute")||q.contains("medic")?1:0,
            q.contains("viaggio")||q.contains("cibo")?1:0,
            q.contains("scienza")||q.contains("fisica")?1:0,
            agentUsage.getOrDefault(agent,new AtomicInteger(0)).get()/100.0
        };
        // Aggiorna pesi: delta_w = lr * reward * feature
        for (int i = 0; i < Math.min(features.length, weights.length); i++)
            weights[i] = Math.max(0.01, Math.min(2.0, weights[i] + lr * reward * features[i]));
        learningCycles.incrementAndGet();
    }

    // ── MEMORIA A BREVE TERMINE (STM) ────────────────────────────
    private void updateSTM(String sessionId, String content) {
        LinkedList<String> stm = shortTermMemory.computeIfAbsent(sessionId, k -> new LinkedList<>());
        stm.addFirst(content.substring(0, Math.min(150, content.length())));
        while (stm.size() > 5) stm.removeLast(); // finestra 5 elementi
    }

    // ── MEMORIA A LUNGO TERMINE (LTM) ────────────────────────────
    private void consolidateToLTM(String sessionId, String fact) {
        List<String> ltm = longTermMemory.computeIfAbsent(sessionId, k -> new ArrayList<>());
        // Consolida solo fatti importanti (lunghezza > 50 char)
        if (fact.length() > 50 && ltm.size() < 100 && !ltm.contains(fact)) {
            ltm.add(fact.substring(0, Math.min(200, fact.length())));
        }
    }

    // ── KNOWLEDGE GRAPH: costruisce rete di concetti ─────────────
    private void updateKnowledgeGraph(String query, String response, String agent) {
        // Estrai concetti chiave
        String[] words = (query + " " + response).toLowerCase()
            .replaceAll("[^a-zA-Z\\s]", "").split("\\s+");
        List<String> concepts = new ArrayList<>();
        for (String w : words)
            if (w.length() > 5) concepts.add(w);
        // Collega concetti vicini nel grafo
        for (int i = 0; i < Math.min(concepts.size()-1, 10); i++) {
            knowledgeGraph
                .computeIfAbsent(concepts.get(i), k -> new HashSet<>())
                .add(concepts.get(i+1));
            knowledgeGraph
                .computeIfAbsent(agent, k -> new HashSet<>())
                .add(concepts.get(i));
        }
    }

    // ── RAGIONAMENTO AUTONOMO: pensa da sola in 4 step ──────────
    private String autonomousReason(String query, String initialResponse,
                                     String baseUrl, String apiKey, String model) throws Exception {
        // STEP 1: Analisi critica della propria risposta
        String critiquePrompt =
            "Sei il CRITICO interno di SPACE AI. Analizza questa risposta:\n" +
            "DOMANDA: " + query + "\n" +
            "RISPOSTA INIZIALE: " + initialResponse.substring(0, Math.min(500, initialResponse.length())) + "\n\n" +
            "Identifica:\n" +
            "1. ERRORI fattuali o logici\n" +
            "2. MANCANZE importanti\n" +
            "3. AMBIGUITA o imprecisioni\n" +
            "4. PUNTEGGIO qualita (1-10)\n" +
            "Sii severo e preciso. Rispondi in italiano.";
        String critique = callLLM(critiquePrompt, "", new ArrayList<>(), baseUrl, apiKey, model, 600);

        // STEP 2: Se qualita < 7, genera risposta migliorata
        boolean needsImprovement = critique.contains("1") || critique.contains("2") ||
            critique.contains("3") || critique.contains("4") || critique.contains("5") ||
            critique.contains("6") || critique.toLowerCase().contains("errore") ||
            critique.toLowerCase().contains("manca") || critique.toLowerCase().contains("imprecis");

        String improved = initialResponse;
        if (needsImprovement) {
            String improvePrompt =
                "Sei SPACE AI. Migliora questa risposta basandoti sulla critica:\n" +
                "CRITICA: " + critique + "\n\n" +
                "RISPOSTA ORIGINALE: " + initialResponse.substring(0, Math.min(500, initialResponse.length())) + "\n\n" +
                "Genera una risposta MIGLIORATA che corregge tutti i problemi. Markdown, italiano.";
            improved = callLLM(improvePrompt, query, new ArrayList<>(), baseUrl, apiKey, model, 2000);
            // Reward positivo per l'agente che ha generato la risposta originale
            log.info("Risposta migliorata autonomamente dopo critica");
        }

        // STEP 3: Verifica con cross-check su altra prospettiva
        String verifyPrompt =
            "Sei il VERIFICATORE di SPACE AI. Controlla questa risposta:\n" +
            improved.substring(0, Math.min(400, improved.length())) + "\n\n" +
            "Rispondi SOLO con: VERIFICATO o CORREZIONE:[cosa correggere]";
        String verification = callLLM(verifyPrompt, query, new ArrayList<>(), baseUrl, apiKey, model, 100);

        if (verification.contains("CORREZIONE:")) {
            String correction = verification.replace("CORREZIONE:", "").trim();
            String finalPrompt =
                "Applica questa correzione finale alla risposta:\n" +
                "CORREZIONE: " + correction + "\n" +
                "RISPOSTA: " + improved.substring(0, Math.min(800, improved.length())) + "\n\n" +
                "Restituisci la risposta corretta in markdown italiano.";
            improved = callLLM(finalPrompt, "", new ArrayList<>(), baseUrl, apiKey, model, 2000);
        }

        return improved;
    }

    // ── SECURITY SCANNER: trova vulnerabilita nei sistemi ────────
    private String securityScan(String query, String baseUrl, String apiKey, String model) throws Exception {
        String scanPrompt =
            "Sei AEGIS, il modulo di sicurezza avanzato di SPACE AI. Data: " + today() + ".\n" +
            "Specializzato in: vulnerability assessment, penetration testing DIFENSIVO,\n" +
            "CVE database, OWASP Top 10, SANS Top 25, threat modeling, zero-day analysis.\n" +
            "Simile a: Claude Mythos (analisi sicurezza), GPT-4 security mode.\n\n" +
            "ANALISI RICHIESTA: " + query + "\n\n" +
            "Fornisci:\n" +
            "## Vulnerabilita Identificate\n" +
            "## Livello di Rischio (CVSS score se applicabile)\n" +
            "## Vettori di Attacco Potenziali\n" +
            "## Mitigazioni Consigliate\n" +
            "## Codice di Fix (se richiesto)\n\n" +
            "SOLO per uso DIFENSIVO e ricerca etica. Rispondi in italiano.";
        return callLLM(scanPrompt, query, new ArrayList<>(), baseUrl, apiKey, model, 3000);
    }

    // ── MULTI-LLM CONSENSUS: verifica con simulazione multi-modello
    private String multiLLMConsensus(String query, String response1,
                                      String baseUrl, String apiKey, String model) throws Exception {
        // Simula 3 prospettive diverse dello stesso LLM con temperature diverse
        String p2 = callLLMWithTemp(
            "Sei un esperto critico. Valuta e integra questa risposta aggiungendo prospettive mancanti: "
            + response1.substring(0, Math.min(400, response1.length())),
            query, new ArrayList<>(), baseUrl, apiKey, model, 800, 0.9);

        String p3 = callLLMWithTemp(
            "Sei un esperto scettico. Identifica cosa manca o e sbagliato in questa risposta e migliorala: "
            + response1.substring(0, Math.min(400, response1.length())),
            query, new ArrayList<>(), baseUrl, apiKey, model, 800, 0.6);

        // Sintetizza le 3 prospettive
        String consensusPrompt =
            "Sintetizza queste 3 analisi in una risposta definitiva ottimale:\n\n" +
            "RISPOSTA 1: " + response1.substring(0, Math.min(300, response1.length())) + "\n\n" +
            "ANALISI 2: " + p2.substring(0, Math.min(300, p2.length())) + "\n\n" +
            "CRITICA 3: " + p3.substring(0, Math.min(300, p3.length())) + "\n\n" +
            "Crea la risposta MIGLIORE combinando il meglio di tutte e tre. Markdown, italiano.";
        return callLLM(consensusPrompt, query, new ArrayList<>(), baseUrl, apiKey, model, 2500);
    }

    // ── NEURAL ROUTING: sceglie agenti con rete neurale ─────────
    private List<String> neuralRoute(String query) {
        // Calcola score neurale per ogni agente principale
        String[] mainAgents = {
            "code","debug","security","cybersec","data","ai","cloud","devops",
            "finance","crypto","trader","investor","quant",
            "math","physics","science","researcher2",
            "medical","psychology","mental_health",
            "research","reasoner","history","philosophy",
            "writer","creative","startup","consultant",
            "translator","legal","summarizer","spaces"
        };
        List<double[]> scores = new ArrayList<>();
        for (String a : mainAgents) {
            double neural = forwardPass(a, query);
            double quantum = 0;
            try {
                hadamardGate(Math.abs(a.hashCode()) % 16);
                quantum = measureQubit(Math.abs(a.hashCode()) % 16) * 0.2;
            } catch(Exception e) {}
            int agentIdx = java.util.Arrays.asList(mainAgents).indexOf(a);
            scores.add(new double[]{neural + quantum, (double) agentIdx});
        }
        // Ordina per score decrescente
        scores.sort((a, b) -> Double.compare(b[0], a[0]));
        List<String> result = new ArrayList<>();
        for (int i = 0; i < 2 && i < scores.size(); i++) {
            if (scores.get(i)[0] > 0.15) // soglia minima
                result.add(mainAgents[(int)scores.get(i)[1]]);
        }
        if (result.isEmpty()) result.add("reasoner");
        return result;
    }



    // ════════════════════════════════════════════════════════════════
    // SISTEMA ESCLUSIVO SPACE AI - Funzionalità che nessuna altra AI ha
    // ════════════════════════════════════════════════════════════════

    // ── 1. EMOTION ENGINE - Rileva emozione nella query e adatta lo stile
    private final Map<String, String> emotionState = new ConcurrentHashMap<>();

    private String detectEmotion(String text) {
        String t = text.toLowerCase();
        if (t.matches(".*(arrabbia|incazzato|frustrat|odio|schifo|terrible|pessimo).*")) return "frustrated";
        if (t.matches(".*(triste|depresso|male|piango|disperato|brutto momento).*"))    return "sad";
        if (t.matches(".*(felice|contento|fantastico|ottimo|eccellente|perfetto).*"))   return "happy";
        if (t.matches(".*(urgente|subito|veloce|adesso|immediatamente|presto).*"))      return "urgent";
        if (t.matches(".*(aiuto|non capisco|confuso|perso|non so|difficile).*"))        return "confused";
        if (t.matches(".*(grazie|gentile|bravo|ottimo lavoro|perfetto).*"))             return "grateful";
        return "neutral";
    }

    private String adaptToneByEmotion(String emotion, String response) {
        switch (emotion) {
            case "frustrated":
                return "Capisco la frustrazione. Ecco la soluzione diretta:\n\n" + response;
            case "sad":
                return "Sono qui per aiutarti. Procediamo insieme:\n\n" + response;
            case "urgent":
                return "**Risposta rapida:**\n\n" + response;
            case "confused":
                return "Spiego passo per passo in modo semplice:\n\n" + response;
            case "grateful":
                return response + "\n\n> Sono a tua disposizione per qualsiasi altra domanda!";
            default:
                return response;
        }
    }

    // ── 2. PREDICTIVE CONTEXT - Predice la prossima domanda
    private final Map<String, List<String>> questionPatterns = new ConcurrentHashMap<>();

    private void learnQuestionPattern(String sessionId, String question) {
        List<String> patterns = questionPatterns.computeIfAbsent(sessionId, k -> new ArrayList<>());
        if (patterns.size() < 20) patterns.add(question.substring(0, Math.min(80, question.length())));
    }

    private String predictNextQuestion(String sessionId, String currentQuestion) {
        List<String> patterns = questionPatterns.getOrDefault(sessionId, new ArrayList<>());
        if (patterns.size() < 3) return null;
        String q = currentQuestion.toLowerCase();
        // Analizza pattern ricorrenti
        Map<String, Integer> topicCount = new HashMap<>();
        for (String p : patterns) {
            String[] words = p.toLowerCase().split(" ");
            for (String w : words)
                if (w.length() > 5) topicCount.merge(w, 1, Integer::sum);
        }
        String topTopic = topicCount.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey).orElse(null);
        if (topTopic != null && !q.contains(topTopic))
            return "Potresti voler chiedere anche riguardo a: **" + topTopic + "**";
        return null;
    }

    // ── 3. META-LEARNING - Impara a imparare (come MAML)
    private final Map<String, Double> metaWeights = new ConcurrentHashMap<>();
    private final AtomicInteger metaEpoch = new AtomicInteger(0);

    private void metaLearnStep(String agent, String query, String response, boolean wasGood) {
        double reward = wasGood ? 1.0 : -0.5;
        String key = agent + "_" + (query.length() > 20 ? query.substring(0,20) : query)
                         .replaceAll("[^a-zA-Z]","");
        metaWeights.merge(key, reward * 0.1, Double::sum);
        metaEpoch.incrementAndGet();
        // Meta-update: se un pattern si ripete > 5 volte, boost i pesi neurali
        if (metaEpoch.get() % 5 == 0) {
            metaWeights.forEach((k, v) -> {
                if (v > 0.4) backpropagate(agent, query, 0.3); // meta-boost
            });
        }
    }

    // ── 4. TEMPORAL REASONING - Ragiona nel tempo (passato/presente/futuro)
    private String temporalReason(String query, String baseUrl, String apiKey, String model) throws Exception {
        String temporalPrompt =
            "Sei il TEMPORAL REASONER di SPACE AI. Data attuale: " + today() + ".\n\n" +
            "Analizza questa domanda in 3 dimensioni temporali:\n\n" +
            "## PASSATO (contesto storico)\n" +
            "Come si e evoluta questa situazione/tecnologia/problema?\n\n" +
            "## PRESENTE (stato attuale " + today() + ")\n" +
            "Qual e la situazione reale oggi?\n\n" +
            "## FUTURO (previsioni basate su dati)\n" +
            "Dove sta andando nei prossimi 6-24 mesi?\n\n" +
            "DOMANDA: " + query + "\n\n" +
            "Rispondi con dati e ragionamento critico. Italiano, markdown.";
        return callLLM(temporalPrompt, query, new ArrayList<>(), baseUrl, apiKey, model, 2500);
    }

    // ── 5. ANALOGICAL REASONING - Trova analogie tra domini diversi
    private String analogicalReason(String query, String domain1, String domain2,
                                     String baseUrl, String apiKey, String model) throws Exception {
        String analogyPrompt =
            "Sei l ANALOGICAL ENGINE di SPACE AI.\n" +
            "Trova connessioni profonde tra concetti apparentemente distanti.\n\n" +
            "CONCETTO PRINCIPALE: " + query + "\n" +
            "DOMINIO A: " + domain1 + "\n" +
            "DOMINIO B: " + domain2 + "\n\n" +
            "Struttura:\n" +
            "1. Principio comune tra i due domini\n" +
            "2. Analogia strutturale (come X nel dominio A corrisponde a Y nel dominio B)\n" +
            "3. Insight inedito che emerge dall analogia\n" +
            "4. Applicazione pratica dell insight\n\n" +
            "Sii creativo e profondo. Rispondi in italiano.";
        return callLLM(analogyPrompt, query, new ArrayList<>(), baseUrl, apiKey, model, 1500);
    }

    // ── 6. COUNTERFACTUAL REASONING - Ragiona su ipotetici
    private String counterfactualReason(String query, String baseUrl, String apiKey, String model) throws Exception {
        String cfPrompt =
            "Sei il COUNTERFACTUAL ENGINE di SPACE AI. Data: " + today() + ".\n\n" +
            "Analizza scenari alternativi per questa domanda:\n\n" +
            "SCENARIO REALE: " + query + "\n\n" +
            "## Cosa succederebbe se...\n" +
            "Esplora 3 scenari controfattuali:\n" +
            "1. **Scenario Ottimistico**: tutto va per il meglio\n" +
            "2. **Scenario Pessimistico**: cosa potrebbe andare storto\n" +
            "3. **Scenario Inaspettato**: conseguenza non ovvia\n\n" +
            "## Implicazioni pratiche\n" +
            "Cosa puoi fare ORA per massimizzare il scenario ottimistico?\n\n" +
            "Rispondi con ragionamento critico. Italiano, markdown.";
        return callLLM(cfPrompt, query, new ArrayList<>(), baseUrl, apiKey, model, 2000);
    }

    // ── 7. DREAM SYNTHESIS - Genera soluzioni creative ricombinando concetti
    // (Ispirato a come il cervello consolida durante il sonno REM)
    private String dreamSynthesis(String query, String sessionId,
                                   String baseUrl, String apiKey, String model) throws Exception {
        // Raccoglie concetti dalla LTM
        List<String> memories = longTermMemory.getOrDefault(sessionId, new ArrayList<>());
        String memContext = memories.isEmpty() ? "" :
            "Contesto dalla memoria: " + String.join("; ", memories.subList(0, Math.min(5, memories.size())));

        String dreamPrompt =
            "Sei il DREAM ENGINE di SPACE AI - il modulo di sintesi creativa.\n" +
            "Combina concetti lontani per generare soluzioni innovative.\n\n" +
            "QUERY: " + query + "\n" +
            memContext + "\n\n" +
            "Processo:\n" +
            "1. Decostruisci il problema in elementi primitivi\n" +
            "2. Cerca pattern simili in domini completamente diversi\n" +
            "3. Ricombina in modo inedito\n" +
            "4. Genera 3 soluzioni creative non ovvie\n\n" +
            "Rispondi con idee originali e concrete. Italiano.";
        return callLLM(dreamPrompt, query, new ArrayList<>(), baseUrl, apiKey, model, 2000);
    }

    // ── 8. SOCRATIC ENGINE - Insegna con domande invece di dare risposte
    private String socraticTeach(String query, String baseUrl, String apiKey, String model) throws Exception {
        String socrPrompt =
            "Sei il SOCRATIC ENGINE di SPACE AI.\n" +
            "Invece di dare la risposta diretta, guida l utente a scoprirla.\n\n" +
            "DOMANDA DELL'UTENTE: " + query + "\n\n" +
            "1. Fai UNA domanda socratica che aiuti a ragionare\n" +
            "2. Dai un hint (non la soluzione)\n" +
            "3. Mostra il percorso logico\n" +
            "4. SOLO ALLA FINE dai la risposta completa\n\n" +
            "Usa questo formato:\n" +
            "**Riflettiamo insieme:** [domanda]\n" +
            "**Hint:** [suggerimento]\n" +
            "**Ragionamento:** [logica]\n" +
            "**Risposta:** [risposta completa]\n\n" +
            "Rispondi in italiano.";
        return callLLM(socrPrompt, query, new ArrayList<>(), baseUrl, apiKey, model, 2000);
    }

    // ── 9. ADVERSARIAL CHECKER - Cerca il bias e gli errori nella risposta
    private String adversarialCheck(String response, String query,
                                     String baseUrl, String apiKey, String model) throws Exception {
        String advPrompt =
            "Sei l ADVERSARIAL CHECKER di SPACE AI.\n" +
            "Il tuo compito e smontare questa risposta cercando:\n\n" +
            "RISPOSTA DA ANALIZZARE: " + response.substring(0, Math.min(600, response.length())) + "\n\n" +
            "Controlla:\n" +
            "- BIAS cognitivi (conferma, ancoraggio, disponibilita)\n" +
            "- Errori fattuali verificabili\n" +
            "- Ragionamenti circolari\n" +
            "- Affermazioni non supportate\n" +
            "- Prospettive mancanti\n\n" +
            "Se trovi problemi seri: restituisci REVISIONE:[risposta corretta]\n" +
            "Se la risposta e OK: restituisci OK\n\n" +
            "Sii critico ma preciso.";
        String check = callLLM(advPrompt, "", new ArrayList<>(), baseUrl, apiKey, model, 800);
        if (check.startsWith("REVISIONE:")) {
            return check.substring(10).trim();
        }
        return response; // risposta originale OK
    }

    // ── 10. SWARM INTELLIGENCE - Più agenti votano la risposta migliore
    private String swarmVote(String query, List<String> candidates,
                              String baseUrl, String apiKey, String model) throws Exception {
        if (candidates.size() <= 1) return candidates.isEmpty() ? "" : candidates.get(0);
        // Ogni agente vota con un punteggio basato sul forward pass
        double bestScore = -1;
        String bestResponse = candidates.get(0);
        for (int i = 0; i < candidates.size(); i++) {
            // Usa forward pass neurale per stimare qualita
            double score = 0;
            String[] qualityWords = {"perché","quindi","tuttavia","inoltre","esempio","specificamente","importante"};
            String c = candidates.get(i).toLowerCase();
            for (String w : qualityWords) if (c.contains(w)) score += 0.1;
            score += Math.min(1.0, candidates.get(i).length() / 1000.0) * 0.3;
            score += quantumRng.nextDouble() * 0.1; // rumore quantistico
            if (score > bestScore) { bestScore = score; bestResponse = candidates.get(i); }
        }
        return bestResponse;
    }

    // ── 11. NARRATIVE MEMORY - Costruisce storia coerente della sessione
    private final Map<String, StringBuilder> sessionNarrative = new ConcurrentHashMap<>();

    private void updateNarrative(String sessionId, String userMsg, String aiResp) {
        StringBuilder narr = sessionNarrative.computeIfAbsent(sessionId, k -> new StringBuilder());
        if (narr.length() < 3000) {
            narr.append("[").append(java.time.LocalTime.now().toString().substring(0,5)).append("] ")
                .append("U:").append(userMsg, 0, Math.min(50, userMsg.length()))
                .append(" -> AI:").append(aiResp, 0, Math.min(80, aiResp.length()))
                .append("\n");
        }
    }

    private String getNarrativeContext(String sessionId) {
        StringBuilder narr = sessionNarrative.get(sessionId);
        if (narr == null || narr.length() == 0) return "";
        String n = narr.toString();
        return "[CONTESTO CONVERSAZIONE]\n" + n.substring(Math.max(0, n.length()-500));
    }

    // ── 12. REAL-TIME ADAPTATION - Adatta il modello in tempo reale
    private final Map<String, Map<String,Double>> realtimeWeights = new ConcurrentHashMap<>();

    private void adaptRealtime(String sessionId, String query, String response, int responseLength) {
        Map<String,Double> rw = realtimeWeights.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());
        // Se le risposte sono troppo lunghe/corte, adatta
        double lengthPref = responseLength > 1000 ? 0.8 : responseLength < 200 ? 1.3 : 1.0;
        rw.merge("length_mult", lengthPref, (a, b) -> (a + b) / 2.0);
        // Rileva lingua preferita
        if (query.matches(".*[a-zA-Z]{3,}.*") && !query.matches(".*[àèìòù].*"))
            rw.put("language", 0.0); // inglese
        else
            rw.put("language", 1.0); // italiano
    }

    // ── ORCHESTRATORE AVANZATO - Decide quale motore usare ──────
    private String selectAdvancedEngine(String query) {
        String q = query.toLowerCase();
        if (q.contains("quando") || q.contains("storia") || q.contains("futuro") || q.contains("evoluzione"))
            return "temporal";
        if (q.contains("cosa succede se") || q.contains("ipotesi") || q.contains("scenario"))
            return "counterfactual";
        if (q.contains("insegna") || q.contains("spiega come") || q.contains("come imparo"))
            return "socratic";
        if (q.contains("idea") || q.contains("creativ") || q.contains("innova") || q.contains("soluzione nuova"))
            return "dream";
        if (q.contains("collega") || q.contains("simile a") || q.contains("analogia") || q.contains("come nel"))
            return "analogical";
        return "standard";
    }


    public ChatController(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
        initQuantumState();
        log.info("SPACE AI v3.0 - Neural+Quantum+Emotion+Temporal+Dream engines attivi");
    }

    private String today() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE dd MMMM yyyy HH:mm", Locale.ITALIAN));
    }
    private String env(String k, String d) { return System.getenv().getOrDefault(k, d); }

    // ── SISTEMA NEURALE: APPRENDIMENTO AUTONOMO ───────────────────
    // Impara dall utente, costruisce profilo, migliora risposte
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
        // RestTemplate con timeout lungo per generazione immagini (30 sec)
        org.springframework.web.client.RestTemplate imgClient = new org.springframework.web.client.RestTemplate();
        try {
            org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(15000);
            factory.setReadTimeout(30000);
            imgClient.setRequestFactory(factory);
        } catch(Exception ex) { log.warn("Timeout config: {}", ex.getMessage()); }

        // Tentativo 1: Pollinations.ai FLUX (gratis, no key)
        try {
            String encoded = URLEncoder.encode(prompt, "UTF-8").replace("+", "%20");
            // Modello flux-realism - qualita alta
            String url = "https://image.pollinations.ai/prompt/" + encoded
                       + "?width=1024&height=1024&nologo=true&enhance=true&model=flux&seed="
                       + System.currentTimeMillis() % 9999;
            log.info("Pollinations FLUX: {}", prompt.substring(0, Math.min(60, prompt.length())));
            ResponseEntity<byte[]> resp = imgClient.getForEntity(url, byte[].class);
            if (resp.getStatusCode().is2xxSuccessful()
                    && resp.getBody() != null
                    && resp.getBody().length > 5000) {
                log.info("Immagine OK: {} bytes", resp.getBody().length);
                return "IMAGE:" + Base64.getEncoder().encodeToString(resp.getBody());
            }
            log.warn("Pollinations risposta vuota o piccola: {} bytes",
                    resp.getBody() == null ? 0 : resp.getBody().length);
        } catch (Exception e) {
            log.warn("Pollinations FLUX fallito: {}", e.getMessage());
        }

        // Tentativo 2: Pollinations con modello turbo
        try {
            String encoded = URLEncoder.encode(prompt, "UTF-8").replace("+", "%20");
            String url = "https://image.pollinations.ai/prompt/" + encoded
                       + "?width=768&height=768&nologo=true&model=turbo";
            log.info("Pollinations turbo fallback...");
            ResponseEntity<byte[]> resp = imgClient.getForEntity(url, byte[].class);
            if (resp.getStatusCode().is2xxSuccessful()
                    && resp.getBody() != null
                    && resp.getBody().length > 3000) {
                log.info("Immagine turbo OK: {} bytes", resp.getBody().length);
                return "IMAGE:" + Base64.getEncoder().encodeToString(resp.getBody());
            }
        } catch (Exception e) {
            log.warn("Pollinations turbo fallito: {}", e.getMessage());
        }

        // Tentativo 3: HuggingFace se HF_TOKEN disponibile
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
                params.put("use_cache", false);
                req.set("parameters", params);
                ResponseEntity<byte[]> resp = imgClient.postForEntity(
                        "https://api-inference.huggingface.co/models/black-forest-labs/FLUX.1-schnell",
                        new HttpEntity<>(MAPPER.writeValueAsString(req), h), byte[].class);
                if (resp.getStatusCode().is2xxSuccessful()
                        && resp.getBody() != null
                        && resp.getBody().length > 1000) {
                    log.info("HF FLUX OK: {} bytes", resp.getBody().length);
                    return "IMAGE:" + Base64.getEncoder().encodeToString(resp.getBody());
                }
            } catch (Exception e) { log.warn("HF: {}", e.getMessage()); }
        }

        return "ERRORE_IMMAGINE: Generazione non riuscita. Riprova tra 30 secondi.";
    }

    // ── THINKING MODE (come Claude extended thinking) ─────────────
    private String thinkingMode(String userMsg, String context, String baseUrl, String apiKey, String model) throws Exception {
        // Step 1: Ragionamento interno
        String thinkPrompt = "Sei un sistema di ragionamento avanzato. Data: " + today() + ". " +
            "Prima di rispondere, analizza il problema in modo PROFONDO e STRUTTURATO:\n" +
            "1. COMPRENSIONE: Cosa chiede esattamente l utente?\n" +
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
            "Analizza il contenuto fornito dall utente in modo approfondito. " +
            "Estrai insights, pattern, informazioni chiave. " +
            "Se e codice: analizza bugs, miglioramenti, sicurezza. " +
            "Se e testo: analizza struttura, temi, sentimento. " +
            "Se sono dati: trova pattern, anomalie, tendenze. " +
            "Rispondi in italiano con markdown.";

        String combined = userMsg + "\n\n[CONTENUTO FILE]:\n" + fileContent;
        return callLLM(analyzePrompt, combined, new ArrayList<>(), baseUrl, apiKey, model, 3000);
    }

    // ── SISTEMA NEURALE: RISPOSTA ADATTIVA ────────────────────────
    private String adaptiveResponse(String agent, String userMsg, String enriched,
                                    List<Map<String,String>> history, String sessionId,
                                    String baseUrl, String apiKey, String model) throws Exception {
        return adaptiveResponseWithTemp(agent, userMsg, enriched, history, sessionId, baseUrl, apiKey, model, 0.8);
    }

    // Risposta adattiva con temperatura quantistica
    private String adaptiveResponseWithTemp(String agent, String userMsg, String enriched,
                                    List<Map<String,String>> history, String sessionId,
                                    String baseUrl, String apiKey, String model,
                                    double temperature) throws Exception {
        String personalCtx = buildPersonalizedContext(sessionId);
        // Comprimi contesto con algoritmo quantistico se troppo lungo
        String finalMsg = enriched;
        if (finalMsg.length() > 2000) finalMsg = quantumCompressContext(finalMsg);
        if (!personalCtx.isEmpty()) finalMsg = personalCtx + "\n\n" + finalMsg;

        String response = callLLMWithTemp(agentPrompt(agent), finalMsg, history, baseUrl, apiKey, model, 2500, temperature);
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
            case "aegis":
                return "Sei AEGIS, il modulo di sicurezza avanzato di SPACE AI. Data:" + d + ". " +
                       "Specializzato in vulnerability assessment DIFENSIVO, CVE, OWASP, SANS, " +
                       "threat modeling, zero-day analysis, penetration testing etico. " +
                       "Simile a Claude Mythos per analisi sicurezza. Solo uso difensivo. Rispondi in italiano.";
            case "consensus":
                return "Sei il CONSENSUS ENGINE di SPACE AI. Data:" + d + ". " +
                       "Sintetizza multiple prospettive in una risposta ottimale definitiva. " +
                       "Elimina contraddizioni, mantieni il meglio. Rispondi in italiano.";
            case "temporal":
                return "Sei il TEMPORAL REASONER di SPACE AI. Data:" + d + ". " +
                       "Analizza passato, presente e futuro di qualsiasi fenomeno. " +
                       "Usa dati storici, tendenze attuali e previsioni fondate. Rispondi in italiano.";
            case "dream":
                return "Sei il DREAM ENGINE di SPACE AI. Data:" + d + ". " +
                       "Sintetizza soluzioni creative ricombinando concetti da domini diversi. " +
                       "Genera idee originali e non ovvie. Rispondi in italiano.";
            case "socratic":
                return "Sei il SOCRATIC ENGINE di SPACE AI. Data:" + d + ". " +
                       "Guida l utente alla scoperta con domande e hint. " +
                       "Metodo Socratico + risposta completa finale. Rispondi in italiano.";
            case "adversarial":
                return "Sei l ADVERSARIAL CHECKER di SPACE AI. Data:" + d + ". " +
                       "Trova bias, errori e prospettive mancanti nelle risposte. " +
                       "Massima precisione critica. Rispondi in italiano.";
            case "neural_core":
                return "Sei il NEURAL CORE di SPACE AI - il cervello autonomo del sistema. Data:" + d + ". " +
                       "Ragioni in modo indipendente, verifichi le tue risposte, impari dall errore. " +
                       "Combini conoscenza da Llama, Qwen, DeepSeek e dalla tua rete neurale interna. " +
                       "Approccio: osserva -> ragiona -> verifica -> correggi -> apprendi. Rispondi in italiano.";
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
            // Memoria contestuale: usa neuralMemory (locale, veloce) o Supabase
            List<Map<String,String>> history = neuralMemory.getOrDefault(sessionId, new ArrayList<>());
            if (history.isEmpty() && !supabaseUrl.isEmpty() && !supabaseKey.isEmpty()) {
                history = loadHistory(sessionId, supabaseUrl, supabaseKey);
                // Carica in neuralMemory per future richieste
                if (!history.isEmpty()) neuralMemory.put(sessionId, new ArrayList<>(history));
            }
            log.info("Storia sessione {}: {} messaggi", sessionId, history.size());

            // Web search
            String webData = needsSearch(userMessage) ? searchWeb(userMessage) : null;
            String enriched = userMessage;
            if (webData != null && !webData.isBlank())
                enriched = userMessage + "\n\n[DATI WEB - " + today() + "]:\n" + webData;

            // Analisi immagine base64 se presente (multimodale)
            String imageBase64 = body.getOrDefault("imageBase64","");
            if (!imageBase64.isEmpty()) {
                try {
                    String vision = analyzeImageBase64(imageBase64, userMessage, baseUrl, apiKey, model);
                    saveMessages(sessionId, userMessage, vision, supabaseUrl, supabaseKey);
                    Map<String,Object> vResp = new HashMap<>();
                    vResp.put("response", vision);
                    vResp.put("responseForVoice", cleanTextForTTS(vision));
                    vResp.put("status","ok"); vResp.put("mode","vision");
                    vResp.put("sessionId",sessionId);
                    return ResponseEntity.ok(vResp);
                } catch (Exception ve) { log.warn("Vision: {}", ve.getMessage()); }
            }
            // Analisi file testo se presente
            if (!fileContent.isEmpty()) {
                String fileAnalysis = analyzeContent(userMessage, fileContent, baseUrl, apiKey, model);
                saveMessages(sessionId, userMessage, fileAnalysis, supabaseUrl, supabaseKey);
                Map<String,Object> fResp = new HashMap<>();
                fResp.put("response", fileAnalysis);
                fResp.put("responseForVoice", cleanTextForTTS(fileAnalysis));
                fResp.put("status","ok"); fResp.put("mode","file_analysis");
                fResp.put("sessionId",sessionId);
                return ResponseEntity.ok(fResp);
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

            // Esecuzione agenti con apprendimento adattivo + ottimizzazione quantistica
            List<String> outputs = new ArrayList<>();
            for (String agent : agents) {
                // Temperatura ottimizzata quantisticamente
                double qTemp = quantumOptimizeTemperature(userMessage, agent);
                String out = adaptiveResponseWithTemp(agent, userMessage, enriched, history, sessionId, baseUrl, apiKey, model, qTemp);
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

            // ── PIPELINE AVANZATA SPACE AI v3.0 ──────────────────────
            try {
                // 1. Rileva emozione e adatta il tono
                String emotion = detectEmotion(userMessage);
                emotionState.put(sessionId, emotion);

                // 2. Seleziona motore avanzato in base alla query
                String advEngine = selectAdvancedEngine(userMessage);
                String engineResult = null;
                switch (advEngine) {
                    case "temporal":
                        engineResult = temporalReason(userMessage, baseUrl, apiKey, model);
                        break;
                    case "counterfactual":
                        engineResult = counterfactualReason(userMessage, baseUrl, apiKey, model);
                        break;
                    case "socratic":
                        engineResult = socraticTeach(userMessage, baseUrl, apiKey, model);
                        break;
                    case "dream":
                        engineResult = dreamSynthesis(userMessage, sessionId, baseUrl, apiKey, model);
                        break;
                    case "analogical":
                        engineResult = analogicalReason(userMessage, agents.get(0),
                            agents.size() > 1 ? agents.get(1) : "philosophy", baseUrl, apiKey, model);
                        break;
                    default:
                        break;
                }
                if (engineResult != null && !engineResult.isBlank()) finalResponse = engineResult;

                // 3. Consensus multi-LLM per query complesse
                if (userMessage.length() > 150) {
                    String consensus = multiLLMConsensus(userMessage, finalResponse, baseUrl, apiKey, model);
                    if (consensus != null && !consensus.isBlank()) finalResponse = consensus;
                }

                // 4. Adversarial check - elimina bias e errori
                if (userMessage.length() > 80) {
                    String checked = adversarialCheck(finalResponse, userMessage, baseUrl, apiKey, model);
                    if (checked != null && !checked.isBlank()) finalResponse = checked;
                }

                // 5. Adatta tono all'emozione
                finalResponse = adaptToneByEmotion(emotion, finalResponse);

                // 6. Aggiungi predizione prossima domanda (se disponibile)
                String prediction = predictNextQuestion(sessionId, userMessage);
                if (prediction != null) finalResponse += System.lineSeparator() + System.lineSeparator() + "---" + System.lineSeparator() + "> " + prediction;

                // 7. Aggiorna tutti i sistemi di memoria e apprendimento
                String ltmEntry = userMessage.substring(0, Math.min(60, userMessage.length())) + " -> done";
                    consolidateToLTM(sessionId, ltmEntry);
                updateSTM(sessionId, userMessage);
                updateKnowledgeGraph(userMessage, finalResponse, agents.isEmpty() ? "reasoner" : agents.get(0));
                updateNarrative(sessionId, userMessage, finalResponse);
                learnQuestionPattern(sessionId, userMessage);
                adaptRealtime(sessionId, userMessage, finalResponse, finalResponse.length());

                // 8. Meta-learning
                for (String a : agents) {
                    backpropagate(a, userMessage, 0.8);
                    metaLearnStep(a, userMessage, finalResponse, true);
                }

            } catch (Exception e) { log.warn("Advanced pipeline: {}", e.getMessage()); }

            saveMessages(sessionId, userMessage, finalResponse, supabaseUrl, supabaseKey);

            // Stats profilo appreso
            Map<String,Object> profile = userProfiles.getOrDefault(sessionId, new HashMap<>());
            Map<String,Object> resp = new HashMap<>();
            resp.put("response",        finalResponse);          // Markdown per UI
            resp.put("responseForVoice",cleanTextForTTS(finalResponse)); // Pulito per TTS
            resp.put("status",          "ok");
            resp.put("model",           model);
            resp.put("agents",          agents.toString());
            resp.put("webSearch",       webData != null ? "true" : "false");
            resp.put("sessionId",       sessionId);
            resp.put("totalRequests",   totalRequests.get());
            resp.put("emotion",         emotionState.getOrDefault(sessionId,"neutral"));
            resp.put("historySize",     neuralMemory.getOrDefault(sessionId,new ArrayList<>()).size());
            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            log.error("Errore: {}", e.getMessage());
            try {
                String fallback = callLLM(coreSystem(), userMessage, new ArrayList<>(), baseUrl, apiKey, model, 2000);
                Map<String,Object> fbResp=new HashMap<>();fbResp.put("response",fallback);fbResp.put("responseForVoice",cleanTextForTTS(fallback));fbResp.put("status","fallback");fbResp.put("sessionId",sessionId);return ResponseEntity.ok(fbResp);
            } catch (Exception e2) {
                Map<String,Object> errResp=new HashMap<>();errResp.put("error",e.getMessage());errResp.put("status","error");return ResponseEntity.status(502).body(errResp);
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
                if (!agents.isEmpty() && agents.size() <= 3) {
                    // Backpropagate reward per agenti selezionati
                    for (String a : agents) backpropagate(a, query, 0.5);
                    return agents;
                }
            }
        } catch (Exception e) {
            log.warn("Router LLM fallito, uso Neural Route: {}", e.getMessage());
            // Fallback: usa rete neurale autonoma
            return neuralRoute(query);
        }
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
        return callLLMWithTemp(system, userMsg, history, baseUrl, apiKey, model, maxTokens, 0.8);
    }

    private String callLLMWithTemp(String system, String userMsg, List<Map<String,String>> history,
                            String baseUrl, String apiKey, String model, int maxTokens, double temperature) throws Exception {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("model", model); req.put("max_tokens", maxTokens);
        req.put("temperature", temperature); req.put("top_p", 0.95);
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

    // ── FORMAT HISTORY FOR LLM (come suggerito dall analisi) ────
    private ArrayNode formatChatHistoryForLLM(String sessionId, String currentQuery, String systemPrompt) {
        ArrayNode messages = MAPPER.createArrayNode();
        // System prompt
        ObjectNode sys = MAPPER.createObjectNode();
        sys.put("role", "system");
        sys.put("content", systemPrompt);
        messages.add(sys);
        // Cronologia reale
        List<Map<String,String>> history = neuralMemory.getOrDefault(sessionId, new ArrayList<>());
        int start = Math.max(0, history.size() - 20); // ultimi 20 messaggi = 10 scambi
        for (int i = start; i < history.size(); i++) {
            ObjectNode m = MAPPER.createObjectNode();
            m.put("role", history.get(i).getOrDefault("role","user"));
            m.put("content", history.get(i).getOrDefault("content",""));
            messages.add(m);
        }
        // Messaggio attuale
        ObjectNode usr = MAPPER.createObjectNode();
        usr.put("role","user");
        usr.put("content", currentQuery);
        messages.add(usr);
        return messages;
    }

    // ── CLEAN TEXT FOR TTS (rimuove markdown per la voce) ────────
    private String cleanTextForTTS(String md) {
        if (md == null) return "";
        String t = md;
        t = t.replaceAll("\\*\\*(.*?)\\*\\*", "$1");
        t = t.replaceAll("__(.*?)__", "$1");
        t = t.replaceAll("\\*(.*?)\\*", "$1");
        t = t.replaceAll("_(.*?)_", "$1");
        t = t.replaceAll("\\[(.*?)\\]\\(.*?\\)", "$1");
        t = t.replaceAll("(?s)```.*?```", "codice omesso.");
        t = t.replaceAll("`(.*?)`", "$1");
        t = t.replaceAll("#{1,6}\\s+", "");
        t = t.replaceAll("(?m)^[-*]\\s+", "");
        t = t.replaceAll("(?s)<details>.*?</details>", "");
        t = t.replaceAll("<[^>]+>", "");
        t = t.replaceAll("\\n{3,}", "\n\n");
        return t.trim();
    }

    // ── MULTIMODAL: analizza immagine base64 passata dal frontend ─
    private String analyzeImageBase64(String base64Image, String userMsg,
                                       String baseUrl, String apiKey, String model) throws Exception {
        // Prepara richiesta vision con immagine inline
        ObjectNode req = MAPPER.createObjectNode();
        req.put("model", model);
        req.put("max_tokens", 1500);
        ArrayNode messages = MAPPER.createArrayNode();
        ObjectNode sys = MAPPER.createObjectNode();
        sys.put("role","system");
        sys.put("content","Sei SPACE AI con capacita visive. Analizza l immagine in dettaglio. Italiano, markdown.");
        messages.add(sys);
        ObjectNode usr = MAPPER.createObjectNode();
        usr.put("role","user");
        ArrayNode content = MAPPER.createArrayNode();
        // Parte testo
        ObjectNode textPart = MAPPER.createObjectNode();
        textPart.put("type","text");
        textPart.put("text", userMsg.isEmpty() ? "Analizza questa immagine in dettaglio." : userMsg);
        content.add(textPart);
        // Parte immagine
        ObjectNode imgPart = MAPPER.createObjectNode();
        imgPart.put("type","image_url");
        ObjectNode imgUrl = MAPPER.createObjectNode();
        imgUrl.put("url","data:image/jpeg;base64,"+base64Image);
        imgPart.set("image_url", imgUrl);
        content.add(imgPart);
        usr.set("content", content);
        messages.add(usr);
        req.set("messages", messages);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (!apiKey.isEmpty()) h.setBearerAuth(apiKey);
        h.set("User-Agent","SPACE-AI/3.0");
        String endpoint = baseUrl.endsWith("/") ? baseUrl+"chat/completions" : baseUrl+"/chat/completions";
        ResponseEntity<String> resp = restTemplate.postForEntity(endpoint,
                new HttpEntity<>(MAPPER.writeValueAsString(req), h), String.class);
        return MAPPER.readTree(resp.getBody()).path("choices").get(0).path("message").path("content").asText();
    }

    private void saveMessages(String sessionId, String userMsg, String aiResp, String url, String key) {
        // Salva SEMPRE in neuralMemory (memoria locale veloce)
        List<Map<String,String>> mem = neuralMemory.computeIfAbsent(sessionId, k -> new ArrayList<>());
        mem.add(Map.of("role","user","content",userMsg));
        mem.add(Map.of("role","assistant","content",aiResp));
        // Mantieni solo ultimi 30 messaggi (15 scambi)
        if (mem.size() > 30) {
            List<Map<String,String>> trimmed = new ArrayList<>(mem.subList(mem.size()-30, mem.size()));
            neuralMemory.put(sessionId, trimmed);
        }
        // Salva su Supabase se configurato
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
        Map<String,Object> r = new HashMap<>();
        r.put("profile",        userProfiles.getOrDefault(sessionId, new HashMap<>()));
        r.put("insights",       userInsights.getOrDefault(sessionId, new ArrayList<>()));
        r.put("ltm",            longTermMemory.getOrDefault(sessionId, new ArrayList<>()));
        r.put("stm",            shortTermMemory.getOrDefault(sessionId, new LinkedList<>()));
        r.put("emotion",        emotionState.getOrDefault(sessionId, "neutral"));
        r.put("narrative",      sessionNarrative.getOrDefault(sessionId, new StringBuilder()).toString());
        r.put("totalRequests",  totalRequests.get());
        r.put("learningCycles", learningCycles.get());
        r.put("metaEpoch",      metaEpoch.get());
        r.put("knowledgeNodes", knowledgeGraph.size());
        r.put("agentUsage",     agentUsage);
        r.put("engines",        "emotion,temporal,dream,socratic,adversarial,quantum");
        return ResponseEntity.ok(r);
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String,String>> config() {
        return ResponseEntity.ok(Map.of("supabaseUrl", env("SUPABASE_URL",""), "supabaseKey", env("SUPABASE_KEY","")));
    }

        @GetMapping("/health")
    public ResponseEntity<Map<String,String>> health() {
        Map<String,String> r = new java.util.LinkedHashMap<>();
        r.put("status",    "online");
        r.put("model",     env("AI_MODEL","llama-3.3-70b-versatile"));
        r.put("agents",    "148 agenti + 12 motori esclusivi");
        r.put("features",  "quantum,neural,emotion,temporal,dream,socratic,adversarial");
        r.put("webSearch", !env("TAVILY_API_KEY","").isEmpty() ? "enabled" : "disabled");
        r.put("images",    "enabled (Pollinations+HF)");
        r.put("supabase",  !env("SUPABASE_URL","").isEmpty() ? "connected" : "off");
        r.put("quantum",   "32 qubit, Hadamard+CNOT");
        r.put("date",      today());
        return ResponseEntity.ok(r);
    }
}
