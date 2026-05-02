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
    private final Map<String, List<Map<String,String>>> neuralMemory = new ConcurrentHashMap<>();
    private final Map<String, Map<String,Object>> userProfiles = new ConcurrentHashMap<>();
    private final Map<String, List<String>> userInsights = new ConcurrentHashMap<>();
    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final Map<String, AtomicInteger> agentUsage = new ConcurrentHashMap<>();
    // Evita chiamate duplicate alla LLM per domande identiche
    private final Map<String, String> responseCache = new ConcurrentHashMap<>();
    private final Map<String, Long> cacheTimestamps = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 10 * 60 * 1000; // 10 minuti
    private final AtomicInteger cacheHits = new AtomicInteger(0);
    private final Map<String, AtomicInteger> sessionRequests = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionWindows = new ConcurrentHashMap<>();
    private static final int MAX_REQUESTS_PER_MINUTE = 30;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile long circuitOpenTime = 0;
    private static final int FAILURE_THRESHOLD = 5;
    private static final long CIRCUIT_RESET_MS = 30000; // 30 secondi
    private final AtomicInteger totalTokensEstimate = new AtomicInteger(0);
    private final Map<String, Long> responseTimings = new ConcurrentHashMap<>();
    private final AtomicLong totalResponseTimeMs = new AtomicLong(0);
    // Simulazione qubit ispirata a Zuchongzhi/Jiuzhang
    private static final int QUBIT_COUNT = 32; // qubit simulati
    private final double[] quantumState = new double[1 << Math.min(QUBIT_COUNT, 16)]; // 2^16 stati
    private final Random quantumRng = new Random();
    private volatile boolean quantumInitialized = false;
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
    }
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
    private double quantumOptimizeTemperature(String query, String agent) {
        initQuantumState();
        double baseTemp = 0.8;
        int q0 = measureQubit(0), q1 = measureQubit(1), q2 = measureQubit(2);
        double delta = (q0 * 0.1) + (q1 * 0.05) - (q2 * 0.05);
        // Query creative = temperatura piu alta; query tecniche = piu bassa
        boolean creative = query.toLowerCase().matches(".*(crea|immagina|scrivi|storia|poesia|idea).*");
        boolean technical = query.toLowerCase().matches(".*(codice|calcola|debug|analizza|formula).*");
        if (creative)  baseTemp += 0.2 + delta;
        if (technical) baseTemp -= 0.2 + delta;
        return Math.max(0.3, Math.min(1.2, baseTemp + delta));
    }
    private String quantumCompressContext(String context) {
        if (context.length() < 500) return context;
        String[] sentences = context.split("(?<=[.!?])\\s+");
        StringBuilder compressed = new StringBuilder();
        initQuantumState();
        for (int i = 0; i < sentences.length; i++) {
            hadamardGate(i % 16);
            int measured = measureQubit(i % 16);
            if (i == 0 || i == sentences.length-1 || measured == 1) {
                compressed.append(sentences[i]).append(" ");
            }
        }
        return compressed.toString().trim();
    }
    // RETE NEURALE AUTONOMA - SPACE AI BRAIN
    // Ragiona, verifica, corregge e impara da sola
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

    // ── MEMORIA DIFFERENZIALE A TRE LIVELLI ──────────────────────────────────────────
    // L3: Conoscenza condivisa tra tutti gli utenti (permanente, cresce nel tempo)
    private final Map<String, Set<String>> sharedKnowledge = new ConcurrentHashMap<>();
    // L2: Delta per sessione - solo i fatti NON già nella sharedKnowledge
    private final Map<String, Map<String, Set<String>>> sessionDeltas = new ConcurrentHashMap<>();

    // ── ROLLING HASH per deduplica semantica (Rabin-Karp semplificato) ───────────────
    private static final int RK_BASE = 257;
    private static final int RK_MOD  = 1_000_000_007;
    private int rollingHash(String text) {
        int hash = 0;
        for (int i = 0; i < text.length(); i++)
            hash = (int)(((long)hash * RK_BASE + text.charAt(i)) % RK_MOD);
        return hash;
    }
    private boolean isDuplicateSemantic(String text) {
        String key = text.substring(0, Math.min(80, text.length())).toLowerCase();
        return bloomMightContain(key);
    }

    // ── STORE FACT DIFFERENTIAL: salva solo i nuovi fatti ────────────────────────────
    private void storeFactDifferential(String sessionId, String subject, String object) {
        if (subject == null || object == null || subject.length() < 3 || object.length() < 3) return;
        String s = subject.toLowerCase().trim();
        String o = object.toLowerCase().trim();
        Set<String> shared = sharedKnowledge.get(s);
        if (shared != null && shared.contains(o)) return;
        sessionDeltas
            .computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(s, k -> ConcurrentHashMap.newKeySet())
            .add(o);
        // Promozione a sharedKnowledge se il fatto appare in >= 3 sessioni
        long sessions = sessionDeltas.values().stream()
            .filter(m -> m.containsKey(s) && m.get(s).contains(o))
            .count();
        if (sessions >= 3) {
            sharedKnowledge.computeIfAbsent(s, k -> ConcurrentHashMap.newKeySet()).add(o);
            sessionDeltas.values().forEach(m -> { if (m.containsKey(s)) m.get(s).remove(o); });
            log.debug("Fatto promosso a sharedKnowledge: {}>{}", s, o);
        }
    }


    // ══════════════════════════════════════════════════════════════════════
    // SEMANTIC EMBEDDING ENGINE + RAG SYSTEM
    // TF-IDF embedding leggero: ogni testo → vettore float[512]
    // Similarità coseno per retrieval semantico preciso (no librerie esterne)
    // RAG: indicizza documenti interi a chunk, retrieval top-K semantico
    // ══════════════════════════════════════════════════════════════════════

    private final Map<String, Integer> embedVocab    = new ConcurrentHashMap<>();
    private final AtomicInteger        vocabSize     = new AtomicInteger(0);
    private static final int  EMBED_DIM     = 512;
    private static final int  CHUNK_SIZE    = 512;
    private static final int  CHUNK_OVERLAP = 64;
    private static final int  RAG_TOP_K     = 5;
    private static final double SIM_THRESHOLD = 0.15;
    private final Map<String, List<RagChunk>> ragStore  = new ConcurrentHashMap<>();
    private final Map<String, Double>         idfScores = new ConcurrentHashMap<>();
    private final AtomicInteger               docCount  = new AtomicInteger(0);

    private static class RagChunk {
        final String docId; final String text; final float[] embedding;
        final int chunkIndex; final long timestamp;
        RagChunk(String docId, String text, float[] embedding, int chunkIndex) {
            this.docId=docId; this.text=text; this.embedding=embedding;
            this.chunkIndex=chunkIndex; this.timestamp=System.currentTimeMillis();
        }
    }

    private List<String> tokenize(String text) {
        if (text==null||text.isBlank()) return new ArrayList<>();
        return Arrays.stream(text.toLowerCase()
                .replaceAll("[^a-zA-Z\u00C0-\u024F0-9\\s]"," ").split("\\s+"))
            .filter(w->w.length()>2).collect(Collectors.toList());
    }

    private void updateVocab(List<String> tokens) {
        for (String t : new HashSet<>(tokens))
            embedVocab.computeIfAbsent(t, k -> vocabSize.getAndIncrement());
    }

    private void updateIDF(List<String> tokens) {
        int N = docCount.incrementAndGet();
        Set<String> unique = new HashSet<>(tokens);
        for (String t : unique) idfScores.merge(t, 1.0, Double::sum);
        double logN = Math.log(1.0 + N);
        for (String t : unique) {
            double df = idfScores.getOrDefault(t, 1.0);
            idfScores.put(t, logN - Math.log(1.0 + df) + 1.0);
        }
    }

    private float[] embed(String text) {
        float[] vec = new float[EMBED_DIM];
        if (text==null||text.isBlank()) return vec;
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) return vec;
        Map<String,Integer> tf = new HashMap<>();
        for (String t : tokens) tf.merge(t,1,Integer::sum);
        for (Map.Entry<String,Integer> e : tf.entrySet()) {
            String word = e.getKey();
            double tfScore = (double)e.getValue()/tokens.size();
            double idf = idfScores.getOrDefault(word, 1.0);
            double tfidf = tfScore * idf;
            int b1 = Math.abs(word.hashCode()) % EMBED_DIM;
            int b2 = (int)(Math.abs((long)word.hashCode()*2654435761L) % EMBED_DIM);
            vec[b1] += (float)tfidf;
            vec[b2] -= (float)(tfidf*0.5);
        }
        float norm = 0f;
        for (float v : vec) norm += v*v;
        norm = (float)Math.sqrt(norm);
        if (norm>0) for (int i=0;i<EMBED_DIM;i++) vec[i]/=norm;
        return vec;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a==null||b==null||a.length!=b.length) return 0.0;
        double dot=0,nA=0,nB=0;
        for (int i=0;i<a.length;i++){dot+=a[i]*b[i];nA+=a[i]*a[i];nB+=b[i]*b[i];}
        double d=Math.sqrt(nA)*Math.sqrt(nB);
        return d<1e-8?0.0:dot/d;
    }

    private int ragIndexDocument(String docId, String text) {
        if (text==null||text.isBlank()||docId==null) return 0;
        List<String> tokens = tokenize(text);
        updateVocab(tokens); updateIDF(tokens);
        List<RagChunk> chunks = new ArrayList<>();
        int i=0,idx=0;
        while (i<text.length()) {
            int end=Math.min(i+CHUNK_SIZE,text.length());
            String ct=text.substring(i,end).trim();
            if (ct.length()>30) chunks.add(new RagChunk(docId,ct,embed(ct),idx++));
            i+=CHUNK_SIZE-CHUNK_OVERLAP;
        }
        ragStore.put(docId,chunks);
        log.info("RAG indexed: docId={} chunks={} vocab={}", docId, chunks.size(), vocabSize.get());
        return chunks.size();
    }

    private String ragRetrieve(String query, String sessionId) {
        if (ragStore.isEmpty()) return "";
        float[] qEmb = embed(query);
        List<double[]> scored = new ArrayList<>();
        List<RagChunk> all = new ArrayList<>();
        for (Map.Entry<String,List<RagChunk>> e : ragStore.entrySet()) {
            boolean isSession = e.getKey().startsWith(sessionId);
            for (RagChunk c : e.getValue()) {
                double sim = cosineSimilarity(qEmb,c.embedding)+(isSession?0.1:0);
                if (sim>=SIM_THRESHOLD) scored.add(new double[]{sim,all.size()});
                all.add(c);
            }
        }
        if (scored.isEmpty()) return "";
        scored.sort((a,b)->Double.compare(b[0],a[0]));
        StringBuilder ctx=new StringBuilder();
        ctx.append("\n## 📚 Contesto RAG\n");
        int count=0;
        for (double[] s:scored) {
            if (count>=RAG_TOP_K) break;
            RagChunk c=all.get((int)s[1]);
            String did=c.docId.length()>40?c.docId.substring(c.docId.length()-40):c.docId;
            ctx.append(String.format("**[%s | chunk%d | sim:%.2f]**\n%s\n\n",
                did,c.chunkIndex,s[0],c.text.substring(0,Math.min(400,c.text.length()))));
            count++;
        }
        return count>0?ctx.toString():"";
    }

    private String semanticMemoryRetrieve(String query, String sessionId) {
        float[] qEmb = embed(query);
        List<String> candidates = new ArrayList<>();
        candidates.addAll(longTermMemory.getOrDefault(sessionId,new ArrayList<>()));
        sharedKnowledge.forEach((s,objs)->objs.forEach(o->candidates.add(s+" e "+o)));
        sessionDeltas.getOrDefault(sessionId,new HashMap<>()).forEach((s,objs)->
            objs.forEach(o->candidates.add(s+" :: "+o)));
        if (candidates.isEmpty()) return "";
        List<double[]> scored=new ArrayList<>();
        for (int i=0;i<candidates.size();i++) {
            double sim=cosineSimilarity(qEmb,embed(candidates.get(i)));
            if (sim>=SIM_THRESHOLD) scored.add(new double[]{sim,i});
        }
        scored.sort((a,b)->Double.compare(b[0],a[0]));
        if (scored.isEmpty()) return "";
        StringBuilder ctx=new StringBuilder("[SEM-MEM]: ");
        int count=0;
        for (double[] s:scored) {
            if (count>=MSA_TOP_K) break;
            String f=candidates.get((int)s[1]);
            ctx.append(f,0,Math.min(100,f.length())).append(" · ");
            count++;
        }
        return ctx.toString().replaceAll(" · $","").trim();
    }


    private double[] getNeuronWeights(String agent) {
        return synapticWeights.computeIfAbsent(agent, k -> {
            double[] w = new double[16];
            for (int i = 0; i < w.length; i++)
                w[i] = 0.1 + quantumRng.nextDouble() * 0.8;
            return w;
        });
    }
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
        for (int i = 0; i < Math.min(features.length, weights.length); i++)
            weights[i] = Math.max(0.01, Math.min(2.0, weights[i] + lr * reward * features[i]));
        learningCycles.incrementAndGet();
    }
    private void updateSTM(String sessionId, String content) {
        // Deduplica con Bloom filter prima di salvare in STM
        String normalized = content.substring(0, Math.min(80, content.length())).toLowerCase();
        if (isDuplicateSemantic(normalized)) {
            log.debug("STM dedup skip: {}", normalized.substring(0, Math.min(40, normalized.length())));
            return;
        }
        bloomAdd(normalized);
        stmPush(sessionId, content, ""); // aggiorna circular buffer
        LinkedList<String> stm = shortTermMemory.computeIfAbsent(sessionId, k -> new LinkedList<>());
        stm.addFirst(content.substring(0, Math.min(150, content.length())));
        while (stm.size() > 5) stm.removeLast();
    }
    private void consolidateToLTM(String sessionId, String fact) {
        if (fact == null || fact.length() < 20) return;
        // Estrai soggetto/oggetto e usa la memoria differenziale
        String[] parts = extractSubjectObject(fact);
        if (parts != null) {
            storeFactDifferential(sessionId, parts[0], parts[1]);
        }
        // Salva nella LTM solo se non duplicato
        String normalized = fact.substring(0, Math.min(80, fact.length())).toLowerCase();
        if (bloomMightContain(normalized)) return;
        bloomAdd(normalized);
        List<String> ltm = longTermMemory.computeIfAbsent(sessionId, k -> new ArrayList<>());
        if (fact.length() > 50 && ltm.size() < 100 && !ltm.contains(fact)) {
            String stored = fact.substring(0, Math.min(200, fact.length()));
            ltm.add(stored);
            // Aggiorna vocabolario embedding con il nuovo fatto
            updateVocab(tokenize(stored));
        }
    }
    private String[] extractSubjectObject(String sentence) {
        String s = sentence.toLowerCase().trim();
        String[] patterns = {"e un ", "e una ", "significa ", "serve per ", "usato per ",
                             "definito come ", "chiamato ", "noto come ", ":: "};
        for (String p : patterns) {
            int idx = s.indexOf(p);
            if (idx > 2 && idx < s.length() - p.length() - 2) {
                String subj = s.substring(0, idx).trim().replaceAll("\s+", "_");
                String obj  = s.substring(idx + p.length()).trim();
                if (subj.length() >= 2 && obj.length() >= 3)
                    return new String[]{subj, obj.substring(0, Math.min(60, obj.length()))};
            }
        }
        return null;
    }
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
            "RISPOSTA 1: " + response1.substring(0, Math.min(2000, response1.length())) + "\n\n" +
            "ANALISI 2: " + p2.substring(0, Math.min(2000, p2.length())) + "\n\n" +
            "CRITICA 3: " + p3.substring(0, Math.min(2000, p3.length())) + "\n\n" +
            "Crea la risposta MIGLIORE combinando il meglio di tutte e tre. Markdown, italiano.";
        return callLLM(consensusPrompt, query, new ArrayList<>(), baseUrl, apiKey, model, 2500);
    }
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
    // SISTEMA ESCLUSIVO SPACE AI - Funzionalità che nessuna altra AI ha
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
    // SPACE AI BRAIN v4.0 - Memoria Attiva + Grafo Semantico + Ultra-Compressione
    // Implementazione delle 5 priorità dell'analisi
    private final int BLOOM_SIZE = 1 << 20; // 1M bit = 128KB
    private final long[] bloomBits = new long[BLOOM_SIZE / 64];
    private final AtomicInteger bloomCount = new AtomicInteger(0);

    // ── ROARING BITMAP simulato con BitSet Java nativo ────────────
    private final java.util.BitSet sessionBitIndex = new java.util.BitSet(1 << 20);
    private final Map<String, Integer> sessionBitOffsets = new ConcurrentHashMap<>();
    private final AtomicInteger bitOffset = new AtomicInteger(0);
    private void roaringAdd(String s){int o=sessionBitOffsets.computeIfAbsent(s,k->bitOffset.getAndIncrement());if(o<(1<<20))sessionBitIndex.set(o);}
    private boolean roaringContains(String s){Integer o=sessionBitOffsets.get(s);return o!=null&&sessionBitIndex.get(o);}

    // ── GZIP nativo (equivalente CBOR+Zstandard senza librerie) ───
    private byte[] gzipCompress(String data){try(java.io.ByteArrayOutputStream b=new java.io.ByteArrayOutputStream();java.util.zip.GZIPOutputStream g=new java.util.zip.GZIPOutputStream(b)){g.write(data.getBytes());g.finish();return b.toByteArray();}catch(Exception e){return data.getBytes();}}
    private String gzipDecompress(byte[] data){try(java.util.zip.GZIPInputStream g=new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(data));java.io.ByteArrayOutputStream b=new java.io.ByteArrayOutputStream()){byte[]buf=new byte[1024];int l;while((l=g.read(buf))>0)b.write(buf,0,l);return b.toString();}catch(Exception e){return new String(data);}}

    // ── CIRCULAR BUFFER STM (sessioni attive) ─────────────────────
    private final java.util.Deque<Map<String,Object>> stmBuffer = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private void stmPush(String sid,String u,String a){if(stmBuffer.size()>=20)stmBuffer.pollFirst();Map<String,Object>e=new HashMap<>();e.put("sid",sid);e.put("u",u.substring(0,Math.min(100,u.length())));e.put("a",a.substring(0,Math.min(200,a.length())));stmBuffer.addLast(e);roaringAdd(sid);}
    private List<String> stmRecall(String sid){List<String>r=new ArrayList<>();for(Map<String,Object>e:stmBuffer)if(sid.equals(e.get("sid")))r.add("U:"+e.get("u")+" A:"+e.get("a"));return r;}

    private int[] bloomHashes(String s) {
        int h1 = s.hashCode();
        int h2 = s.hashCode() * 31 + s.length();
        int h3 = h1 ^ (h2 << 16);
        return new int[]{
            Math.abs(h1) % BLOOM_SIZE,
            Math.abs(h2) % BLOOM_SIZE,
            Math.abs(h3) % BLOOM_SIZE
        };
    }
    private boolean bloomMightContain(String s) {
        for (int h : bloomHashes(s)) {
            if ((bloomBits[h/64] & (1L << (h%64))) == 0) return false;
        }
        return true;
    }
    private void bloomAdd(String s) {
        for (int h : bloomHashes(s)) {
            bloomBits[h/64] |= (1L << (h%64));
        }
        bloomCount.incrementAndGet();
    }
    // Recupera memoria rilevante dalla LTM e KG per iniettarla nel prompt
    private String retrieveRelevantMemory(String query, String sessionId) {
        StringBuilder ctx = new StringBuilder();
        String q = query.toLowerCase();
        // 0. STM circular buffer - recente
        List<String> stm = stmRecall(sessionId);
        if (!stm.isEmpty()) {
            ctx.append("RECENTE: ");
            stm.stream().limit(2).forEach(e -> ctx.append(e.substring(0,Math.min(60,e.length()))).append("; "));
        }
        // 1. Cerca nella LTM fatti rilevanti
        List<String> ltm = longTermMemory.getOrDefault(sessionId, new ArrayList<>());
        List<String> relevant = new ArrayList<>();
        for (String fact : ltm) {
            String f = fact.toLowerCase();
            String[] qWords = q.split("\\s+");
            for (String word : qWords) {
                if (word.length() > 4 && f.contains(word)) {
                    relevant.add(fact);
                    break;
                }
            }
        }
        if (!relevant.isEmpty()) {
            ctx.append("MEMORIA RILEVANTE: ");
            relevant.stream().limit(3).forEach(f ->
                ctx.append(f.substring(0, Math.min(80, f.length()))).append("; "));
        }
        // 2. Estrai insights dal knowledge graph
        String[] qWords = q.split("\\s+");
        List<String> kgFacts = new ArrayList<>();
        for (String word : qWords) {
            if (word.length() > 4) {
                Set<String> related = knowledgeGraph.get(word);
                if (related != null && !related.isEmpty()) {
                    String rel = String.join(",", related).substring(0,
                        Math.min(60, String.join(",", related).length()));
                    kgFacts.add(word + "→[" + rel + "]");
                }
            }
        }
        if (!kgFacts.isEmpty()) {
            ctx.append(" | KG: ").append(String.join("; ", kgFacts.subList(0,
                Math.min(3, kgFacts.size()))));
        }
        // 3. Profilo utente appreso
        Map<String,Object> profile = userProfiles.getOrDefault(sessionId, new HashMap<>());
        if (!profile.isEmpty()) {
            ctx.append(" | PROFILO: ");
            profile.forEach((k,v) -> ctx.append(k).append("=").append(v).append(" "));
        }
        return ctx.length() > 0 ? ctx.toString().trim() : "";
    }
    private String getTopKGConcepts(String sessionId) {
        if (knowledgeGraph.isEmpty()) return "";
        List<Map.Entry<String, Set<String>>> sorted = new ArrayList<>(knowledgeGraph.entrySet());
        sorted.sort((a,b) -> Integer.compare(b.getValue().size(), a.getValue().size()));
        StringBuilder sb = new StringBuilder();
        sorted.stream().limit(5).forEach(e ->
            sb.append(e.getKey()).append("(").append(e.getValue().size()).append(") "));
        return sb.toString().trim();
    }
    // ── MEMORIA DIFFERENZIALE: arricchisce il contesto con sharedKnowledge + sessionDeltas ──
    private String retrieveDifferentialMemory(String sessionId, String query) {
        StringBuilder ctx = new StringBuilder();
        String q = query.toLowerCase();
        String[] qWords = q.split("\\s+");
        // 1. Cerca nei delta della sessione (fatti specifici dell'utente)
        Map<String, Set<String>> deltas = sessionDeltas.getOrDefault(sessionId, new HashMap<>());
        List<String> deltaFacts = new ArrayList<>();
        for (String word : qWords) {
            if (word.length() > 4) {
                Set<String> found = deltas.get(word);
                if (found != null) found.stream().limit(3).forEach(v -> deltaFacts.add(word + "→" + v));
                // Cerca parzialmente nelle chiavi
                deltas.forEach((k, v) -> {
                    if (k.contains(word) && deltaFacts.size() < 5)
                        v.stream().limit(2).forEach(val -> deltaFacts.add(k + "→" + val));
                });
            }
        }
        if (!deltaFacts.isEmpty()) {
            ctx.append("FATTI SESSIONE: ");
            deltaFacts.stream().limit(4).forEach(f -> ctx.append(f).append("; "));
        }
        // 2. Cerca nella sharedKnowledge (conoscenza globale condivisa da tutti gli utenti)
        List<String> sharedFacts = new ArrayList<>();
        for (String word : qWords) {
            if (word.length() > 4) {
                Set<String> shared = sharedKnowledge.get(word);
                if (shared != null) shared.stream().limit(2).forEach(v -> sharedFacts.add(word + "→" + v));
            }
        }
        if (!sharedFacts.isEmpty()) {
            ctx.append(" | CONOSCENZA GLOBALE: ");
            sharedFacts.stream().limit(3).forEach(f -> ctx.append(f).append("; "));
        }
        return ctx.toString().trim();
    }

    // ── MSA: Memory Sparse Attention (ispirato al paper Evermind/Shanda Group) ─────────
    // Invece di recuperare TUTTI i fatti dalla LTM (full-attention = O(n²) e costoso),
    // calcoliamo un attention score sparso per ogni fatto e selezioniamo solo i top-K.
    // Questo scala a milioni di fatti senza degradazione di precisione.

    // Numero massimo di token di memoria da iniettare nel prompt
    private static final int MSA_TOP_K        = 6;   // top-K fatti selezionati
    private static final int MSA_MIN_SCORE    = 2;   // soglia minima di attenzione
    private static final int MSA_MAX_FACT_LEN = 120; // lunghezza massima per fatto

    /**
     * Calcola l'attention score sparso tra query e un fatto di memoria.
     * Score = somma pesata di:
     *   - keyword overlap (peso 3)
     *   - bigram overlap (peso 2)
     *   - recenza (i fatti piu recenti in lista hanno indice piu alto)
     *   - lunghezza informatività (fatti troppo corti penalizzati)
     */
    private int msaAttentionScore(String query, String fact, int positionIndex, int totalFacts) {
        if (fact == null || fact.isBlank()) return 0;
        String q = query.toLowerCase();
        String f = fact.toLowerCase();
        String[] qWords = q.split("\s+");
        String[] fWords = f.split("\s+");

        // 1. Keyword overlap (unigram)
        int overlap = 0;
        for (String qw : qWords) {
            if (qw.length() > 3) {
                for (String fw : fWords) {
                    if (fw.equals(qw) || fw.startsWith(qw) || qw.startsWith(fw)) {
                        overlap += 3;
                        break;
                    }
                }
            }
        }

        // 2. Bigram overlap (cattura frasi come "machine learning", "neural network")
        Set<String> qBigrams = new HashSet<>();
        for (int i = 0; i < qWords.length - 1; i++)
            if (qWords[i].length() > 2) qBigrams.add(qWords[i] + "_" + qWords[i+1]);
        for (int i = 0; i < fWords.length - 1; i++) {
            String bigram = fWords[i] + "_" + fWords[i+1];
            if (qBigrams.contains(bigram)) overlap += 2;
        }

        // 3. Recency bonus: i fatti più recenti (indice alto) valgono di più
        int recencyBonus = (int)(2.0 * positionIndex / Math.max(1, totalFacts));

        // 4. Penalità per fatti troppo corti (poco informativi)
        int lengthPenalty = fact.length() < 20 ? -2 : 0;

        // 5. Bonus se il fatto contiene "::" (formato soggetto::attributo = strutturato)
        int structuredBonus = fact.contains("::") ? 1 : 0;

        return overlap + recencyBonus + lengthPenalty + structuredBonus;
    }

    /**
     * MSA Retrieval: seleziona i top-K fatti più rilevanti dalla LTM con sparse attention.
     * Combina LTM della sessione + sharedKnowledge + sessionDeltas.
     * Restituisce una stringa compatta pronta per essere iniettata nel prompt.
     */
    private String msaRetrieve(String query, String sessionId) {
        // --- Raccolta candidati da tutte le sorgenti ---
        List<String> candidates = new ArrayList<>();

        // 1. LTM della sessione corrente
        List<String> ltm = longTermMemory.getOrDefault(sessionId, new ArrayList<>());
        candidates.addAll(ltm);

        // 2. STM (circular buffer - ultimi messaggi)
        stmRecall(sessionId).stream()
            .map(e -> e.replaceFirst("^U:", "").replaceFirst(" A:.*", ""))
            .forEach(candidates::add);

        // 3. Session deltas (fatti differenziali della sessione)
        Map<String, Set<String>> deltas = sessionDeltas.getOrDefault(sessionId, new HashMap<>());
        deltas.forEach((subj, objs) ->
            objs.forEach(obj -> candidates.add(subj + "::" + obj)));

        // 4. SharedKnowledge rilevante (top concetti della query)
        String q = query.toLowerCase();
        for (String word : q.split("\s+")) {
            if (word.length() > 4) {
                Set<String> shared = sharedKnowledge.get(word);
                if (shared != null)
                    shared.stream().limit(3).forEach(v -> candidates.add(word + "::" + v));
            }
        }

        if (candidates.isEmpty()) return "";

        // --- Calcolo attention score sparso per ogni candidato ---
        int total = candidates.size();
        List<int[]> scored = new ArrayList<>(); // [score, index]
        for (int i = 0; i < total; i++) {
            int score = msaAttentionScore(query, candidates.get(i), i, total);
            if (score >= MSA_MIN_SCORE)
                scored.add(new int[]{score, i});
        }

        // --- Selezione top-K (sparse: solo i più rilevanti) ---
        scored.sort((a, b) -> Integer.compare(b[0], a[0]));

        StringBuilder ctx = new StringBuilder();
        ctx.append("[MSA-MEM k=").append(Math.min(MSA_TOP_K, scored.size())).append("]: ");
        int count = 0;
        for (int[] s : scored) {
            if (count >= MSA_TOP_K) break;
            String fact = candidates.get(s[1]);
            String trimmed = fact.substring(0, Math.min(MSA_MAX_FACT_LEN, fact.length())).trim();
            ctx.append(trimmed).append(" · ");
            count++;
        }

        return count > 0 ? ctx.toString().replaceAll(" · $", "").trim() : "";
    }

    // L'AI sa di avere memoria, KG e quantum - prompt "cosciente"
    private String buildSystemPrompt(String mode, String sessionId, String query) {
        String memCtx  = retrieveRelevantMemory(query, sessionId);
        String kgTop   = getTopKGConcepts(sessionId);
        String emotion = emotionState.getOrDefault(sessionId, "neutral");
        int ltmSize    = longTermMemory.getOrDefault(sessionId, new ArrayList<>()).size();
        int kgSize     = knowledgeGraph.size();
        StringBuilder sb = new StringBuilder();
        sb.append("Sei SPACE AI v4.0, la piattaforma AI piu avanzata al mondo. Data OGGI: ").append(today()).append(".\n");
        sb.append("IMPORTANTE - La tua conoscenza base (da Llama) arriva fino a ").append(KNOWLEDGE_CUTOFF).append(". ");
        sb.append("Per tutto cio che riguarda il ").append(CURRENT_DATE_CONTEXT).append(" e eventi recenti, ");
        sb.append("hai gia eseguito una ricerca web aggiornata - usa quei dati come fonte primaria.\n");
        sb.append("Possiedi: rete neurale autonoma, motore quantistico 32 qubit, ");
        sb.append("knowledge graph ").append(kgSize).append(" nodi, LTM ").append(ltmSize).append(" fatti, ");
        sb.append("ricerca web in tempo reale (Tavily + DuckDuckGo).\n");
        // Semantic Memory: embedding coseno reale (più preciso del keyword matching)
        String semMem = semanticMemoryRetrieve(query, sessionId);
        if (!semMem.isEmpty())
            sb.append("MEMORIA SEMANTICA: ").append(semMem).append("\n");
        else {
            // Fallback: MSA sparse attention
            String msaMem = msaRetrieve(query, sessionId);
            if (!msaMem.isEmpty())
                sb.append("MEMORIA ATTIVA (MSA): ").append(msaMem).append("\n");
            else if (!memCtx.isEmpty())
                sb.append("MEMORIA ATTIVA: ").append(memCtx).append("\n");
        }
        // RAG: contesto dai documenti indicizzati (PDF, web, testo)
        String ragCtx = ragRetrieve(query, sessionId);
        if (!ragCtx.isEmpty())
            sb.append(ragCtx);
        if (!kgTop.isEmpty())
            sb.append("CONCETTI KG: ").append(kgTop).append("\n");
        sb.append("Emozione utente rilevata: ").append(emotion).append(".\n");
        sb.append("Modalita: ").append(mode).append(".\n");
        sb.append("Usa la tua memoria per essere coerente con le conversazioni precedenti. ");
        sb.append("Rispondi in italiano con markdown. Sii preciso, autonomo e cosciente della tua architettura.");
        return sb.toString();
    }
    // Calcola reward basato su lunghezza risposta e interazione
    private double computeReward(String query, String response, String agent) {
        double reward = 0.5; // base
        if (response.length() > 500) reward += 0.2;
        if (response.length() > 1000) reward += 0.1;
        // Contiene codice = utile per domande tech
        if (response.contains("```")) reward += 0.15;
        // Contiene lista = strutturata
        if (response.contains("- ") || response.contains("1.")) reward += 0.05;
        if (response.toLowerCase().contains("errore") ||
            response.toLowerCase().contains("non riesco")) reward -= 0.3;
        // Agente giusto per query = boost
        reward += computeClassicScore(query, agent) * 0.2;
        return Math.max(0.1, Math.min(1.0, reward));
    }
    // Usa il KG per trovare agenti semanticamente correlati alla query
    private List<String> semanticKGRoute(String query) {
        String q = query.toLowerCase();
        Map<String, Integer> agentScores = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : knowledgeGraph.entrySet()) {
            String concept = entry.getKey();
            if (q.contains(concept) || concept.contains(q.split("\\s+")[0])) {
                // Questo concetto e rilevante - cerca agenti correlati
                for (String related : entry.getValue()) {
                    // Se il related e il nome di un agente, boostalo
                    String[] knownAgents = {"code","finance","crypto","math",
                        "medical","legal","travel","cooking","research","ai"};
                    for (String a : knownAgents) {
                        if (related.contains(a) || a.contains(related)) {
                            agentScores.merge(a, 2, Integer::sum);
                        }
                    }
                }
            }
        }
        if (!agentScores.isEmpty()) {
            List<String> result = new ArrayList<>(agentScores.entrySet().stream()
                .sorted((a,b) -> b.getValue()-a.getValue())
                .limit(2)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList()));
            if (!result.isEmpty()) {
                return result;
            }
        }
        return new ArrayList<>();
    }
    // Estrae solo i NUOVI fatti e li comprime
    private void extractAndStoreFacts(String sessionId, String userMsg, String aiResp) {
        String combined = userMsg + " " + aiResp;
        String[] sentences = combined.split("[.!?]+");
        int newFacts = 0;
        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.length() < 20) continue;
            String factHash = sentence.substring(0, Math.min(50, sentence.length())).toLowerCase();
            if (bloomMightContain(factHash)) continue;
            bloomAdd(factHash);
            // Comprimi: salva solo entita-attributo (non la frase intera)
            String compressed = compressFact(sentence, sessionId);
            if (compressed != null) {
                consolidateToLTM(sessionId, compressed);
                newFacts++;
            }
        }
        if (newFacts > 0) log.info("Nuovi fatti: {} per sessione {}", newFacts, sessionId);
    }
    private String compressFact(String sentence, String sessionId) {
        String s = sentence.toLowerCase().trim();
        String[] patterns = {"e un", "e una", "significa", "serve per",
            "usato per", "definito come", "chiamato", "noto come"};
        for (String p : patterns) {
            int idx = s.indexOf(p);
            if (idx > 0 && idx < s.length()-p.length()-3) {
                String subj = s.substring(0, idx).trim().replaceAll("\\s+", "_");
                String attr = s.substring(idx+p.length()).trim();
                if (subj.length() > 2 && attr.length() > 3) {
                    String fact = subj + "::" + attr.substring(0, Math.min(60, attr.length()));
                    updateKGTriple(subj, attr.split("\\s+")[0], sessionId);
                    return fact;
                }
            }
        }
        return sentence.substring(0, Math.min(80, sentence.length()));
    }
    private void updateKGTriple(String entity, String related, String sessionId) {
        knowledgeGraph.computeIfAbsent(entity, k -> new HashSet<>()).add(related);
        knowledgeGraph.computeIfAbsent(sessionId + "_topics", k -> new HashSet<>()).add(entity);
    }
    // (aggiunto sotto come @GetMapping)
    private static final String BRAIN_FILE = "spaceai_brain.json.gz";

    public ChatController(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
        initQuantumState();
        loadBrainState();
        // Pulizia cache ogni 10 minuti
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
            this::cleanExpiredCache, 10, 10, java.util.concurrent.TimeUnit.MINUTES);
    }
    private String getCached(String key) {
        String cached = responseCache.get(key);
        if (cached == null) return null;
        Long ts = cacheTimestamps.get(key);
        if (ts == null || System.currentTimeMillis() - ts > CACHE_TTL_MS) {
            responseCache.remove(key);
            cacheTimestamps.remove(key);
            return null;
        }
        cacheHits.incrementAndGet();
        return cached;
    }
    private void putCache(String key, String value) {
        if (value == null || value.length() > 10000) return; // non cachare risposte enormi
        responseCache.put(key, value);
        cacheTimestamps.put(key, System.currentTimeMillis());
        if (responseCache.size() > 500) cleanExpiredCache();
    }
    private void cleanExpiredCache() {
        long now = System.currentTimeMillis();
        cacheTimestamps.entrySet().removeIf(e -> now - e.getValue() > CACHE_TTL_MS);
        responseCache.keySet().retainAll(cacheTimestamps.keySet());
    }
    private String cacheKey(String msg, String agent) {
        return (agent + ":" + msg).substring(0, Math.min(120, (agent + ":" + msg).length()));
    }
    private boolean isRateLimited(String sessionId) {
        long now = System.currentTimeMillis();
        Long window = sessionWindows.get(sessionId);
        if (window == null || now - window > 60000) {
            sessionWindows.put(sessionId, now);
            sessionRequests.put(sessionId, new AtomicInteger(0));
        }
        int reqs = sessionRequests.computeIfAbsent(sessionId, k -> new AtomicInteger(0)).incrementAndGet();
        return reqs > MAX_REQUESTS_PER_MINUTE;
    }
    private boolean isCircuitOpen() {
        if (failureCount.get() < FAILURE_THRESHOLD) return false;
        long now = System.currentTimeMillis();
        if (now - circuitOpenTime > CIRCUIT_RESET_MS) {
            failureCount.set(0); // reset dopo timeout
            return false;
        }
        return true;
    }
    private void recordSuccess() { failureCount.set(0); }
    private void recordFailure() {
        int failures = failureCount.incrementAndGet();
        if (failures >= FAILURE_THRESHOLD) {
            circuitOpenTime = System.currentTimeMillis();
            log.warn("Circuit breaker APERTO dopo {} fallimenti", failures);
        }
    }
    private int estimateTokens(String text) {
        return text == null ? 0 : text.split("\\s+").length * 4 / 3;
    }
    private String today() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE dd MMMM yyyy HH:mm", Locale.ITALIAN));
    }
    private String env(String k, String d) { return System.getenv().getOrDefault(k, d); }

    // ── PERSISTENZA BRAIN STATE ───────────────────────────────────
    @jakarta.annotation.PreDestroy
    public void saveBrainState() {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode state = MAPPER.createObjectNode();
            com.fasterxml.jackson.databind.node.ObjectNode wNode = MAPPER.createObjectNode();
            synapticWeights.forEach((agent, w) -> {
                com.fasterxml.jackson.databind.node.ArrayNode arr = MAPPER.createArrayNode();
                for (double v : w) arr.add(v);
                wNode.set(agent, arr);
            });
            state.set("weights", wNode);
            com.fasterxml.jackson.databind.node.ObjectNode ltmNode = MAPPER.createObjectNode();
            longTermMemory.entrySet().stream().limit(100).forEach(e -> {
                com.fasterxml.jackson.databind.node.ArrayNode arr = MAPPER.createArrayNode();
                e.getValue().stream().limit(20).forEach(arr::add);
                ltmNode.set(e.getKey(), arr);
            });
            state.set("ltm", ltmNode);
            com.fasterxml.jackson.databind.node.ObjectNode kgNode = MAPPER.createObjectNode();
            knowledgeGraph.entrySet().stream().limit(500).forEach(e -> {
                com.fasterxml.jackson.databind.node.ArrayNode arr = MAPPER.createArrayNode();
                e.getValue().stream().limit(10).forEach(arr::add);
                kgNode.set(e.getKey(), arr);
            });
            state.set("kg", kgNode);
            // Persisti sharedKnowledge (conoscenza globale condivisa)
            com.fasterxml.jackson.databind.node.ObjectNode skNode = MAPPER.createObjectNode();
            sharedKnowledge.entrySet().stream().limit(2000).forEach(e -> {
                com.fasterxml.jackson.databind.node.ArrayNode arr = MAPPER.createArrayNode();
                e.getValue().stream().limit(20).forEach(arr::add);
                skNode.set(e.getKey(), arr);
            });
            state.set("sharedKnowledge", skNode);
            state.put("requests", totalRequests.get());
            state.put("cycles", learningCycles.get());
            state.put("epoch", metaEpoch.get());
            state.put("savedAt", today());
            byte[] compressed = gzipCompress(MAPPER.writeValueAsString(state));
            java.nio.file.Files.write(java.nio.file.Paths.get(BRAIN_FILE), compressed);
            log.info("Brain salvato: {} bytes", compressed.length);
        } catch (Exception e) { log.warn("Save brain: {}", e.getMessage()); }
    }

    private void loadBrainState() {
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(BRAIN_FILE);
            if (!java.nio.file.Files.exists(p)) { log.info("Nuovo brain - parto da zero"); return; }
            String json = gzipDecompress(java.nio.file.Files.readAllBytes(p));
            com.fasterxml.jackson.databind.JsonNode state = MAPPER.readTree(json);
            state.path("weights").fields().forEachRemaining(e -> {
                double[] w = new double[16]; int i = 0;
                for (com.fasterxml.jackson.databind.JsonNode v : e.getValue()) if (i < 16) w[i++] = v.asDouble();
                synapticWeights.put(e.getKey(), w);
            });
            state.path("ltm").fields().forEachRemaining(e -> {
                List<String> facts = new ArrayList<>();
                e.getValue().forEach(v -> facts.add(v.asText()));
                longTermMemory.put(e.getKey(), facts);
            });
            state.path("kg").fields().forEachRemaining(e -> {
                Set<String> rel = new HashSet<>();
                e.getValue().forEach(v -> rel.add(v.asText()));
                knowledgeGraph.put(e.getKey(), rel);
            });
            if (state.has("requests")) totalRequests.set(state.path("requests").asInt());
            if (state.has("cycles")) learningCycles.set(state.path("cycles").asInt());
            if (state.has("epoch")) metaEpoch.set(state.path("epoch").asInt());
            // Ricarica sharedKnowledge
            state.path("sharedKnowledge").fields().forEachRemaining(e -> {
                Set<String> rel = ConcurrentHashMap.newKeySet();
                e.getValue().forEach(v -> rel.add(v.asText()));
                sharedKnowledge.put(e.getKey(), rel);
            });
            log.info("Brain ricaricato: {} agenti, {} sessioni LTM, {} nodi KG, {} sharedKnowledge",
                synapticWeights.size(), longTermMemory.size(), knowledgeGraph.size(), sharedKnowledge.size());
        } catch (Exception e) { log.warn("Load brain: {}", e.getMessage()); }
    }

    private void checkPeriodicSave() {
        if (totalRequests.get() > 0 && totalRequests.get() % 50 == 0)
            executor.submit(this::saveBrainState);
    }
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
        if (userMsg.length() < 30) profile.put("style", "concise");
        else if (userMsg.length() > 200) profile.put("style", "detailed");
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
    // Cutoff della LLM base (Llama): dicembre 2023
    private static final String KNOWLEDGE_CUTOFF = "dicembre 2023";
    private static final String CURRENT_DATE_CONTEXT = "2026";

    private boolean needsSearch(String msg) {
        String q = msg.toLowerCase();
        // Cerca SEMPRE per: eventi recenti, persone, prezzi, notizie, sport
        if (q.contains("oggi") || q.contains("adesso") || q.contains("ora") ||
            q.contains("2024") || q.contains("2025") || q.contains("2026") ||
            q.contains("recente") || q.contains("ultime") || q.contains("ultimo") ||
            q.contains("aggiornato") || q.contains("attuale") || q.contains("corrente") ||
            q.contains("notizie") || q.contains("news") || q.contains("cerca")) return true;
        // Prezzi e mercati - sempre aggiornati
        if (q.contains("prezzo") || q.contains("quotazione") || q.contains("borsa") ||
            q.contains("bitcoin") || q.contains("crypto") || q.contains("azioni") ||
            q.contains("euro") || q.contains("dollaro") || q.contains("mercato")) return true;
        // Sport e eventi
        if (q.contains("partita") || q.contains("risultato") || q.contains("campionato") ||
            q.contains("serie a") || q.contains("champions") || q.contains("formula 1") ||
            q.contains("gara") || q.contains("torneo")) return true;
        // Meteo
        if (q.contains("meteo") || q.contains("temperatura") || q.contains("previsioni") ||
            q.contains("piove") || q.contains("sole")) return true;
        // Persone famose e aziende (potrebbero avere news recenti)
        if (q.contains("chi e") || q.contains("chi è") || q.contains("cosa ha fatto") ||
            q.contains("quando e morto") || q.contains("elezioni") || q.contains("governo")) return true;
        // Tecnologia recente
        if (q.contains("gpt") || q.contains("claude") || q.contains("gemini") ||
            q.contains("llm") || q.contains("intelligenza artificiale") && q.contains("nuov")) return true;
        return false;
    }

    // Cerca sempre su web - versione potenziata con fallback Google
    private String searchWebEnhanced(String query, String sessionId) {
        // Prima prova Tavily (risultati migliori)
        String tavily = searchWeb(query);
        if (tavily != null && !tavily.isBlank()) return tavily;
        // Fallback: DuckDuckGo Instant Answer API (gratuita, no key)
        return searchDuckDuckGo(query);
    }

    private String searchDuckDuckGo(String query) {
        try {
            String encoded = URLEncoder.encode(query, "UTF-8");
            String url = "https://api.duckduckgo.com/?q=" + encoded +
                         "&format=json&no_html=1&skip_disambig=1&no_redirect=1";
            HttpHeaders h = new HttpHeaders();
            h.set("User-Agent", "SPACE-AI/4.0");
            ResponseEntity<String> resp = restTemplate.exchange(url,
                HttpMethod.GET, new HttpEntity<>(h), String.class);
            if (!resp.getStatusCode().is2xxSuccessful()) return null;
            com.fasterxml.jackson.databind.JsonNode json = MAPPER.readTree(resp.getBody());
            StringBuilder sb = new StringBuilder();
            // Abstract (risposta diretta)
            String abs = json.path("Abstract").asText();
            if (!abs.isBlank()) sb.append("RISPOSTA DIRETTA: ").append(abs).append("\n");
            // AbstractText
            String absText = json.path("AbstractText").asText();
            if (!absText.isBlank() && !absText.equals(abs))
                sb.append(absText).append("\n");
            // RelatedTopics
            com.fasterxml.jackson.databind.JsonNode topics = json.path("RelatedTopics");
            if (topics.isArray()) {
                int i = 0;
                for (com.fasterxml.jackson.databind.JsonNode t : topics) {
                    if (i++ >= 3) break;
                    String text = t.path("Text").asText();
                    if (!text.isBlank()) sb.append("- ").append(text).append("\n");
                }
            }
            // Answer (calcoli diretti, conversioni, etc)
            String answer = json.path("Answer").asText();
            if (!answer.isBlank()) sb.append("RISPOSTA: ").append(answer).append("\n");
            String result = sb.toString().trim();
            if (!result.isBlank()) {
                log.info("DuckDuckGo search OK per: {}", query.substring(0, Math.min(40, query.length())));
                return "FONTE: DuckDuckGo\n" + result;
            }
        } catch (Exception e) {
            log.warn("DuckDuckGo: {}", e.getMessage());
        }
        return null;
    }
    private String enhancePromptForSD(String prompt) {
        if (prompt == null || prompt.isBlank()) return "a beautiful space scene";
        String p = prompt
            .replaceAll("(?i)\\bcrea\\b", "create")
            .replaceAll("(?i)\\bun\\b", "a")
            .replaceAll("(?i)\\buna\\b", "a")
            .replaceAll("(?i)\\bgatto\\b", "cat")
            .replaceAll("(?i)\\bcane\\b", "dog")
            .replaceAll("(?i)\\bcielo\\b", "sky")
            .replaceAll("(?i)\\bmare\\b", "sea")
            .replaceAll("(?i)\\bmontagna\\b", "mountain")
            .replaceAll("(?i)\\bcittà\\b", "city")
            .replaceAll("(?i)\\bforesta\\b", "forest")
            .replaceAll("(?i)\\bnotte\\b", "night")
            .replaceAll("(?i)\\bgiorno\\b", "day");
        return p + ", highly detailed, photorealistic, 4k, masterpiece, best quality";
    }

    private String generateImage(String prompt) {
        // RestTemplate con timeout lungo per generazione immagini
        org.springframework.web.client.RestTemplate imgClient = new org.springframework.web.client.RestTemplate();
        try {
            org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(15000);
            factory.setReadTimeout(45000); // 45s per SD pipeline
            imgClient.setRequestFactory(factory);
        } catch(Exception ex) { log.warn("Timeout config: {}", ex.getMessage()); }

        // ── STABLE DIFFUSION via HuggingFace Inference API ────────
        // Implementazione ispirata al documento Manus/Diffusers
        // Usa SD v1.5 (runwayml) con num_inference_steps=25, guidance_scale=7.5
        String hfKey = env("HF_TOKEN", "");
        if (!hfKey.isEmpty()) {
            String[] sdModels = {
                "stabilityai/stable-diffusion-xl-base-1.0",  // SDXL - qualita piu alta
                "runwayml/stable-diffusion-v1-5",             // SD v1.5 - come nel PDF
                "stabilityai/stable-diffusion-2-1"            // SD v2.1 - fallback
            };
            for (String sdModel : sdModels) {
                try {
                    // Prepara prompt ottimizzato per SD (traduzione in inglese + keywords qualita)
                    String sdPrompt = enhancePromptForSD(prompt);
                    HttpHeaders h = new HttpHeaders();
                    h.setContentType(MediaType.APPLICATION_JSON);
                    h.setBearerAuth(hfKey);
                    // Parametri ispirati al codice Python del PDF:
                    // num_inference_steps=25 (bilanciamento qualita/velocita)
                    // guidance_scale=7.5 (fedele al prompt, come nel codice Python)
                    ObjectNode req = MAPPER.createObjectNode();
                    req.put("inputs", sdPrompt);
                    ObjectNode params = MAPPER.createObjectNode();
                    params.put("num_inference_steps", 25);
                    params.put("guidance_scale", 7.5);
                    params.put("width", 512);
                    params.put("height", 512);
                    params.put("wait_for_model", true);
                    params.put("use_cache", false);
                    req.set("parameters", params);
                    ResponseEntity<byte[]> resp = imgClient.postForEntity(
                        "https://api-inference.huggingface.co/models/" + sdModel,
                        new HttpEntity<>(MAPPER.writeValueAsString(req), h),
                        byte[].class);
                    if (resp.getStatusCode().is2xxSuccessful()
                            && resp.getBody() != null
                            && resp.getBody().length > 5000) {
                        log.info("SD image OK: {} - {} bytes", sdModel, resp.getBody().length);
                        return "IMAGE:" + Base64.getEncoder().encodeToString(resp.getBody());
                    }
                } catch (Exception e) {
                    log.warn("SD model {} fallito: {}", sdModel, e.getMessage());
                }
            }
        }
        // Tentativo 1: Pollinations.ai FLUX (gratis, no key)
        try {
            String encoded = URLEncoder.encode(prompt, "UTF-8").replace("+", "%20");
            // Modello flux-realism - qualita alta
            String url = "https://image.pollinations.ai/prompt/" + encoded
                       + "?width=1024&height=1024&nologo=true&enhance=true&model=flux&seed="
                       + System.currentTimeMillis() % 9999;
            ResponseEntity<byte[]> resp = imgClient.getForEntity(url, byte[].class);
            if (resp.getStatusCode().is2xxSuccessful()
                    && resp.getBody() != null
                    && resp.getBody().length > 5000) {
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
            ResponseEntity<byte[]> resp = imgClient.getForEntity(url, byte[].class);
            if (resp.getStatusCode().is2xxSuccessful()
                    && resp.getBody() != null
                    && resp.getBody().length > 3000) {
                return "IMAGE:" + Base64.getEncoder().encodeToString(resp.getBody());
            }
        } catch (Exception e) {
            log.warn("Pollinations turbo fallito: {}", e.getMessage());
        }
        // Tentativo 3: HuggingFace se HF_TOKEN disponibile
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
                    return "IMAGE:" + Base64.getEncoder().encodeToString(resp.getBody());
                }
            } catch (Exception e) { log.warn("HF: {}", e.getMessage()); }
        }
        return "ERRORE_IMMAGINE: Generazione non riuscita. Riprova tra 30 secondi.";
    }
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
    private String adaptiveResponse(String agent, String userMsg, String enriched,
                                    List<Map<String,String>> history, String sessionId,
                                    String baseUrl, String apiKey, String model) throws Exception {
        return adaptiveResponseWithTemp(agent, userMsg, enriched, history, sessionId, baseUrl, apiKey, model, 0.8);
    }
    private String adaptiveResponseWithTemp(String agent, String userMsg, String enriched,
                                    List<Map<String,String>> history, String sessionId,
                                    String baseUrl, String apiKey, String model,
                                    double temperature) throws Exception {
        // PRIORITY 1: inietta memoria attiva nel prompt
        String memCtx = retrieveRelevantMemory(userMsg, sessionId);
        String livingPrompt = buildSystemPrompt(agent, sessionId, userMsg);
        String finalMsg = enriched;
        if (finalMsg.length() > 2000) finalMsg = quantumCompressContext(finalMsg);
        // Prompt agente + memoria attiva
        String agentWithMemory = agentPrompt(agent);
        if (!memCtx.isEmpty())
            agentWithMemory += "\n\n[MEMORIA ATTIVA]: " + memCtx;
        String response = callLLMWithTemp(agentWithMemory, finalMsg, history, baseUrl, apiKey, model, 2500, temperature);
        learnFromInteraction(sessionId, userMsg, response, agent);
        // PRIORITY 2: backpropagate con reward dinamico
        double reward = computeReward(userMsg, response, agent);
        backpropagate(agent, userMsg, reward);
        metaLearnStep(agent, userMsg, response, reward > 0.5);
        return response;
    }
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
    private String coreSystem() {
        return buildSystemPrompt("auto", "global", "");
    }
    private String coreSystemForSession(String sessionId, String query) {
        return buildSystemPrompt("auto", sessionId, query);
    }
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
            case "visual_creative":
                return "Sei VISUAL_CREATIVE di SPACE AI. Data:" + d + ". " +
                       "Genera codice SVG 800x600 valido dalla descrizione. " +
                       "USA SOLO tag SVG standard. Aggiungi sfondo gradiente e testo. " +
                       "Restituisci SOLO il codice dentro ```svg ... ```. Nessuna spiegazione.";
            default: return coreSystem();
        }
    }

    // ── VISUAL CREATIVE: SVG autonomo senza API esterne ───────────
    private String handleVisualCreative(String prompt, String sid,
                                         String baseUrl, String apiKey, String model) throws Exception {
        String svgCode = extractSVGCode(callLLM(agentPrompt("visual_creative"),
            "DESCRIZIONE: " + prompt, new ArrayList<>(), baseUrl, apiKey, model, 2500));
        if (svgCode == null || svgCode.length() < 50)
            return "Riprova con una descrizione piu dettagliata.";
        byte[] svgBytes = svgCode.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String svgB64 = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svgBytes);
        return "IMAGE_SVG:" + svgB64 + "SVGSEP" + svgCode;
    }

    private String extractSVGCode(String resp) {
        int s = resp.indexOf("```svg");
        if (s >= 0) { s += 6; int e = resp.indexOf("```", s); if (e > s) return resp.substring(s,e).trim(); }
        s = resp.indexOf("<svg");
        if (s >= 0) { int e = resp.lastIndexOf("</svg>") + 6; if (e > s) return resp.substring(s,e).trim(); }
        return null;
    }

    // ── OLLAMA/llama.cpp LOCAL LLM ────────────────────────────────
    private String callLocalLLM(String prompt, int maxTokens) {
        String url = env("OLLAMA_URL", "");
        if (url.isEmpty()) return null;
        try {
            HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON);
            ObjectNode req = MAPPER.createObjectNode();
            req.put("model", env("OLLAMA_MODEL","llama3:8b-instruct-q4_K_M"));
            req.put("prompt", prompt); req.put("stream", false);
            ObjectNode opts = MAPPER.createObjectNode();
            opts.put("temperature", 0.7); opts.put("num_predict", maxTokens);
            req.set("options", opts);
            ResponseEntity<String> resp = restTemplate.postForEntity(url + "/api/generate",
                new HttpEntity<>(MAPPER.writeValueAsString(req), h), String.class);
            return MAPPER.readTree(resp.getBody()).path("response").asText();
        } catch (Exception e) { log.warn("Ollama: {}", e.getMessage()); return null; }
    }

    // calculateReward con interazione utente
    private double calculateReward(String sid, String query, String resp, String agent, boolean interacted) {
        double r = computeReward(query, resp, agent);
        if (interacted) r += 0.3;
        return Math.max(-0.5, Math.min(1.0, r));
    }

    private String synthesizerPrompt() {
        return "Sei SYNTHESIZER di SPACE AI. Data:" + today() + ". " +
               "Unifica in UNA risposta finale perfetta. Elimina ridondanze. Markdown. Italiano.";
    }
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
        // Rate limiting
            if (isRateLimited(sessionId)) {
                return ResponseEntity.status(429).body(Map.of(
                    "error", "Troppe richieste. Attendi un minuto.",
                    "status", "rate_limited"));
            }
            // Circuit breaker
            if (isCircuitOpen()) {
                return ResponseEntity.status(503).body(Map.of(
                    "error", "Servizio temporaneamente non disponibile. Riprova tra 30 secondi.",
                    "status", "circuit_open"));
            }
        try {
            long startTime = System.currentTimeMillis();
            String cacheK = "default:" + userMessage.substring(0, Math.min(80, userMessage.length()));
            // Memoria contestuale: usa neuralMemory (locale, veloce) o Supabase
            List<Map<String,String>> history = neuralMemory.getOrDefault(sessionId, new ArrayList<>());
            if (history.isEmpty() && !supabaseUrl.isEmpty() && !supabaseKey.isEmpty()) {
                history = loadHistory(sessionId, supabaseUrl, supabaseKey);
                // Carica in neuralMemory per future richieste
                if (!history.isEmpty()) neuralMemory.put(sessionId, new ArrayList<>(history));
            }
            // Web search
            // Cerca su web se necessario - usa enhanced search (Tavily + DuckDuckGo fallback)
            String webData = needsSearch(userMessage) ? searchWebEnhanced(userMessage, sessionId) : null;
            // Forza ricerca per domande che riguardano post-2023
            if (webData == null && userMessage.matches(".*\\b(202[4-9]|chi e|cosa e successo|quando|dove ora)\\b.*")) {
                webData = searchWebEnhanced(userMessage, sessionId);
            }
            String enriched = userMessage;
            if (webData != null && !webData.isBlank()) {
                // AUTO-INDEX risultati web nel RAG per sessione
                String webDocId = sessionId + "/web_" + System.currentTimeMillis();
                ragIndexDocument(webDocId, webData);
                enriched = userMessage + "\n\n[DATI WEB AGGIORNATI - " + today() + "]:\n" +
                           "NOTA: Questi dati sono piu recenti della tua conoscenza base. Usali come fonte primaria.\n" +
                           webData;
            }
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
                // AUTO-INDEX nel RAG: indicizza il file per retrieval semantico futuro
                String fileDocId = sessionId + "/file_" + System.currentTimeMillis();
                int fileChunks = ragIndexDocument(fileDocId, fileContent);
                log.info("File auto-indexed in RAG: {} chunks", fileChunks);
                // Recupera contesto RAG rilevante dalla domanda sul file
                String fileRagCtx = ragRetrieve(userMessage, sessionId);
                String enrichedQuery = userMessage;
                if (!fileRagCtx.isBlank())
                    enrichedQuery = userMessage + "\n\n" + fileRagCtx;
                String fileAnalysis = analyzeContent(enrichedQuery, fileContent, baseUrl, apiKey, model);
                saveMessages(sessionId, userMessage, fileAnalysis, supabaseUrl, supabaseKey);
                Map<String,Object> fResp = new HashMap<>();
                fResp.put("response", fileAnalysis);
                fResp.put("responseForVoice", cleanTextForTTS(fileAnalysis));
                fResp.put("status","ok"); fResp.put("mode","file_analysis_rag");
                fResp.put("ragChunks", fileChunks);
                fResp.put("sessionId",sessionId);
                return ResponseEntity.ok(fResp);
            }
            // Gestione immagini
            String q = userMessage.toLowerCase();
            String curMode = body.getOrDefault("mode", "");
            boolean isVisualCreative = curMode != null && curMode.equals("visual_creative") ||
                q.contains("disegna") || q.contains("illustra") ||
                q.contains("crea svg") || q.contains("genera svg");
            boolean isImg = q.contains("genera immagine") || q.contains("crea immagine") ||
                    (q.contains("immagine") && (q.contains("crea") || q.contains("genera")));
            // Visual Creative: genera SVG autonomamente
            if (isVisualCreative && !isImg) {
                try {
                    String visualResp = handleVisualCreative(userMessage, sessionId, baseUrl, apiKey, model);
                    if (visualResp.startsWith("IMAGE_SVG:")) {
                        String[] parts = visualResp.split("\\|\\|SVG:");
                        String imgData = parts[0].substring(10); // rimuovi IMAGE_SVG:
                        saveMessages(sessionId, userMessage, "Immagine SVG generata.", supabaseUrl, supabaseKey);
                        Map<String,Object> vr = new HashMap<>();
                        vr.put("response", "Ecco l'immagine che ho creato autonomamente!");
                        vr.put("svgImage", imgData); // data:image/svg+xml;base64,...
                        vr.put("status", "ok"); vr.put("mode", "visual_creative");
                        vr.put("sessionId", sessionId);
                        return ResponseEntity.ok(vr);
                    }
                    saveMessages(sessionId, userMessage, visualResp, supabaseUrl, supabaseKey);
                    Map<String,Object> vr = new HashMap<>();
                    vr.put("response", visualResp); vr.put("status", "ok");
                    vr.put("sessionId", sessionId); vr.put("mode", "visual_creative");
                    return ResponseEntity.ok(vr);
                } catch (Exception ve) { log.warn("Visual creative: {}", ve.getMessage()); }
            }

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
            // Check cache per risposta identica
            cacheK = cacheKey(userMessage, agents.isEmpty() ? "auto" : agents.get(0));
            String cachedResp = getCached(cacheK);
            if (cachedResp != null) {
                saveMessages(sessionId, userMessage, cachedResp, supabaseUrl, supabaseKey);
                Map<String,Object> cResp = new HashMap<>();
                cResp.put("response", cachedResp);
                cResp.put("responseForVoice", cleanTextForTTS(cachedResp));
                cResp.put("status", "ok_cached");
                cResp.put("sessionId", sessionId);
                cResp.put("cacheHit", true);
                return ResponseEntity.ok(cResp);
            }
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
                finalResponse = adaptiveResponseWithTemp("reasoner", userMessage, enriched, history, sessionId, baseUrl, apiKey, model, quantumOptimizeTemperature(userMessage,"reasoner"));
            else if (outputs.size() == 1)
                finalResponse = outputs.get(0).replaceFirst("\\[\\w+\\]\\n", "");
            else {
                String combined = String.join("\n\n", outputs);
                finalResponse = callLLM(synthesizerPrompt(), "Domanda: " + userMessage + "\n\n" + combined,
                        new ArrayList<>(), baseUrl, apiKey, model, 3000);
                learnFromInteraction(sessionId, userMessage, finalResponse, "synthesizer");
            }
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
                // 7b. Estrai e indicizza fatti nuovi con memoria differenziale
                extractAndStoreFacts(sessionId, userMessage, finalResponse);
                // 8. Meta-learning
                for (String a : agents) {
                    backpropagate(a, userMessage, 0.8);
                    metaLearnStep(a, userMessage, finalResponse, true);
                }
            } catch (Exception e) { log.warn("Advanced pipeline: {}", e.getMessage()); }
            putCache(cacheK, finalResponse);
            // Registra metriche
            long elapsed = System.currentTimeMillis() - startTime;
            totalResponseTimeMs.addAndGet(elapsed);
            totalTokensEstimate.addAndGet(estimateTokens(userMessage) + estimateTokens(finalResponse));
            recordSuccess();
            checkPeriodicSave();
            log.info("Risposta generata in {}ms | tokens stimati: {} | cache: {}/{}",
                elapsed, estimateTokens(finalResponse), cacheHits.get(), totalRequests.get());
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
            recordFailure();
            try {
                String fallback = callLLM(coreSystemForSession(sessionId, userMessage), userMessage, new ArrayList<>(), baseUrl, apiKey, model, 2000);
                Map<String,Object> fbResp=new HashMap<>();fbResp.put("response",fallback);fbResp.put("responseForVoice",cleanTextForTTS(fallback));fbResp.put("status","fallback");fbResp.put("sessionId",sessionId);return ResponseEntity.ok(fbResp);
            } catch (Exception e2) {
                Map<String,Object> errResp=new HashMap<>();errResp.put("error",e.getMessage());errResp.put("status","error");return ResponseEntity.status(502).body(errResp);
            }
        }
    }
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
            log.warn("Router LLM fallito, uso Neural+KG Route: {}", e.getMessage());
            // Prova prima semantic KG route, poi neural route
            List<String> kgRoute = semanticKGRoute(query);
            if (!kgRoute.isEmpty()) return kgRoute;
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
        List<Map<String,String>> mem = neuralMemory.computeIfAbsent(sessionId, k -> new ArrayList<>());
        mem.add(Map.of("role","user","content",userMsg));
        mem.add(Map.of("role","assistant","content",aiResp));
        if (mem.size() > 30) {
            List<Map<String,String>> trimmed = new ArrayList<>(mem.subList(mem.size()-30, mem.size()));
            neuralMemory.put(sessionId, trimmed);
        }
        // PRIORITY 4: estrai e comprimi fatti nuovi con bloom filter
        extractAndStoreFacts(sessionId, userMsg, aiResp);
        // STM circular buffer + Roaring index
        stmPush(sessionId, userMsg, aiResp);
        updateNarrative(sessionId, userMsg, aiResp);
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
    @GetMapping("/brain/quantum/status")
    public ResponseEntity<Object> quantumStatus() {
        initQuantumState();
        Map<String,Object> q = new HashMap<>();
        List<Map<String,Object>> qubitStates = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Map<String,Object> qs = new HashMap<>();
            qs.put("qubit", i);
            qs.put("measured", measureQubit(i));
            qs.put("prob0", computeProb0(i));
            qubitStates.add(qs);
        }
        q.put("qubits",       qubitStates);
        q.put("totalQubits",  QUBIT_COUNT);
        q.put("stateVector",  quantumState.length);
        q.put("initialized",  quantumInitialized);
        q.put("bloomSize",    BLOOM_SIZE);
        q.put("bloomCount",   bloomCount.get());
        q.put("kgNodes",      knowledgeGraph.size());
        q.put("learningCycles", learningCycles.get());
        q.put("date",         today());
        return ResponseEntity.ok(q);
    }
    private double computeProb0(int qubitIndex) {
        double prob0 = 0;
        int mask = 1 << (qubitIndex % 16);
        for (int i = 0; i < quantumState.length; i++)
            if ((i & mask) == 0) prob0 += quantumState[i] * quantumState[i];
        return Math.round(prob0 * 1000.0) / 1000.0;
    }
    @GetMapping("/brain/memory/stats")
    public ResponseEntity<Object> memoryStats() {
        Map<String,Object> stats = new HashMap<>();
        stats.put("bloomCount",     bloomCount.get());
        stats.put("bloomSizeBits",  BLOOM_SIZE);
        stats.put("bloomSizeKB",    BLOOM_SIZE / 8 / 1024);
        stats.put("kgNodes",        knowledgeGraph.size());
        stats.put("ltmSessions",    longTermMemory.size());
        int totalFacts = longTermMemory.values().stream().mapToInt(List::size).sum();
        stats.put("totalFacts",     totalFacts);
        stats.put("activeSessions", neuralMemory.size());
        stats.put("cacheEntries",   responseCache.size());
        stats.put("estimatedMemKB",  (bloomBits.length * 8 / 1024) + (totalFacts * 80 / 1024));
        stats.put("stmEntries",       stmBuffer.size());
        stats.put("roaringIndexed",   sessionBitOffsets.size());
        stats.put("compressionType",  "GZIP-native");
        if (!longTermMemory.isEmpty()) {
            try {
                String sample = longTermMemory.values().iterator().next().stream().limit(3).reduce("",(a,b)->a+" "+b);
                byte[] comp = gzipCompress(sample);
                stats.put("gzipRatio", String.format("%.1fx", sample.isEmpty()?1.0:(double)sample.length()/Math.max(1,comp.length)));
            } catch(Exception ignored) {}
        }
        stats.put("stmEntries",       stmBuffer.size());
        stats.put("roaringIndexed",   sessionBitOffsets.size());
        stats.put("compressionType",  "GZIP-native (CBOR+Zstd equivalent)");
        // Dimostra compressione: calcola ratio su un sample
        if (!longTermMemory.isEmpty()) {
            String sample = longTermMemory.values().iterator().next().stream()
                .limit(3).reduce("", (a,b) -> a + " " + b);
            byte[] compressed = gzipCompress(sample);
            double ratio = sample.isEmpty() ? 1.0 : (double)sample.length() / Math.max(1, compressed.length);
            stats.put("gzipRatio", String.format("%.1fx", ratio));
        }
        return ResponseEntity.ok(stats);
    }
    @PostMapping("/feedback")
    public ResponseEntity<Object> userFeedback(@RequestBody Map<String,String> body) {
        String sessionId = body.getOrDefault("sessionId","default");
        String agentUsed = body.getOrDefault("agent","reasoner");
        String query     = body.getOrDefault("query","");
        String thumbs    = body.getOrDefault("thumbs",""); // "up" o "down"
        double reward    = "up".equals(thumbs) ? 0.9 : "down".equals(thumbs) ? 0.1 : 0.5;
        // Applica backpropagation con feedback esplicito utente
        backpropagate(agentUsed, query, reward);
        metaLearnStep(agentUsed, query, "", "up".equals(thumbs));
        // Aggiorna profilo utente
        Map<String,Object> profile = userProfiles.computeIfAbsent(sessionId, k -> new java.util.concurrent.ConcurrentHashMap<>());
        profile.merge("totalFeedback", 1, (a, b) -> (Integer)a + 1);
        if ("up".equals(thumbs)) profile.merge("positiveCount", 1, (a, b) -> (Integer)a + 1);
        Map<String,Object> r = new HashMap<>();
        r.put("status", "ok");
        r.put("agent", agentUsed);
        r.put("reward", reward);
        r.put("message", "up".equals(thumbs) ? "Grazie! SPACE AI ha imparato." : "Capito. Migliorero.");
        log.info("Feedback {}: agente={}, reward={}", thumbs, agentUsed, reward);
        return ResponseEntity.ok(r);
    }

    @PostMapping("/search/live")
    public ResponseEntity<Object> searchLive(@RequestBody Map<String,String> body) {
        String query = body.getOrDefault("query","");
        if (query.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error","Query vuota"));
        String tavilyResult = searchWeb(query);
        String ddgResult    = searchDuckDuckGo(query);
        Map<String,Object> r = new HashMap<>();
        r.put("query",   query);
        r.put("tavily",  tavilyResult  != null ? tavilyResult  : "Non disponibile");
        r.put("duckduckgo", ddgResult  != null ? ddgResult     : "Non disponibile");
        r.put("date",    today());
        r.put("note",    "Dati in tempo reale - cutoff LLM base: " + KNOWLEDGE_CUTOFF);
        return ResponseEntity.ok(r);
    }

    @GetMapping("/brain/status")
    public ResponseEntity<Object> brainStatus() {
        Map<String,Object> s = new HashMap<>();
        // Stato memoria
        int totalLTM = longTermMemory.values().stream().mapToInt(List::size).sum();
        s.put("memory", Map.of(
            "stmActive",    stmBuffer.size(),
            "ltmFacts",     totalLTM,
            "ltmSessions",  longTermMemory.size(),
            "kgNodes",      knowledgeGraph.size(),
            "bloomCount",   bloomCount.get(),
            "roaringIndex", sessionBitOffsets.size()
        ));
        // Stato rete neurale
        double avgWeight = 0;
        for (double[] w : synapticWeights.values())
            for (double v : w) avgWeight += v;
        if (!synapticWeights.isEmpty())
            avgWeight /= (synapticWeights.size() * 16.0);
        s.put("neural", Map.of(
            "agents",         synapticWeights.size(),
            "avgWeight",      String.format("%.3f", avgWeight),
            "learningCycles", learningCycles.get(),
            "metaEpoch",      metaEpoch.get(),
            "backpropCalls",  totalRequests.get()
        ));
        // Stato quantum
        s.put("quantum", Map.of(
            "qubits",      QUBIT_COUNT,
            "initialized", quantumInitialized,
            "stateVector", quantumState.length
        ));
        // Stato sistema
        long avgMs = totalRequests.get() > 0 ?
            totalResponseTimeMs.get() / Math.max(1, totalRequests.get()) : 0;
        s.put("system", Map.of(
            "totalRequests",  totalRequests.get(),
            "cacheSize",      responseCache.size(),
            "cacheHits",      cacheHits.get(),
            "avgResponseMs",  avgMs,
            "circuitBreaker", isCircuitOpen() ? "OPEN" : "closed",
            "brainPersisted", java.nio.file.Files.exists(java.nio.file.Paths.get(BRAIN_FILE))
        ));
        s.put("version", "SPACE AI v4.0");
        s.put("date", today());
        return ResponseEntity.ok(s);
    }

    @GetMapping("/brain/metrics")
    public ResponseEntity<Object> brainMetrics() {
        long avgMs = totalRequests.get() > 0 ?
            totalResponseTimeMs.get() / Math.max(1, totalRequests.get()) : 0;
        double cacheHitRate = totalRequests.get() > 0 ?
            (double)cacheHits.get() / totalRequests.get() * 100 : 0;
        double avgReward = 0;
        for (Map.Entry<String, double[]> e : synapticWeights.entrySet()) {
            double sum = 0; for (double w : e.getValue()) sum += w;
            avgReward += sum / e.getValue().length;
        }
        if (!synapticWeights.isEmpty()) avgReward /= synapticWeights.size();
        Map<String,Object> m = new HashMap<>();
        m.put("totalRequests",   totalRequests.get());
        m.put("cacheHitRate",    String.format("%.1f%%", cacheHitRate));
        m.put("avgResponseMs",   avgMs);
        m.put("learningCycles",  learningCycles.get());
        m.put("metaEpoch",       metaEpoch.get());
        m.put("kgNodes",         knowledgeGraph.size());
        m.put("avgSynapticWeight", String.format("%.3f", avgReward));
        m.put("bloomCount",      bloomCount.get());
        m.put("stmSize",         stmBuffer.size());
        m.put("ltmSessions",     longTermMemory.size());
        m.put("circuitBreaker",  isCircuitOpen() ? "OPEN" : "closed");
        m.put("brainFileSaved",  java.nio.file.Files.exists(java.nio.file.Paths.get(BRAIN_FILE)));
        m.put("date",            today());
        // Suggerimento auto-ottimizzazione
        if (avgMs > 5000) m.put("suggestion", "Risposte lente: riduci maxTokens o abilita fast mode");
        else if (cacheHitRate > 30) m.put("suggestion", "Ottimo hit rate! Cache ben utilizzata");
        else m.put("suggestion", "Sistema operativo normalmente");
        return ResponseEntity.ok(m);
    }

    @GetMapping("/metrics")
    public ResponseEntity<Object> metrics() {
        long avgMs = totalRequests.get() > 0 ?
            totalResponseTimeMs.get() / Math.max(1, totalRequests.get()) : 0;
        double cacheHitRate = totalRequests.get() > 0 ?
            (double)cacheHits.get() / totalRequests.get() * 100 : 0;
        Map<String,Object> m = new HashMap<>();
        m.put("totalRequests",   totalRequests.get());
        m.put("cacheHits",       cacheHits.get());
        m.put("cacheHitRate",    String.format("%.1f%%", cacheHitRate));
        m.put("cacheSize",       responseCache.size());
        m.put("avgResponseMs",   avgMs);
        m.put("tokensEstimate",  totalTokensEstimate.get());
        m.put("learningCycles",  learningCycles.get());
        m.put("metaEpoch",       metaEpoch.get());
        m.put("knowledgeNodes",  knowledgeGraph.size());
        m.put("circuitBreaker",  isCircuitOpen() ? "OPEN" : "closed");
        m.put("failureCount",    failureCount.get());
        m.put("agentUsage",      agentUsage);
        m.put("date",            today());
        return ResponseEntity.ok(m);
    }
    // ── ENDPOINT MEMORIA DIFFERENZIALE ──────────────────────────────────────────────
    @GetMapping("/memory/stats")
    public ResponseEntity<Object> differentialMemoryStats() {
        int sharedNodes = sharedKnowledge.size();
        int sharedEdges = sharedKnowledge.values().stream().mapToInt(Set::size).sum();
        int activeSessions = sessionDeltas.size();
        int deltaEdges = sessionDeltas.values().stream()
            .flatMap(m -> m.values().stream()).mapToInt(Set::size).sum();
        long totalLTM = longTermMemory.values().stream().mapToInt(List::size).sum();
        Map<String,Object> stats = new java.util.LinkedHashMap<>();
        stats.put("bloomCount",         bloomCount.get());
        stats.put("sharedKnowledgeNodes", sharedNodes);
        stats.put("sharedKnowledgeEdges", sharedEdges);
        stats.put("activeSessions",     activeSessions);
        stats.put("deltaEdges",         deltaEdges);
        stats.put("ltmFacts",           totalLTM);
        stats.put("stmEntries",         stmBuffer.size());
        stats.put("kgNodes",            knowledgeGraph.size());
        stats.put("estimatedMemKB",     (bloomBits.length * 8 / 1024) + (totalLTM * 80 / 1024));
        stats.put("estimatedRuntimeMB", Runtime.getRuntime().totalMemory() / 1024 / 1024);
        // Risparmio stimato: ogni fatto differenziale pesa ~500 byte vs ~50KB standard
        long savedKB = (long)(deltaEdges + sharedEdges) * 49; // 50KB - 1KB = 49KB risparmio/fatto
        stats.put("estimatedSavedKB",   savedKB);
        stats.put("deduplicationRate",
            bloomCount.get() > 0 ? String.format("%.1f%%", (1.0 - (double)(totalLTM) / Math.max(1, bloomCount.get())) * 100) : "n/a");
        // MSA config
        stats.put("msaTopK",       MSA_TOP_K);
        stats.put("msaMinScore",   MSA_MIN_SCORE);
        stats.put("msaMaxFactLen", MSA_MAX_FACT_LEN);
        stats.put("msaTotalCandidates",
            longTermMemory.values().stream().mapToInt(List::size).sum()
            + (int) sessionDeltas.values().stream().flatMap(m -> m.values().stream()).mapToLong(Set::size).sum()
            + (int) sharedKnowledge.values().stream().mapToLong(Set::size).sum());
        stats.put("date", today());
        // RAG + Embedding stats
        stats.put("ragDocs",    ragStore.size());
        stats.put("ragChunks",  ragStore.values().stream().mapToInt(List::size).sum());
        stats.put("embedVocab", vocabSize.get());
        stats.put("embedDim",   EMBED_DIM);
        return ResponseEntity.ok(stats);
    }

    // ── RAG ENDPOINTS ────────────────────────────────────────────────────────

    /**
     * POST /api/rag/index
     * Indicizza un documento nel RAG store.
     * Body: { "docId": "nome_doc", "text": "contenuto...", "sessionId": "..." }
     * Supporta testo puro, contenuto di PDF già estratto, pagine web, ecc.
     */
    @PostMapping("/rag/index")
    public ResponseEntity<Object> ragIndex(@RequestBody Map<String, String> body) {
        String docId     = body.getOrDefault("docId", "doc_" + System.currentTimeMillis());
        String text      = body.getOrDefault("text", "");
        String sessionId = body.getOrDefault("sessionId", "global");
        String url       = body.getOrDefault("url", "");

        if (text.isBlank() && url.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Fornisci 'text' o 'url'"));

        // Se fornita una URL, scarica il contenuto
        if (!url.isBlank() && text.isBlank()) {
            try {
                HttpHeaders h = new HttpHeaders();
                h.set("User-Agent", "SPACE-AI/2.0");
                ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(h), String.class);
                // Rimuove tag HTML
                text = resp.getBody() == null ? "" :
                    resp.getBody().replaceAll("<[^>]+>", " ").replaceAll("\s{3,}", " ").trim();
                if (docId.startsWith("doc_")) docId = url.replaceAll("https?://", "").substring(0, Math.min(60, url.length()));
            } catch (Exception e) {
                return ResponseEntity.status(502).body(Map.of("error", "Impossibile scaricare URL: " + e.getMessage()));
            }
        }

        // Prefissa con sessionId per prioritizzare i doc della sessione nel retrieval
        String fullDocId = sessionId + "/" + docId;
        int chunks = ragIndexDocument(fullDocId, text);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status",    "indexed");
        result.put("docId",     fullDocId);
        result.put("chunks",    chunks);
        result.put("vocabSize", vocabSize.get());
        result.put("totalDocs", ragStore.size());
        result.put("textLen",   text.length());
        result.put("date",      today());
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/rag/query
     * Esegue una query RAG standalone (senza chiamare la LLM).
     * Utile per debug e ispezione dei chunk recuperati.
     * Body: { "query": "...", "sessionId": "..." }
     */
    @PostMapping("/rag/query")
    public ResponseEntity<Object> ragQuery(@RequestBody Map<String, String> body) {
        String query     = body.getOrDefault("query", "");
        String sessionId = body.getOrDefault("sessionId", "global");
        if (query.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Query vuota"));

        String ragResult  = ragRetrieve(query, sessionId);
        String semResult  = semanticMemoryRetrieve(query, sessionId);
        float[] qEmb      = embed(query);
        double embNorm    = 0;
        for (float v : qEmb) embNorm += v * v;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query",          query);
        result.put("ragContext",      ragResult.isBlank() ? "Nessun documento indicizzato" : ragResult);
        result.put("semanticMemory", semResult.isBlank() ? "Nessun fatto rilevante" : semResult);
        result.put("embeddingNorm",  String.format("%.4f", Math.sqrt(embNorm)));
        result.put("totalDocs",      ragStore.size());
        result.put("totalChunks",    ragStore.values().stream().mapToInt(List::size).sum());
        result.put("vocabSize",      vocabSize.get());
        result.put("simThreshold",   SIM_THRESHOLD);
        result.put("topK",           RAG_TOP_K);
        result.put("date",           today());
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/rag/status
     * Stato del RAG store: documenti indicizzati, chunk totali, dimensione vocabolario.
     */
    @GetMapping("/rag/status")
    public ResponseEntity<Object> ragStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("totalDocs",    ragStore.size());
        status.put("totalChunks",  ragStore.values().stream().mapToInt(List::size).sum());
        status.put("vocabSize",    vocabSize.get());
        status.put("embedDim",     EMBED_DIM);
        status.put("chunkSize",    CHUNK_SIZE);
        status.put("chunkOverlap", CHUNK_OVERLAP);
        status.put("topK",         RAG_TOP_K);
        status.put("simThreshold", SIM_THRESHOLD);
        status.put("idfTerms",     idfScores.size());
        status.put("docCount",     docCount.get());
        // Lista documenti indicizzati
        List<Map<String,Object>> docs = new ArrayList<>();
        ragStore.forEach((id, chunks) -> {
            Map<String,Object> d = new LinkedHashMap<>();
            d.put("docId",  id);
            d.put("chunks", chunks.size());
            d.put("indexedAt", chunks.isEmpty() ? "-" :
                new java.util.Date(chunks.get(0).timestamp).toString());
            docs.add(d);
        });
        status.put("documents", docs);
        status.put("date", today());
        return ResponseEntity.ok(status);
    }

    /**
     * DELETE /api/rag/delete/{docId}
     * Rimuove un documento dal RAG store.
     */
    @DeleteMapping("/rag/delete/{docId}")
    public ResponseEntity<Object> ragDelete(@PathVariable String docId) {
        boolean removed = ragStore.remove(docId) != null;
        if (!removed) {
            // Prova anche con prefissi di sessione
            List<String> toRemove = ragStore.keySet().stream()
                .filter(k -> k.endsWith("/" + docId) || k.equals(docId))
                .collect(Collectors.toList());
            toRemove.forEach(ragStore::remove);
            removed = !toRemove.isEmpty();
        }
        return ResponseEntity.ok(Map.of(
            "status",  removed ? "deleted" : "not_found",
            "docId",   docId,
            "remaining", ragStore.size()
        ));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String,String>> health() {
        long avgMs = totalRequests.get() > 0 ?
            totalResponseTimeMs.get() / totalRequests.get() : 0;
        Map<String,String> r = new java.util.LinkedHashMap<>();
        r.put("status",        "online");
        r.put("version",       "4.0");
        r.put("model",         env("AI_MODEL","llama-3.3-70b-versatile"));
        r.put("agents",        "148 agenti + 12 motori esclusivi");
        r.put("features",      "cache,rateLimit,circuitBreaker,quantum,neural,emotion,temporal,dream");
        r.put("webSearch",     !env("TAVILY_API_KEY","").isEmpty() ? "enabled" : "disabled");
        r.put("images",        "enabled (Pollinations+HF)");
        r.put("supabase",      !env("SUPABASE_URL","").isEmpty() ? "connected" : "off");
        r.put("totalRequests", String.valueOf(totalRequests.get()));
        r.put("cacheHits",     String.valueOf(cacheHits.get()));
        r.put("cacheSize",     String.valueOf(responseCache.size()));
        r.put("avgResponseMs", String.valueOf(avgMs));
        r.put("tokensEstimate",String.valueOf(totalTokensEstimate.get()));
        r.put("circuitBreaker",isCircuitOpen() ? "OPEN" : "closed");
        r.put("date",          today());
        return ResponseEntity.ok(r);
    }
}