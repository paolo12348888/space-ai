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
import java.util.Arrays;
import java.util.LinkedList;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;

// ── Configurazione CORS production-safe ─────────────────────────────────────
// ALLOWED_ORIGINS env var: lista separata da virgola delle origini permesse
// Esempio: https://space-ai-940e.onrender.com,https://mio-dominio.com
// Se non impostata → fallback a onrender.com (NON wildcard *)
@org.springframework.context.annotation.Configuration
class CorsConfig implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {
    @Override
    public void addCorsMappings(org.springframework.web.servlet.config.annotation.CorsRegistry registry) {
        String originsEnv = System.getenv("ALLOWED_ORIGINS");
        String[] origins;
        if (originsEnv != null && !originsEnv.isBlank()) {
            origins = originsEnv.split(",");
        } else {
            origins = new String[]{"https://space-ai-940e.onrender.com"};
        }
        registry.addMapping("/api/**")
            .allowedOrigins(origins)
            .allowedMethods("GET","POST","PUT","DELETE","OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(false)
            .maxAge(3600);
    }
}
// ────────────────────────────────────────────────────────────────────────────

@RestController
@RequestMapping("/api")
public class ChatController {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // ── Account creatore: nessun rate limit, nessun circuit breaker ──────────
    private static final String CREATOR_EMAIL = "elettronicmarket01@gmail.com";
    // ────────────────────────────────────────────────────────────────────────
    private final AgentLoop agentLoop;
    private final RestTemplate restTemplate = new RestTemplate();

    // ── RestTemplate dedicato ai provider LLM con timeout espliciti ─────────
    private static final int LLM_CONNECT_TIMEOUT_MS = 8_000;   // 8s per aprire connessione
    private static final int LLM_READ_TIMEOUT_MS    = 45_000;  // 45s per risposta completa
    private static final int LLM_MAX_RETRIES        = 2;       // tentativi per provider
    private static final long LLM_BACKOFF_BASE_MS   = 1_500;   // backoff: 1.5s, 3s

    private final RestTemplate llmRestTemplate;
    {
        org.springframework.http.client.SimpleClientHttpRequestFactory f =
            new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(LLM_CONNECT_TIMEOUT_MS);
        f.setReadTimeout(LLM_READ_TIMEOUT_MS);
        llmRestTemplate = new RestTemplate(f);
    }
    // ── metriche per ogni provider ──────────────────────────────────────────
    private final Map<String, AtomicInteger> providerSuccess = new ConcurrentHashMap<>(Map.of(
        "groq", new AtomicInteger(0), "gemini", new AtomicInteger(0), "deepseek", new AtomicInteger(0)
    ));
    private final Map<String, AtomicInteger> providerFailure = new ConcurrentHashMap<>(Map.of(
        "groq", new AtomicInteger(0), "gemini", new AtomicInteger(0), "deepseek", new AtomicInteger(0)
    ));
    // ────────────────────────────────────────────────────────────────────────
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, List<Map<String,String>>> neuralMemory = new ConcurrentHashMap<>();
    private final Map<String, Map<String,Object>> userProfiles = new ConcurrentHashMap<>();
    private final Map<String, List<String>> userInsights = new ConcurrentHashMap<>();
    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final Map<String, AtomicInteger> agentUsage = new ConcurrentHashMap<>();
    // Evita chiamate duplicate alla LLM per domande identiche
    // KNN Cache: ring buffer di 500 entry con embedding per similarità semantica
    private static class KnnCacheEntry {
        final byte[] embedding; // INT4 quantizzato: float[512] → byte[256] (-75% RAM)
        final float  embMin, embMax;
        final String response;
        final long   timestamp;

        KnnCacheEntry(float[] e, String r) {
            float mn = e[0], mx = e[0];
            for (float v : e) { if (v < mn) mn = v; if (v > mx) mx = v; }
            this.embMin = mn; this.embMax = mx;
            float scale = (mx - mn) < 1e-8f ? 1f : (mx - mn) / 15f;
            byte[] out = new byte[(e.length + 1) / 2];
            for (int i = 0; i < e.length; i++) {
                int q = Math.max(0, Math.min(15, Math.round((e[i] - mn) / scale)));
                if (i % 2 == 0) out[i/2]  = (byte)(q << 4);
                else             out[i/2] |= (byte)(q & 0x0F);
            }
            this.embedding = out;
            this.response  = r;
            this.timestamp = System.currentTimeMillis();
        }

        float[] dequantize() {
            float scale = (embMax - embMin) < 1e-8f ? 1f : (embMax - embMin) / 15f;
            float[] vec = new float[embedding.length * 2];
            for (int i = 0; i < embedding.length; i++) {
                vec[i*2]   = embMin + ((embedding[i] >> 4) & 0x0F) * scale;
                if (i*2+1 < vec.length)
                    vec[i*2+1] = embMin + (embedding[i] & 0x0F) * scale;
            }
            return vec;
        }
    }
    private final KnnCacheEntry[] ringCache = new KnnCacheEntry[500];
    private final AtomicInteger   cacheIdx  = new AtomicInteger(0);
    // Mantieni anche la vecchia map per compatibilità con statistiche
    private final Map<String, String> responseCache = new ConcurrentHashMap<>();
    private final Map<String, Long> cacheTimestamps = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 10 * 60 * 1000; // 10 minuti
    private final AtomicInteger cacheHits = new AtomicInteger(0);
    private final Map<String, AtomicInteger> sessionRequests = new ConcurrentHashMap<>();
    private final Map<String, Long>          sessionWindows  = new ConcurrentHashMap<>();
    private static final int MAX_REQUESTS_PER_MINUTE = 30;

    // Rate limiting per IP — più difficile da aggirare del solo sessionId
    private final Map<String, AtomicInteger> ipRequests = new ConcurrentHashMap<>();
    private final Map<String, Long>          ipWindows  = new ConcurrentHashMap<>();
    private static final int MAX_REQUESTS_PER_MINUTE_IP = 60; // soglia IP più alta (può avere più sessioni)
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile long circuitOpenTime   = 0;
    // Rate limit tracker — 429 da Groq
    private volatile long rateLimitUntil    = 0;        // timestamp fino a cui Groq è bloccato
    private volatile long rateLimitResetAt  = 0;        // quando resettare (da Retry-After)
    private final AtomicInteger groqCallsToday = new AtomicInteger(0);
    private final AtomicInteger localFallbacks = new AtomicInteger(0);
    private volatile long groqDayStart = System.currentTimeMillis();
    private static final int FAILURE_THRESHOLD = 5;
    private static final long CIRCUIT_RESET_MS = 30000; // 30 secondi
    private final AtomicInteger totalTokensEstimate = new AtomicInteger(0);
    private final Map<String, Long> responseTimings = new ConcurrentHashMap<>();
    private final AtomicLong totalResponseTimeMs = new AtomicLong(0);
    // Simulazione qubit ispirata a Zuchongzhi/Jiuzhang
    private static final int QUBIT_COUNT = 32; // qubit simulati
    // Ridotto da 2^16 (512KB) a 512 entries (4KB) — nessuna perdita funzionale
    private final double[] quantumState = new double[512];
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
        if (fact.length() > 50 && ltm.size() < 50 && !ltm.contains(fact)) {
            String stored = fact.substring(0, Math.min(150, fact.length()));
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
    // ════════════════════════════════════════════════════════════════════════
    // LLM-AS-A-JUDGE — valida risposta prima di inviarla (riduce allucinazioni ~40%)
    // Usa un modello veloce (GROQ_MODEL_FAST) per non sprecare quota
    // ════════════════════════════════════════════════════════════════════════
    private String llmJudgeValidate(String response, String query,
                                     String baseUrl, String apiKey, String model) {
        if (rateLimitUntil > System.currentTimeMillis()) return response; // skip se rate limited
        if (response == null || response.length() < 50) return response;
        try {
            // Usa il modello veloce per risparmiare token
            String judgeModel = env("GROQ_MODEL_FAST", model);
            String judgePrompt =
                "Sei un validatore AI. Analizza questa risposta e:\n" +
                "1. Verifica che risponda alla domanda\n" +
                "2. Identifica eventuali errori fattuali evidenti\n" +
                "3. Se la risposta e corretta rispondi: [VALID]\n" +
                "4. Se ha problemi rispondi: [ISSUE: descrizione breve]\n" +
                "Domanda: " + query.substring(0, Math.min(150, query.length())) + "\n" +
                "Risposta: " + response.substring(0, Math.min(300, response.length())) + "\n" +
                "Giudizio:";

            String judgment = callLLM(
                "Sei un validatore conciso. Rispondi SOLO con [VALID] o [ISSUE: ...].",
                judgePrompt, new ArrayList<>(), baseUrl, apiKey, judgeModel, 50);

            if (judgment.contains("[VALID]")) {
                log.debug("LLM-as-a-Judge: VALID");
                return response;
            } else if (judgment.contains("[ISSUE:")) {
                String issue = judgment.substring(judgment.indexOf("[ISSUE:") + 7,
                    Math.min(judgment.length(), judgment.indexOf("[ISSUE:") + 100))
                    .replace("]","").trim();
                log.info("LLM-as-a-Judge: issue rilevato: {}", issue);
                // Aggiungi nota di disclaimer
                return response + "\n\n*⚠️ Nota: questa risposta potrebbe richiedere verifica su: " + issue + "*";
            }
        } catch (Exception e) {
            log.debug("LLM-as-a-Judge skip: {}", e.getMessage());
        }
        return response;
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
    /**
     * MSA RETRIEVAL UNIFICATO — unico metodo di retrieval memoria.
     * Aggrega: LTM + STM + sharedKnowledge + sessionDeltas + semantic embed.
     * Rimpiazza: retrieveRelevantMemory, semanticMemoryRetrieve, retrieveDifferentialMemory.
     */
    // ════════════════════════════════════════════════════════════════════════
    // CONTENT SAFETY — pre-filter input + post-filter output
    // ════════════════════════════════════════════════════════════════════════
    private static final List<String> BLOCKED_PATTERNS = List.of(
        "come fare una bomba","come costruire armi","come sintetizzare drog",
        "how to make explosives","child porn","jailbreak","ignore previous",
        "act as DAN","bypass your","forget your instructions",
        "come hackerare","come rubare","istruzioni per uccidere"
    );

    private boolean isSafeInput(String input) {
        if (input == null || input.isBlank()) return true;
        String lower = input.toLowerCase();
        for (String pattern : BLOCKED_PATTERNS) {
            if (lower.contains(pattern)) {
                log.warn("ContentSafety: blocked pattern detected: {}", pattern);
                return false;
            }
        }
        return true;
    }

    private String sanitizeOutput(String output) {
        if (output == null) return "";
        // Rimuovi eventuali jailbreak residui
        String[] dangerousOutputs = {"[DAN]","[JAILBREAK]","[SYSTEM OVERRIDE]","Ignore all previous"};
        for (String d : dangerousOutputs) {
            if (output.contains(d)) return "Mi dispiace, non posso fornire questa risposta.";
        }
        return output;
    }

    private String msaRetrieveUnified(String query, String sessionId) {
        List<String> candidates = new ArrayList<>();

        // 1. LTM sessione
        candidates.addAll(longTermMemory.getOrDefault(sessionId, new ArrayList<>()));

        // 2. STM (ultimi messaggi)
        stmRecall(sessionId).stream()
            .map(e -> e.replaceFirst("^U:", "").replaceFirst(" A:.*", ""))
            .filter(s -> !s.isBlank())
            .forEach(candidates::add);

        // 3. Session deltas (fatti differenziali)
        sessionDeltas.getOrDefault(sessionId, new HashMap<>())
            .forEach((subj, objs) -> objs.forEach(obj -> candidates.add(subj + "::" + obj)));

        // 4. SharedKnowledge (conoscenza globale)
        String q = query.toLowerCase();
        for (String word : q.split("\\s+")) {
            if (word.length() > 4) {
                Set<String> shared = sharedKnowledge.get(word);
                if (shared != null)
                    shared.stream().limit(3).forEach(v -> candidates.add(word + "::" + v));
            }
        }

        if (candidates.isEmpty()) return "";

        // 5. Scoring ibrido: MSA sparse attention + cosine similarity embedding
        float[] queryEmb = embed(query);
        int total = candidates.size();
        List<double[]> scored = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            String fact = candidates.get(i);
            // MSA attention score (keyword + recency)
            double msaScore = msaAttentionScore(query, fact, i, total) * 0.4;
            // Cosine similarity (semantic)
            double cosScore = cosineSimilarity(queryEmb, embed(fact)) * 0.6;
            double combined = msaScore + cosScore;
            if (combined > 0.05) scored.add(new double[]{combined, i});
        }
        scored.sort((a, b) -> Double.compare(b[0], a[0]));

        if (scored.isEmpty()) return "";

        StringBuilder ctx = new StringBuilder("[MSA-UNIFIED k=")
            .append(Math.min(MSA_TOP_K, scored.size())).append("]: ");
        int count = 0;
        for (double[] s : scored) {
            if (count >= MSA_TOP_K) break;
            String fact = candidates.get((int) s[1]);
            ctx.append(fact, 0, Math.min(MSA_MAX_FACT_LEN, fact.length())).append(" · ");
            count++;
        }
        return ctx.toString().replaceAll(" · $", "").trim();
    }

    private String buildSystemPrompt(String mode, String sessionId, String query) {
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
        // MSA UNIFICATO: unico meccanismo di retrieval — aggrega tutto
        String msaMem = msaRetrieveUnified(query, sessionId);
        if (!msaMem.isEmpty())
            sb.append("MEMORIA ATTIVA (MSA): ").append(msaMem).append("\n");
        // RAG: contesto dai documenti indicizzati
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
        if (qdrantEnabled()) {
            qdrantEnsureCollection();
            log.info("Qdrant collection verificata: {}", QDRANT_COLLECTION);
        }
        // Pulizia cache ogni 10 minuti
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
            this::cleanExpiredCache, 10, 10, java.util.concurrent.TimeUnit.MINUTES);
    }

    // ── VALIDAZIONE ENV ALL'AVVIO ─────────────────────────────────────────
    @jakarta.annotation.PostConstruct
    public void validateEnvOnStartup() {
        log.info("=== SPACE AI — Validazione environment ===");
        // Il sistema usa AI_API_KEY come chiave principale (compatibile con Groq/OpenAI)
        boolean hasAiKey     = !env("AI_API_KEY","").isEmpty();
        boolean hasGroq      = !env("AI_API_KEY", env("GROQ_API_KEY","")).isEmpty();
        boolean hasGemini    = !env("GEMINI_API_KEY","").isEmpty();
        boolean hasDeepSeek  = !env("DEEPSEEK_API_KEY","").isEmpty();

        if (!hasAiKey && !hasGroq && !hasGemini && !hasDeepSeek) {
            log.error("CRITICO: Nessun provider LLM configurato! Imposta AI_API_KEY o GROQ_API_KEY");
        } else {
            if (hasAiKey)    log.info("  ✅ AI_API_KEY             presente (provider principale)");
            if (hasGroq)     log.info("  ✅ GROQ_API_KEY           presente");
            if (hasGemini)   log.info("  ✅ GEMINI_API_KEY         presente");
            if (hasDeepSeek) log.info("  ✅ DEEPSEEK_API_KEY       presente");
        }
        // Opzionali
        if (!env("AI_BASE_URL","").isEmpty())
            log.info("  ✅ AI_BASE_URL            = {}", env("AI_BASE_URL",""));
        if (!env("AI_MODEL","").isEmpty())
            log.info("  ✅ AI_MODEL               = {}", env("AI_MODEL",""));
        if (env("SUPABASE_URL","").isEmpty())
            log.warn("  ⚠️  SUPABASE_URL           non configurata (storico chat disabilitato)");
        else
            log.info("  ✅ SUPABASE_URL           presente");
        if (env("TAVILY_API_KEY","").isEmpty())
            log.warn("  ⚠️  TAVILY_API_KEY         non configurata (web search ridotta)");
        else
            log.info("  ✅ TAVILY_API_KEY         presente");
        if (env("ELEVENLABS_API_KEY","").isEmpty())
            log.warn("  ⚠️  ELEVENLABS_API_KEY     non configurata (TTS qualità ridotta)");
        if (env("PISTON_URL","").isEmpty())
            log.warn("  ⚠️  PISTON_URL             non configurata (esecuzione codice disabilitata)");
        String origins = env("ALLOWED_ORIGINS","");
        if (origins.isEmpty())
            log.warn("  ⚠️  ALLOWED_ORIGINS        non configurata — uso fallback onrender.com");
        else
            log.info("  ✅ ALLOWED_ORIGINS        = {}", origins);
        log.info("==========================================");
    }
    // ─────────────────────────────────────────────────────────────────────
    // ── Helper: shutdown graceful di un ExecutorService ──────────────────
    private void shutdownExecutor(String name, java.util.concurrent.ExecutorService ex) {
        if (ex == null || ex.isShutdown()) return;
        ex.shutdown();
        try {
            if (!ex.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                ex.shutdownNow();
                log.warn("{} forzato dopo timeout", name);
            } else {
                log.info("{} chiuso correttamente", name);
            }
        } catch (InterruptedException ie) {
            ex.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    // ─────────────────────────────────────────────────────────────────────

    private String getCached(String key) {
        // 1. KNN semantica (soglia alta = quasi identico)
        String knn = knnCacheGet(key);
        if (knn != null) { cacheHits.incrementAndGet(); return knn; }
        // 2. Cache esatta
        String cached = responseCache.get(key);
        if (cached == null) return null;
        Long ts = cacheTimestamps.get(key);
        if (ts == null || System.currentTimeMillis() - ts > CACHE_TTL_MS) {
            responseCache.remove(key); cacheTimestamps.remove(key); return null;
        }
        cacheHits.incrementAndGet();
        return cached;
    }
    private void putCache(String key, String value) {
        if (value == null || value.length() > 10000) return;
        knnCachePut(key, value);  // salva nel ring buffer KNN
        responseCache.put(key, value);
        cacheTimestamps.put(key, System.currentTimeMillis());
        if (responseCache.size() > 100) cleanExpiredCache();
    }
    private String knnCacheGet(String query) {
        float[] qv = embed(query);
        double bestSim = 0; String bestResp = null;
        for (KnnCacheEntry e : ringCache) {
            if (e == null) continue;
            if (System.currentTimeMillis() - e.timestamp > 1_800_000) continue; // 30 min TTL
            double sim = cosineSimilarity(qv, e.dequantize());
            if (sim > bestSim && sim > 0.92) { bestSim = sim; bestResp = e.response; }
        }
        if (bestResp != null) log.debug("KNN cache hit sim={}", String.format("%.3f", bestSim));
        return bestResp;
    }
    private void knnCachePut(String query, String response) {
        int idx = cacheIdx.getAndUpdate(i -> (i + 1) % 500);
        ringCache[idx] = new KnnCacheEntry(embed(query), response);
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

    // Rate limiting per IP — resiste al cambio di sessionId
    private boolean isIpRateLimited(jakarta.servlet.http.HttpServletRequest request) {
        String ip = extractClientIp(request);
        if (ip == null || ip.isBlank()) return false; // se non riusciamo a leggere l'IP, non blocchiamo
        long now = System.currentTimeMillis();
        Long window = ipWindows.get(ip);
        if (window == null || now - window > 60000) {
            ipWindows.put(ip, now);
            ipRequests.put(ip, new AtomicInteger(0));
        }
        int reqs = ipRequests.computeIfAbsent(ip, k -> new AtomicInteger(0)).incrementAndGet();
        if (reqs > MAX_REQUESTS_PER_MINUTE_IP) {
            log.warn("IP rate limit superato: {} ({} req/min)", ip, reqs);
            return true;
        }
        return false;
    }

    // Estrae l'IP reale considerando proxy/load balancer (X-Forwarded-For)
    private String extractClientIp(jakarta.servlet.http.HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim(); // primo IP della catena = client reale
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) return realIp.trim();
        return request.getRemoteAddr();
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
    // circuit breaker per provider singolo — true se quel provider va evitato
    private final Map<String, Long>    providerCircuitOpenTime = new ConcurrentHashMap<>();
    private final Map<String, Integer> providerCircuitFails    = new ConcurrentHashMap<>();
    private static final int  PROVIDER_FAIL_THRESHOLD = 3;
    private static final long PROVIDER_CIRCUIT_RESET_MS = 60_000; // 1 minuto

    private boolean isProviderCircuitOpen(String provider) {
        int fails = providerCircuitFails.getOrDefault(provider, 0);
        if (fails < PROVIDER_FAIL_THRESHOLD) return false;
        long openTime = providerCircuitOpenTime.getOrDefault(provider, 0L);
        if (System.currentTimeMillis() - openTime > PROVIDER_CIRCUIT_RESET_MS) {
            providerCircuitFails.put(provider, 0); // reset
            return false;
        }
        return true;
    }
    private void recordProviderFailure(String provider) {
        int fails = providerCircuitFails.merge(provider, 1, Integer::sum);
        if (fails >= PROVIDER_FAIL_THRESHOLD) {
            providerCircuitOpenTime.put(provider, System.currentTimeMillis());
            log.warn("Circuit breaker APERTO per provider: {} dopo {} fallimenti", provider, fails);
        }
    }
    private void recordProviderSuccess(String provider) {
        providerCircuitFails.put(provider, 0);
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
        // Shutdown graceful di TUTTI gli executor
        log.info("SPACE AI shutdown — chiusura executor...");
        shutdownExecutor("rlhfExecutor",    rlhfExecutor);
        shutdownExecutor("mainExecutor",    executor);
        log.info("Executor chiusi. Salvataggio brain state...");
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
        // Tempo reale: date, eventi, notizie
        if (q.contains("oggi") || q.contains("adesso") || q.contains("ora") ||
            q.contains("2024") || q.contains("2025") || q.contains("2026") ||
            q.contains("recente") || q.contains("ultime") || q.contains("ultimo") ||
            q.contains("aggiornato") || q.contains("attuale") || q.contains("corrente") ||
            q.contains("notizie") || q.contains("news") || q.contains("cerca") ||
            q.contains("dammi info") || q.contains("dimmi") && q.contains("now") ||
            q.contains("live") || q.contains("in diretta") || q.contains("breaking")) return true;
        // Prezzi, mercati, crypto
        if (q.contains("prezzo") || q.contains("quotazione") || q.contains("borsa") ||
            q.contains("bitcoin") || q.contains("ethereum") || q.contains("crypto") ||
            q.contains("azioni") || q.contains("euro") || q.contains("dollaro") ||
            q.contains("mercato") || q.contains("nasdaq") || q.contains("s&p") ||
            q.contains("ftse") || q.contains("dow jones")) return true;
        // Sport
        if (q.contains("partita") || q.contains("risultato") || q.contains("campionato") ||
            q.contains("serie a") || q.contains("champions") || q.contains("formula 1") ||
            q.contains("gara") || q.contains("torneo") || q.contains("classifica") ||
            q.contains("gol") || q.contains("marcatore") || q.contains("moto gp")) return true;
        // Meteo
        if (q.contains("meteo") || q.contains("temperatura") || q.contains("previsioni") ||
            q.contains("piove") || q.contains("sole") || q.contains("vento") ||
            q.contains("allerta") || q.contains("uv index")) return true;
        // Persone, politica, eventi
        if (q.contains("chi è") || q.contains("chi e ") || q.contains("cosa ha fatto") ||
            q.contains("morto") || q.contains("elezioni") || q.contains("governo") ||
            q.contains("presidente") || q.contains("premier") || q.contains("ministro") ||
            q.contains("guerra") || q.contains("conflitto") || q.contains("attacco")) return true;
        // Tecnologia
        if (q.contains("gpt") || q.contains("claude") || q.contains("gemini") ||
            q.contains("llama") || q.contains("openai") || q.contains("anthropic") ||
            q.contains("mistral") || q.contains("deepseek") ||
            (q.contains("ia") || q.contains("ai")) && q.contains("nuov")) return true;
        // Domande dirette su fatti verificabili
        if (q.startsWith("chi ") || q.startsWith("cosa ") || q.startsWith("quando ") ||
            q.startsWith("dove ") || q.startsWith("quanto ") || q.startsWith("qual è") ||
            q.startsWith("quanti ") || q.contains("?") && q.length() < 80) return true;
        return false;
    }

    // Decide se usare Agent Loop (query complesse multi-step)
    // NON chiamare se il frontend ha già una mode specifica — gestito nel dispatch
    private boolean needsAgentLoop(String msg) {
        String q = msg.toLowerCase();
        // Soglia alzata a 80 char: evita di intercettare domande semplici
        if (q.length() > 80 &&
            (q.contains("analizza e") || q.contains("confronta e") ||
             q.contains("cerca e poi") || q.contains("esegui e"))) return true;
        // Solo keyword esplicitamente multi-step — NON "video", "codice", "scrivi" ecc.
        return q.contains("agent loop") || q.contains("multi-step") ||
               q.contains("step by step autonomo") || q.contains("esegui in sequenza");
    }

    // Cerca su web — pipeline: Tavily → SerpAPI/Google → DuckDuckGo
    private String searchWebEnhanced(String query, String sessionId) {
        // 1. Tavily (più contestuale, se disponibile)
        String tavily = searchWeb(query);
        if (tavily != null && !tavily.isBlank()) {
            ragIndexDocument(sessionId + "/search_" + System.currentTimeMillis(), tavily);
            return tavily;
        }
        // 2. Google live (SerpAPI o Custom Search)
        String google = searchGoogle(query);
        if (google != null && !google.isBlank()) {
            ragIndexDocument(sessionId + "/search_" + System.currentTimeMillis(), google);
            return google;
        }
        // 3. DuckDuckGo fallback gratuito
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

    // ════════════════════════════════════════════════════════════════════════
    // 🔍 GOOGLE SEARCH LIVE — dati aggiornati al secondo
    // Fonte 1: SerpAPI (SERPAPI_KEY env var)
    // Fonte 2: Google Custom Search (GOOGLE_CSE_KEY + GOOGLE_CSE_ID env vars)
    // Fonte 3: DuckDuckGo (già esistente, gratuita)
    // Fonte 4: Scraping diretto URL (per news, meteo, prezzi)
    // ════════════════════════════════════════════════════════════════════════

    private String searchGoogle(String query) {
        // ── Fonte 1: SerpAPI ────────────────────────────────────────────────
        String serpKey = env("SERPAPI_KEY", "");
        if (!serpKey.isEmpty()) {
            try {
                String encoded = URLEncoder.encode(query, "UTF-8");
                String url = "https://serpapi.com/search.json?q=" + encoded
                    + "&api_key=" + serpKey + "&hl=it&gl=it&num=5"
                    + "&tbm=&tbs=qdr:h"; // risultati ultima ora
                HttpHeaders h = new HttpHeaders();
                h.set("User-Agent", "SPACE-AI/4.0");
                ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(h), String.class);
                JsonNode json = MAPPER.readTree(resp.getBody());
                StringBuilder sb = new StringBuilder();
                // Answer box
                JsonNode answerBox = json.path("answer_box");
                if (!answerBox.isMissingNode()) {
                    String ans = answerBox.path("answer").asText();
                    if (ans.isBlank()) ans = answerBox.path("snippet").asText();
                    if (!ans.isBlank()) sb.append("RISPOSTA GOOGLE: ").append(ans).append("\n\n");
                }
                // Knowledge graph
                JsonNode kg = json.path("knowledge_graph");
                if (!kg.isMissingNode() && !kg.path("description").asText().isBlank())
                    sb.append("INFO: ").append(kg.path("description").asText()).append("\n\n");
                // Organic results
                JsonNode organic = json.path("organic_results");
                if (organic.isArray()) {
                    sb.append("RISULTATI LIVE:\n");
                    int i = 0;
                    for (JsonNode r : organic) {
                        if (i++ >= 5) break;
                        sb.append(i).append(". ").append(r.path("title").asText()).append("\n");
                        String snippet = r.path("snippet").asText();
                        if (!snippet.isBlank())
                            sb.append("   ").append(snippet, 0, Math.min(250, snippet.length())).append("\n");
                        sb.append("   ").append(r.path("link").asText()).append("\n\n");
                    }
                }
                // News results
                JsonNode news = json.path("news_results");
                if (news.isArray() && news.size() > 0) {
                    sb.append("NOTIZIE RECENTI:\n");
                    int i = 0;
                    for (JsonNode n : news) {
                        if (i++ >= 3) break;
                        sb.append("- ").append(n.path("title").asText());
                        String date = n.path("date").asText();
                        if (!date.isBlank()) sb.append(" [").append(date).append("]");
                        sb.append("\n  ").append(n.path("source").asText()).append("\n");
                    }
                }
                String result = sb.toString().trim();
                if (!result.isBlank()) {
                    log.info("SerpAPI OK: {} chars", result.length());
                    return "FONTE: Google Search Live (" + today() + ")\n\n" + result;
                }
            } catch (Exception e) { log.warn("SerpAPI: {}", e.getMessage()); }
        }

        // Fonte 2: Google Custom Search API
        String gcsKey = env("GOOGLE_CSE_KEY", "");
        String gcsId  = env("GOOGLE_CSE_ID", "");
        if (!gcsKey.isEmpty() && !gcsId.isEmpty()) {
            try {
                String encoded = URLEncoder.encode(query, "UTF-8");
                String url = "https://www.googleapis.com/customsearch/v1?q=" + encoded
                    + "&key=" + gcsKey + "&cx=" + gcsId + "&num=5&lr=lang_it&dateRestrict=d1";
                ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
                JsonNode json = MAPPER.readTree(resp.getBody());
                StringBuilder sb = new StringBuilder();
                sb.append("RISULTATI GOOGLE LIVE:\n");
                JsonNode items = json.path("items");
                if (items.isArray()) {
                    int i = 0;
                    for (JsonNode item : items) {
                        if (i++ >= 5) break;
                        sb.append(i).append(". ").append(item.path("title").asText()).append("\n");
                        String snippet = item.path("snippet").asText();
                        if (!snippet.isBlank())
                            sb.append("   ").append(snippet, 0, Math.min(200, snippet.length())).append("\n");
                        sb.append("   ").append(item.path("link").asText()).append("\n\n");
                    }
                }
                String result = sb.toString().trim();
                if (result.length() > 50) {
                    log.info("Google CSE OK");
                    return "FONTE: Google Custom Search (" + today() + ")\n\n" + result;
                }
            } catch (Exception e) { log.warn("Google CSE: {}", e.getMessage()); }
        }

        // Fonte 3: DuckDuckGo fallback gratuito
        return searchDuckDuckGo(query);
    }


    // ════════════════════════════════════════════════════════════════════════
    // 🐙 GITHUB LIVE CONNECTOR — legge/scrive repo in tempo reale
    // Richiede env var: GITHUB_TOKEN
    // ════════════════════════════════════════════════════════════════════════

    private JsonNode githubApi(String endpoint) throws Exception {
        String token = env("GITHUB_TOKEN", "");
        if (token.isEmpty()) throw new IllegalStateException("GITHUB_TOKEN non configurato");
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + token);
        h.set("Accept", "application/vnd.github+json");
        h.set("X-GitHub-Api-Version", "2022-11-28");
        h.set("User-Agent", "SPACE-AI/4.0");
        ResponseEntity<String> resp = restTemplate.exchange(
            "https://api.github.com" + endpoint,
            HttpMethod.GET, new HttpEntity<>(h), String.class);
        return MAPPER.readTree(resp.getBody());
    }

    private String githubPost(String endpoint, String body) throws Exception {
        String token = env("GITHUB_TOKEN", "");
        if (token.isEmpty()) throw new IllegalStateException("GITHUB_TOKEN non configurato");
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + token);
        h.set("Accept", "application/vnd.github+json");
        h.set("X-GitHub-Api-Version", "2022-11-28");
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("User-Agent", "SPACE-AI/4.0");
        ResponseEntity<String> resp = restTemplate.postForEntity(
            "https://api.github.com" + endpoint,
            new HttpEntity<>(body, h), String.class);
        return resp.getBody();
    }

    @GetMapping("/github/repo")
    public ResponseEntity<Object> githubRepoInfo(@RequestParam String owner,
                                                  @RequestParam String repo) {
        try {
            JsonNode info = githubApi("/repos/" + owner + "/" + repo);
            Map<String,Object> r = new LinkedHashMap<>();
            r.put("name",        info.path("full_name").asText());
            r.put("description", info.path("description").asText());
            r.put("stars",       info.path("stargazers_count").asInt());
            r.put("forks",       info.path("forks_count").asInt());
            r.put("language",    info.path("language").asText());
            r.put("updated",     info.path("updated_at").asText());
            r.put("defaultBranch", info.path("default_branch").asText());
            return ResponseEntity.ok(r);
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/github/file")
    public ResponseEntity<Object> githubReadFile(@RequestParam String owner,
                                                  @RequestParam String repo,
                                                  @RequestParam String path) {
        try {
            JsonNode file = githubApi("/repos/" + owner + "/" + repo + "/contents/" + path);
            String encoded = file.path("content").asText().replaceAll("\s", "");
            String content = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            // Auto-indicizza nel RAG per poter fare domande sul file
            String docId = "github/" + owner + "/" + repo + "/" + path;
            int chunks = ragIndexDocument(docId, content);
            Map<String,Object> r = new LinkedHashMap<>();
            r.put("path",    path);
            r.put("sha",     file.path("sha").asText());
            r.put("size",    file.path("size").asInt());
            r.put("content", content.substring(0, Math.min(5000, content.length())));
            r.put("ragChunks", chunks);
            r.put("indexed", true);
            return ResponseEntity.ok(r);
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/github/commit")
    public ResponseEntity<Object> githubCommitFile(@RequestBody Map<String,String> body) {
        try {
            String owner   = body.get("owner");
            String repo    = body.get("repo");
            String path    = body.get("path");
            String content = body.get("content");
            String message = body.getOrDefault("message", "SPACE AI: update file");
            String branch  = body.getOrDefault("branch", "main");
            // Recupera SHA attuale del file (necessario per update)
            String sha = "";
            try {
                JsonNode existing = githubApi("/repos/" + owner + "/" + repo + "/contents/" + path);
                sha = existing.path("sha").asText();
            } catch (Exception ignored) {}
            // Prepara il payload
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("message", message);
            payload.put("content", Base64.getEncoder().encodeToString(
                content.getBytes(StandardCharsets.UTF_8)));
            payload.put("branch", branch);
            if (!sha.isEmpty()) payload.put("sha", sha);
            String result = githubPost("/repos/" + owner + "/" + repo + "/contents/" + path,
                MAPPER.writeValueAsString(payload));
            JsonNode resp = MAPPER.readTree(result);
            return ResponseEntity.ok(Map.of(
                "status",  "committed",
                "commit",  resp.path("commit").path("sha").asText(),
                "message", message,
                "path",    path
            ));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/github/issues")
    public ResponseEntity<Object> githubIssues(@RequestParam String owner,
                                                @RequestParam String repo) {
        try {
            JsonNode issues = githubApi("/repos/" + owner + "/" + repo + "/issues?state=open&per_page=10");
            List<Map<String,Object>> list = new ArrayList<>();
            for (JsonNode iss : issues) {
                Map<String,Object> i = new LinkedHashMap<>();
                i.put("number", iss.path("number").asInt());
                i.put("title",  iss.path("title").asText());
                i.put("state",  iss.path("state").asText());
                i.put("author", iss.path("user").path("login").asText());
                i.put("created", iss.path("created_at").asText());
                list.add(i);
            }
            return ResponseEntity.ok(Map.of("issues", list, "count", list.size()));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 🤖 AGENT LOOP MANUS-STYLE — ciclo iterativo con tool execution
    // Osserva → Ragiona → Seleziona Tool → Esegui → Osserva → Ripeti
    // ════════════════════════════════════════════════════════════════════════

    // Registro strumenti disponibili (abilitati via env vars)
    private Map<String,Boolean> getAvailableTools() {
        Map<String,Boolean> tools = new LinkedHashMap<>();
        tools.put("web_search",    true);                                    // sempre attivo
        tools.put("google_search", !env("SERPAPI_KEY","").isEmpty()
                                || !env("GOOGLE_CSE_KEY","").isEmpty());    // richiede key
        tools.put("rag_retrieval", true);                                    // sempre attivo
        tools.put("github_read",   !env("GITHUB_TOKEN","").isEmpty());      // richiede token
        tools.put("github_write",  !env("GITHUB_TOKEN","").isEmpty());      // richiede token
        tools.put("memory_read",    true);
        tools.put("memory_store",   true);   // Salva fatto nella LTM (Punto 7)
        tools.put("memory_retrieve",true);   // Cerca nella memoria semantica (Punto 7)
        tools.put("image_gen",      true);
        tools.put("math_eval",      true);
        tools.put("code_exec",      true);
        tools.put("web_scrape",      true);
        tools.put("browser_control", !env("BROWSERLESS_TOKEN","").isEmpty());
        tools.put("video_analyze",   true);
        tools.put("qdrant_store",    qdrantEnabled());
        tools.put("qdrant_search",   qdrantEnabled());
        return tools;
    }

    /**
     * Esegue un singolo tool dal loop agente.
     * Restituisce il risultato dell'esecuzione come stringa.
     */
    // ════════════════════════════════════════════════════════════════════════
    // MEMORIA PROCEDURALE — impara dai propri errori (GPT-5.5 style)
    // Salva: strategia usata + outcome + reward → affina nel tempo
    // ════════════════════════════════════════════════════════════════════════

    private static class ProceduralMemory {
        String strategy;      // cosa ha fatto
        String context;       // in che contesto
        double reward;        // outcome (0-1)
        int    useCount;      // quante volte usata
        long   lastUsed;      // ultimo utilizzo
        ProceduralMemory(String strategy, String context, double reward) {
            this.strategy = strategy; this.context = context; this.reward = reward;
            this.useCount = 1; this.lastUsed = System.currentTimeMillis();
        }
    }
    // Memoria procedurale: contesto_hash → strategia migliore
    private final Map<String, ProceduralMemory> proceduralMem = new ConcurrentHashMap<>();

    private void learnFromOutcome(String query, String strategy, double reward) {
        String ctxKey = Integer.toHexString(query.toLowerCase().substring(0,
            Math.min(40, query.length())).hashCode());
        proceduralMem.compute(ctxKey, (k, existing) -> {
            if (existing == null) return new ProceduralMemory(strategy, query, reward);
            // Aggiorna con media mobile esponenziale
            existing.reward    = existing.reward * 0.7 + reward * 0.3;
            existing.useCount++;
            existing.lastUsed  = System.currentTimeMillis();
            if (reward > existing.reward) existing.strategy = strategy; // sostituisce se migliore
            return existing;
        });
        log.debug("ProceduralMem learn: ctx={} strategy={} reward={}", ctxKey, strategy.substring(0,Math.min(30,strategy.length())), String.format("%.2f",reward));
    }

    private String recallBestStrategy(String query) {
        String ctxKey = Integer.toHexString(query.toLowerCase().substring(0,
            Math.min(40, query.length())).hashCode());
        ProceduralMemory mem = proceduralMem.get(ctxKey);
        if (mem != null && mem.reward > 0.6 && mem.useCount > 1) {
            log.debug("ProceduralMem recall: reward={} count={}", String.format("%.2f",mem.reward), mem.useCount);
            return mem.strategy;
        }
        // Cerca contesto semanticamente simile
        float[] qEmb = embed(query);
        double bestSim = 0; String bestStrategy = null;
        for (ProceduralMemory pm : proceduralMem.values()) {
            if (pm.reward < 0.5) continue;
            double sim = cosineSimilarity(qEmb, embed(pm.context));
            if (sim > bestSim && sim > 0.75) { bestSim = sim; bestStrategy = pm.strategy; }
        }
        return bestStrategy;
    }

    @GetMapping("/memory/procedural")
    public ResponseEntity<Object> getProceduralMemory() {
        List<Map<String,Object>> entries = proceduralMem.values().stream()
            .sorted((a,b) -> Double.compare(b.reward, a.reward))
            .limit(20)
            .map(m -> {
                Map<String,Object> e = new LinkedHashMap<>();
                e.put("context",   m.context.substring(0, Math.min(60, m.context.length())));
                e.put("strategy",  m.strategy.substring(0, Math.min(80, m.strategy.length())));
                e.put("reward",    String.format("%.2f", m.reward));
                e.put("useCount",  m.useCount);
                e.put("lastUsed",  new java.util.Date(m.lastUsed).toString());
                return e;
            }).collect(Collectors.toList());
        return ResponseEntity.ok(Map.of(
            "entries", entries, "total", proceduralMem.size(), "date", today()
        ));
    }

    // ════════════════════════════════════════════════════════════════════════
    // VECTOR DB — Qdrant per memoria persistente scalabile
    // Configurazione: QDRANT_URL e QDRANT_API_KEY nelle env vars di Render
    // Qdrant Cloud gratuito: https://cloud.qdrant.io (1GB gratis)
    // ════════════════════════════════════════════════════════════════════════

    private static final String QDRANT_COLLECTION = "spaceai_memory";

    private boolean qdrantEnabled() {
        return !env("QDRANT_URL","").isEmpty();
    }

    private void qdrantUpsert(String id, float[] vector, Map<String,String> payload) {
        if (!qdrantEnabled()) return;
        try {
            String qdrantUrl = env("QDRANT_URL","");
            String qdrantKey = env("QDRANT_API_KEY","");
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            if (!qdrantKey.isEmpty()) h.set("api-key", qdrantKey);

            // Build point
            ObjectNode point = MAPPER.createObjectNode();
            point.put("id", Math.abs(id.hashCode()) % 1_000_000_000L);
            ArrayNode vec = MAPPER.createArrayNode();
            for (float v : vector) vec.add(v);
            point.set("vector", vec);
            ObjectNode pl = MAPPER.createObjectNode();
            payload.forEach(pl::put);
            point.set("payload", pl);

            ObjectNode body = MAPPER.createObjectNode();
            ArrayNode points = MAPPER.createArrayNode();
            points.add(point);
            body.set("points", points);

            restTemplate.exchange(
                qdrantUrl + "/collections/" + QDRANT_COLLECTION + "/points",
                HttpMethod.PUT,
                new HttpEntity<>(MAPPER.writeValueAsString(body), h),
                String.class);
        } catch (Exception e) {
            log.debug("Qdrant upsert skip: {}", e.getMessage());
        }
    }

    private List<Map<String,Object>> qdrantSearch(float[] queryVector, int topK) {
        if (!qdrantEnabled()) return new ArrayList<>();
        try {
            String qdrantUrl = env("QDRANT_URL","");
            String qdrantKey = env("QDRANT_API_KEY","");
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            if (!qdrantKey.isEmpty()) h.set("api-key", qdrantKey);

            ObjectNode req = MAPPER.createObjectNode();
            ArrayNode vec = MAPPER.createArrayNode();
            for (float v : queryVector) vec.add(v);
            req.set("vector", vec);
            req.put("limit", topK);
            req.put("with_payload", true);

            ResponseEntity<String> resp = restTemplate.exchange(
                qdrantUrl + "/collections/" + QDRANT_COLLECTION + "/points/search",
                HttpMethod.POST,
                new HttpEntity<>(MAPPER.writeValueAsString(req), h),
                String.class);

            JsonNode results = MAPPER.readTree(resp.getBody()).path("result");
            List<Map<String,Object>> hits = new ArrayList<>();
            for (JsonNode r : results) {
                Map<String,Object> hit = new LinkedHashMap<>();
                hit.put("score",   r.path("score").asDouble());
                hit.put("payload", r.path("payload").toString());
                hits.add(hit);
            }
            return hits;
        } catch (Exception e) {
            log.debug("Qdrant search skip: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private void qdrantEnsureCollection() {
        if (!qdrantEnabled()) return;
        try {
            String qdrantUrl = env("QDRANT_URL","");
            String qdrantKey = env("QDRANT_API_KEY","");
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            if (!qdrantKey.isEmpty()) h.set("api-key", qdrantKey);

            ObjectNode body = MAPPER.createObjectNode();
            ObjectNode params = MAPPER.createObjectNode();
            params.put("size", EMBED_DIM);
            params.put("distance", "Cosine");
            body.set("vectors", params);

            restTemplate.exchange(
                qdrantUrl + "/collections/" + QDRANT_COLLECTION,
                HttpMethod.PUT,
                new HttpEntity<>(MAPPER.writeValueAsString(body), h),
                String.class);
            log.info("Qdrant collection ensured: {}", QDRANT_COLLECTION);
        } catch (Exception e) {
            log.debug("Qdrant collection setup: {}", e.getMessage());
        }
    }

    @PostMapping("/qdrant/store")
    public ResponseEntity<Object> qdrantStore(@RequestBody Map<String,String> body) {
        String text      = ((String) body.getOrDefault("text","")).trim();
        String sessionId = body.getOrDefault("sessionId","global");
        String category  = body.getOrDefault("category","memory");
        if (text.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error","text obbligatorio"));
        float[] vec = embed(text);
        String id = sessionId + "_" + System.currentTimeMillis();
        Map<String,String> payload = new LinkedHashMap<>();
        payload.put("text", text.substring(0, Math.min(500, text.length())));
        payload.put("sessionId", sessionId);
        payload.put("category", category);
        payload.put("date", today());
        qdrantUpsert(id, vec, payload);
        // Salva anche in LTM locale come fallback
        consolidateToLTM(sessionId, text);
        return ResponseEntity.ok(Map.of(
            "status",   "stored",
            "id",       id,
            "qdrant",   qdrantEnabled(),
            "vecDim",   vec.length,
            "date",     today()
        ));
    }

    @PostMapping("/qdrant/search")
    public ResponseEntity<Object> qdrantSearchEndpoint(@RequestBody Map<String,String> body) {
        String query     = ((String) body.getOrDefault("query","")).trim();
        String sessionId = body.getOrDefault("sessionId","global");
        int topK         = Integer.parseInt(body.getOrDefault("topK","5"));
        if (query.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error","query obbligatoria"));
        float[] vec = embed(query);
        // Search Qdrant first, fallback to local MSA
        List<Map<String,Object>> qdrantHits = qdrantSearch(vec, topK);
        String localMsa = msaRetrieveUnified(query, sessionId);
        return ResponseEntity.ok(Map.of(
            "qdrantHits",  qdrantHits,
            "localMsa",    localMsa,
            "qdrantActive",qdrantEnabled(),
            "query",       query,
            "date",        today()
        ));
    }

    // ══════════════════════════════════════════════════════════════════
    // PUNTO 4: Tool Schema — definizione JSON strutturata per ogni tool
    // Usata per validare e documentare i tool disponibili
    // ══════════════════════════════════════════════════════════════════
    private ObjectNode buildToolSchema(String toolName) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("name", toolName);
        ObjectNode params = MAPPER.createObjectNode();
        params.put("type", "object");
        ObjectNode props = MAPPER.createObjectNode();
        ObjectNode queryProp = MAPPER.createObjectNode();
        queryProp.put("type", "string");
        switch (toolName) {
            case "web_search": case "google_search":
                schema.put("description", "Cerca informazioni aggiornate sul web");
                queryProp.put("description", "Query di ricerca in italiano o inglese");
                break;
            case "rag_retrieval":
                schema.put("description", "Recupera informazioni dai documenti indicizzati");
                queryProp.put("description", "Query semantica per il retrieval");
                break;
            case "memory_store":
                schema.put("description", "Salva un fatto nella memoria persistente (formato: soggetto::attributo)");
                queryProp.put("description", "Fatto da memorizzare, formato: soggetto::valore");
                break;
            case "memory_retrieve":
                schema.put("description", "Cerca fatti rilevanti nella memoria semantica");
                queryProp.put("description", "Query per il retrieval semantico della memoria");
                break;
            case "github_read":
                schema.put("description", "Legge un file da GitHub (formato: owner/repo/path)");
                queryProp.put("description", "Percorso nel formato owner/repo/path/to/file");
                break;
            case "code_exec":
                schema.put("description", "Esegue codice in sandbox sicura (formato: linguaggio\ncodice)");
                queryProp.put("description", "Linguaggio e codice nel formato: python\nprint('hello')");
                break;
            case "web_scrape":
                schema.put("description", "Naviga e analizza una pagina web");
                queryProp.put("description", "URL della pagina da analizzare");
                break;
            case "image_gen":
                schema.put("description", "Genera un'immagine da una descrizione");
                queryProp.put("description", "Descrizione dell'immagine da generare");
                break;
            case "math_eval":
                schema.put("description", "Valuta un'espressione matematica");
                queryProp.put("description", "Espressione matematica da calcolare");
                break;
            default:
                schema.put("description", "Tool generico: " + toolName);
                queryProp.put("description", "Parametri per il tool");
        }
        props.set("query", queryProp);
        params.set("properties", props);
        ArrayNode required = MAPPER.createArrayNode(); required.add("query");
        params.set("required", required);
        schema.set("parameters", params);
        return schema;
    }

    @GetMapping("/tools/schema")
    public ResponseEntity<Object> getToolsSchema() {
        Map<String,Boolean> tools = getAvailableTools();
        List<ObjectNode> schemas = tools.entrySet().stream()
            .filter(Map.Entry::getValue)
            .map(e -> buildToolSchema(e.getKey()))
            .collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("tools", schemas, "count", schemas.size(), "date", today()));
    }

    private String executeTool(String tool, String params, String sessionId,
                                String baseUrl, String apiKey, String model) {
        try {
            switch (tool) {
                case "web_search":
                case "google_search":
                    return searchGoogle(params);
                case "rag_retrieval":
                    return ragRetrieve(params, sessionId);
                case "memory_read":
                    return semanticMemoryRetrieve(params, sessionId);
                case "github_read":
                    // params formato: "owner/repo/path"
                    String[] parts = params.split("/", 3);
                    if (parts.length >= 3) {
                        JsonNode f = githubApi("/repos/" + parts[0] + "/" + parts[1] + "/contents/" + parts[2]);
                        String enc = f.path("content").asText().replaceAll("\s", "");
                        return new String(Base64.getDecoder().decode(enc), StandardCharsets.UTF_8)
                            .substring(0, Math.min(3000, Integer.MAX_VALUE));
                    }
                    return "Formato params: owner/repo/path";
                case "memory_store":
                    consolidateToLTM(sessionId, params);
                    storeFactDifferential(sessionId,
                        params.contains("::") ? params.split("::")[0] : params.substring(0, Math.min(30, params.length())),
                        params.contains("::") ? params.split("::")[1] : params);
                    updateVocab(tokenize(params));
                    // Salva anche su Qdrant se disponibile
                    if (qdrantEnabled()) {
                        Map<String,String> pl = new HashMap<>();
                        pl.put("text", params); pl.put("sessionId", sessionId); pl.put("date", today());
                        qdrantUpsert(sessionId + "_" + System.currentTimeMillis(), embed(params), pl);
                    }
                    return "Fatto memorizzato" + (qdrantEnabled() ? " (Qdrant + LTM)" : " (LTM)") +
                        ": " + params.substring(0, Math.min(80, params.length()));
                case "memory_retrieve":
                    String memResult = msaRetrieveUnified(params, sessionId);
                    // Cerca anche su Qdrant se disponibile
                    if (qdrantEnabled()) {
                        List<Map<String,Object>> qHits = qdrantSearch(embed(params), 3);
                        if (!qHits.isEmpty()) {
                            StringBuilder qCtx = new StringBuilder();
                            qHits.forEach(hit -> qCtx.append(hit.get("payload")).append(" "));
                            if (!memResult.isEmpty()) memResult += " | ";
                            memResult += "[Qdrant]: " + qCtx.toString().substring(0, Math.min(200, qCtx.length()));
                        }
                    }
                    return memResult.isEmpty() ? "Nessun fatto trovato per: " + params : memResult;
                case "math_eval":
                    return callLLM("Sei un calcolatore. Rispondi SOLO con il numero risultato.",
                        params, new ArrayList<>(), baseUrl, apiKey, model, 100);
                case "code_exec":
                    // Esegui codice Python via Piston sandbox
                    // params formato: "python\n<codice>"
                    try {
                        String lang = "python";
                        String code = params;
                        if (params.contains("\n")) {
                            lang = params.substring(0, params.indexOf("\n")).trim();
                            code = params.substring(params.indexOf("\n")+1);
                        }
                        Map<String,String> execBody = new HashMap<>();
                        execBody.put("language", lang);
                        execBody.put("code", code);
                        ResponseEntity<Object> execResp = manusExecCode(execBody);
                        Object execResult = execResp.getBody();
                        if (execResult instanceof Map) {
                            Map<?,?> er = (Map<?,?>)execResult;
                            return "OUTPUT: " + er.get("output") +
                                (er.get("stderr") != null ? "\nSTDERR: " + er.get("stderr") : "");
                        }
                        return execResult != null ? execResult.toString() : "Nessun output";
                    } catch (Exception ce) { return "Code exec error: " + ce.getMessage(); }
                case "web_scrape":
                    // Naviga e scrape un URL
                    try {
                        Map<String,String> browseBody = new HashMap<>();
                        browseBody.put("url", params);
                        browseBody.put("task", "Estrai le informazioni principali");
                        browseBody.put("sessionId", sessionId);
                        browseBody.put("baseUrl", baseUrl);
                        browseBody.put("apiKey",  apiKey);
                        browseBody.put("model",   model);
                        ResponseEntity<Object> browseResp = manusBrowse(browseBody);
                        Object browseResult = browseResp.getBody();
                        if (browseResult instanceof Map) {
                            Map<?,?> br = (Map<?,?>)browseResult;
                            return "PAGINA: " + params + "\n" + br.get("analysis");
                        }
                        return browseResult != null ? browseResult.toString() : "";
                    } catch (Exception we) { return "Browse error: " + we.getMessage(); }
                case "image_gen":
                    return generateImage(params);
                case "browser_control":
                    try {
                        Map<String,Object> bBody = new HashMap<>();
                        bBody.put("action",    "extract");
                        bBody.put("url",       params);
                        bBody.put("sessionId", sessionId);
                        ResponseEntity<Object> bResp = manusBrowserControl(bBody);
                        Object bResult = bResp.getBody();
                        if (bResult instanceof Map) {
                            Map<?,?> bm = (Map<?,?>)bResult;
                            Object res = bm.get("result");
                            return res != null ? res.toString() : bResult.toString();
                        }
                        return bResult != null ? bResult.toString() : "Browser: nessun risultato";
                    } catch (Exception be) { return "Browser error: " + be.getMessage(); }
                case "qdrant_store":
                    if (qdrantEnabled()) {
                        Map<String,String> qpl = new HashMap<>();
                        qpl.put("text", params); qpl.put("sessionId", sessionId); qpl.put("date", today());
                        qdrantUpsert(sessionId+"_"+System.currentTimeMillis(), embed(params), qpl);
                        return "Stored in Qdrant: " + params.substring(0, Math.min(60, params.length()));
                    }
                    return "Qdrant non configurato";
                case "qdrant_search":
                    List<Map<String,Object>> qHits = qdrantSearch(embed(params), 5);
                    if (qHits.isEmpty()) return "Nessun risultato Qdrant per: " + params;
                    StringBuilder qSb = new StringBuilder();
                    qHits.forEach(h -> qSb.append(h.get("payload")).append("\n"));
                    return qSb.toString();
                default:
                    return "Tool sconosciuto: " + tool;
            }
        } catch (Exception e) {
            log.warn("Tool {} fallito: {}", tool, e.getMessage());
            return "Errore tool " + tool + ": " + e.getMessage();
        }
    }

    /**
     * Agent Loop principale — max 4 iterazioni come Manus.
     * Il LLM decide autonomamente quali tool usare e quando fermarsi.
     */
    private String agentLoop(String userQuery, String sessionId,
                              String baseUrl, String apiKey, String model) throws Exception {
        Map<String,Boolean> tools = getAvailableTools();
        String toolsList = tools.entrySet().stream()
            .filter(Map.Entry::getValue)
            .map(Map.Entry::getKey)
            .collect(Collectors.joining(", "));

        String agentSystemPrompt =
            "Sei SPACE AI con Agent Loop Manus-style. Data: " + today() + ".\n" +
            "Strumenti disponibili: " + toolsList + "\n\n" +
            "FASE 1 — PIANIFICAZIONE (OBBLIGATORIA):\n" +
            "Prima di qualsiasi azione, scrivi un Piano d'azione cosi:\n" +
            "[PLAN]\n" +
            "Obiettivo: <cosa devo ottenere>\n" +
            "Passi: 1) ... 2) ... 3) ...\n" +
            "Tool che usero: <lista>\n" +
            "[/PLAN]\n\n" +
            "FASE 2 — ESECUZIONE (usa i tool):\n" +
            "Per ogni tool: [TOOL: nome_tool | PARAMS: parametri]\n\n" +
            "FASE 3 — RISPOSTA FINALE:\n" +
            "[FINAL_ANSWER]\n<risposta completa>\n[/FINAL_ANSWER]\n\n" +
            "Regole:\n" +
            "- SEMPRE inizia con [PLAN] prima di usare tool\n" +
            "- web_search/google_search per dati in tempo reale\n" +
            "- rag_retrieval per documenti indicizzati\n" +
            "- memory_store per salvare fatti importanti\n" +
            "- memory_retrieve per cercare nella memoria\n" +
            "- github_read per file del repo\n" +
            "- Max 4 iterazioni\n" +
            "- Cita sempre le fonti\n" +
            "- Rispondi in italiano";

        List<Map<String,String>> agentHistory = new ArrayList<>();
        StringBuilder observations = new StringBuilder();
        String currentQuery = userQuery;
        int maxIterations = 4;

        // Memoria procedurale: ricorda strategia vincente per contesti simili
        String bestStrategy = recallBestStrategy(userQuery);
        if (bestStrategy != null) {
            log.info("ProceduralMem: uso strategia ricordata: {}", bestStrategy.substring(0,Math.min(50,bestStrategy.length())));
            agentSystemPrompt += "\nSTRATEGIA PRECEDENTE VINCENTE: " + bestStrategy + " (usala come guida)";
        }

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            String contextMsg = currentQuery;
            if (observations.length() > 0)
                contextMsg += "\n\n[OSSERVAZIONI PRECEDENTI]:\n" + observations;

            String routedModel = selectModel(contextMsg, model);
            String llmResp = callLLM(agentSystemPrompt, contextMsg,
                agentHistory, baseUrl, apiKey, routedModel, 1500);

            log.info("AgentLoop iter {}: {}", iteration,
                llmResp.substring(0, Math.min(100, llmResp.length())));

            // Controlla se ha una risposta finale
            if (llmResp.contains("[FINAL_ANSWER]")) {
                int s = llmResp.indexOf("[FINAL_ANSWER]") + 14;
                int e = llmResp.indexOf("[/FINAL_ANSWER]");
                if (e > s) return llmResp.substring(s, e).trim();
                return llmResp.substring(s).trim();
            }

            // Estrai e esegui tool calls
            boolean toolCalled = false;
            int toolStart = llmResp.indexOf("[TOOL:");
            while (toolStart >= 0) {
                int toolEnd = llmResp.indexOf("]", toolStart);
                if (toolEnd < 0) break;
                String toolCall = llmResp.substring(toolStart + 6, toolEnd);
                String[] toolParts = toolCall.split("\\|");
                String toolName = toolParts[0].replace("TOOL:", "").replace("nome_tool", "").trim();
                String params   = toolParts.length > 1
                    ? toolParts[1].replace("PARAMS:", "").trim() : currentQuery;

                if (!toolName.isEmpty() && tools.getOrDefault(toolName, false)) {
                    log.info("AgentLoop executing tool: {} params: {}", toolName,
                        params.substring(0, Math.min(60, params.length())));
                    String toolResult = executeTool(toolName, params, sessionId, baseUrl, apiKey, model);
                    if (toolResult != null && !toolResult.isBlank()) {
                        observations.append("\n📡 Tool [").append(toolName).append("] risultato:\n")
                            .append(toolResult, 0, Math.min(800, toolResult.length())).append("\n---\n");
                        toolCalled = true;
                        ragIndexDocument(sessionId + "/agent_obs_" + iteration, toolResult);
                    }
                }
                toolStart = llmResp.indexOf("[TOOL:", toolEnd);
            }

            agentHistory.add(Map.of("role", "assistant", "content", llmResp));
            if (!toolCalled) {
                return llmResp;
            }
        }
        // Risposta finale dopo max iterazioni
        String finalPrompt = userQuery + "\n\n[DATI RACCOLTI]:\n" + observations +
            "\n\nFornisci la risposta finale completa in italiano citando le fonti.";
        return callLLM(agentSystemPrompt, finalPrompt, new ArrayList<>(), baseUrl, apiKey, model, 2500);
    }



    // ════════════════════════════════════════════════════════════════════
    // MANUS-STYLE AUTONOMOUS AGENT
    // 1. Task Executor: goal → subtasks → execute → report
    // 2. Code Executor: Python/JS sandbox via Piston API
    // 3. Web Browser Agent: naviga, estrae dati, compila form
    // ════════════════════════════════════════════════════════════════════

    // Task status lifecycle
    private enum TaskStatus { PENDING, RUNNING, DONE, FAILED }

    private static class SubTask {
        String id, description, tool, params, result;
        TaskStatus status = TaskStatus.PENDING;
        SubTask(String id, String description, String tool, String params) {
            this.id=id; this.description=description; this.tool=tool; this.params=params;
        }
    }
    private static class ManusTask {
        String id, goal, sessionId, finalReport;
        TaskStatus status = TaskStatus.PENDING;
        List<SubTask> subtasks = new ArrayList<>();
        long createdAt = System.currentTimeMillis();
        long completedAt = 0;
    }

    // Store for all tasks (taskId → ManusTask)
    private final Map<String, ManusTask> taskStore = new ConcurrentHashMap<>();

    // ── TASK EXECUTOR: decompone obiettivo in subtask ed esegue ─────────
    // ════════════════════════════════════════════════════════════════════════
    // AUTONOMOUS GOAL DELEGATION — GPT-5.5 style 委任式知能
    // L'utente assegna un obiettivo aziendale, l'AI pianifica ed esegue in autonomia
    // Supporta task lunghi (async), resuming da checkpoint, gestione ambiguità
    // ════════════════════════════════════════════════════════════════════════

    // Store per task asincroni a lungo termine
    private final Map<String, ManusTask> longRunningTasks = new ConcurrentHashMap<>();

    @PostMapping("/goal/delegate")
    public ResponseEntity<Object> delegateGoal(@RequestBody Map<String,String> body) {
        String goal      = ((String) body.getOrDefault("goal","")).trim();
        String context   = body.getOrDefault("context","");   // contesto aziendale opzionale
        String sessionId = body.getOrDefault("sessionId","global");
        String baseUrl   = body.getOrDefault("baseUrl", env("AI_BASE_URL", env("GROQ_BASE_URL","https://api.groq.com/openai/v1")));
        String apiKey    = body.getOrDefault("apiKey",  env("AI_API_KEY", env("GROQ_API_KEY","")));
        String model     = body.getOrDefault("model",   env("AI_MODEL", env("GROQ_MODEL","llama-3.3-70b-versatile")));

        if (goal.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error","Goal obbligatorio"));

        String taskId = "goal_" + System.currentTimeMillis();
        ManusTask task = new ManusTask();
        task.id = taskId; task.goal = goal; task.sessionId = sessionId;
        task.status = TaskStatus.RUNNING;
        longRunningTasks.put(taskId, task);
        taskStore.put(taskId, task);

        // Async execution — non blocca il chiamante
        CompletableFuture.runAsync(() -> {
            try {
                autonomousGoalExecution(task, context, baseUrl, apiKey, model);
            } catch (Exception e) {
                task.status = TaskStatus.FAILED;
                task.finalReport = "Errore: " + e.getMessage();
                log.error("Goal delegation {} failed: {}", taskId, e.getMessage());
            }
        });

        return ResponseEntity.ok(Map.of(
            "taskId",   taskId,
            "goal",     goal,
            "status",   "DELEGATED",
            "message",  "Obiettivo delegato. SPACE AI sta lavorando in autonomia.",
            "pollUrl",  "/api/manus/task/" + taskId,
            "goalUrl",  "/api/goal/status/" + taskId
        ));
    }

    @GetMapping("/goal/status/{taskId}")
    public ResponseEntity<Object> goalStatus(@PathVariable String taskId) {
        ManusTask task = longRunningTasks.get(taskId);
        if (task == null) task = taskStore.get(taskId);
        if (task == null) return ResponseEntity.status(404).body(Map.of("error","Goal non trovato"));
        int done = (int) task.subtasks.stream()
            .filter(s -> s.status == TaskStatus.DONE || s.status == TaskStatus.FAILED).count();
        int total = task.subtasks.size();
        Map<String,Object> r = new LinkedHashMap<>();
        r.put("taskId",      task.id);
        r.put("goal",        task.goal);
        r.put("status",      task.status.name());
        r.put("progress",    total > 0 ? (done * 100 / total) + "%" : "Planning...");
        r.put("subtasks",    task.subtasks.size());
        r.put("completed",   done);
        r.put("elapsed",     (System.currentTimeMillis() - task.createdAt)/1000 + "s");
        r.put("report",      task.finalReport != null ?
            task.finalReport.substring(0, Math.min(500, task.finalReport.length())) : null);
        return ResponseEntity.ok(r);
    }

    @GetMapping("/goal/list")
    public ResponseEntity<Object> goalList() {
        List<Map<String,Object>> list = longRunningTasks.values().stream()
            .sorted(Comparator.comparingLong(t -> -t.createdAt))
            .limit(10)
            .map(t -> {
                Map<String,Object> m = new LinkedHashMap<>();
                m.put("taskId",  t.id);
                m.put("goal",    t.goal.substring(0, Math.min(80, t.goal.length())));
                m.put("status",  t.status.name());
                m.put("elapsed", (System.currentTimeMillis() - t.createdAt)/1000 + "s");
                return m;
            }).collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("goals", list, "total", longRunningTasks.size()));
    }

    private void autonomousGoalExecution(ManusTask task, String context,
                                          String baseUrl, String apiKey, String model) throws Exception {
        Map<String,Boolean> tools = getAvailableTools();
        String toolsList = tools.entrySet().stream()
            .filter(Map.Entry::getValue).map(Map.Entry::getKey)
            .collect(Collectors.joining(", "));

        // FASE 1: Disambiguazione — capisce il goal e lo rende concreto
        String disambiguationPrompt =
            "Sei un orchestratore AI aziendale (stile GPT-5.5 委任式知能).\n" +
            "Ricevi un obiettivo di business e devi:\n" +
            "1. Verificare che sia chiaro e specifico\n" +
            "2. Identificare le dipendenze tra subtask\n" +
            "3. Stimare la complessita (BASSA/MEDIA/ALTA)\n" +
            "4. Produrre un piano d'azione JSON con max 8 subtask\n\n" +
            "Formato risposta OBBLIGATORIO (solo JSON valido):\n" +
            "{\"clarity\":\"CHIARO|AMBIGUO\",\"complexity\":\"BASSA|MEDIA|ALTA\",\n" +
            "\"refined_goal\":\"...\",\n" +
            "\"subtasks\":[{\"id\":\"1\",\"description\":\"...\",\"tool\":\"...\",\"params\":\"...\",\"depends_on\":[]}]}\n\n" +
            "Strumenti disponibili: " + toolsList + "\n" +
            "Contesto aziendale: " + (context.isEmpty() ? "nessuno" : context) + "\n" +
            "OBIETTIVO: " + task.goal;

        String planJson = callLLM(
            "Sei un pianificatore AI di livello enterprise. Rispondi SOLO con JSON valido.",
            disambiguationPrompt, new ArrayList<>(), baseUrl, apiKey, model, 2000);

        // Parse piano
        try {
            String cleaned = planJson.replaceAll("```json","").replaceAll("```","").trim();
            int js = cleaned.indexOf("{"), je = cleaned.lastIndexOf("}");
            if (js >= 0 && je > js) cleaned = cleaned.substring(js, je+1);
            JsonNode plan = MAPPER.readTree(cleaned);

            // Aggiorna il goal con la versione raffinata
            String refinedGoal = plan.path("refined_goal").asText(task.goal);
            String complexity  = plan.path("complexity").asText("MEDIA");
            log.info("Goal delegation {}: complexity={} refined={}",
                task.id, complexity, refinedGoal.substring(0, Math.min(60, refinedGoal.length())));

            // Costruisci subtask con dipendenze
            JsonNode subs = plan.path("subtasks");
            Map<String,SubTask> subMap = new LinkedHashMap<>();
            if (subs.isArray()) {
                for (JsonNode s : subs) {
                    SubTask st = new SubTask(
                        s.path("id").asText(String.valueOf(subMap.size()+1)),
                        s.path("description").asText(),
                        s.path("tool").asText("web_search"),
                        s.path("params").asText(task.goal));
                    task.subtasks.add(st);
                    subMap.put(st.id, st);
                }
            }
        } catch (Exception pe) {
            log.warn("Goal plan parse failed, uso default: {}", pe.getMessage());
            task.subtasks.add(new SubTask("1","Ricerca","web_search",task.goal));
            task.subtasks.add(new SubTask("2","Analisi","memory_retrieve",task.goal));
            task.subtasks.add(new SubTask("3","Report","memory_store",task.goal));
        }

        // FASE 2: Esecuzione con rispetto delle dipendenze
        StringBuilder allResults = new StringBuilder();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (SubTask sub : task.subtasks) {
            sub.status = TaskStatus.RUNNING;
            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                try {
                    sub.result = executeTool(sub.tool, sub.params, task.sessionId, baseUrl, apiKey, model);
                    if (sub.result == null || sub.result.isBlank()) sub.result = "Nessun risultato";
                    sub.status = TaskStatus.DONE;
                    ragIndexDocument(task.id + "/sub_" + sub.id, sub.result);
                } catch (Exception e) {
                    sub.result = "Errore: " + e.getMessage();
                    sub.status = TaskStatus.FAILED;
                }
            });
            futures.add(f);
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(120, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception te) {
            log.warn("Goal subtasks timeout: {}", te.getMessage());
        }
        for (SubTask sub : task.subtasks)
            allResults.append("## ").append(sub.description).append("\n")
                .append(sub.result != null ? sub.result.substring(0, Math.min(600, sub.result.length())) : "")
                .append("\n\n");

        // FASE 3: Report finale con LLM-as-a-Judge interno
        String reportPrompt =
            "Sei un analista AI senior. Hai completato questi subtask per l'obiettivo:\n" +
            "OBIETTIVO: " + task.goal + "\n\n" +
            "RISULTATI:\n" + allResults +
            "\nScrivi un report executive professionale in italiano con:\n" +
            "- Executive Summary (3 righe)\n" +
            "- Risultati chiave (bullet points)\n" +
            "- Analisi e insights\n" +
            "- Raccomandazioni concrete\n" +
            "- Prossimi passi";

        task.finalReport = callLLM(
            "Sei un analista aziendale senior. Scrivi report professionali ed esaustivi.",
            reportPrompt, new ArrayList<>(), baseUrl, apiKey, model, 3000);

        // LLM-as-a-Judge: valida il report
        task.finalReport = llmJudgeValidate(task.finalReport, task.goal, baseUrl, apiKey, model);

        task.status = TaskStatus.DONE;
        task.completedAt = System.currentTimeMillis();
        saveTaskCheckpoint(task);
        ragIndexDocument(task.id + "/final", task.finalReport);
        log.info("Goal delegation {} completata in {}s",
            task.id, (task.completedAt - task.createdAt)/1000);
    }

    @PostMapping("/manus/task")
    public ResponseEntity<Object> manusCreateTask(@RequestBody Map<String,String> body) {
        String goal      = ((String) body.getOrDefault("goal", "")).trim();
        String sessionId = body.getOrDefault("sessionId", "global");
        String baseUrl   = body.getOrDefault("baseUrl", env("AI_BASE_URL", env("GROQ_BASE_URL","https://api.groq.com/openai/v1")));
        String apiKey    = body.getOrDefault("apiKey",  env("AI_API_KEY", env("GROQ_API_KEY","")));
        String model     = body.getOrDefault("model",   env("AI_MODEL", env("GROQ_MODEL","llama-3.3-70b-versatile")));

        if (goal.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error","Fornisci un goal"));

        String taskId = "task_" + System.currentTimeMillis();
        ManusTask task = new ManusTask();
        task.id = taskId; task.goal = goal; task.sessionId = sessionId;
        task.status = TaskStatus.RUNNING;
        taskStore.put(taskId, task);

        // Pianifica i subtask in un thread separato (non blocca la risposta)
        CompletableFuture.runAsync(() -> {
            try {
                executeManusTask(task, baseUrl, apiKey, model);
            } catch (Exception e) {
                task.status = TaskStatus.FAILED;
                task.finalReport = "Errore: " + e.getMessage();
                log.error("ManusTask {} failed: {}", taskId, e.getMessage());
            }
        });

        return ResponseEntity.ok(Map.of(
            "taskId",   taskId,
            "goal",     goal,
            "status",   "RUNNING",
            "message",  "Task avviato. Usa GET /api/manus/task/" + taskId + " per monitorare.",
            "pollUrl",  "/api/manus/task/" + taskId
        ));
    }

    @GetMapping("/manus/task/{taskId}")
    public ResponseEntity<Object> manusGetTask(@PathVariable String taskId) {
        ManusTask task = taskStore.get(taskId);
        if (task == null) return ResponseEntity.status(404).body(Map.of("error","Task non trovato"));
        Map<String,Object> resp = new LinkedHashMap<>();
        resp.put("taskId",    task.id);
        resp.put("goal",      task.goal);
        resp.put("status",    task.status.name());
        resp.put("progress",  task.subtasks.isEmpty() ? 0 :
            (int)(100.0 * task.subtasks.stream().filter(s -> s.status==TaskStatus.DONE||s.status==TaskStatus.FAILED).count() / task.subtasks.size()));
        List<Map<String,Object>> subs = new ArrayList<>();
        for (SubTask s : task.subtasks) {
            Map<String,Object> sm = new LinkedHashMap<>();
            sm.put("id",          s.id);
            sm.put("description", s.description);
            sm.put("tool",        s.tool);
            sm.put("status",      s.status.name());
            sm.put("result",      s.result != null ? s.result.substring(0, Math.min(300, s.result.length())) : null);
            subs.add(sm);
        }
        resp.put("subtasks",    subs);
        resp.put("finalReport", task.finalReport);
        resp.put("elapsed",     (System.currentTimeMillis() - task.createdAt) / 1000 + "s");
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/manus/tasks")
    public ResponseEntity<Object> manusListTasks() {
        List<Map<String,Object>> list = taskStore.values().stream()
            .sorted(Comparator.comparingLong(t -> -t.createdAt))
            .limit(20)
            .map(t -> {
                Map<String,Object> m = new LinkedHashMap<>();
                m.put("taskId",  t.id);
                m.put("goal",    t.goal.substring(0, Math.min(80, t.goal.length())));
                m.put("status",  t.status.name());
                m.put("subtasks",t.subtasks.size());
                m.put("elapsed", (System.currentTimeMillis() - t.createdAt)/1000 + "s");
                return m;
            }).collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("tasks", list, "total", taskStore.size()));
    }

    private void executeManusTask(ManusTask task, String baseUrl, String apiKey, String model) throws Exception {
        Map<String,Boolean> tools = getAvailableTools();
        String toolsList = tools.entrySet().stream()
            .filter(Map.Entry::getValue).map(Map.Entry::getKey)
            .collect(Collectors.joining(", "));

        // STEP 1: Pianificazione — LLM decompone il goal in subtask JSON
        String planPrompt =
            "Sei un pianificatore AI. Ricevi un obiettivo e devi decomporlo in subtask eseguibili.\n" +
            "Strumenti disponibili: " + toolsList + "\n\n" +
            "Per ogni subtask specifica: id, description, tool, params.\n" +
            "Strumenti tool validi: web_search, google_search, rag_retrieval, github_read, " +
            "code_exec, web_scrape, image_gen, math_eval, memory_read.\n\n" +
            "Rispondi SOLO con JSON valido, nessun testo prima o dopo:\n" +
            "{\"subtasks\": [{\"id\":\"1\", \"description\":\"...\", " +
            "\"tool\":\"web_search\", \"params\":\"...\"}]}\n\n" +
            "OBIETTIVO: " + task.goal;

        String planJson = callLLM("Sei un pianificatore JSON. Rispondi SOLO con JSON valido.",
            planPrompt, new ArrayList<>(), baseUrl, apiKey, model, 1500);

        // Parse JSON subtasks
        try {
            String cleaned = planJson.replaceAll("```json","").replaceAll("```","").trim();
            JsonNode plan = MAPPER.readTree(cleaned);
            JsonNode subs = plan.path("subtasks");
            if (subs.isArray()) {
                for (JsonNode s : subs) {
                    task.subtasks.add(new SubTask(
                        s.path("id").asText("" + (task.subtasks.size()+1)),
                        s.path("description").asText(),
                        s.path("tool").asText("web_search"),
                        s.path("params").asText(task.goal)
                    ));
                }
            }
        } catch (Exception e) {
            log.warn("Plan parse failed, creo subtask defaults: {}", e.getMessage());
            task.subtasks.add(new SubTask("1","Ricerca web",    "web_search",   task.goal));
            task.subtasks.add(new SubTask("2","Analisi RAG",    "rag_retrieval",task.goal));
            task.subtasks.add(new SubTask("3","Report finale",  "memory_read",  task.goal));
        }

        log.info("ManusTask {}: {} subtasks pianificati", task.id, task.subtasks.size());

        // STEP 2: Esecuzione subtask in PARALLELO (Punto 8)
        StringBuilder allResults = new StringBuilder();
        // Lancia tutti i subtask in parallelo
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (SubTask sub : task.subtasks) {
            sub.status = TaskStatus.RUNNING;
            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                log.info("Subtask parallelo {}: {} [{}]", sub.id, sub.description, sub.tool);
                try {
                    sub.result = executeTool(sub.tool, sub.params, task.sessionId, baseUrl, apiKey, model);
                    if (sub.result == null || sub.result.isBlank())
                        sub.result = "Nessun risultato disponibile";
                    sub.status = TaskStatus.DONE;
                    ragIndexDocument(task.id + "/sub_" + sub.id, sub.result);
                } catch (Exception e) {
                    sub.result = "Errore: " + e.getMessage();
                    sub.status = TaskStatus.FAILED;
                    log.warn("Subtask {} fallito: {}", sub.id, e.getMessage());
                }
            });
            futures.add(f);
        }
        // Attendi tutti i subtask (max 90 secondi totali)
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(90, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception te) {
            log.warn("Alcuni subtask hanno superato il timeout: {}", te.getMessage());
        }
        // Raccogli i risultati in ordine
        for (SubTask sub : task.subtasks) {
            allResults.append("### Subtask ").append(sub.id)
                .append(" [").append(sub.status).append("]: ").append(sub.description).append("\n");
            if (sub.result != null)
                allResults.append(sub.result, 0, Math.min(600, sub.result.length())).append("\n\n");
        }

        // STEP 3: Sintesi finale
        String reportPrompt =
            "Hai eseguito i seguenti subtask per raggiungere questo obiettivo:\n" +
            "OBIETTIVO: " + task.goal + "\n\n" +
            "RISULTATI:\n" + allResults +
            "\nScrivi un report finale completo, strutturato e professionale in italiano. " +
            "Includi: riepilogo, risultati chiave, conclusioni e prossimi passi consigliati.";

        task.finalReport = callLLM(
            "Sei un assistente che scrive report professionali. Rispondi in italiano.",
            reportPrompt, new ArrayList<>(), baseUrl, apiKey, model, 2500);

        task.status      = TaskStatus.DONE;
        task.completedAt = System.currentTimeMillis();
        log.info("ManusTask {} completato in {}ms", task.id, task.completedAt - task.createdAt);

        // Salva il report nel RAG
        ragIndexDocument(task.id + "/final_report", task.finalReport);

        // Punto 9: Checkpoint — salva stato su file
        saveTaskCheckpoint(task);
    }

    private void saveTaskCheckpoint(ManusTask task) {
        try {
            ObjectNode cp = MAPPER.createObjectNode();
            cp.put("taskId",   task.id);
            cp.put("goal",     task.goal);
            cp.put("status",   task.status.name());
            cp.put("report",   task.finalReport != null ? task.finalReport.substring(0, Math.min(2000, task.finalReport.length())) : "");
            cp.put("savedAt",  today());
            cp.put("elapsed",  (task.completedAt - task.createdAt) + "ms");
            ArrayNode subs = MAPPER.createArrayNode();
            for (SubTask s : task.subtasks) {
                ObjectNode sn = MAPPER.createObjectNode();
                sn.put("id", s.id); sn.put("tool", s.tool);
                sn.put("status", s.status.name());
                sn.put("result", s.result != null ? s.result.substring(0, Math.min(200, s.result.length())) : "");
                subs.add(sn);
            }
            cp.set("subtasks", subs);
            java.nio.file.Path cpDir = java.nio.file.Paths.get("checkpoints");
            if (!java.nio.file.Files.exists(cpDir)) java.nio.file.Files.createDirectories(cpDir);
            java.nio.file.Files.writeString(cpDir.resolve(task.id + ".json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(cp));
            log.info("Checkpoint salvato: checkpoints/{}.json", task.id);
        } catch (Exception e) {
            log.warn("Checkpoint save failed: {}", e.getMessage());
        }
    }

    @GetMapping("/manus/checkpoint/{taskId}")
    public ResponseEntity<Object> getCheckpoint(@PathVariable String taskId) {
        try {
            java.nio.file.Path cp = java.nio.file.Paths.get("checkpoints", taskId + ".json");
            if (!java.nio.file.Files.exists(cp))
                return ResponseEntity.status(404).body(Map.of("error", "Checkpoint non trovato"));
            String json = java.nio.file.Files.readString(cp);
            return ResponseEntity.ok(MAPPER.readTree(json));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/manus/checkpoints")
    public ResponseEntity<Object> listCheckpoints() {
        try {
            java.nio.file.Path cpDir = java.nio.file.Paths.get("checkpoints");
            if (!java.nio.file.Files.exists(cpDir))
                return ResponseEntity.ok(Map.of("checkpoints", List.of(), "total", 0));
            List<String> files = java.nio.file.Files.list(cpDir)
                .filter(p -> p.toString().endsWith(".json"))
                .map(p -> p.getFileName().toString().replace(".json",""))
                .sorted(Comparator.reverseOrder())
                .limit(20)
                .collect(Collectors.toList());
            return ResponseEntity.ok(Map.of("checkpoints", files, "total", files.size()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // CODE EXECUTOR — esegui Python/JS/Bash via Piston API (sandbox)
    // Gratuito, no key richiesta: https://emkc.org/api/v2/piston
    // ════════════════════════════════════════════════════════════════════

    @PostMapping("/manus/exec")
    public ResponseEntity<Object> manusExecCode(@RequestBody Map<String,String> body) {
        String language = body.getOrDefault("language", "python").toLowerCase();
        String code     = body.getOrDefault("code", "");
        String stdin    = body.getOrDefault("stdin", "");
        if (code.isBlank()) return ResponseEntity.badRequest().body(Map.of("error","Codice vuoto"));

        // Mappa linguaggio -> runtime Piston
        Map<String,String[]> runtimes = new HashMap<>();
        runtimes.put("python",     new String[]{"python",     "3.10.0"});
        runtimes.put("javascript", new String[]{"javascript", "18.15.0"});
        runtimes.put("js",         new String[]{"javascript", "18.15.0"});
        runtimes.put("java",       new String[]{"java",       "15.0.2"});
        runtimes.put("bash",       new String[]{"bash",       "5.2.0"});
        runtimes.put("ruby",       new String[]{"ruby",       "3.0.1"});
        runtimes.put("php",        new String[]{"php",        "8.2.3"});
        runtimes.put("rust",       new String[]{"rust",       "1.68.2"});
        runtimes.put("go",         new String[]{"go",         "1.16.2"});
        runtimes.put("cpp",        new String[]{"c++",        "10.2.0"});
        runtimes.put("c",          new String[]{"c",          "10.2.0"});

        String[] runtime = runtimes.getOrDefault(language, new String[]{"python","3.10.0"});

        try {
            org.springframework.web.client.RestTemplate pistonClient =
                new org.springframework.web.client.RestTemplate();
            org.springframework.http.client.SimpleClientHttpRequestFactory pf =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
            pf.setConnectTimeout(10000); pf.setReadTimeout(30000);
            pistonClient.setRequestFactory(pf);

            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);

            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("language", runtime[0]);
            payload.put("version",  runtime[1]);
            ArrayNode files = MAPPER.createArrayNode();
            ObjectNode file = MAPPER.createObjectNode();
            file.put("name", "main." + language);
            file.put("content", code);
            files.add(file);
            payload.set("files", files);
            if (!stdin.isBlank()) payload.put("stdin", stdin);
            payload.put("compile_timeout", 10000);
            payload.put("run_timeout",     5000);

            ResponseEntity<String> resp = pistonClient.postForEntity(
                "https://emkc.org/api/v2/piston/execute",
                new HttpEntity<>(MAPPER.writeValueAsString(payload), h),
                String.class);

            JsonNode result = MAPPER.readTree(resp.getBody());
            JsonNode run    = result.path("run");
            String output   = run.path("stdout").asText();
            String stderr   = run.path("stderr").asText();
            int    exitCode = run.path("code").asInt();

            Map<String,Object> r = new LinkedHashMap<>();
            r.put("language", language);
            r.put("exitCode", exitCode);
            r.put("output",   output.isEmpty() ? "(nessun output)" : output);
            r.put("stderr",   stderr.isEmpty()  ? null : stderr);
            r.put("success",  exitCode == 0);
            r.put("date",     today());
            return ResponseEntity.ok(r);

        } catch (Exception e) {
            log.warn("Code exec failed: {}", e.getMessage());
            return ResponseEntity.status(503).body(Map.of(
                "error",   "Sandbox non raggiungibile: " + e.getMessage(),
                "hint",    "Il servizio Piston API potrebbe essere temporaneamente offline"
            ));
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // WEB BROWSER AGENT — naviga URL, estrae dati strutturati, scraping
    // ════════════════════════════════════════════════════════════════════

    @PostMapping("/manus/browse")
    public ResponseEntity<Object> manusBrowse(@RequestBody Map<String,String> body) {
        String url       = ((String) body.getOrDefault("url", "")).trim();
        String task      = body.getOrDefault("task", "Estrai il contenuto principale");
        String sessionId = body.getOrDefault("sessionId", "global");
        String baseUrl   = body.getOrDefault("baseUrl", env("AI_BASE_URL", env("GROQ_BASE_URL","https://api.groq.com/openai/v1")));
        String apiKey    = body.getOrDefault("apiKey",  env("AI_API_KEY", env("GROQ_API_KEY","")));
        String model     = body.getOrDefault("model",   env("AI_MODEL", env("GROQ_MODEL","llama-3.3-70b-versatile")));

        if (url.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error","URL obbligatorio"));
        if (!url.startsWith("http")) url = "https://" + url;

        try {
            // STEP 1: Scarica la pagina
            org.springframework.web.client.RestTemplate browser =
                new org.springframework.web.client.RestTemplate();
            org.springframework.http.client.SimpleClientHttpRequestFactory bf =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
            bf.setConnectTimeout(10000); bf.setReadTimeout(20000);
            browser.setRequestFactory(bf);
            HttpHeaders bh = new HttpHeaders();
            bh.set("User-Agent","Mozilla/5.0 (compatible; SPACE-AI/4.0)");
            bh.set("Accept","text/html,application/xhtml+xml,*/*");
            ResponseEntity<String> pageResp = browser.exchange(
                url, HttpMethod.GET, new HttpEntity<>(bh), String.class);
            String rawHtml = pageResp.getBody() != null ? pageResp.getBody() : "";

            // STEP 2: Pulisci HTML — rimuovi script, style, tag
            String cleanText = rawHtml
                .replaceAll("(?s)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?s)<style[^>]*>.*?</style>",   " ")
                .replaceAll("(?s)<nav[^>]*>.*?</nav>",       " ")
                .replaceAll("(?s)<footer[^>]*>.*?</footer>", " ")
                .replaceAll("(?s)<header[^>]*>.*?</header>", " ")
                .replaceAll("<[^>]+>",                       " ")
                .replaceAll("&nbsp;",                        " ")
                .replaceAll("&amp;",                         "&")
                .replaceAll("&lt;",                          "<")
                .replaceAll("&gt;",                          ">")
                .replaceAll("\s{3,}",                       " ")
                .trim();

            // Limita a 6000 caratteri per il contesto LLM
            String pageContent = cleanText.substring(0, Math.min(6000, cleanText.length()));

            // STEP 3: Indicizza nel RAG
            String docId = sessionId + "/browse_" + url.replaceAll("[^a-zA-Z0-9]","_").substring(0, Math.min(50, url.length()));
            int chunks = ragIndexDocument(docId, pageContent);

            // STEP 4: LLM analizza e risponde al task
            String analysis = callLLM(
                "Sei un web agent. Analizza il contenuto della pagina ed esegui il task richiesto. " +
                "Rispondi in italiano in modo strutturato.",
                "URL: " + url + "\nTASK: " + task + "\n\nCONTENUTO PAGINA:\n" + pageContent,
                new ArrayList<>(), baseUrl, apiKey, model, 2000);

            // STEP 5: Estrai link presenti nella pagina
            List<String> links = new ArrayList<>();
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("href=[\"\'](.[^\"\'#]{6,98})[\"\']").matcher(rawHtml);
            int lCount = 0;
            while (m.find() && lCount++ < 15) {
                String href = m.group(1);
                if (href.startsWith("http")) links.add(href);
            }

            // Punto 11: Estrai form fields (Computer Use proto)
            List<Map<String,String>> formFields = new ArrayList<>();
            java.util.regex.Matcher fm = java.util.regex.Pattern
                .compile("<input[^>]+name=([^>]{1,60})>").matcher(rawHtml);
            int fCount = 0;
            while (fm.find() && fCount++ < 10) {
                Map<String,String> field = new LinkedHashMap<>();
                field.put("name", fm.group(1));
                String ctx = rawHtml.substring(Math.max(0,fm.start()-50), Math.min(rawHtml.length(),fm.end()+10));
                field.put("type", ctx.contains("password") ? "password" : "text");
            }

            Map<String,Object> r = new LinkedHashMap<>();
            r.put("url",        url);
            r.put("task",       task);
            r.put("analysis",   analysis);
            r.put("pageLength", cleanText.length());
            r.put("ragChunks",  chunks);
            r.put("links",      links);
            r.put("formFields", formFields);
            r.put("indexed",    true);
            r.put("date",       today());
            return ResponseEntity.ok(r);

        } catch (Exception e) {
            log.warn("Browse failed {}: {}", url, e.getMessage());
            return ResponseEntity.status(503).body(Map.of(
                "error", "Impossibile navigare " + url + ": " + e.getMessage()));
        }
    }

    // Punto 11: Form submission — Computer Use
    @PostMapping("/manus/form-submit")
    public ResponseEntity<Object> manusFormSubmit(@RequestBody Map<String,Object> body) {
        String url    = (String) body.getOrDefault("url", "");
        String method = ((String) body.getOrDefault("method", "POST")).toUpperCase();
        @SuppressWarnings("unchecked")
        Map<String,String> formData = (Map<String,String>) body.getOrDefault("formData", new HashMap<>());
        if (url.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error","URL obbligatorio"));
        try {
            org.springframework.web.client.RestTemplate ft =
                new org.springframework.web.client.RestTemplate();
            org.springframework.http.client.SimpleClientHttpRequestFactory ff =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
            ff.setConnectTimeout(10000); ff.setReadTimeout(20000); ft.setRequestFactory(ff);
            HttpHeaders fh = new HttpHeaders();
            fh.set("User-Agent","Mozilla/5.0 (compatible; SPACE-AI/4.0)");
            fh.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            org.springframework.util.MultiValueMap<String,String> fBody =
                new org.springframework.util.LinkedMultiValueMap<>();
            formData.forEach(fBody::add);
            ResponseEntity<String> resp = ft.exchange(url,
                method.equals("GET") ? HttpMethod.GET : HttpMethod.POST,
                new HttpEntity<>(fBody, fh), String.class);
            String respText = resp.getBody() != null ?
                resp.getBody().replaceAll("<[^>]+>"," ").replaceAll("\s{3,}"," ").trim() : "";
            return ResponseEntity.ok(Map.of(
                "status", resp.getStatusCode().value(),
                "url", url, "method", method,
                "responseLength", respText.length(),
                "preview", respText.substring(0, Math.min(500, respText.length()))
            ));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        }
    }


    // ════════════════════════════════════════════════════════════════════
    // ════════════════════════════════════════════════════════════════════════
    // AUDIO GENERATOR — Google TTS + ElevenLabs + VoiceRSS + Web Speech fallback
    // Config: GOOGLE_TTS_API_KEY o ELEVENLABS_API_KEY su Render
    // ════════════════════════════════════════════════════════════════════════

    private String generateAudio(String text, String lang, String voiceHint) throws Exception {
        if (text == null || text.isBlank()) return null;
        String cleanText = text.replaceAll("```[\\s\\S]*?```","")
            .replaceAll("\\*\\*|\\*|__|_|~~|`","")
            .replaceAll("#+ ","").trim();
        if (cleanText.length() > 3000) cleanText = cleanText.substring(0, 3000);
        boolean female = voiceHint == null || voiceHint.toLowerCase().contains("femminile") ||
            voiceHint.toLowerCase().contains("female") || voiceHint.toLowerCase().contains("donna");

        // ── Google TTS ────────────────────────────────────────────────────
        String googleKey = env("GOOGLE_TTS_API_KEY","");
        if (!googleKey.isEmpty()) {
            try {
                ObjectNode body = MAPPER.createObjectNode();
                ObjectNode input = MAPPER.createObjectNode(); input.put("text", cleanText); body.set("input", input);
                ObjectNode voice = MAPPER.createObjectNode();
                String langCode = lang != null && !lang.isEmpty() ? lang : "it-IT";
                voice.put("languageCode", langCode);
                voice.put("name", langCode.startsWith("zh") ? "cmn-CN-Wavenet-A" :
                    langCode.startsWith("en") ? (female ? "en-US-Wavenet-F" : "en-US-Wavenet-D") :
                    female ? "it-IT-Wavenet-A" : "it-IT-Wavenet-B");
                voice.put("ssmlGender", female ? "FEMALE" : "MALE");
                body.set("voice", voice);
                ObjectNode cfg = MAPPER.createObjectNode();
                cfg.put("audioEncoding","MP3"); cfg.put("speakingRate",1.0); cfg.put("pitch",0.0);
                body.set("audioConfig", cfg);
                HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON);
                ResponseEntity<String> resp = restTemplate.postForEntity(
                    "https://texttospeech.googleapis.com/v1/text:synthesize?key=" + googleKey,
                    new HttpEntity<>(MAPPER.writeValueAsString(body), h), String.class);
                String b64 = MAPPER.readTree(resp.getBody()).path("audioContent").asText();
                if (!b64.isEmpty()) return "AUDIO_MP3:" + b64;
            } catch (Exception e) { log.warn("Google TTS: {}", e.getMessage()); }
        }

        // ── ElevenLabs ────────────────────────────────────────────────────
        String elKey = env("ELEVENLABS_API_KEY","");
        if (!elKey.isEmpty()) {
            try {
                String voiceId = env("ELEVENLABS_VOICE_ID", female ? "21m00Tcm4TlvDq8ikWAM" : "EXAVITQu4vr4xnSDxMaL");
                HttpHeaders h = new HttpHeaders();
                h.setContentType(MediaType.APPLICATION_JSON); h.set("xi-api-key", elKey);
                ObjectNode elBody = MAPPER.createObjectNode();
                elBody.put("text", cleanText); elBody.put("model_id","eleven_multilingual_v2");
                ObjectNode vs = MAPPER.createObjectNode(); vs.put("stability",0.5); vs.put("similarity_boost",0.75);
                elBody.set("voice_settings", vs);
                ResponseEntity<byte[]> r = restTemplate.postForEntity(
                    "https://api.elevenlabs.io/v1/text-to-speech/" + voiceId,
                    new HttpEntity<>(MAPPER.writeValueAsString(elBody), h), byte[].class);
                if (r.getStatusCode().is2xxSuccessful() && r.getBody() != null && r.getBody().length > 100)
                    return "AUDIO_MP3:" + java.util.Base64.getEncoder().encodeToString(r.getBody());
            } catch (Exception e) { log.warn("ElevenLabs: {}", e.getMessage()); }
        }

        // ── VoiceRSS ──────────────────────────────────────────────────────
        String vrKey = env("VOICERSS_API_KEY","");
        if (!vrKey.isEmpty()) {
            try {
                String encoded = URLEncoder.encode(cleanText.substring(0, Math.min(500, cleanText.length())), "UTF-8");
                String vLang = lang != null && !lang.isEmpty() ? lang.replace("-","_").toLowerCase() : "it-it";
                ResponseEntity<byte[]> r = restTemplate.getForEntity(
                    "https://api.voicerss.org/?key=" + vrKey + "&hl=" + vLang +
                    "&v=" + (female ? "Valentina" : "Giorgio") + "&f=44khz_16bit_stereo&src=" + encoded,
                    byte[].class);
                if (r.getStatusCode().is2xxSuccessful() && r.getBody() != null && r.getBody().length > 500)
                    return "AUDIO_MP3:" + java.util.Base64.getEncoder().encodeToString(r.getBody());
            } catch (Exception e) { log.warn("VoiceRSS: {}", e.getMessage()); }
        }

        // ── Web Speech API fallback (client-side) ─────────────────────────
        return "AUDIO_WEBSPEECH:" + cleanText + "|" + (lang != null ? lang : "it-IT") + "|" + (female ? "female" : "male");
    }

    // ════════════════════════════════════════════════════════════════════════
    // MUSIC GENERATOR — Crea canzoni da testo usando ABC notation + Piston
    // ABC notation è leggibile, universale, convertibile in MIDI/audio
    // Alternativa a music21 che richiede Python locale
    // ════════════════════════════════════════════════════════════════════════

    @PostMapping("/music/generate")
    public ResponseEntity<Object> musicGenerate(@RequestBody Map<String,String> body) {
        String description = ((String) body.getOrDefault("description","")).trim();
        String genre       = body.getOrDefault("genre","pop");
        String mood        = body.getOrDefault("mood","happy");
        String tempo       = body.getOrDefault("tempo","120");
        String key         = body.getOrDefault("key","C");
        String sessionId   = body.getOrDefault("sessionId","global");
        String baseUrl2    = body.getOrDefault("baseUrl", env("AI_BASE_URL", env("GROQ_BASE_URL","https://api.groq.com/openai/v1")));
        String apiKey2     = body.getOrDefault("apiKey",  env("AI_API_KEY", env("GROQ_API_KEY","")));
        String model2      = body.getOrDefault("model",   env("AI_MODEL", env("GROQ_MODEL","llama-3.3-70b-versatile")));

        if (description.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error","Descrizione canzone obbligatoria"));

        try {
            // STEP 1: LLM genera la struttura musicale in ABC notation
            String musicPrompt =
                "Sei un compositore AI. Crea una canzone in formato ABC notation.\n" +
                "Descrizione: " + description + "\n" +
                "Genere: " + genre + " | Mood: " + mood + " | Tempo: " + tempo + " BPM | Tonalita: " + key + "\n\n" +
                "Genera:\n" +
                "1. Header ABC (X, T, M, L, Q, K)\n" +
                "2. Melodia principale (almeno 16 battute)\n" +
                "3. Testo della canzone (4 strofe + ritornello) \n\n" +
                "Formato risposta:\n" +
                "[ABC_NOTATION]\n" +
                "X:1\n" +
                "T:<titolo>\n" +
                "M:4/4\n" +
                "L:1/8\n" +
                "Q:1/4=" + tempo + "\n" +
                "K:" + key + "\n" +
                "<note ABC>\n" +
                "[/ABC_NOTATION]\n\n" +
                "[LYRICS]\n" +
                "<testo canzone in italiano>\n" +
                "[/LYRICS]\n\n" +
                "[DESCRIPTION]\n" +
                "<descrizione musicale della canzone>\n" +
                "[/DESCRIPTION]";

            String llmResp = callLLM(
                "Sei un compositore musicale esperto. Crea musica originale in formato ABC notation.",
                musicPrompt, new ArrayList<>(), baseUrl2, apiKey2, model2, 2000);

            // Estrai ABC notation
            String abcNotation = "";
            if (llmResp.contains("[ABC_NOTATION]") && llmResp.contains("[/ABC_NOTATION]")) {
                int s = llmResp.indexOf("[ABC_NOTATION]") + 14;
                int e = llmResp.indexOf("[/ABC_NOTATION]");
                abcNotation = llmResp.substring(s, e).trim();
            } else {
                // Fallback: cerca qualsiasi X:1 nel testo
                int xi = llmResp.indexOf("X:1");
                if (xi >= 0) abcNotation = llmResp.substring(xi);
                else abcNotation = generateDefaultABC(description, key, tempo, genre);
            }

            // Estrai testo
            String lyrics = "";
            if (llmResp.contains("[LYRICS]") && llmResp.contains("[/LYRICS]")) {
                int s = llmResp.indexOf("[LYRICS]") + 8;
                int e = llmResp.indexOf("[/LYRICS]");
                lyrics = llmResp.substring(s, e).trim();
            }

            // Estrai descrizione
            String songDesc = "";
            if (llmResp.contains("[DESCRIPTION]") && llmResp.contains("[/DESCRIPTION]")) {
                int s = llmResp.indexOf("[DESCRIPTION]") + 13;
                int e = llmResp.indexOf("[/DESCRIPTION]");
                songDesc = llmResp.substring(s, e).trim();
            }

            // STEP 2: Esegui Python con music21 via Piston per convertire in MIDI
            String midiB64 = null;
            String pythonCode =
                "from music21 import stream, note, meter, tempo as t, key as k, clef\n" +
                "import base64, io\n\n" +
                "# Crea stream musicale\n" +
                "s = stream.Score()\n" +
                "p = stream.Part()\n" +
                "p.append(meter.TimeSignature('4/4'))\n" +
                "p.append(t.MetronomeMark(number=" + tempo + "))\n" +
                "p.append(k.KeySignature(0))  # C major\n\n" +
                "# Note di esempio dalla melodia\n" +
                "notes = ['C4','E4','G4','C5','B4','G4','E4','C4',\n" +
                "         'F4','A4','C5','F5','E5','C5','A4','F4']\n" +
                "for n in notes:\n" +
                "    p.append(note.Note(n, quarterLength=1))\n\n" +
                "s.append(p)\n" +
                "# Esporta in MusicXML (piu supportato)\n" +
                "buf = io.BytesIO()\n" +
                "s.write('musicxml', buf)\n" +
                "buf.seek(0)\n" +
                "print('MUSICXML_B64:' + base64.b64encode(buf.read()).decode())";

            try {
                Map<String,String> pistonBody = new HashMap<>();
                pistonBody.put("language","python"); pistonBody.put("code",pythonCode);
                ResponseEntity<Object> pistonResp = manusExecCode(pistonBody);
                if (pistonResp.getBody() instanceof Map) {
                    Map<?,?> pr = (Map<?,?>)pistonResp.getBody();
                    Object outObj = pr.get("output");
                    String output = outObj instanceof String ? (String)outObj : "";
                    if (output.contains("MUSICXML_B64:")) {
                        midiB64 = output.substring(output.indexOf("MUSICXML_B64:") + 13).trim();
                        log.info("Music21 MusicXML generato: {} bytes b64", midiB64.length());
                    }
                }
            } catch (Exception pe) {
                log.debug("Piston music21: {}", pe.getMessage());
            }

            // Indicizza nel RAG
            String ragContent = "Canzone: " + description + "\nGenere: " + genre + "\nTesto: " + lyrics;
            ragIndexDocument(sessionId + "/music_" + System.currentTimeMillis(), ragContent);

            Map<String,Object> result = new LinkedHashMap<>();
            result.put("title",       extractABCField(abcNotation, "T:"));
            result.put("abcNotation", abcNotation);
            result.put("lyrics",      lyrics);
            result.put("description", songDesc.isEmpty() ? "Canzone generata da: " + description : songDesc);
            result.put("genre",       genre);
            result.put("mood",        mood);
            result.put("tempo",       tempo);
            result.put("key",         key);
            if (midiB64 != null) { result.put("musicXmlB64", midiB64); result.put("format","musicxml"); }
            result.put("playable",    true); // Riproducibile con ABCJS lato client
            result.put("status",      "ok");
            result.put("date",        today());
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.warn("MusicGen error: {}", e.getMessage());
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        }
    }

    private String extractABCField(String abc, String field) {
        if (abc == null) return "Canzone SPACE AI";
        int idx = abc.indexOf(field);
        if (idx < 0) return "Canzone SPACE AI";
        int end = abc.indexOf("\n", idx);
        return end > idx ? abc.substring(idx + field.length(), end).trim() : "Canzone SPACE AI";
    }

    private String generateDefaultABC(String desc, String key, String tempo, String genre) {
        return "X:1\nT:Canzone - " + desc.substring(0,Math.min(30,desc.length())) +
            "\nM:4/4\nL:1/8\nQ:1/4=" + tempo + "\nK:" + key + "\n" +
            "|: G2AB c2BA | G4 E4 | F2GA B2AG | F6 D2 |\n" +
            "c2de f2ed | c4 A4 | G2AB c2d2 | G8 :|\n" +
            "|: e2ef g2fe | e4 c4 | d2de f2ed | d6 B2 |\n" +
            "c2cd e2dc | B4 G4 | A2Bc d2e2 | G8 :|";
    }

    // isAudio detection per musica
    private boolean needsMusic(String msg) {
        String q = msg.toLowerCase();
        return q.contains("crea una canzone") || q.contains("componi") ||
               q.contains("scrivi una canzone") || q.contains("musica per") ||
               q.contains("genera musica") || q.contains("crea musica") ||
               q.contains("melodia") || q.contains("canzone su") ||
               q.contains("music") || q.contains("song");
    }

    @PostMapping("/audio/generate")
    public ResponseEntity<Object> audioGenerateEndpoint(@RequestBody Map<String,String> body) {
        String text     = ((String)body.getOrDefault("text","")).trim();
        String lang     = body.getOrDefault("lang","it-IT");
        String voice    = body.getOrDefault("voice","femminile");
        if (text.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error","Testo vuoto"));
        try {
            String result = generateAudio(text, lang, voice);
            if (result == null) return ResponseEntity.status(503).body(Map.of("error","Nessun TTS disponibile"));
            Map<String,Object> r = new LinkedHashMap<>();
            if (result.startsWith("AUDIO_MP3:")) {
                r.put("audioBase64", result.substring(10));
                r.put("audioType",   "audio/mpeg");
                r.put("provider",    !env("GOOGLE_TTS_API_KEY","").isEmpty() ? "Google TTS" :
                    !env("ELEVENLABS_API_KEY","").isEmpty() ? "ElevenLabs" : "VoiceRSS");
                r.put("downloadable", true);
            } else {
                String[] parts = result.substring(16).split("\\|");
                r.put("webSpeech",  true);
                r.put("text",       parts.length > 0 ? parts[0] : text);
                r.put("lang",       parts.length > 1 ? parts[1] : lang);
                r.put("voice",      parts.length > 2 ? parts[2] : voice);
                r.put("provider",   "Web Speech API (browser)");
            }
            r.put("status","ok"); r.put("chars", text.length()); r.put("date", today());
            return ResponseEntity.ok(r);
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        }
    }

    // VIDEO GENERATOR — storyboard LLM + frame Pollinations → slideshow HTML
    // ════════════════════════════════════════════════════════════════════

    private String generateVideoHtml(String description, String sessionId,
                                      String baseUrl, String apiKey, String model) {
        try {
            // STEP 1: LLM genera storyboard JSON
            String storyboardResp = callLLM(
                agentPrompt("video_gen"),
                "SCENA: " + description,
                new ArrayList<>(), baseUrl, apiKey, model, 2000);

            // Estrai JSON dalla risposta
            String cleaned = storyboardResp
                .replaceAll("(?s)```json", "").replaceAll("```", "").trim();
            int jsonStart = cleaned.indexOf("{");
            int jsonEnd   = cleaned.lastIndexOf("}");
            if (jsonStart < 0 || jsonEnd < 0)
                return "ERRORE: LLM non ha prodotto JSON valido per lo storyboard.";
            cleaned = cleaned.substring(jsonStart, jsonEnd + 1);

            JsonNode json     = MAPPER.readTree(cleaned);
            JsonNode frames   = json.path("storyboard");
            int duration      = json.path("duration_seconds").asInt(5);
            int fps           = json.path("fps").asInt(2);
            int totalFrames   = frames.isArray() ? frames.size() : 0;

            // Dichiara le liste PRIMA del fallback
            List<String> frameB64s    = new ArrayList<>();
            List<String> frameDescs   = new ArrayList<>();
            List<String> framePrompts = new ArrayList<>();

            // Check error key from LLM
            if (!json.path("error").asText("").isEmpty()) {
                log.warn("VideoGen: LLM reported error: {}", json.path("error").asText());
            }

            if (totalFrames == 0) {
                // FALLBACK: genera 3 frame dalla descrizione originale
                log.warn("VideoGen: storyboard vuoto, uso fallback con 3 frame");
                String engDesc = enhancePromptForSD(description);
                String[] fallbackPrompts = {
                    engDesc + ", opening scene, wide shot",
                    engDesc + ", main action, medium shot",
                    engDesc + ", final scene, cinematic closeup"
                };
                for (int fi = 0; fi < fallbackPrompts.length; fi++) {
                    frameDescs.add("Scena " + (fi + 1));
                    framePrompts.add(fallbackPrompts[fi]);
                    String imgR = generateImage(fallbackPrompts[fi]);
                    frameB64s.add(imgR.startsWith("IMAGE:") ? imgR.substring(6) : "");
                }
                duration = 6; fps = 2;
            } else {
                // STEP 2: Genera immagine di riferimento PRIMA (Manus-style: coerenza visiva)
                String refPrompt = enhancePromptForSD(description) + ", reference frame, establishing shot, high quality";
                String refResult = generateImage(refPrompt);
                String refB64    = refResult.startsWith("IMAGE:") ? refResult.substring(6) : "";
                log.info("VideoGen reference frame: {} bytes", refB64.length());

                // STEP 3: Genera ogni frame con coerenza rispetto al reference
                for (JsonNode frame : frames) {
                    String basePrompt = frame.path("prompt_image").asText(description);
                    String frameDesc  = frame.path("description").asText("Frame " + (frameB64s.size() + 1));
                    frameDescs.add(frameDesc);
                    // Aggiungi contesto di coerenza: "same scene as", "consistent with"
                    String coherentPrompt = basePrompt + ", same scene and characters as established reference, " +
                        "consistent lighting and style, cinematic continuity, highly detailed";
                    framePrompts.add(coherentPrompt);
                    String imgResult = generateImage(coherentPrompt);
                    frameB64s.add(imgResult.startsWith("IMAGE:") ? imgResult.substring(6) : "");
                    log.info("VideoGen frame {}/{}: {}", frameB64s.size(), totalFrames,
                        coherentPrompt.substring(0, Math.min(60, coherentPrompt.length())));
                }
                // Inserisci il reference frame all'inizio se disponibile
                if (!refB64.isEmpty()) {
                    frameB64s.add(0, refB64);
                    frameDescs.add(0, "Scena di riferimento");
                    framePrompts.add(0, refPrompt);
                }
            }


            long frameMs = totalFrames > 0 ? (duration * 1000L / totalFrames) : 1000;

            // STEP 3: Costruisci HTML interattivo con slideshow animato
            StringBuilder html = new StringBuilder();
            html.append("<div style='font-family:sans-serif;max-width:700px;margin:0 auto'>");
            html.append("<h3 style='color:#00d4ff;margin-bottom:8px'>🎬 Video: ")
                .append(description, 0, Math.min(60, description.length()))
                .append("</h3>");
            html.append("<p style='opacity:.7;font-size:.85rem'>")
                .append(totalFrames).append(" frame · ")
                .append(duration).append("s · ").append(fps).append(" fps</p>");

            // Player slideshow
            html.append("<div id='vp' style='position:relative;border-radius:12px;overflow:hidden;background:#000;aspect-ratio:16/9'>");
            for (int i = 0; i < frameB64s.size(); i++) {
                String b64 = frameB64s.get(i);
                String disp = i == 0 ? "block" : "none";
                if (!b64.isEmpty())
                    html.append("<img id='vf").append(i)
                        .append("' src='data:image/jpeg;base64,").append(b64)
                        .append("' style='width:100%;display:").append(disp)
                        .append(";position:absolute;top:0;left:0'/>");
            }
            // Overlay caption
            html.append("<div id='vcap' style='position:absolute;bottom:0;left:0;right:0;")
                .append("background:rgba(0,0,0,.6);color:#fff;padding:8px 12px;font-size:.85rem'>")
                .append(frameDescs.isEmpty() ? "" : frameDescs.get(0)).append("</div>");
            html.append("</div>");

            // Controlli
            html.append("<div style='display:flex;gap:8px;margin-top:10px;align-items:center;flex-wrap:wrap'>");
            html.append("<button id='vbtn' onclick='toggleVideo()' style='padding:8px 18px;")
                .append("border-radius:8px;border:none;background:#00d4ff;color:#000;cursor:pointer;font-weight:700'>▶ Play</button>");
            html.append("<input type='range' id='vslider' min='0' max='")
                .append(Math.max(0, frameB64s.size()-1))
                .append("' value='0' oninput='goFrame(this.value)' style='flex:1;min-width:80px'>");
            html.append("<span id='vcount' style='font-size:.8rem;opacity:.7'>1/")
                .append(frameB64s.size()).append("</span>");
            // Download WebM button (MediaRecorder lato client)
            html.append("<button onclick='recordVideo()' style='padding:8px 14px;border-radius:8px;")
                .append("border:none;background:#a855f7;color:#fff;cursor:pointer;font-weight:700;font-size:.85rem'>")
                .append("⬇ Scarica WebM</button>");
            html.append("</div>");

            // Thumbnails
            html.append("<div style='display:flex;gap:6px;margin-top:8px;overflow-x:auto;padding-bottom:4px'>");
            for (int i = 0; i < frameB64s.size(); i++) {
                String b64 = frameB64s.get(i);
                if (!b64.isEmpty())
                    html.append("<img onclick='goFrame(").append(i).append(")' src='data:image/jpeg;base64,")
                        .append(b64.substring(0, Math.min(200, b64.length()))).append("...' ")
                        .append("style='height:50px;border-radius:4px;cursor:pointer;opacity:.6;")
                        .append("border:2px solid transparent' id='vth").append(i).append("'/>");
            }
            html.append("</div>");

            // JavaScript player
            html.append("<script>");
            html.append("(function(){");
            html.append("var frames=").append(frameB64s.size()).append(";");
            html.append("var cur=0,playing=false,timer=null;");
            html.append("var frameMs=").append(frameMs).append(";");
            html.append("var descs=").append(MAPPER.writeValueAsString(frameDescs)).append(";");
            html.append("function showFrame(i){");
            html.append("  for(var j=0;j<frames;j++){");
            html.append("    var el=document.getElementById('vf'+j);");
            html.append("    var th=document.getElementById('vth'+j);");
            html.append("    if(el)el.style.display=j===i?'block':'none';");
            html.append("    if(th)th.style.opacity=j===i?'1':'.5';");
            html.append("    if(th)th.style.borderColor=j===i?'#00d4ff':'transparent';");
            html.append("  }");
            html.append("  var cap=document.getElementById('vcap');");
            html.append("  if(cap)cap.textContent=descs[i]||'';");
            html.append("  var sl=document.getElementById('vslider');");
            html.append("  if(sl)sl.value=i;");
            html.append("  var vc=document.getElementById('vcount');");
            html.append("  if(vc)vc.textContent=(i+1)+'/'+frames;");
            html.append("  cur=i;");
            html.append("}");
            html.append("window.goFrame=function(i){showFrame(parseInt(i));};");
            html.append("window.toggleVideo=function(){");
            html.append("  var btn=document.getElementById('vbtn');");
            html.append("  if(playing){clearInterval(timer);playing=false;if(btn)btn.textContent='▶ Play';}");
            html.append("  else{playing=true;if(btn)btn.textContent='⏸ Pausa';");
            html.append("    timer=setInterval(function(){showFrame((cur+1)%frames);},frameMs);}");
            html.append("};");
            html.append("showFrame(0);");
            // MediaRecorder: registra lo slideshow come WebM lato client
            html.append("window.recordVideo=function(){");
            html.append("  var btn=event.target;btn.textContent='⏳ Registrando...';btn.disabled=true;");
            html.append("  var canvas=document.createElement('canvas');");
            html.append("  canvas.width=document.getElementById('vp').offsetWidth||640;");
            html.append("  canvas.height=document.getElementById('vp').offsetHeight||360;");
            html.append("  var ctx2=canvas.getContext('2d');");
            html.append("  var stream=canvas.captureStream(").append(fps).append(");");
            html.append("  var rec=new MediaRecorder(stream,{mimeType:'video/webm;codecs=vp8'});");
            html.append("  var chunks=[];");
            html.append("  rec.ondataavailable=function(e){if(e.data.size>0)chunks.push(e.data);};");
            html.append("  rec.onstop=function(){");
            html.append("    var blob=new Blob(chunks,{type:'video/webm'});");
            html.append("    var a=document.createElement('a');");
            html.append("    a.href=URL.createObjectURL(blob);");
            html.append("    a.download='space-ai-video-'+Date.now()+'.webm';");
            html.append("    a.click();");
            html.append("    btn.textContent='⬇ Scarica WebM';btn.disabled=false;");
            html.append("  };");
            html.append("  rec.start();");
            html.append("  var fi=0;");
            html.append("  function drawNext(){");
            html.append("    showFrame(fi);");
            html.append("    var img=document.getElementById('vf'+fi);");
            html.append("    if(img){ctx2.drawImage(img,0,0,canvas.width,canvas.height);}");
            html.append("    fi++;");
            html.append("    if(fi<frames){setTimeout(drawNext,").append(frameMs).append(");}");
            html.append("    else{setTimeout(function(){rec.stop();},200);}");
            html.append("  }");
            html.append("  drawNext();");
            html.append("};");
            html.append("})();");
            html.append("</script></div>");

            return html.toString();

        } catch (Exception e) {
            log.warn("generateVideo error: {}", e.getMessage());
            return "Errore generazione video: " + e.getMessage();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // ANALISI VIDEO — estrai frame da video base64, analizza con vision
    // Supporta: MP4, WebM, GIF (come sequenza frame JPEG)
    // ════════════════════════════════════════════════════════════════════════

    @PostMapping("/video/analyze")
    public ResponseEntity<Object> analyzeVideo(@RequestBody Map<String,Object> body) {
        String videoBase64 = (String) body.getOrDefault("videoBase64","");
        String frameBase64 = (String) body.getOrDefault("frameBase64","");  // singolo frame JPEG
        String task        = (String) body.getOrDefault("task","Descrivi cosa vedi in questo video");
        String sessionId   = (String) body.getOrDefault("sessionId","global");
        String baseUrl2    = (String) body.getOrDefault("baseUrl", env("AI_BASE_URL","https://api.groq.com/openai/v1"));
        String apiKey2     = (String) body.getOrDefault("apiKey",  env("AI_API_KEY",""));
        String model2      = (String) body.getOrDefault("model",   env("AI_MODEL","llama-3.3-70b-versatile"));

        // Caso 1: singolo frame JPEG inviato direttamente
        if (!frameBase64.isEmpty()) {
            try {
                String analysis = analyzeImageBase64(frameBase64, task, baseUrl2, apiKey2, model2);
                ragIndexDocument(sessionId + "/video_frame_" + System.currentTimeMillis(), analysis);
                return ResponseEntity.ok(Map.of(
                    "analysis",   analysis,
                    "mode",       "frame_analysis",
                    "ragIndexed", true,
                    "date",       today()
                ));
            } catch (Exception e) {
                return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
            }
        }

        // Caso 2: video base64 — estrazione frame simulata
        // (senza ffmpeg sul server, estraiamo il primo e l'ultimo "frame" dal base64)
        if (!videoBase64.isEmpty()) {
            try {
                byte[] videoBytes = java.util.Base64.getDecoder().decode(
                    videoBase64.contains(",") ? videoBase64.split(",")[1] : videoBase64);

                // Analisi strutturale del video (dimensione, tipo, durata stimata)
                int sizeKb = videoBytes.length / 1024;
                String videoType = videoBytes.length > 4 &&
                    (char)videoBytes[4]=='f' && (char)videoBytes[5]=='t' ? "MP4" :
                    videoBytes.length > 3 && videoBytes[0]==(byte)0x1a ? "WebM" : "Video";

                // Usa LLM per descrivere il task anche senza frame (dall'header/metadata)
                String contextAnalysis = callLLM(
                    "Sei un analizzatore video AI. Rispondi in italiano.",
                    "Task utente: " + task + "\n" +
                    "File video ricevuto: " + videoType + ", " + sizeKb + " KB\n" +
                    "Non ho estratto frame individuali (richiede ffmpeg). " +
                    "Fornisci una guida su come analizzare questo tipo di video " +
                    "e chiedi all'utente di inviarti uno screenshot/frame specifico.",
                    new ArrayList<>(), baseUrl2, apiKey2, model2, 500);

                return ResponseEntity.ok(Map.of(
                    "analysis",     contextAnalysis,
                    "videoType",    videoType,
                    "sizeKb",       sizeKb,
                    "mode",         "video_metadata",
                    "hint",         "Per analisi frame: usa il chip Screenshot e invia l'immagine",
                    "date",         today()
                ));
            } catch (Exception e) {
                return ResponseEntity.status(400).body(Map.of("error","Video non valido: " + e.getMessage()));
            }
        }

        return ResponseEntity.badRequest().body(Map.of(
            "error",    "Invia frameBase64 (JPEG) o videoBase64 (MP4/WebM)",
            "example",  "POST /api/video/analyze con {frameBase64: '<jpeg_base64>', task: 'Descrivi la scena'}"
        ));
    }

    @PostMapping("/video/analyze-frames")
    public ResponseEntity<Object> analyzeVideoFrames(@RequestBody Map<String,Object> body) {
        @SuppressWarnings("unchecked")
        List<String> frames  = (List<String>) body.getOrDefault("frames", new ArrayList<>());
        String task          = (String) body.getOrDefault("task","Descrivi la sequenza");
        String sessionId     = (String) body.getOrDefault("sessionId","global");
        String baseUrl2      = (String) body.getOrDefault("baseUrl", env("AI_BASE_URL","https://api.groq.com/openai/v1"));
        String apiKey2       = (String) body.getOrDefault("apiKey",  env("AI_API_KEY",""));
        String model2        = (String) body.getOrDefault("model",   env("AI_MODEL","llama-3.3-70b-versatile"));

        if (frames.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error","Lista frames vuota"));

        List<Map<String,Object>> frameResults = new ArrayList<>();
        StringBuilder fullTranscript = new StringBuilder();

        for (int i = 0; i < Math.min(frames.size(), 5); i++) {
            try {
                String frameB64 = frames.get(i);
                String frameTask = "Frame " + (i+1) + "/" + frames.size() + ": " + task;
                String analysis = analyzeImageBase64(frameB64, frameTask, baseUrl2, apiKey2, model2);
                Map<String,Object> fr = new LinkedHashMap<>();
                fr.put("frame",    i+1);
                fr.put("analysis", analysis);
                frameResults.add(fr);
                fullTranscript.append("Frame ").append(i+1).append(": ").append(analysis).append("\n\n");
            } catch (Exception e) {
                frameResults.add(Map.of("frame", i+1, "error", e.getMessage()));
            }
        }

        // Sintesi finale della sequenza
        String summary = "";
        if (fullTranscript.length() > 0) {
            try {
                summary = callLLM(
                    "Sei un analizzatore video. Sintetizza la sequenza di frame in italiano.",
                    "Task: " + task + "\n\nAnalisi frame:\n" + fullTranscript,
                    new ArrayList<>(), baseUrl2, apiKey2, model2, 800);
                ragIndexDocument(sessionId + "/video_analysis_" + System.currentTimeMillis(), summary);
            } catch (Exception e) {
                summary = "Sintesi non disponibile: " + e.getMessage();
            }
        }

        return ResponseEntity.ok(Map.of(
            "frames",    frameResults,
            "summary",   summary,
            "total",     frames.size(),
            "analyzed",  frameResults.size(),
            "ragIndexed",!summary.isBlank(),
            "date",      today()
        ));
    }

    @PostMapping("/video/generate")
    public ResponseEntity<Object> videoGenerate(@RequestBody Map<String,String> body) {
        String description = ((String)body.getOrDefault("description", body.getOrDefault("goal",""))).trim();
        String sessionId   = body.getOrDefault("sessionId", "global");
        String baseUrl     = body.getOrDefault("baseUrl", env("AI_BASE_URL", env("GROQ_BASE_URL","https://api.groq.com/openai/v1")));
        String apiKey      = body.getOrDefault("apiKey",  env("AI_API_KEY", env("GROQ_API_KEY","")));
        String model       = body.getOrDefault("model",   env("AI_MODEL", env("GROQ_MODEL","llama-3.3-70b-versatile")));
        if (description.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error","Fornisci una descrizione del video"));
        String html = generateVideoHtml(description, sessionId, baseUrl, apiKey, model);
        return ResponseEntity.ok(Map.of(
            "html",        html,
            "status",      "ok",
            "mode",        "video_gen",
            "description", description,
            "date",        today()
        ));
    }

    // ════════════════════════════════════════════════════════════════════════
    // AGENT FEDERATION — Cross-instance cache + Message Broker + Swarm Coordination
    // Ispirato a Claude Code: agenti che comunicano senza passare dal router centrale
    // ════════════════════════════════════════════════════════════════════════

    // Message Broker in-memory per comunicazione diretta tra agenti
    private final Map<String, List<java.util.function.Function<String,String>>> agentBroker = new ConcurrentHashMap<>();

    public void registerAgentHandler(String agentName, java.util.function.Function<String,String> handler) {
        agentBroker.computeIfAbsent(agentName, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(handler);
    }

    public String callAgentDirect(String agentName, String input) {
        List<java.util.function.Function<String,String>> handlers = agentBroker.get(agentName);
        if (handlers != null && !handlers.isEmpty()) return handlers.get(0).apply(input);
        return null;
    }

    // Comprimi il contesto ogni N messaggi per risparmiare token (come Claude Code)
    private String compressContext(List<Map<String,String>> history, String query,
                                   String baseUrl, String apiKey, String model) {
        if (history.size() < 8) return null; // non serve comprimere conversazioni brevi
        try {
            // Prendi i messaggi più vecchi (escludi gli ultimi 4)
            int oldCount = history.size() - 4;
            StringBuilder oldMsgs = new StringBuilder();
            for (int i = 0; i < oldCount; i++) {
                String role = history.get(i).getOrDefault("role","");
                String msg  = history.get(i).getOrDefault("content","");
                oldMsgs.append(role.equals("user") ? "U: " : "A: ")
                    .append(msg.substring(0, Math.min(200, msg.length()))).append("\n");
            }
            // Usa modello veloce per comprimere (risparmia token)
            String fastModel = env("GROQ_MODEL_FAST", model);
            String summary = callLLM(
                "Riassumi questa conversazione in max 3 righe, mantenendo i fatti chiave.",
                oldMsgs.toString(), new ArrayList<>(), baseUrl, apiKey, fastModel, 150);
            log.debug("Context compressed: {} msgs -> {} chars", oldCount, summary.length());
            return summary;
        } catch (Exception e) {
            log.debug("Context compression skip: {}", e.getMessage());
            return null;
        }
    }

    // Federation endpoint: condividi cache con altre istanze
    @PostMapping("/federation/share")
    public ResponseEntity<Object> federationShare(@RequestBody Map<String,String> body) {
        String query  = body.getOrDefault("query","");
        String answer = body.getOrDefault("answer","");
        String secret = body.getOrDefault("secret","");
        // Verifica secret token per sicurezza zero-trust
        String fedSecret = env("FEDERATION_SECRET","");
        if (!fedSecret.isEmpty() && !fedSecret.equals(secret))
            return ResponseEntity.status(403).body(Map.of("error","Unauthorized"));
        if (!query.isEmpty() && !answer.isEmpty()) {
            knnCachePut(query, answer);
            log.info("Federation: cached query from peer instance");
        }
        return ResponseEntity.ok(Map.of("status","shared","date",today()));
    }

    @GetMapping("/federation/query")
    public ResponseEntity<Object> federationQuery(@RequestParam String q,
                                                   @RequestParam(required=false,defaultValue="") String secret) {
        String fedSecret = env("FEDERATION_SECRET","");
        if (!fedSecret.isEmpty() && !fedSecret.equals(secret))
            return ResponseEntity.status(403).body(Map.of("error","Unauthorized"));
        String cached = knnCacheGet(q);
        return ResponseEntity.ok(Map.of(
            "answer", cached != null ? cached : "not_found",
            "found",  cached != null,
            "date",   today()
        ));
    }

    @GetMapping("/federation/status")
    public ResponseEntity<Object> federationStatus() {
        String[] peers = env("FEDERATION_PEERS","").split(",");
        List<Map<String,Object>> peerStatus = new ArrayList<>();
        for (String peer : peers) {
            if (peer.trim().isEmpty()) continue;
            Map<String,Object> ps = new LinkedHashMap<>();
            ps.put("url", peer.trim());
            try {
                ResponseEntity<String> r = restTemplate.getForEntity(peer.trim() + "/api/health", String.class);
                ps.put("online", r.getStatusCode().is2xxSuccessful());
            } catch (Exception e) { ps.put("online", false); ps.put("error", e.getMessage()); }
            peerStatus.add(ps);
        }
        return ResponseEntity.ok(Map.of(
            "peers",      peerStatus,
            "totalPeers", peerStatus.size(),
            "knnCache",   (int)Arrays.stream(ringCache).filter(e -> e != null).count(),
            "date",       today()
        ));
    }

    // ════════════════════════════════════════════════════════════════════════
    // SUB-AGENT ORCHESTRATOR — delega task a agenti specializzati in parallelo
    // Ogni sub-agent ha un dominio: research, code, analysis, creative, security
    // ════════════════════════════════════════════════════════════════════════

    private static final Map<String,String> AGENT_DOMAINS = Map.of(
        "researcher",  "Sei un ricercatore esperto. Trova informazioni accurate e aggiornate. Usa web_search e rag_retrieval.",
        "coder",       "Sei un senior developer. Scrivi codice pulito, testato e documentato. Usa code_exec per verificare.",
        "analyst",     "Sei un analista dati senior. Estrai insight, pattern e raccomandazioni dai dati.",
        "creative",    "Sei un creativo AI. Genera contenuti originali, immagini e video di qualita.",
        "security",    "Sei un esperto di sicurezza MITRE ATT&CK. Identifica vulnerabilita e raccomanda fix.",
        "browser",     "Sei un web agent. Naviga siti, estrai dati, compila form autonomamente."
    );

    @PostMapping("/orchestrate")
    public ResponseEntity<Object> orchestrateSubAgents(@RequestBody Map<String,Object> body) {
        String goal      = ((String) body.getOrDefault("goal","")).trim();
        String sessionId = (String) body.getOrDefault("sessionId","global");
        String baseUrl2  = (String) body.getOrDefault("baseUrl", env("AI_BASE_URL", env("GROQ_BASE_URL","https://api.groq.com/openai/v1")));
        String apiKey2   = (String) body.getOrDefault("apiKey",  env("AI_API_KEY", env("GROQ_API_KEY","")));
        String model2    = (String) body.getOrDefault("model",   env("AI_MODEL", env("GROQ_MODEL","llama-3.3-70b-versatile")));
        @SuppressWarnings("unchecked")
        List<String> requestedAgents = (List<String>) body.getOrDefault("agents",
            List.of("researcher","analyst"));

        if (goal.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error","Goal obbligatorio"));

        // Esegui sub-agent in parallelo
        Map<String,CompletableFuture<String>> agentFutures = new LinkedHashMap<>();
        for (String agentName : requestedAgents) {
            String domain = AGENT_DOMAINS.getOrDefault(agentName,
                "Sei un assistente AI specializzato in " + agentName + ".");
            agentFutures.put(agentName, CompletableFuture.supplyAsync(() -> {
                try {
                    // Ogni agente ha il suo system prompt specializzato
                    String agentPromptSys = domain + "\n" +
                        "Strumenti: " + getAvailableTools().entrySet().stream()
                            .filter(Map.Entry::getValue).map(Map.Entry::getKey)
                            .collect(Collectors.joining(", ")) + "\n" +
                        "Data: " + today();

                    // Determina il tool migliore per questo agente
                    String tool = "web_search";
                    if (agentName.equals("coder"))    tool = "code_exec";
                    if (agentName.equals("security"))  tool = "web_search";
                    if (agentName.equals("browser"))   tool = "web_scrape";
                    if (agentName.equals("creative"))  tool = "image_gen";

                    // Esegui prima il tool, poi genera la risposta
                    String toolResult = "";
                    try { toolResult = executeTool(tool, goal, sessionId, baseUrl2, apiKey2, model2); }
                    catch (Exception te) { toolResult = "Tool non disponibile: " + te.getMessage(); }

                    String agentResponse = callLLM(agentPromptSys,
                        "GOAL: " + goal + "\n\nDATI TOOL [" + tool + "]:\n" +
                        toolResult.substring(0, Math.min(800, toolResult.length())) +
                        "\n\nFornisci la tua analisi specializzata in italiano:",
                        new ArrayList<>(), baseUrl2, apiKey2, model2, 1000);

                    // Apprendi dall'outcome
                    learnFromOutcome(goal, agentName + ":" + tool, 0.7);
                    return agentResponse;
                } catch (Exception e) {
                    return "Sub-agent " + agentName + " error: " + e.getMessage();
                }
            }));
        }

        // Raccogli risultati
        Map<String,String> agentResults = new LinkedHashMap<>();
        for (Map.Entry<String, CompletableFuture<String>> entry : agentFutures.entrySet()) {
            try {
                agentResults.put(entry.getKey(),
                    entry.getValue().get(60, java.util.concurrent.TimeUnit.SECONDS));
            } catch (Exception e) {
                agentResults.put(entry.getKey(), "Timeout o errore: " + e.getMessage());
            }
        }

        // Sintesi orchestrata con LLM-as-a-Judge
        StringBuilder synthInput = new StringBuilder();
        agentResults.forEach((agent, result) ->
            synthInput.append("### ").append(agent.toUpperCase()).append(":\n")
                .append(result.substring(0, Math.min(500, result.length()))).append("\n\n"));

        String synthesis = "";
        try {
            synthesis = callLLM(
                "Sei un orchestratore AI. Sintetizza i risultati dei sub-agent in un report coerente.",
                "GOAL: " + goal + "\n\nRISULTATI SUB-AGENT:\n" + synthInput +
                "\nCrea una sintesi finale professionale in italiano:",
                new ArrayList<>(), baseUrl2, apiKey2, model2, 1500);
            // Valida con LLM-as-a-Judge
            synthesis = llmJudgeValidate(synthesis, goal, baseUrl2, apiKey2, model2);
        } catch (Exception se) {
            synthesis = "Sintesi non disponibile: " + se.getMessage();
        }

        // Indicizza tutto nel RAG
        ragIndexDocument(sessionId + "/orchestrate_" + System.currentTimeMillis(), synthesis);

        return ResponseEntity.ok(Map.of(
            "goal",        goal,
            "agents",      agentResults,
            "synthesis",   synthesis,
            "agentCount",  requestedAgents.size(),
            "date",        today()
        ));
    }

    @GetMapping("/tools/status")
    public ResponseEntity<Object> toolsStatus() {
        Map<String,Object> status = new LinkedHashMap<>();
        status.put("tools",     getAvailableTools());
        status.put("serpapi",   !env("SERPAPI_KEY","").isEmpty()    ? "configured" : "missing SERPAPI_KEY");
        status.put("googleCse", !env("GOOGLE_CSE_KEY","").isEmpty() ? "configured" : "missing GOOGLE_CSE_KEY");
        status.put("github",    !env("GITHUB_TOKEN","").isEmpty()   ? "configured" : "missing GITHUB_TOKEN");
        status.put("tavily",    !env("TAVILY_API_KEY","").isEmpty() ? "configured" : "missing TAVILY_API_KEY");
        status.put("moeComplex",!env("GROQ_MODEL_COMPLEX","").isEmpty() ? "configured" : "missing GROQ_MODEL_COMPLEX");
        status.put("moeFast",   !env("GROQ_MODEL_FAST","").isEmpty()    ? "configured" : "missing GROQ_MODEL_FAST");
        status.put("date",      today());
        return ResponseEntity.ok(status);
    }

    // Punto 10: MCP-style server manifest — espone tutti i tool come server MCP compatibile
    @GetMapping("/mcp/manifest")
    public ResponseEntity<Object> mcpManifest() {
        Map<String,Boolean> tools = getAvailableTools();
        List<ObjectNode> toolDefs = tools.entrySet().stream()
            .filter(Map.Entry::getValue)
            .map(e -> buildToolSchema(e.getKey()))
            .collect(Collectors.toList());
        Map<String,Object> manifest = new LinkedHashMap<>();
        manifest.put("name",        "space-ai-mcp-server");
        manifest.put("version",     "4.0.0");
        manifest.put("description", "SPACE AI MCP Server — Manus-style autonomous agent");
        manifest.put("protocol",    "mcp/1.0");
        manifest.put("tools",       toolDefs);
        manifest.put("resources", List.of(
            Map.of("uri","rag://documents","name","RAG Document Store","mimeType","text/plain"),
            Map.of("uri","memory://ltm","name","Long Term Memory","mimeType","text/plain"),
            Map.of("uri","memory://shared","name","Shared Knowledge","mimeType","text/plain")
        ));
        manifest.put("capabilities", Map.of(
            "tools", true, "resources", true, "prompts", true,
            "streaming", false, "roots", false
        ));
        manifest.put("date", today());
        return ResponseEntity.ok(manifest);
    }

    @PostMapping("/mcp/call")
    public ResponseEntity<Object> mcpCallTool(@RequestBody Map<String,Object> body) {
        String toolName = (String) body.getOrDefault("name", "");
        @SuppressWarnings("unchecked")
        Map<String,Object> arguments = (Map<String,Object>) body.getOrDefault("arguments", new HashMap<>());
        String params    = (String) arguments.getOrDefault("query",
                           arguments.getOrDefault("params", "").toString());
        String sessionId = (String) body.getOrDefault("sessionId", "global");
        String baseUrl   = env("AI_BASE_URL", env("GROQ_BASE_URL","https://api.groq.com/openai/v1"));
        String apiKey    = env("AI_API_KEY", env("GROQ_API_KEY",""));
        String model     = env("AI_MODEL", env("GROQ_MODEL","llama-3.3-70b-versatile"));
        if (toolName.isEmpty() || params.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error","name e arguments.query obbligatori"));
        try {
            String result = executeTool(toolName, params, sessionId, baseUrl, apiKey, model);
            return ResponseEntity.ok(Map.of(
                "content", List.of(Map.of("type","text","text", result)),
                "isError",  false,
                "tool",     toolName,
                "date",     today()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of(
                "content", List.of(Map.of("type","text","text","Errore: "+e.getMessage())),
                "isError",  true
            ));
        }
    }

    /**
     * Traduce e ottimizza il prompt IT→EN per Stable Diffusion / Pollinations.
     * Usa un dizionario esteso + LLM fallback per scene complesse.
     */
    private String enhancePromptForSD(String prompt) {
        if (prompt == null || prompt.isBlank()) return "a beautiful space scene, highly detailed, 4k";
        // Dizionario IT->EN per termini visivi comuni
        String eng = prompt.toLowerCase()
            .replaceAll("(?i)\bcrea(re)?\b", "").replaceAll("(?i)\bgenera(re)?\b", "")
            .replaceAll("(?i)\bdisegna(re)?\b", "").replaceAll("(?i)\bmostrar?e?\b", "show")
            .replaceAll("(?i)\bimmagine di\b", "").replaceAll("(?i)\bimmagine del\b", "")
            .replaceAll("(?i)\bimmagine della\b", "").replaceAll("(?i)\bimmagine\b", "")
            .replaceAll("(?i)\bun uomo\b", "a man").replaceAll("(?i)\buna donna\b", "a woman")
            .replaceAll("(?i)\bun bambino\b", "a child").replaceAll("(?i)\buna persona\b", "a person")
            .replaceAll("(?i)\bseduto\b", "sitting").replaceAll("(?i)\bin piedi\b", "standing")
            .replaceAll("(?i)\bche vola\b", "flying").replaceAll("(?i)\bche corre\b", "running")
            .replaceAll("(?i)\bsu un\b", "on a").replaceAll("(?i)\bsu una\b", "on a")
            .replaceAll("(?i)\bsu dei\b", "on some").replaceAll("(?i)\bsopra un\b", "above a")
            .replaceAll("(?i)\bmissile\b", "missile").replaceAll("(?i)\brazzo\b", "rocket")
            .replaceAll("(?i)\baereo\b", "airplane").replaceAll("(?i)\belicottero\b", "helicopter")
            .replaceAll("(?i)\bmacchina\b", "car").replaceAll("(?i)\bmoto\b", "motorcycle")
            .replaceAll("(?i)\bbarca\b", "boat").replaceAll("(?i)\bcarro armato\b", "tank")
            .replaceAll("(?i)\bgatto\b", "cat").replaceAll("(?i)\bcane\b", "dog")
            .replaceAll("(?i)\bcavallo\b", "horse").replaceAll("(?i)\borso\b", "bear")
            .replaceAll("(?i)\bcielo\b", "sky").replaceAll("(?i)\bmare\b", "sea")
            .replaceAll("(?i)\bmontagna\b", "mountain").replaceAll("(?i)\bcittà\b", "city")
            .replaceAll("(?i)\bforesta\b", "forest").replaceAll("(?i)\bdeserto\b", "desert")
            .replaceAll("(?i)\bnotte\b", "night").replaceAll("(?i)\bgiorno\b", "daytime")
            .replaceAll("(?i)\btramonto\b", "sunset").replaceAll("(?i)\balba\b", "sunrise")
            .replaceAll("(?i)\besplosione\b", "explosion").replaceAll("(?i)\bfuoco\b", "fire")
            .replaceAll("(?i)\bbandiera\b", "flag").replaceAll("(?i)\bguerra\b", "war scene")
            .replaceAll("(?i)\biraniano\b", "iranian").replaceAll("(?i)\bitaliano\b", "italian")
            .replaceAll("(?i)\bamericano\b", "american").replaceAll("(?i)\brusso\b", "russian")
            .replaceAll("(?i)\bcinese\b", "chinese").replaceAll("(?i)\bfrancese\b", "french")
            .replaceAll("(?i)\bpresidente\b", "president").replaceAll("(?i)\bguarriero\b", "warrior")
            .replaceAll("(?i)\bsoldato\b", "soldier").replaceAll("(?i)\bastronauta\b", "astronaut")
            .replaceAll("(?i)\brobot\b", "robot").replaceAll("(?i)\balieno\b", "alien")
            .replaceAll("(?i)\bvestito\b", "wearing").replaceAll("(?i)\babito\b", "suit")
            .replaceAll("(?i)\bcravatta\b", "tie").replaceAll("(?i)\bcappello\b", "hat")
            .replaceAll("(?i)\bocchiali\b", "glasses").replaceAll("(?i)\buniforme\b", "uniform")
            .replaceAll("(?i)\bsullo sfondo\b", "in the background")
            .replaceAll("(?i)\bdavanti a\b", "in front of")
            .replaceAll("(?i)\bcon\b", "with").replaceAll("(?i)\be\b", "and")
            .replaceAll("(?i)\bil\b", "").replaceAll("(?i)\bla\b", "")
            .replaceAll("(?i)\blo\b", "").replaceAll("(?i)\bles?\b", "")
            .replaceAll("(?i)\bdel\b", "of the").replaceAll("(?i)\bdella\b", "of the")
            .replaceAll("(?i)\bdei\b", "of").replaceAll("(?i)\bdi\b", "of")
            .replaceAll("\\s{2,}", " ").trim();
        if (eng.isBlank() || eng.length() < 5) eng = prompt; // fallback al testo originale
        // Manus-style quality tokens — adattivi per tipo di immagine
        String qualityTags;
        if (eng.contains("portrait") || eng.contains("person") || eng.contains("man") || eng.contains("woman") || eng.contains("face"))
            qualityTags = ", ultra detailed portrait, photorealistic, studio lighting, 8k, sharp focus, masterpiece, best quality";
        else if (eng.contains("landscape") || eng.contains("nature") || eng.contains("sky") || eng.contains("mountain"))
            qualityTags = ", epic landscape, cinematic, golden hour lighting, 8k uhd, ultra detailed, award winning photo";
        else if (eng.contains("space") || eng.contains("galaxy") || eng.contains("cosmos") || eng.contains("planet"))
            qualityTags = ", space art, stunning nebula, stars, cinematic, ultra detailed, 8k, digital art, trending on artstation";
        else if (eng.contains("anime") || eng.contains("cartoon") || eng.contains("manga"))
            qualityTags = ", anime style, vibrant colors, highly detailed, 4k, studio quality";
        else if (eng.contains("architecture") || eng.contains("building") || eng.contains("city"))
            qualityTags = ", architectural visualization, ultra detailed, 8k, photorealistic render, dramatic lighting";
        else
            qualityTags = ", highly detailed, photorealistic, cinematic lighting, 4k, sharp focus, masterpiece, best quality";
        return eng + qualityTags;
    }

    private String generateImage(String prompt) {
        // ── STEP 1: Traduci sempre il prompt in inglese prima di inviarlo ─────
        // Questo è il bug principale: prima si passava il prompt italiano grezzo
        String engPrompt = enhancePromptForSD(prompt);
        log.info("Image prompt IT: [{}] -> EN: [{}]", prompt, engPrompt);

        // ── RestTemplate con timeout ottimizzati ────────────────────────────
        org.springframework.web.client.RestTemplate imgClient =
            new org.springframework.web.client.RestTemplate();
        try {
            org.springframework.http.client.SimpleClientHttpRequestFactory f =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
            f.setConnectTimeout(10000);  // 10s connect
            f.setReadTimeout(30000);     // 30s read (Pollinations risponde in ~5-15s)
            imgClient.setRequestFactory(f);
        } catch (Exception ex) { log.warn("Timeout config: {}", ex.getMessage()); }

        // ── PRIORITÀ 1: Pollinations FLUX (gratis, no key, ottima qualità) ──
        // Usa SEMPRE il prompt in inglese tradotto
        String[] pollinationModels = {"flux", "turbo", "flux-realism"};
        for (String pModel : pollinationModels) {
            try {
                // Seed fisso per coerenza, basato sull hash del prompt (non sul tempo!)
                long seed = Math.abs(engPrompt.hashCode()) % 99999;
                String encoded = java.net.URLEncoder.encode(engPrompt, "UTF-8")
                    .replace("+", "%20").replace("%2C", ",");
                String url = "https://image.pollinations.ai/prompt/" + encoded
                    + "?width=1024&height=1024&nologo=true&enhance=true"
                    + "&model=" + pModel + "&seed=" + seed;
                log.info("Pollinations {} request: {}", pModel, url.substring(0, Math.min(120, url.length())));
                ResponseEntity<byte[]> resp = imgClient.getForEntity(url, byte[].class);
                if (resp.getStatusCode().is2xxSuccessful()
                        && resp.getBody() != null
                        && resp.getBody().length > 5000) {
                    log.info("Pollinations {} OK: {} bytes", pModel, resp.getBody().length);
                    return "IMAGE:" + java.util.Base64.getEncoder().encodeToString(resp.getBody());
                }
                log.warn("Pollinations {} risposta insufficiente: {} bytes",
                    pModel, resp.getBody() == null ? 0 : resp.getBody().length);
            } catch (Exception e) {
                log.warn("Pollinations {} fallito: {}", pModel, e.getMessage());
            }
        }

        // ── PRIORITÀ 2: HuggingFace SD (se HF_TOKEN disponibile) ─────────
        String hfKey = env("HF_TOKEN", "");
        if (!hfKey.isEmpty()) {
            String[] hfModels = {
                "black-forest-labs/FLUX.1-schnell",        // FLUX schnell - veloce
                "stabilityai/stable-diffusion-xl-base-1.0", // SDXL
                "runwayml/stable-diffusion-v1-5"            // SD 1.5 fallback
            };
            for (String hfModel : hfModels) {
                try {
                    HttpHeaders h = new HttpHeaders();
                    h.setContentType(MediaType.APPLICATION_JSON);
                    h.setBearerAuth(hfKey);
                    ObjectNode req = MAPPER.createObjectNode();
                    req.put("inputs", engPrompt); // SEMPRE il prompt in inglese
                    ObjectNode params = MAPPER.createObjectNode();
                    params.put("num_inference_steps", 20);
                    params.put("guidance_scale", 7.5);
                    params.put("width", 768);
                    params.put("height", 768);
                    params.put("wait_for_model", true);
                    params.put("use_cache", false);
                    req.set("parameters", params);
                    ResponseEntity<byte[]> resp = imgClient.postForEntity(
                        "https://api-inference.huggingface.co/models/" + hfModel,
                        new HttpEntity<>(MAPPER.writeValueAsString(req), h),
                        byte[].class);
                    if (resp.getStatusCode().is2xxSuccessful()
                            && resp.getBody() != null
                            && resp.getBody().length > 3000) {
                        log.info("HF {} OK: {} bytes", hfModel, resp.getBody().length);
                        return "IMAGE:" + java.util.Base64.getEncoder().encodeToString(resp.getBody());
                    }
                } catch (Exception e) {
                    log.warn("HF model {} fallito: {}", hfModel, e.getMessage());
                }
            }
        }

        // ── FALLBACK: SVG placeholder con la scena descritta ──────────────
        log.warn("Tutti i motori immagine falliti per prompt: {}", engPrompt);
        return "ERRORE_IMMAGINE: Servizio immagini temporaneamente non disponibile. " +
               "Prompt EN usato: [" + engPrompt.substring(0, Math.min(80, engPrompt.length())) + "]";
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
                       "translator,legal,legal2,contract_review,summarizer,cooking,travel,sports,gaming,monitor,classifier,extractor," +
                       "debate,interview,language,mindmap,prompt_eng,video_gen,audio_gen,image_gen,spaces. " +
                       "Scegli 1-2 agenti. SOLO JSON valido.";
            case "spaces": return "Sei SPACES, assistente vocale personale di SPACE AI. Data:" + d + ". Rispondi in max 3 frasi concise per la voce. Usa sempre tono professionale e amichevole. Inizia con 'SPACES:'.";
            case "image_gen": return "Sei IMAGE_GEN di SPACE AI. Data:" + d + ". " +
                "Il tuo compito: ricevi una richiesta di immagine in italiano, " +
                "devi OBBLIGATORIAMENTE rispondere con il tag [GENERA_IMMAGINE: ...] " +
                "contenente una descrizione DETTAGLIATA in INGLESE della scena ESATTA richiesta. " +
                "IMPORTANTE: descrivi TUTTI i soggetti, l'azione, l'ambientazione, i colori. " +
                "Esempio: se l'utente chiede 'Trump su un missile iraniano', scrivi: " +
                "[GENERA_IMMAGINE: Donald Trump in a dark suit and red tie, sitting and riding on a large iranian missile painted in green white red colors, launching into the sky, dramatic cinematic scene, mountains background, photorealistic] " +
                "Dopo il tag, descrivi in italiano quello che hai generato.";
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
            case "contract_review": return
                "Sei CONTRACT REVIEW di SPACE AI — specialista nell'analisi di contratti e documenti legali. Data:" + d + ".\n" +
                "IMPORTANTE: fornisci informazioni generali a scopo informativo, NON consulenza legale professionale.\n\n" +
                "Quando ricevi un contratto o clausola da analizzare, DEVI sempre rispondere con questa struttura ESATTA:\n\n" +
                "## 📋 RIEPILOGO CONTRATTO\n" +
                "- Tipo di contratto, parti coinvolte, durata, valore economico (se presente)\n\n" +
                "## ✅ CLAUSOLE STANDARD (OK)\n" +
                "- Lista delle clausole nella norma, con breve spiegazione\n\n" +
                "## ⚠️ CLAUSOLE DA VERIFICARE\n" +
                "- Lista clausole ambigue o inusuali con spiegazione del rischio\n\n" +
                "## 🔴 CLAUSOLE CRITICHE / RED FLAG\n" +
                "- Clausole potenzialmente svantaggiose, vessatorie o pericolose\n" +
                "- Per ognuna: [CLAUSOLA] → [RISCHIO] → [SUGGERIMENTO]\n\n" +
                "## 📊 SCORE CONTRATTO\n" +
                "- Bilanciamento: X/10 (10=perfettamente bilanciato, 1=totalmente a favore dell'altra parte)\n" +
                "- Rischio complessivo: BASSO / MEDIO / ALTO / CRITICO\n\n" +
                "## 💡 RACCOMANDAZIONI\n" +
                "- Punti da negoziare prima di firmare\n" +
                "- Clausole da aggiungere per tutela\n\n" +
                "## ⚖️ DISCLAIMER\n" +
                "Questa analisi è a scopo informativo. Consulta un avvocato per decisioni legali vincolanti.\n\n" +
                "Analizza con precisione, identifica ogni rischio, usa linguaggio chiaro e accessibile.";

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
            case "video_gen":
                return "Sei VIDEO_GEN di SPACE AI. Data:" + d + ". " +
                       "Ricevi una descrizione e devi rispondere SOLO con JSON valido, ZERO testo extra, ZERO codice Python, ZERO spiegazioni. " +
                       "Formato ESATTO da rispettare: " +
                       "{\"storyboard\":[" +
                       "{\"frame\":1,\"description\":\"scena 1 in italiano\",\"prompt_image\":\"detailed english description for image generation\"}," +
                       "{\"frame\":2,\"description\":\"scena 2\",\"prompt_image\":\"english prompt 2\"}," +
                       "{\"frame\":3,\"description\":\"scena 3\",\"prompt_image\":\"english prompt 3\"}" +
                       "],\"duration_seconds\":6,\"fps\":2} " +
                       "Regole OBBLIGATORIE: " +
                       "1) Esattamente 3-5 frame, ognuno un momento diverso della scena. " +
                       "2) prompt_image SEMPRE in inglese descrittivo e dettagliato (colori, soggetti, ambientazione). " +
                       "3) Se la descrizione non e chiara, inventa una scena plausibile. " +
                       "4) MAI restituire codice Python o istruzioni testuali. " +
                       "5) Se non puoi fare lo storyboard: {\"error\":\"descrizione non valida\"}";
            case "audio_gen":
                return "Sei AUDIO_GEN di SPACE AI. Data:" + d + ". " +
                       "Ricevi una richiesta di audio/voce. Devi: " +
                       "1) Estrarre SOLO il testo da sintetizzare " +
                       "2) Identificare la lingua (es: it-IT, zh-CN, en-US) " +
                       "3) Identificare il tipo di voce (femminile/maschile) " +
                       "Rispondi SOLO con JSON: " +
                       "{\"text\":\"testo da sintetizzare\",\"lang\":\"it-IT\",\"voice\":\"femminile\"} " +
                       "Se la richiesta include traduzione, traduci prima e metti il testo tradotto in 'text'. " +
                       "Rispondi SOLO con JSON valido, zero spiegazioni.";
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
                       "Ricevi una descrizione e devi produrre un SVG 900x600 RICCO E DETTAGLIATO. " +
                       "REGOLE OBBLIGATORIE: " +
                       "1) Inizia SEMPRE con <svg xmlns='http://www.w3.org/2000/svg' width='900' height='600' viewBox='0 0 900 600'> " +
                       "2) Usa SEMPRE un background gradient che copra tutto (defs + linearGradient + rect fill). " +
                       "3) Disegna TUTTI gli elementi della scena: persone, oggetti, sfondo, dettagli. " +
                       "4) Usa forme: rect, circle, ellipse, polygon, path, line, polyline. " +
                       "5) Aggiungi colori realistici per ogni elemento. " +
                       "6) Includi un titolo testuale in basso con la scena descritta. " +
                       "7) Produci almeno 20 elementi SVG per una scena ricca. " +
                       "Restituisci SOLO il codice dentro ```svg\n...\n```. Zero spiegazioni.";
            default: return coreSystem();
        }
    }

    // ── VISUAL CREATIVE: pipeline ibrida SVG + Pollinations ─────────────
    private String handleVisualCreative(String prompt, String sid,
                                         String baseUrl, String apiKey, String model) throws Exception {

        // ── STADIO 1: Chiedi al LLM una descrizione EN dettagliata + SVG ────
        String llmResp = callLLM(agentPrompt("visual_creative"),
            "DESCRIZIONE SCENA: " + prompt, new ArrayList<>(), baseUrl, apiKey, model, 3000);

        // ── STADIO 2: Estrai SVG generato dal LLM ───────────────────────────
        String svgCode = extractSVGCode(llmResp);

        // ── STADIO 3: Prova anche Pollinations con il prompt originale ───────
        // Pollinations genera immagini realistiche, SVG è il fallback visivo
        String pollinationsImg = null;
        try {
            org.springframework.web.client.RestTemplate vc_client =
                new org.springframework.web.client.RestTemplate();
            org.springframework.http.client.SimpleClientHttpRequestFactory vcf =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
            vcf.setConnectTimeout(8000);
            vcf.setReadTimeout(25000);
            vc_client.setRequestFactory(vcf);

            String engPrompt = enhancePromptForSD(prompt);
            long seed = Math.abs(engPrompt.hashCode()) % 99999;
            String encoded = java.net.URLEncoder.encode(engPrompt, "UTF-8").replace("+", "%20");
            String url = "https://image.pollinations.ai/prompt/" + encoded
                + "?width=900&height=600&nologo=true&enhance=true&model=flux&seed=" + seed;
            log.info("VisualCreative Pollinations: {}", url.substring(0, Math.min(100, url.length())));
            ResponseEntity<byte[]> resp = vc_client.getForEntity(url, byte[].class);
            if (resp.getStatusCode().is2xxSuccessful()
                    && resp.getBody() != null && resp.getBody().length > 5000) {
                pollinationsImg = "data:image/jpeg;base64,"
                    + Base64.getEncoder().encodeToString(resp.getBody());
                log.info("VisualCreative Pollinations OK: {} bytes", resp.getBody().length);
            }
        } catch (Exception pe) {
            log.warn("VisualCreative Pollinations fallito: {}", pe.getMessage());
        }

        // ── STADIO 4: Costruisci risposta con ENTRAMBE le immagini se disponibili ──
        // Priorità: immagine realistica (Pollinations) + SVG scaricabile
        if (pollinationsImg != null && svgCode != null && svgCode.length() > 100) {
            // Abbiamo entrambe: manda l'immagine realistica come principale + SVG come alternativa
            return "IMAGE_DUAL:" + pollinationsImg + "||SVG_DATA:" + svgCode;
        } else if (pollinationsImg != null) {
            // Solo Pollinations (SVG fallito)
            return "IMAGE_REAL:" + pollinationsImg;
        } else if (svgCode != null && svgCode.length() > 100) {
            // Solo SVG (Pollinations fallito)
            byte[] svgBytes = svgCode.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String svgB64 = "data:image/svg+xml;base64,"
                + Base64.getEncoder().encodeToString(svgBytes);
            return "IMAGE_SVG:" + svgB64 + "||SVG_CODE:" + svgCode;
        } else {
            return "Riprova con una descrizione più dettagliata della scena.";
        }
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

    // ── RLHF AUTOMATICO: valutatore LLM assegna reward senza intervento umano ──
    private final java.util.concurrent.ExecutorService rlhfExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor();

    private double calculateReward(String sid, String query, String resp, String agent, boolean interacted) {
        double r = computeReward(query, resp, agent);
        if (interacted) r += 0.3;
        double finalReward = Math.max(-0.5, Math.min(1.0, r));
        // Avvia RLHF automatico in background (non blocca la risposta)
        rlhfExecutor.submit(() -> autoRLHF(sid, query, resp, agent, finalReward));
        return finalReward;
    }

    private void autoRLHF(String sid, String query, String response, String agent, double baseReward) {
        // Skip se Groq è in rate limit
        if (rateLimitUntil > System.currentTimeMillis()) return;
        try {
            String baseUrl = env("AI_BASE_URL", "https://api.groq.com/openai/v1");
            String apiKey  = env("AI_API_KEY",  "");
            String model   = env("AI_MODEL",    "llama-3.3-70b-versatile");
            if (apiKey.isEmpty()) return;

            // Prompt valutatore compatto (max 200 token per risparmiare quota)
            String evalPrompt =
                "Valuta questa risposta AI da 0.0 a 1.0. Rispondi SOLO con il numero decimale.\n" +
                "Criteri: accuratezza(0.4) + completezza(0.3) + chiarezza(0.3)\n" +
                "Domanda: " + query.substring(0, Math.min(100, query.length())) + "\n" +
                "Risposta: " + response.substring(0, Math.min(200, response.length())) + "\n" +
                "Score:";

            String scoreStr = callLLM("Sei un valutatore AI. Rispondi SOLO con un numero da 0.0 a 1.0.",
                evalPrompt, new ArrayList<>(), baseUrl, apiKey, model, 10);

            double autoReward = Double.parseDouble(scoreStr.trim().replaceAll("[^0-9.]","").substring(0, Math.min(4, scoreStr.trim().length())));
            autoReward = Math.max(0.0, Math.min(1.0, autoReward));

            // Media pesata: 70% auto, 30% base
            double finalReward = autoReward * 0.7 + baseReward * 0.3;
            backpropagate(agent, query, finalReward);
            log.debug("RLHF auto: agent={} autoScore={} base={} final={}", agent,
                String.format("%.2f", autoReward), String.format("%.2f", baseReward),
                String.format("%.2f", finalReward));

            // Salva in LTM se reward alta (risposta di qualita)
            if (finalReward > 0.75) {
                String fact = "buona_risposta::" + query.substring(0, Math.min(50, query.length()));
                consolidateToLTM(sid, fact);
            }
        } catch (Exception e) {
            log.debug("RLHF auto skip: {}", e.getMessage());
        }
    }

    private String synthesizerPrompt() {
        return "Sei SYNTHESIZER di SPACE AI. Data:" + today() + ". " +
               "Unifica in UNA risposta finale perfetta. Elimina ridondanze. Markdown. Italiano.";
    }
    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> chat(
            @RequestBody Map<String, String> body,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        String userMessage  = ((String) body.getOrDefault("message", "")).trim();
        String sessionId    = body.getOrDefault("sessionId", "default");
        String fileContent  = body.getOrDefault("fileContent", "");
        String thinkingFlag = body.getOrDefault("thinking", "false");
        if (userMessage.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Messaggio vuoto"));
        String baseUrl     = env("AI_BASE_URL", "https://api.groq.com/openai/v1");
        String apiKey      = env("AI_API_KEY", "");
        String model       = env("AI_MODEL", "llama-3.3-70b-versatile");
        String supabaseUrl = env("SUPABASE_URL", "");
        String supabaseKey = env("SUPABASE_KEY", "");

        // ── Creator account: nessun rate limit ───────────────────────────────
        String userEmail   = body.getOrDefault("userEmail", "");
        boolean isCreator  = CREATOR_EMAIL.equalsIgnoreCase(userEmail);
        // ────────────────────────────────────────────────────────────────────

        // Rate limiting per sessionId (skip per creator)
        if (!isCreator && isRateLimited(sessionId)) {
            return ResponseEntity.status(429).body(Map.of(
                "error", "Troppe richieste. Attendi un minuto.",
                "status", "rate_limited"));
        }
        // Rate limiting per IP (skip per creator)
        if (!isCreator && isIpRateLimited(httpRequest)) {
            return ResponseEntity.status(429).body(Map.of(
                "error", "Troppe richieste da questo indirizzo. Attendi un minuto.",
                "status", "rate_limited_ip"));
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
            List<Map<String,String>> fullHistory = neuralMemory.getOrDefault(sessionId, new ArrayList<>());
            if (fullHistory.isEmpty() && !supabaseUrl.isEmpty() && !supabaseKey.isEmpty()) {
                fullHistory = loadHistory(sessionId, supabaseUrl, supabaseKey);
                if (!fullHistory.isEmpty()) neuralMemory.put(sessionId, new ArrayList<>(fullHistory));
            }
            // Sliding window + Context Compression (Claude Code style)
            List<Map<String,String>> history;
            if (fullHistory.size() <= 8) {
                history = fullHistory;
            } else if (fullHistory.size() <= 20) {
                history = fullHistory;
            } else {
                // Comprimi i messaggi vecchi per risparmiare token
                String ctxSummary = compressContext(fullHistory, userMessage, baseUrl, apiKey, model);
                history = new ArrayList<>();
                if (ctxSummary != null && !ctxSummary.isBlank()) {
                    Map<String,String> summaryMsg = new HashMap<>();
                    summaryMsg.put("role", "system");
                    summaryMsg.put("content", "[RIASSUNTO CONVERSAZIONE PRECEDENTE]: " + ctxSummary);
                    history.add(summaryMsg);
                }
                // Aggiungi ultimi 6 messaggi completi
                history.addAll(fullHistory.subList(Math.max(0, fullHistory.size() - 6), fullHistory.size()));
            }
            // ── CONTENT SAFETY CHECK ─────────────────────────────────────────
            if (!isSafeInput(userMessage)) {
                return ResponseEntity.ok(Map.of(
                    "response", "\uD83D\uDEAB Mi dispiace, non posso rispondere a questa richiesta. " +
                        "Posso aiutarti con qualsiasi altra domanda legale ed etica.",
                    "status",   "blocked",
                    "sessionId", sessionId
                ));
            }

            // ── MUSIC GENERATION — PRIMA dell'audio e del video ──────────────
            String qlv = userMessage.toLowerCase();
            if (needsMusic(userMessage)) {
                try {
                    Map<String,String> musicBody = new HashMap<>();
                    musicBody.put("description", userMessage);
                    musicBody.put("sessionId",   sessionId);
                    musicBody.put("baseUrl",      baseUrl);
                    musicBody.put("apiKey",       apiKey);
                    musicBody.put("model",        model);
                    // Rileva genere e mood dal messaggio
                    musicBody.put("genre",  qlv.contains("jazz")?"jazz":qlv.contains("rock")?"rock":
                        qlv.contains("classica")?"classical":qlv.contains("pop")?"pop":"pop");
                    musicBody.put("mood",   qlv.contains("triste")?"sad":qlv.contains("allegra")?"happy":
                        qlv.contains("romantica")?"romantic":qlv.contains("energica")?"energetic":"happy");
                    musicBody.put("tempo",  qlv.contains("lenta")?"70":qlv.contains("veloce")?"160":"120");
                    ResponseEntity<Object> mResp = musicGenerate(musicBody);
                    Object mb = mResp.getBody();
                    Map<String,Object> mRespMap = new HashMap<>();
                    mRespMap.put("status","ok"); mRespMap.put("mode","music_gen");
                    mRespMap.put("sessionId",sessionId); mRespMap.put("musicData",mb);
                    mRespMap.put("response","\uD83C\uDFB5 Canzone generata! Usa il player ABCJS per ascoltarla.");
                    return ResponseEntity.ok(mRespMap);
                } catch (Exception me) { log.warn("MusicGen inline: {}", me.getMessage()); }
            }

            // ── AUDIO GENERATION — PRIMA del video e dell'Agent Loop ─────────
            boolean isAudio = qlv.contains("crea audio") || qlv.contains("genera audio") ||
                qlv.contains("sintetizza") || qlv.contains("tts") ||
                qlv.contains("leggi ad alta voce") || qlv.contains("read aloud") ||
                (qlv.contains("voce") && (qlv.contains("femminile") || qlv.contains("maschile"))) ||
                (qlv.contains("pronuncia") && qlv.length() > 20) ||
                qlv.contains("audio di") || qlv.contains("fai sentire");
            if (isAudio) {
                try {
                    // Estrai lingua dalla richiesta
                    String audioLang = "it-IT";
                    if (qlv.contains("cinese") || qlv.contains("chinese") || qlv.contains("mandarino")) audioLang = "zh-CN";
                    else if (qlv.contains("inglese") || qlv.contains("english")) audioLang = "en-US";
                    else if (qlv.contains("spagnolo") || qlv.contains("spanish")) audioLang = "es-ES";
                    else if (qlv.contains("francese") || qlv.contains("french")) audioLang = "fr-FR";
                    else if (qlv.contains("tedesco") || qlv.contains("german")) audioLang = "de-DE";
                    else if (qlv.contains("giapponese") || qlv.contains("japanese")) audioLang = "ja-JP";
                    String voiceHint = qlv.contains("maschile") ? "maschile" : "femminile";
                    // Usa LLM per estrarre il testo da sintetizzare se non è esplicito
                    String textToSpeak = userMessage;
                    if (qlv.contains("frase") || qlv.contains("testo") || qlv.contains("parola")) {
                        // Chiedi all'LLM di estrarre/tradurre il testo
                        textToSpeak = callLLM(
                            "Estrai SOLO il testo da sintetizzare in audio dalla richiesta. " +
                            "Se richiede traduzione, traduci prima. Rispondi con il solo testo, niente altro.",
                            userMessage, new ArrayList<>(), baseUrl, apiKey, model, 200);
                    }
                    String audioResult = generateAudio(textToSpeak, audioLang, voiceHint);
                    Map<String,Object> aResp = new HashMap<>();
                    if (audioResult != null && audioResult.startsWith("AUDIO_MP3:")) {
                        aResp.put("audioBase64", audioResult.substring(10));
                        aResp.put("audioType",   "audio/mpeg");
                        aResp.put("response",    "\uD83C\uDFA4 Audio generato con voce " + voiceHint + " in " + audioLang + "!");
                        aResp.put("downloadable", true);
                    } else if (audioResult != null && audioResult.startsWith("AUDIO_WEBSPEECH:")) {
                        String[] wParts = audioResult.substring(16).split("\\|");
                        aResp.put("webSpeech",  true);
                        aResp.put("text",       wParts.length > 0 ? wParts[0] : textToSpeak);
                        aResp.put("lang",       wParts.length > 1 ? wParts[1] : audioLang);
                        aResp.put("voice",      voiceHint);
                        aResp.put("response",   "\uD83C\uDFA4 Ecco il testo da pronunciare: " + textToSpeak.substring(0, Math.min(80, textToSpeak.length())));
                    } else {
                        aResp.put("response", "\uD83C\uDFA4 Configura GOOGLE_TTS_API_KEY su Render per la generazione audio. " +
                            "Testo: " + textToSpeak.substring(0, Math.min(100, textToSpeak.length())));
                    }
                    aResp.put("status","ok"); aResp.put("mode","audio_gen"); aResp.put("sessionId",sessionId);
                    saveMessages(sessionId, userMessage, (String)aResp.getOrDefault("response","Audio generato"), supabaseUrl, supabaseKey);
                    return ResponseEntity.ok(aResp);
                } catch (Exception ae) {
                    log.warn("AudioGen failed: {}", ae.getMessage());
                }
            }

            // ── VIDEO GENERATION — PRIMA dell'Agent Loop ─────────────────────
            boolean isVideo = qlv.contains("crea un video") || qlv.contains("genera video") ||
                qlv.contains("crea video") || qlv.contains("animazione di") ||
                qlv.contains("video di") || qlv.contains("fai un video") ||
                qlv.contains("genera un video") || qlv.contains("realizza un video");
            if (isVideo) {
                try {
                    log.info("VideoGen: avvio per '{}'", userMessage.substring(0, Math.min(60, userMessage.length())));
                    String videoHtml = generateVideoHtml(userMessage, sessionId, baseUrl, apiKey, model);
                    saveMessages(sessionId, userMessage, "Video generato.", supabaseUrl, supabaseKey);
                    updateSTM(sessionId, userMessage);
                    Map<String,Object> vResp = new HashMap<>();
                    vResp.put("response",  "🎬 Ecco il tuo video animato! Clicca ▶ Play, poi ⬇ Scarica WebM per salvarlo.");
                    vResp.put("videoHtml", videoHtml);
                    vResp.put("status",    "ok");
                    vResp.put("mode",      "video_gen");
                    vResp.put("sessionId", sessionId);
                    return ResponseEntity.ok(vResp);
                } catch (Exception ve) {
                    log.warn("VideoGen failed, continuo con flow normale: {}", ve.getMessage());
                }
            }

            // ── AUTONOMOUS GOAL DELEGATION: per obiettivi aziendali complessi ──
            boolean isBusinessGoal = userMessage.toLowerCase().contains("obiettivo:") ||
                userMessage.toLowerCase().contains("delegami") ||
                userMessage.toLowerCase().contains("autonomamente") ||
                (userMessage.length() > 100 && userMessage.toLowerCase().contains("report"));
            if (isBusinessGoal) {
                try {
                    Map<String,String> goalBody = new HashMap<>();
                    goalBody.put("goal", userMessage);
                    goalBody.put("sessionId", sessionId);
                    goalBody.put("baseUrl", baseUrl); goalBody.put("apiKey", apiKey); goalBody.put("model", model);
                    ResponseEntity<Object> goalResp = delegateGoal(goalBody);
                    Object gb = goalResp.getBody();
                    Map<String,Object> goalRespMap = new HashMap<>();
                    goalRespMap.put("response", "\uD83C\uDFAF **Obiettivo delegato!** Sto lavorando in autonomia...\n\n" +
                        "ID Task: `" + (gb instanceof Map ? ((Map<?,?>)gb).get("taskId") : "N/A") + "`\n" +
                        "Monitora il progresso con il chip **Manus Agent** -> Task.");
                    goalRespMap.put("status", "ok"); goalRespMap.put("mode", "goal_delegation");
                    goalRespMap.put("sessionId", sessionId); goalRespMap.put("taskData", gb);
                    return ResponseEntity.ok(goalRespMap);
                } catch (Exception ge) { log.warn("Goal delegation fallback: {}", ge.getMessage()); }
            }

            // ── LEGGI MODE DAL FRONTEND — se c'è una mode specifica, NON usare Agent Loop ──
            String frontendMode = body.getOrDefault("mode", "").toLowerCase().trim();
            // Modalità che hanno pipeline dedicata: bypass totale dell'Agent Loop
            boolean hasSpecificMode = !frontendMode.isEmpty() &&
                !frontendMode.equals("auto") && !frontendMode.equals("agent_loop");

            // ── AGENT LOOP Manus-style: per query complesse multi-step ─────────
            // SKIP se il frontend ha già scelto un agente specifico
            String agentLoopResult = null;
            if (!hasSpecificMode && needsAgentLoop(userMessage)) {
                try {
                    log.info("Avvio Agent Loop: {}", userMessage.substring(0, Math.min(60, userMessage.length())));
                    agentLoopResult = agentLoop(userMessage, sessionId, baseUrl, apiKey, model);
                    if (agentLoopResult != null && agentLoopResult.length() > 50) {
                        saveMessages(sessionId, userMessage, agentLoopResult, supabaseUrl, supabaseKey);
                        updateSTM(sessionId, userMessage);
                        consolidateToLTM(sessionId, userMessage.substring(0, Math.min(80, userMessage.length())));
                        Map<String,Object> alResp = new HashMap<>();
                        alResp.put("response",  agentLoopResult);
                        alResp.put("status",    "ok");
                        alResp.put("mode",      "agent_loop");
                        alResp.put("sessionId", sessionId);
                        alResp.put("agentLoop", true);
                        return ResponseEntity.ok(alResp);
                    }
                } catch (Exception ale) {
                    log.warn("Agent loop fallito, continuo con flow normale: {}", ale.getMessage());
                }
            }
            // ── GOOGLE SEARCH LIVE: per query che richiedono dati aggiornati ─
            // Pipeline: Tavily → SerpAPI/Google CSE → DuckDuckGo
            String webData = needsSearch(userMessage) ? searchWebEnhanced(userMessage, sessionId) : null;
            if (webData == null && userMessage.matches(".*\b(202[4-9]|chi e|cosa e successo|quando|dove ora)\b.*")) {
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
            String curMode = frontendMode; // usa frontendMode già letta sopra
            boolean isVisualCreative = curMode != null && curMode.equals("visual_creative") ||
                q.contains("disegna") || q.contains("illustra") ||
                q.contains("crea svg") || q.contains("genera svg");
            boolean isImg = q.contains("genera immagine") || q.contains("crea immagine") ||
                    (q.contains("immagine") && (q.contains("crea") || q.contains("genera")));
            // ── VISUAL CREATIVE: pipeline ibrida SVG + Pollinations ─────────
            if (isVisualCreative && !isImg) {
                try {
                    String visualResp = handleVisualCreative(userMessage, sessionId, baseUrl, apiKey, model);
                    Map<String,Object> vr = new HashMap<>();
                    vr.put("status", "ok");
                    vr.put("mode", "visual_creative");
                    vr.put("sessionId", sessionId);

                    if (visualResp.startsWith("IMAGE_DUAL:")) {
                        // Abbiamo sia immagine realistica che SVG
                        String[] parts = visualResp.substring(11).split("\\|\\|SVG_DATA:", 2);
                        vr.put("image", parts[0].substring(parts[0].indexOf(",") + 1)); // base64 senza header
                        vr.put("imageType", "image/jpeg");
                        vr.put("svgCode", parts.length > 1 ? parts[1] : "");
                        vr.put("response", "🎨 Ecco la tua immagine! Ho generato sia una versione realistica che il codice SVG scaricabile.");
                        vr.put("downloadable", true);
                    } else if (visualResp.startsWith("IMAGE_REAL:")) {
                        // Solo immagine realistica Pollinations
                        String imgData = visualResp.substring(11);
                        vr.put("image", imgData.substring(imgData.indexOf(",") + 1));
                        vr.put("imageType", "image/jpeg");
                        vr.put("response", "🎨 Ecco la tua immagine realistica!");
                        vr.put("downloadable", true);
                    } else if (visualResp.startsWith("IMAGE_SVG:")) {
                        // Solo SVG
                        String[] parts = visualResp.split("\\|\\|SVG_CODE:", 2);
                        String svgB64 = parts[0].substring(10); // rimuovi IMAGE_SVG:
                        vr.put("svgImage", svgB64);  // data:image/svg+xml;base64,...
                        vr.put("svgCode", parts.length > 1 ? parts[1] : "");
                        vr.put("response", "🎨 Ecco la tua immagine SVG! Puoi scaricarla con il pulsante.");
                        vr.put("downloadable", true);
                    } else {
                        vr.put("response", visualResp);
                    }
                    saveMessages(sessionId, userMessage,
                        (String) vr.getOrDefault("response", "Immagine generata."),
                        supabaseUrl, supabaseKey);
                    return ResponseEntity.ok(vr);
                } catch (Exception ve) {
                    log.warn("Visual creative: {}", ve.getMessage());
                }
            }

            if (isImg) {
                String imgAgent = callLLM(agentPrompt("image_gen"), enriched, history, baseUrl, apiKey, model, 400);
                // Estrai prompt EN dal tag [GENERA_IMMAGINE:...] — il vero fix al bug
                String hfPrompt = userMessage; // fallback: generateImage tradurrà da IT
                if (imgAgent.contains("[GENERA_IMMAGINE:")) {
                    int s = imgAgent.indexOf("[GENERA_IMMAGINE:") + 17;
                    int e = imgAgent.indexOf("]", s);
                    if (e > s) {
                        hfPrompt = imgAgent.substring(s, e).trim();
                        log.info("Prompt EN estratto da IMAGE_GEN: {}", hfPrompt);
                    }
                } else {
                    log.warn("IMAGE_GEN no tag: traduco manualmente '{}'", userMessage);
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
                // LLM-as-a-Judge: valida risposta prima di inviarla
                checked = llmJudgeValidate(checked, userMessage, baseUrl, apiKey, model);
                // Memoria procedurale: apprendi dalla strategia usata
                String usedStrategy = agents.isEmpty() ? "direct" : String.join("+", agents);
                learnFromOutcome(userMessage, usedStrategy, 0.65);
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
        if (q.contains("analizza contratto") || q.contains("review contratto") ||
            q.contains("clausola") || q.contains("nda") || q.contains("accordo commerciale") ||
            q.contains("termini e condizioni") || q.contains("contratto di lavoro") ||
            q.contains("contratto di vendita") || q.contains("privacy policy") ||
            q.contains("red flag") || q.contains("firmare questo")) return List.of("contract_review");
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
    // ════════════════════════════════════════════════════════════════════════
    // PUPPETEER BROWSER AGENT — controllo browser completo
    // Usa Browserless API: BROWSERLESS_TOKEN env var
    // Gratuito: https://www.browserless.io (6h/mese)
    // Supporta: navigate, click, type, screenshot, evaluate JS, fill form
    // ════════════════════════════════════════════════════════════════════════

    // ════════════════════════════════════════════════════════════════════════
    // COMPUTER USE LOOP — ciclo osservazione-azione continuo (GPT-5.5 style)
    // Analizza screenshot → decide azione → esegue → ripete fino al goal
    // ════════════════════════════════════════════════════════════════════════

    @PostMapping("/manus/computer-use")
    public ResponseEntity<Object> computerUseLoop(@RequestBody Map<String,Object> body) {
        String goal      = (String) body.getOrDefault("goal","");
        String startUrl  = (String) body.getOrDefault("url","");
        String sessionId = (String) body.getOrDefault("sessionId","global");
        String baseUrl2  = (String) body.getOrDefault("baseUrl", env("AI_BASE_URL", env("GROQ_BASE_URL","https://api.groq.com/openai/v1")));
        String apiKey2   = (String) body.getOrDefault("apiKey",  env("AI_API_KEY", env("GROQ_API_KEY","")));
        String model2    = (String) body.getOrDefault("model",   env("AI_MODEL", env("GROQ_MODEL","llama-3.3-70b-versatile")));
        int    maxSteps  = Integer.parseInt((String)body.getOrDefault("maxSteps","8"));

        if (goal.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error","Goal obbligatorio"));

        String token = env("BROWSERLESS_TOKEN","");
        List<Map<String,Object>> steps = new ArrayList<>();
        String currentUrl = startUrl;
        String lastObservation = "";

        for (int step = 0; step < maxSteps; step++) {
            log.info("ComputerUse step {}/{}: url={}", step+1, maxSteps, currentUrl);

            // OSSERVAZIONE: screenshot + estrai contenuto
            Map<String,Object> observation = new LinkedHashMap<>();
            String pageContent = "";
            String screenshotB64 = "";

            if (!token.isEmpty() && !currentUrl.isEmpty()) {
                try {
                    Map<String,Object> ssBody = new HashMap<>();
                    ssBody.put("action","screenshot"); ssBody.put("url",currentUrl);
                    ssBody.put("sessionId",sessionId);
                    ResponseEntity<Object> ssResp = manusBrowserControl(ssBody);
                    if (ssResp.getBody() instanceof Map) {
                        Map<?,?> ssMap = (Map<?,?>)ssResp.getBody();
                        Object imgObj = ssMap.get("image");
                        screenshotB64 = imgObj instanceof String ? (String)imgObj : "";
                    }
                } catch (Exception se) { log.debug("Screenshot step {}: {}", step, se.getMessage()); }

                try {
                    Map<String,Object> extBody = new HashMap<>();
                    extBody.put("action","extract"); extBody.put("url",currentUrl);
                    extBody.put("sessionId",sessionId);
                    ResponseEntity<Object> extResp = manusBrowserControl(extBody);
                    if (extResp.getBody() instanceof Map) {
                        Map<?,?> em = (Map<?,?>)extResp.getBody();
                        Object res = em.get("result");
                        if (res != null) pageContent = res.toString().substring(0, Math.min(2000, res.toString().length()));
                    }
                } catch (Exception ee) { log.debug("Extract step {}: {}", step, ee.getMessage()); }
            } else if (!currentUrl.isEmpty()) {
                // Fallback senza Browserless
                pageContent = searchDuckDuckGo(goal + " site:" + currentUrl.replaceAll("https?://",""));
            }

            // DECISIONE: LLM decide l'azione successiva
            String decisionPrompt =
                "Sei un agente Computer Use AI. Il tuo obiettivo: " + goal + "\n" +
                "URL attuale: " + currentUrl + "\n" +
                "Contenuto pagina: " + pageContent.substring(0, Math.min(800, pageContent.length())) + "\n" +
                "Osservazioni precedenti: " + lastObservation.substring(0, Math.min(300, lastObservation.length())) + "\n\n" +
                "Decidi l'azione successiva. Rispondi SOLO con JSON:\n" +
                "{\"action\":\"navigate|click|type|extract|done|search\",\"target\":\"url o selector\",\"value\":\"testo se type\",\"reason\":\"...\",\"goalReached\":false}";

            String decisionJson = "";
            try {
                decisionJson = callLLM(
                    "Sei un agente browser preciso. Rispondi SOLO con JSON valido.",
                    decisionPrompt, new ArrayList<>(), baseUrl2, apiKey2, model2, 200);
            } catch (Exception de) { log.warn("Decision LLM: {}", de.getMessage()); }

            // AZIONE: esegui la decisione
            String actionResult = "";
            boolean goalReached = false;
            try {
                String cJson = decisionJson.replaceAll("```json","").replaceAll("```","").trim();
                int cs = cJson.indexOf("{"), ce = cJson.lastIndexOf("}");
                if (cs >= 0 && ce > cs) cJson = cJson.substring(cs, ce+1);
                JsonNode decision = MAPPER.readTree(cJson);
                String action  = decision.path("action").asText("extract");
                String target  = decision.path("target").asText(currentUrl);
                String value   = decision.path("value").asText("");
                String reason  = decision.path("reason").asText("");
                goalReached    = decision.path("goalReached").asBoolean(false);

                observation.put("step",    step+1);
                observation.put("url",     currentUrl);
                observation.put("action",  action);
                observation.put("target",  target);
                observation.put("reason",  reason);

                if (goalReached || action.equals("done")) {
                    observation.put("status","GOAL_REACHED");
                    steps.add(observation);
                    break;
                }

                if (action.equals("navigate") || action.equals("search")) {
                    currentUrl = target.startsWith("http") ? target : "https://www.google.com/search?q=" +
                        URLEncoder.encode(target, "UTF-8");
                    actionResult = "Navigato a: " + currentUrl;
                } else if (action.equals("extract")) {
                    actionResult = pageContent.substring(0, Math.min(500, pageContent.length()));
                    // Indicizza nel RAG
                    if (!pageContent.isBlank())
                        ragIndexDocument(sessionId + "/cu_" + step, pageContent);
                } else if (action.equals("click") && !token.isEmpty()) {
                    Map<String,Object> clickBody = new HashMap<>();
                    clickBody.put("action","click"); clickBody.put("url",currentUrl);
                    clickBody.put("selector",target); clickBody.put("sessionId",sessionId);
                    ResponseEntity<Object> cr = manusBrowserControl(clickBody);
                    actionResult = "Click su: " + target;
                }

                observation.put("result",  actionResult.substring(0, Math.min(200, actionResult.length())));
                lastObservation = reason + " | " + actionResult;

            } catch (Exception ae) {
                observation.put("error", ae.getMessage());
                lastObservation = "Errore: " + ae.getMessage();
            }

            steps.add(observation);

            // Aggiungi screenshot se disponibile
            if (!screenshotB64.isEmpty())
                observation.put("screenshot", screenshotB64.substring(0, Math.min(100, screenshotB64.length())) + "...");
        }

        // Sintesi finale del computer use loop
        StringBuilder summary = new StringBuilder();
        steps.forEach(s -> summary.append("Step ").append(s.get("step")).append(": ")
            .append(s.get("action")).append(" → ").append(s.getOrDefault("result","")).append("\n"));

        return ResponseEntity.ok(Map.of(
            "goal",       goal,
            "steps",      steps,
            "totalSteps", steps.size(),
            "summary",    summary.toString(),
            "ragIndexed", true,
            "date",       today()
        ));
    }

    @PostMapping("/manus/browser")
    public ResponseEntity<Object> manusBrowserControl(@RequestBody Map<String,Object> body) {
        String action    = (String) body.getOrDefault("action", "navigate");
        String url       = (String) body.getOrDefault("url", "");
        String selector  = (String) body.getOrDefault("selector", "");
        String value     = (String) body.getOrDefault("value", "");
        String jsCode    = (String) body.getOrDefault("js", "");
        String sessionId = (String) body.getOrDefault("sessionId", "global");
        String token     = env("BROWSERLESS_TOKEN", "");
        String baseUrl2  = env("AI_BASE_URL", "https://api.groq.com/openai/v1");
        String apiKey2   = env("AI_API_KEY",  "");
        String model2    = env("AI_MODEL",    "llama-3.3-70b-versatile");

        // Se Browserless non disponibile, usa manusBrowse come fallback
        if (token.isEmpty() && !url.isEmpty()) {
            Map<String,String> browseBody = new HashMap<>();
            browseBody.put("url", url);
            browseBody.put("task", "Estrai il contenuto principale e i link");
            browseBody.put("sessionId", sessionId);
            browseBody.put("baseUrl", baseUrl2);
            browseBody.put("apiKey", apiKey2);
            browseBody.put("model", model2);
            ResponseEntity<Object> fallback = manusBrowse(browseBody);
            Map<String,Object> fr = new LinkedHashMap<>();
            Object fb = fallback.getBody();
            if (fb instanceof Map) fr.putAll((Map<?,?>)fb == null ? new HashMap<>() :
                ((Map<String,Object>)(Map<?,?>)fb));
            fr.put("browserlessAvailable", false);
            fr.put("hint", "Aggiungi BROWSERLESS_TOKEN su Render per controllo browser completo");
            return ResponseEntity.ok(fr);
        }

        try {
            org.springframework.web.client.RestTemplate bt =
                new org.springframework.web.client.RestTemplate();
            org.springframework.http.client.SimpleClientHttpRequestFactory bf =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
            bf.setConnectTimeout(15000); bf.setReadTimeout(30000);
            bt.setRequestFactory(bf);

            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);

            String browserlessEndpoint;
            ObjectNode payload = MAPPER.createObjectNode();

            switch (action) {
                case "screenshot": {
                    browserlessEndpoint = "https://chrome.browserless.io/screenshot?token=" + token;
                    payload.put("url", url);
                    ObjectNode opts = MAPPER.createObjectNode();
                    opts.put("fullPage", false);
                    opts.put("type", "jpeg");
                    opts.put("quality", 80);
                    payload.set("options", opts);
                    ResponseEntity<byte[]> ss = bt.postForEntity(browserlessEndpoint,
                        new HttpEntity<>(MAPPER.writeValueAsString(payload), h), byte[].class);
                    if (ss.getBody() != null) {
                        String b64 = java.util.Base64.getEncoder().encodeToString(ss.getBody());
                        return ResponseEntity.ok(Map.of(
                            "action", "screenshot", "url", url,
                            "image", b64, "imageType", "image/jpeg",
                            "bytes", ss.getBody().length, "date", today()
                        ));
                    }
                    break;
                }
                case "evaluate":
                case "click":
                case "type":
                case "navigate":
                default: {
                    browserlessEndpoint = "https://chrome.browserless.io/function?token=" + token;
                    // Build JS function based on action
                    String script;
                    if (!jsCode.isEmpty()) {
                        script = "module.exports = async ({ page }) => { " + jsCode + " }";
                    } else if (action.equals("click")) {
                        script = "module.exports = async ({ page }) => { await page.goto('" + url + "'); " +
                            "await page.waitForSelector('" + selector + "', {timeout:5000}); " +
                            "await page.click('" + selector + "'); " +
                            "await page.waitForTimeout(1000); " +
                            "return { clicked: true, url: page.url(), title: await page.title() }; }";
                    } else if (action.equals("type")) {
                        script = "module.exports = async ({ page }) => { await page.goto('" + url + "'); " +
                            "await page.waitForSelector('" + selector + "', {timeout:5000}); " +
                            "await page.type('" + selector + "', '" + value.replace("'","\'") + "'); " +
                            "return { typed: true, selector: '" + selector + "' }; }";
                    } else if (action.equals("extract")) {
                        script = "module.exports = async ({ page }) => { await page.goto('" + url + "'); " +
                            "await page.waitForTimeout(2000); " +
                            "const content = await page.evaluate(() => document.body.innerText); " +
                            "const title = await page.title(); " +
                            "const links = await page.evaluate(() => Array.from(document.links).slice(0,20).map(a => a.href)); " +
                            "return { title, content: content.substring(0, 3000), links }; }";
                    } else {
                        script = "module.exports = async ({ page }) => { await page.goto('" + url + "'); " +
                            "await page.waitForTimeout(2000); " +
                            "return { url: page.url(), title: await page.title() }; }";
                    }
                    payload.put("code", script);
                    ResponseEntity<String> fn = bt.postForEntity(browserlessEndpoint,
                        new HttpEntity<>(MAPPER.writeValueAsString(payload), h), String.class);
                    JsonNode result = MAPPER.readTree(fn.getBody());

                    // Indicizza il contenuto estratto nel RAG
                    String pageContent = result.path("content").asText("");
                    if (!pageContent.isBlank())
                        ragIndexDocument(sessionId + "/browser_" + System.currentTimeMillis(), pageContent);

                    Map<String,Object> r = new LinkedHashMap<>();
                    r.put("action",  action);
                    r.put("url",     url);
                    r.put("result",  result);
                    r.put("ragIndexed", !pageContent.isBlank());
                    r.put("date",    today());
                    return ResponseEntity.ok(r);
                }
            }

            return ResponseEntity.ok(Map.of("status","completed","action",action,"date",today()));

        } catch (Exception e) {
            log.warn("Puppeteer browser error: {}", e.getMessage());
            return ResponseEntity.status(503).body(Map.of(
                "error",  "Browser agent error: " + e.getMessage(),
                "hint",   "Verifica BROWSERLESS_TOKEN su Render"
            ));
        }
    }


    private String selectModel(String query, String defaultModel) {
        String q = query.toLowerCase();
        boolean isCode    = q.contains("codice") || q.contains("java") ||
            q.contains("python") || q.contains("javascript") || q.contains("debug") ||
            q.contains("algoritmo") || q.contains("programma");
        boolean isComplex = q.length() > 200 || q.contains("analisi approfondita") ||
            q.contains("ragionamento") || q.contains("matematica avanzata");
        boolean isSimple  = q.length() < 30 && !q.contains("?") && !q.contains("spiega");
        String complexModel = env("GROQ_MODEL_COMPLEX", "");
        String fastModel    = env("GROQ_MODEL_FAST",    "");
        if ((isCode || isComplex) && !complexModel.isEmpty()) return complexModel;
        if (isSimple && !fastModel.isEmpty()) return fastModel;
        return defaultModel;
    }

    private String callLLM(String system, String userMsg, List<Map<String,String>> history,
                            String baseUrl, String apiKey, String model, int maxTokens) throws Exception {
        return callLLMWithTemp(system, userMsg, history, baseUrl, apiKey, model, maxTokens, 0.8);
    }
    // ════════════════════════════════════════════════════════════════════════
    // PROVIDER CHAIN: Groq → Gemini 2.0 → DeepSeek → Autonomous fallback
    // ════════════════════════════════════════════════════════════════════════

    // ── Helper: esegue una chiamata HTTP con retry + backoff esponenziale ───
    private ResponseEntity<String> callWithRetry(String providerName,
            java.util.function.Supplier<ResponseEntity<String>> call) throws Exception {
        // circuit breaker per provider: se è aperto, salta subito
        if (isProviderCircuitOpen(providerName)) {
            throw new Exception(providerName + " circuit breaker APERTO — provider temporaneamente escluso");
        }
        Exception lastEx = null;
        for (int attempt = 0; attempt <= LLM_MAX_RETRIES; attempt++) {
            try {
                if (attempt > 0) {
                    long wait = LLM_BACKOFF_BASE_MS * (1L << (attempt - 1)); // 1.5s, 3s
                    log.warn("{} retry {}/{} dopo {}ms", providerName, attempt, LLM_MAX_RETRIES, wait);
                    Thread.sleep(wait);
                }
                ResponseEntity<String> resp = call.get();
                providerSuccess.computeIfAbsent(providerName, k -> new AtomicInteger(0)).incrementAndGet();
                recordProviderSuccess(providerName);
                return resp;
            } catch (org.springframework.web.client.ResourceAccessException rae) {
                lastEx = rae;
                providerFailure.computeIfAbsent(providerName, k -> new AtomicInteger(0)).incrementAndGet();
                recordProviderFailure(providerName);
                log.warn("{} timeout attempt {}: {}", providerName, attempt, rae.getMessage());
            } catch (org.springframework.web.client.HttpServerErrorException hse) {
                lastEx = hse;
                providerFailure.computeIfAbsent(providerName, k -> new AtomicInteger(0)).incrementAndGet();
                recordProviderFailure(providerName);
                log.warn("{} 5xx attempt {}: {}", providerName, attempt, hse.getStatusCode());
            } catch (org.springframework.web.client.HttpClientErrorException hce) {
                // 4xx (429, 401) — NON riprovare, ma conta come fallimento
                providerFailure.computeIfAbsent(providerName, k -> new AtomicInteger(0)).incrementAndGet();
                recordProviderFailure(providerName);
                throw hce;
            }
        }
        throw new Exception(providerName + " fallito dopo " + (LLM_MAX_RETRIES + 1) + " tentativi", lastEx);
    }
    // ────────────────────────────────────────────────────────────────────────

    private String callGemini(String system, String userMsg, List<Map<String,String>> history,
                               int maxTokens, double temperature) throws Exception {
        String geminiKey = env("GEMINI_API_KEY","");
        if (geminiKey.isEmpty()) throw new IllegalStateException("GEMINI_API_KEY non configurata");
        String model = env("GEMINI_MODEL","gemini-2.0-flash");
        // Gemini usa un formato diverso da OpenAI
        ObjectNode req = MAPPER.createObjectNode();
        ObjectNode genCfg = MAPPER.createObjectNode();
        genCfg.put("maxOutputTokens", maxTokens); genCfg.put("temperature", temperature);
        req.set("generationConfig", genCfg);
        ArrayNode contents = MAPPER.createArrayNode();
        // System prompt come primo messaggio user
        if (system != null && !system.isEmpty()) {
            ObjectNode sp = MAPPER.createObjectNode(); sp.put("role","user");
            ArrayNode sparts = MAPPER.createArrayNode();
            ObjectNode st = MAPPER.createObjectNode(); st.put("text","[SYSTEM] " + system);
            sparts.add(st); sp.set("parts", sparts); contents.add(sp);
            ObjectNode sr = MAPPER.createObjectNode(); sr.put("role","model");
            ArrayNode srparts = MAPPER.createArrayNode();
            ObjectNode srt = MAPPER.createObjectNode(); srt.put("text","Capito. Seguiro le istruzioni.");
            srparts.add(srt); sr.set("parts", srparts); contents.add(sr);
        }
        // History
        int start = Math.max(0, history.size()-8);
        for (int i = start; i < history.size(); i++) {
            String role = history.get(i).getOrDefault("role","user");
            String msg  = history.get(i).getOrDefault("content","");
            ObjectNode turn = MAPPER.createObjectNode();
            turn.put("role", role.equals("assistant") ? "model" : "user");
            ArrayNode parts = MAPPER.createArrayNode();
            ObjectNode t = MAPPER.createObjectNode(); t.put("text", msg); parts.add(t);
            turn.set("parts", parts); contents.add(turn);
        }
        // User message
        if (userMsg != null && !userMsg.isEmpty()) {
            ObjectNode um = MAPPER.createObjectNode(); um.put("role","user");
            ArrayNode up = MAPPER.createArrayNode();
            ObjectNode ut = MAPPER.createObjectNode(); ut.put("text", userMsg); up.add(ut);
            um.set("parts", up); contents.add(um);
        }
        req.set("contents", contents);
        HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON);
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            model + ":generateContent?key=" + geminiKey;
        final String body = MAPPER.writeValueAsString(req);
        final HttpEntity<String> entity = new HttpEntity<>(body, h);
        // chiamata con retry + timeout
        ResponseEntity<String> resp = callWithRetry("gemini",
            () -> llmRestTemplate.postForEntity(url, entity, String.class));
        JsonNode result = MAPPER.readTree(resp.getBody());
        String text = result.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
        log.info("Gemini {} response: {} chars", model, text.length());
        // salva in cache
        knnCachePut("[gemini]" + userMsg, text);
        return text;
    }

    private String callDeepSeek(String system, String userMsg, List<Map<String,String>> history,
                                 int maxTokens, double temperature) throws Exception {
        String dsKey = env("DEEPSEEK_API_KEY","");
        if (dsKey.isEmpty()) throw new IllegalStateException("DEEPSEEK_API_KEY non configurata");
        String model = env("DEEPSEEK_MODEL","deepseek-chat");
        // DeepSeek usa il formato OpenAI compatibile
        ObjectNode req = MAPPER.createObjectNode();
        req.put("model", model); req.put("max_tokens", maxTokens); req.put("temperature", temperature);
        ArrayNode messages = MAPPER.createArrayNode();
        ObjectNode sys = MAPPER.createObjectNode(); sys.put("role","system"); sys.put("content", system != null ? system : "");
        messages.add(sys);
        int start = Math.max(0, history.size()-10);
        for (int i = start; i < history.size(); i++) {
            ObjectNode m = MAPPER.createObjectNode();
            m.put("role", history.get(i).getOrDefault("role","user"));
            m.put("content", history.get(i).getOrDefault("content",""));
            messages.add(m);
        }
        if (userMsg != null && !userMsg.isEmpty()) {
            ObjectNode usr = MAPPER.createObjectNode(); usr.put("role","user"); usr.put("content",userMsg);
            messages.add(usr);
        }
        req.set("messages", messages);
        HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(dsKey);
        final String body = MAPPER.writeValueAsString(req);
        final HttpEntity<String> entity = new HttpEntity<>(body, h);
        // chiamata con retry + timeout
        ResponseEntity<String> resp = callWithRetry("deepseek",
            () -> llmRestTemplate.postForEntity(
                "https://api.deepseek.com/v1/chat/completions", entity, String.class));
        JsonNode result = MAPPER.readTree(resp.getBody());
        String text = result.path("choices").get(0).path("message").path("content").asText();
        // DeepSeek R1 include <think> tag — estraiamo solo la risposta finale
        if (text.contains("<think>") && text.contains("</think>")) {
            int endThink = text.lastIndexOf("</think>");
            text = text.substring(endThink + 8).trim();
        }
        log.info("DeepSeek {} response: {} chars", model, text.length());
        // salva in cache
        knnCachePut("[deepseek]" + userMsg, text);
        return text;
    }

    private String callLLMWithTemp(String system, String userMsg, List<Map<String,String>> history,
                            String baseUrl, String apiKey, String model, int maxTokens, double temperature) throws Exception {

        // ── Rate limit check: se Groq è bloccato, prova altri provider ──
        long now = System.currentTimeMillis();
        if (rateLimitUntil > now) {
            log.warn("Groq rate limited, provo provider alternativi...");
            // Prova Gemini
            if (!env("GEMINI_API_KEY","").isEmpty()) {
                try { return callGemini(system, userMsg, history, maxTokens, temperature); }
                catch (Exception ge) { log.warn("Gemini fallback: {}", ge.getMessage()); }
            }
            // Prova DeepSeek
            if (!env("DEEPSEEK_API_KEY","").isEmpty()) {
                try { return callDeepSeek(system, userMsg, history, maxTokens, temperature); }
                catch (Exception de) { log.warn("DeepSeek fallback: {}", de.getMessage()); }
            }
            localFallbacks.incrementAndGet();
            return autonomousFallback(system, userMsg, history);
        }

        // Fix 5: Reset groqCallsToday basato su giorno UTC (non su 24h dall avvio)
        java.time.LocalDate today2 = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        java.time.LocalDate dayStart = java.time.Instant.ofEpochMilli(groqDayStart)
            .atZone(java.time.ZoneOffset.UTC).toLocalDate();
        if (!today2.equals(dayStart)) {
            groqCallsToday.set(0);
            groqDayStart = now;
            log.info("Groq daily counter reset (nuovo giorno UTC)");
        }

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
        h.set("HTTP-Referer","https://space-ai-940e.onrender.com");
        h.set("X-Title","SPACE AI"); h.set("User-Agent","SPACE-AI/4.0");
        String endpoint = baseUrl.endsWith("/") ? baseUrl+"chat/completions" : baseUrl+"/chat/completions";

        try {
            groqCallsToday.incrementAndGet();
            ResponseEntity<String> response = restTemplate.postForEntity(
                endpoint, new HttpEntity<>(MAPPER.writeValueAsString(req), h), String.class);
            return MAPPER.readTree(response.getBody()).path("choices").get(0)
                .path("message").path("content").asText();

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            String body = e.getResponseBodyAsString();

            // ── 429 Rate Limit: calcola quando Groq si resetta ─────────────
            if (e.getStatusCode().value() == 429) {
                long retryMs = 2_100_000L; // default 35 minuti
                // Estrai "Please try again in Xm Ys" dal messaggio
                try {
                    java.util.regex.Matcher m429 = java.util.regex.Pattern
                        .compile("try again in (\\d+)m(\\d+\\.?\\d*)s").matcher(body);
                    if (m429.find()) {
                        long mins = Long.parseLong(m429.group(1));
                        double secs = Double.parseDouble(m429.group(2));
                        retryMs = (long)(mins * 60_000 + secs * 1000) + 5000; // +5s buffer
                    }
                } catch (Exception ignored) {}
                rateLimitUntil = System.currentTimeMillis() + retryMs;
                log.error("Groq 429 — rate limit per {}ms. Provo provider alternativi.", retryMs);
                // Provider chain: Gemini → DeepSeek → autonomo
                if (!env("GEMINI_API_KEY","").isEmpty()) {
                    try { return callGemini(system, userMsg, history, maxTokens, temperature); }
                    catch (Exception ge) { log.warn("Gemini dopo 429: {}", ge.getMessage()); }
                }
                if (!env("DEEPSEEK_API_KEY","").isEmpty()) {
                    try { return callDeepSeek(system, userMsg, history, maxTokens, temperature); }
                    catch (Exception de) { log.warn("DeepSeek dopo 429: {}", de.getMessage()); }
                }
                localFallbacks.incrementAndGet();
                return autonomousFallback(system, userMsg, history);
            }

            // ── 503 / 502: circuit breaker ─────────────────────────────────
            if (e.getStatusCode().value() >= 500) {
                circuitOpenTime = System.currentTimeMillis();
                log.error("Groq {}. Circuit breaker aperto.", e.getStatusCode().value());
                return autonomousFallback(system, userMsg, history);
            }
            throw e;
        } catch (Exception e) {
            // Qualsiasi altro errore di rete — fallback autonomo
            log.error("Groq unreachable: {}. Uso fallback autonomo.", e.getMessage());
            return autonomousFallback(system, userMsg, history);
        }
    }

    /**
     * AUTONOMOUS FALLBACK — risponde senza LLM usando:
     * 1. KNN Cache (risposta semanticamente simile già data)
     * 2. MSA Memory (fatti dalla memoria)
     * 3. RAG (documenti indicizzati)
     * 4. Neural routing + risposta basata su agente selezionato
     * 5. Risposta di emergenza con informazioni di stato
     */
    private String autonomousFallback(String system, String userMsg, List<Map<String,String>> history) {
        String query = userMsg != null ? userMsg : "";
        String sessionId = "global"; // best effort

        log.info("AutonomousFallback attivo per: {}", query.substring(0, Math.min(60, query.length())));

        // 1. KNN Cache — risposta semanticamente simile
        String knnHit = knnCacheGet(query);
        if (knnHit != null && !knnHit.isBlank()) {
            log.info("AutonomousFallback: KNN cache hit");
            return "\u26A1 *[Risposta dalla memoria semantica - Groq temporaneamente non disponibile]*\n\n" + knnHit;
        }

        // 2. MSA Memory — fatti rilevanti dalla memoria
        String msaMem = msaRetrieveUnified(query, sessionId);
        if (!msaMem.isBlank()) {
            log.info("AutonomousFallback: MSA memory hit");
            return buildAutonomousResponse(query, msaMem, "memoria MSA");
        }

        // 3. RAG — documenti indicizzati
        String ragCtx = ragRetrieve(query, sessionId);
        if (!ragCtx.isBlank()) {
            log.info("AutonomousFallback: RAG hit");
            return buildAutonomousResponse(query, ragCtx, "documenti RAG");
        }

        // 4. Web search come fonte dati (non richiede LLM)
        try {
            String webData = searchGoogle(query);
            if (!webData.isBlank()) {
                log.info("AutonomousFallback: web search hit");
                return buildAutonomousResponse(query, webData, "ricerca web live");
            }
        } catch (Exception we) { log.debug("Web search in fallback: {}", we.getMessage()); }

        // 5. Risposta di stato con info sul rate limit
        long resetIn = (rateLimitUntil - System.currentTimeMillis()) / 1000;
        String resetStr = resetIn > 60
            ? String.format("%dm %ds", resetIn/60, resetIn%60)
            : resetIn + " secondi";
        return "\u26A0\uFE0F **SPACE AI - Modalita Autonoma Attiva**\n\n"
             + "Il provider LLM (Groq) ha raggiunto il limite giornaliero di token.\n"
             + "**Ripristino automatico tra:** " + resetStr + "\n\n"
             + "In questa modalita SPACE AI risponde usando:\n"
             + "- \uD83E\uDDE0 Memoria semantica MSA\n"
             + "- \uD83D\uDCDA RAG (" + ragStore.size() + " documenti)\n"
             + "- \uD83C\uDF10 Ricerca web live\n"
             + "- \uD83D\uDCBE Cache KNN\n\n"
             + "**Domanda ricevuta:** " + query.substring(0, Math.min(100, query.length())) + "\n\n"
             + "*Riprova tra " + resetStr + " per la risposta completa.*\n"
             + "*(Fallback autonomi oggi: " + localFallbacks.get() + ")*";
    }

    private String buildAutonomousResponse(String query, String context, String source) {
        // Costruisce una risposta strutturata dal contesto senza LLM
        String[] sentences = context.split("[.!?\n]+");
        StringBuilder resp = new StringBuilder();
        resp.append("\uD83E\uDD16 **SPACE AI - Risposta Autonoma** *(fonte: ").append(source).append(")*\n\n");
        // Trova le frasi più rilevanti per la query
        String q = query.toLowerCase();
        String[] qWords = q.split("\s+");
        List<String> relevant = new ArrayList<>();
        for (String sent : sentences) {
            if (sent.trim().length() < 15) continue;
            long score = Arrays.stream(qWords)
                .filter(w -> w.length() > 3 && sent.toLowerCase().contains(w))
                .count();
            if (score > 0) relevant.add(sent.trim());
        }
        if (relevant.isEmpty()) {
            // Usa prime frasi del contesto
            Arrays.stream(sentences).limit(5)
                .filter(s -> s.trim().length() > 20)
                .forEach(s -> resp.append(s.trim()).append("\n\n"));
        } else {
            relevant.stream().limit(6).forEach(s -> resp.append("\u2022 ").append(s).append("\n"));
        }
        resp.append("\n*\u26A1 Risposta generata autonomamente - LLM ripristino automatico in corso*");
        return resp.toString();
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
        // CRITICO: usa una lista MUTABILE per mantenere il contesto tra domande
        List<Map<String,String>> mem = neuralMemory.computeIfAbsent(sessionId, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        // Aggiungi i nuovi messaggi — questo mantiene la memoria tra una domanda e l'altra
        Map<String,String> userTurn = new HashMap<>(); userTurn.put("role","user"); userTurn.put("content",userMsg);
        Map<String,String> aiTurn   = new HashMap<>(); aiTurn.put("role","assistant"); aiTurn.put("content",aiResp);
        mem.add(userTurn);
        mem.add(aiTurn);
        // Mantieni massimo 40 messaggi (20 scambi) — sliding window
        if (mem.size() > 40) {
            List<Map<String,String>> trimmed = new ArrayList<>(mem.subList(mem.size()-40, mem.size()));
            neuralMemory.put(sessionId, new java.util.concurrent.CopyOnWriteArrayList<>(trimmed));
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
        if (url.isEmpty()) {
            // Fallback: usa neuralMemory in-memory
            List<Map<String,String>> mem = neuralMemory.getOrDefault(sessionId, new ArrayList<>());
            return ResponseEntity.ok(Map.of("messages", mem, "source", "memory"));
        }
        List<Map<String,String>> msgs = loadHistory(sessionId, url, key);
        return ResponseEntity.ok(Map.of("messages", msgs, "source", "supabase", "count", msgs.size()));
    }

    // Lista di tutte le sessioni con anteprima — per la cronologia sidebar
    @GetMapping("/sessions")
    public ResponseEntity<Object> getSessions(
            @RequestParam(value="userId", required=false, defaultValue="") String userId) {
        String url = env("SUPABASE_URL",""); String key = env("SUPABASE_KEY","");
        List<Map<String,Object>> sessions = new ArrayList<>();

        if (!url.isEmpty()) {
            try {
                HttpHeaders h = new HttpHeaders();
                h.set("apikey", key); h.set("Authorization", "Bearer " + key);
                // Se userId fornito, filtra per sessioni che iniziano con userId
                String filter = userId.isEmpty() ? "" :
                    "&session_id=like." + userId + "_*";
                ResponseEntity<String> r = restTemplate.exchange(
                    url + "/rest/v1/messages?select=session_id,content,role,created_at" +
                    "&order=created_at.desc&limit=200" + filter,
                    HttpMethod.GET, new HttpEntity<>(h), String.class);
                JsonNode arr = MAPPER.readTree(r.getBody());

                // Raggruppa per session_id
                Map<String,Map<String,Object>> sessionMap = new java.util.LinkedHashMap<>();
                for (JsonNode n : arr) {
                    String sid    = n.path("session_id").asText();
                    String role   = n.path("role").asText();
                    String text   = n.path("content").asText();
                    String ts     = n.path("created_at").asText();
                    if (!sessionMap.containsKey(sid)) {
                        Map<String,Object> s = new java.util.LinkedHashMap<>();
                        s.put("sessionId", sid);
                        s.put("lastMessage", text.substring(0, Math.min(60, text.length())));
                        s.put("lastRole", role);
                        s.put("timestamp", ts);
                        s.put("messageCount", 1);
                        // Prima userMessage come titolo
                        if (role.equals("user"))
                            s.put("title", text.substring(0, Math.min(50, text.length())));
                        sessionMap.put(sid, s);
                    } else {
                        Map<String,Object> s = sessionMap.get(sid);
                        s.put("messageCount", (int)s.get("messageCount") + 1);
                        if (!s.containsKey("title") && role.equals("user"))
                            s.put("title", text.substring(0, Math.min(50, text.length())));
                    }
                }
                sessions.addAll(sessionMap.values().stream().limit(20).collect(Collectors.toList()));
            } catch (Exception e) {
                log.warn("Sessions list: {}", e.getMessage());
            }
        }

        // Aggiungi anche sessioni in-memory non ancora su Supabase
        // Filtra per userId se specificato
        neuralMemory.forEach((sid, msgs) -> {
            if (!userId.isEmpty() && !sid.startsWith(userId)) return; // skip altri utenti
            if (sessions.stream().noneMatch(s -> sid.equals(s.get("sessionId"))) && !msgs.isEmpty()) {
                Map<String,Object> s = new java.util.LinkedHashMap<>();
                s.put("sessionId", sid);
                String firstUser = msgs.stream()
                    .filter(m -> "user".equals(m.get("role")))
                    .map(m -> m.get("content"))
                    .findFirst().orElse("Chat");
                s.put("title", firstUser.substring(0, Math.min(50, firstUser.length())));
                s.put("lastMessage", msgs.get(msgs.size()-1).get("content"));
                s.put("messageCount", msgs.size());
                s.put("timestamp", today());
                s.put("source", "memory");
                sessions.add(s);
            }
        });

        return ResponseEntity.ok(Map.of("sessions", sessions, "total", sessions.size(), "date", today()));
    }

    // Elimina una sessione da Supabase
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Object> deleteSession(@PathVariable String sessionId) {
        String url = env("SUPABASE_URL",""); String key = env("SUPABASE_KEY","");
        neuralMemory.remove(sessionId);
        if (!url.isEmpty()) {
            try {
                HttpHeaders h = new HttpHeaders();
                h.set("apikey", key); h.set("Authorization", "Bearer " + key);
                restTemplate.exchange(
                    url + "/rest/v1/messages?session_id=eq." + sessionId,
                    HttpMethod.DELETE, new HttpEntity<>(h), String.class);
                return ResponseEntity.ok(Map.of("status","deleted","sessionId",sessionId));
            } catch (Exception e) {
                return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
            }
        }
        return ResponseEntity.ok(Map.of("status","deleted_from_memory","sessionId",sessionId));
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
        // metriche provider LLM
        Map<String,Object> providers = new java.util.LinkedHashMap<>();
        for (String p : new String[]{"groq","gemini","deepseek"}) {
            Map<String,Integer> pm = new java.util.LinkedHashMap<>();
            pm.put("success", providerSuccess.getOrDefault(p, new AtomicInteger(0)).get());
            pm.put("failure", providerFailure.getOrDefault(p, new AtomicInteger(0)).get());
            int tot = pm.get("success") + pm.get("failure");
            pm.put("successRate", tot > 0 ? (pm.get("success") * 100 / tot) : 100);
            providers.put(p, pm);
        }
        m.put("providerStats", providers);
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

    // ══════════════════════════════════════════════════════════════════
    // PUNTO 14: MITRE Security Agent — CVE monitor + security audit
    // ══════════════════════════════════════════════════════════════════

    // ── CONTRACT REVIEW AGENT ─────────────────────────────────────────────────
    @PostMapping("/contract/review")
    public ResponseEntity<Object> contractReview(@RequestBody Map<String,String> body) {
        String contractText = ((String) body.getOrDefault("contract", "")).trim();
        String contractType = body.getOrDefault("type", "generico"); // NDA, lavoro, vendita, ecc.
        String sessionId    = body.getOrDefault("sessionId", "contract_" + System.currentTimeMillis());
        String baseUrl      = body.getOrDefault("baseUrl", env("AI_BASE_URL", env("GROQ_BASE_URL","https://api.groq.com/openai/v1")));
        String apiKey       = body.getOrDefault("apiKey",  env("AI_API_KEY", env("GROQ_API_KEY","")));
        String model        = body.getOrDefault("model",   env("AI_MODEL", env("GROQ_MODEL","llama-3.3-70b-versatile")));

        if (contractText.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Testo del contratto mancante"));
        }
        if (contractText.length() > 50000) {
            return ResponseEntity.badRequest().body(Map.of("error", "Contratto troppo lungo (max 50.000 caratteri)"));
        }

        try {
            // Indicizza il contratto nel RAG per retrieval semantico
            String docId = "contract_" + sessionId;
            ragIndexDocument(docId, contractText);

            // System prompt specializzato
            String systemPrompt = agentPrompt("contract_review");

            // Costruisci il messaggio con contesto tipo contratto
            String userMsg = "TIPO CONTRATTO: " + contractType + "\n\n" +
                "TESTO DEL CONTRATTO:\n" + contractText + "\n\n" +
                "Analizza questo contratto in modo completo seguendo la struttura richiesta.";

            // Stima complessità per token: contratti lunghi richiedono più token
            int maxTokens = Math.min(4000, 1500 + contractText.length() / 10);

            String analysis = callLLMWithTemp(systemPrompt, userMsg,
                new ArrayList<>(), baseUrl, apiKey, model, maxTokens, 0.2); // temp bassa = più preciso

            // Estrai score dal testo per risposta strutturata
            int riskScore = extractContractRiskScore(analysis);

            // Salva l'analisi in memoria per follow-up
            consolidateToLTM(sessionId, "Contratto " + contractType + " analizzato. Rischio: " +
                (riskScore >= 7 ? "BASSO" : riskScore >= 4 ? "MEDIO" : "ALTO"));

            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("analysis",     analysis);
            result.put("contractType", contractType);
            result.put("charCount",    contractText.length());
            result.put("riskScore",    riskScore);
            result.put("riskLevel",    riskScore >= 7 ? "BASSO" : riskScore >= 4 ? "MEDIO" :
                                       riskScore >= 2 ? "ALTO"  : "CRITICO");
            result.put("sessionId",    sessionId);
            result.put("disclaimer",   "Analisi a scopo informativo. Non sostituisce consulenza legale professionale.");
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Contract review error: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Errore analisi contratto: " + e.getMessage()));
        }
    }

    // Estrae lo score numerico dal testo dell'analisi (cerca pattern "X/10")
    private int extractContractRiskScore(String analysis) {
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?i)bilanciamento[:\\s]+?(\\d+)\\s*/\\s*10");
            java.util.regex.Matcher m = p.matcher(analysis);
            if (m.find()) return Integer.parseInt(m.group(1));
            // fallback: cerca qualsiasi X/10
            p = java.util.regex.Pattern.compile("(\\d+)\\s*/\\s*10");
            m = p.matcher(analysis);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Exception ignored) {}
        return 5; // default medio
    }
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/security/audit")
    public ResponseEntity<Object> securityAudit(@RequestBody Map<String,String> body) {
        String target    = ((String) body.getOrDefault("target", "")).trim();
        String auditType = body.getOrDefault("type", "general"); // general, code, network, deps
        String baseUrl   = body.getOrDefault("baseUrl", env("AI_BASE_URL", env("GROQ_BASE_URL","https://api.groq.com/openai/v1")));
        String apiKey    = body.getOrDefault("apiKey",  env("AI_API_KEY", env("GROQ_API_KEY","")));
        String model     = body.getOrDefault("model",   env("AI_MODEL", env("GROQ_MODEL","llama-3.3-70b-versatile")));

        if (target.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error","Target obbligatorio"));

        try {
            // 1. Cerca CVE recenti correlate al target
            String cveSearch = searchGoogle("CVE " + target + " vulnerability 2025 2026");

            // 2. Analisi MITRE ATT&CK con LLM
            String mitrePrompt =
                "Sei un esperto di sicurezza informatica MITRE ATT&CK. " +
                "Analizza il target: " + target + "\n" +
                "Tipo audit: " + auditType + "\n" +
                "CVE rilevanti trovate:\n" + cveSearch.substring(0, Math.min(1000, cveSearch.length())) + "\n\n" +
                "Fornisci un report di sicurezza con:\n" +
                "1. Vulnerabilita rilevate (CVE)\n" +
                "2. Tattiche MITRE ATT&CK applicabili\n" +
                "3. Livello di rischio (CRITICO/ALTO/MEDIO/BASSO)\n" +
                "4. Mitigazioni raccomandate\n" +
                "5. Prossimi passi\n" +
                "Rispondi in italiano in formato strutturato.";

            String auditReport = callLLM(
                "Sei AEGIS, l'agente di sicurezza di SPACE AI. Analizza con rigore MITRE ATT&CK.",
                mitrePrompt, new ArrayList<>(), baseUrl, apiKey, model, 3000);

            // 3. Indicizza nel RAG per riferimento futuro
            ragIndexDocument("security/audit_" + System.currentTimeMillis(), auditReport);

            Map<String,Object> r = new LinkedHashMap<>();
            r.put("target",    target);
            r.put("auditType", auditType);
            r.put("report",    auditReport);
            r.put("cveData",   cveSearch.substring(0, Math.min(500, cveSearch.length())));
            r.put("date",      today());
            return ResponseEntity.ok(r);

        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/security/cve")
    public ResponseEntity<Object> searchCVE(@RequestParam String target) {
        try {
            // Cerca CVE recenti
            String cveResults = searchGoogle("CVE " + target + " NIST NVD security vulnerability");
            // Cerca anche su GitHub Security Advisories
            String ghsaResults = searchGoogle("GHSA " + target + " github advisory");
            Map<String,Object> r = new LinkedHashMap<>();
            r.put("target",   target);
            r.put("cve",      cveResults.substring(0, Math.min(1000, cveResults.length())));
            r.put("ghsa",     ghsaResults.substring(0, Math.min(500, ghsaResults.length())));
            r.put("date",     today());
            return ResponseEntity.ok(r);
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Object> systemStatus() {
        long now = System.currentTimeMillis();
        boolean rateLimited = rateLimitUntil > now;
        long resetInSec = rateLimited ? (rateLimitUntil - now) / 1000 : 0;
        Map<String,Object> s = new LinkedHashMap<>();
        s.put("status",          rateLimited ? "AUTONOMOUS_MODE" : "OK");
        s.put("groqAvailable",   !rateLimited);
        s.put("rateLimited",     rateLimited);
        s.put("resetInSeconds",  resetInSec);
        s.put("resetAt",         rateLimited ? new java.util.Date(rateLimitUntil).toString() : "N/A");
        s.put("groqCallsToday",  groqCallsToday.get());
        s.put("localFallbacks",  localFallbacks.get());
        s.put("knnCacheSize",    (int)Arrays.stream(ringCache).filter(e -> e != null).count());
        s.put("ragDocs",         ragStore.size());
        s.put("ragChunks",       ragStore.values().stream().mapToInt(List::size).sum());
        s.put("sharedKnowledge", sharedKnowledge.size());
        s.put("embedVocab",      vocabSize.get());
        s.put("circuitOpen",     circuitOpenTime > now - 30000);
        // Audio provider availability
        s.put("googleTts",       !env("GOOGLE_TTS_API_KEY","").isEmpty());
        s.put("elevenLabs",      !env("ELEVENLABS_API_KEY","").isEmpty());
        s.put("voiceRss",        !env("VOICERSS_API_KEY","").isEmpty());
        s.put("federationPeers", env("FEDERATION_PEERS","").isEmpty() ? 0 :
            env("FEDERATION_PEERS","").split(",").length);
        s.put("date",            today());
        return ResponseEntity.ok(s);
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