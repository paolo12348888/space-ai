#!/usr/bin/env bash
set -e

echo "🌌 SPACE AI — Build su Render"
echo "================================"

# ════════════════════════════════════════════════════════
# STEP 1: Fix SpaceAIApplication.java — versione web server
# ════════════════════════════════════════════════════════
APPDIR="src/main/java/com/spaceai"
mkdir -p "$APPDIR"
cat > "$APPDIR/SpaceAIApplication.java" << 'JAVAEOF'
package com.spaceai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
public class SpaceAIApplication {
    public static void main(String[] args) {
        SpringApplication.run(SpaceAIApplication.class, args);
    }
}
JAVAEOF
echo "✅ SpaceAIApplication.java (web mode) scritto"

# ════════════════════════════════════════════════════════
# STEP 2: Fix application.yml — web server mode
# ════════════════════════════════════════════════════════
RESDIR="src/main/resources"
mkdir -p "$RESDIR"
cat > "$RESDIR/application.yml" << 'YMLEOF'
spring:
  main:
    web-application-type: servlet
    banner-mode: off
  threads:
    virtual:
      enabled: true
  ai:
    anthropic:
      api-key: ${AI_API_KEY:missing-key}
      base-url: ${AI_BASE_URL:https://api.anthropic.com}
      chat:
        options:
          model: ${AI_MODEL:claude-sonnet-4-20250514}
          max-tokens: ${AI_MAX_TOKENS:8096}
          temperature: 0.7
    model:
      embedding: none
      image: none
      audio:
        speech: none
        transcription: none
      moderation: none
    openai:
      api-key: ${AI_API_KEY:missing-key}
      base-url: ${AI_BASE_URL:https://api.openai.com}
      chat:
        options:
          model: ${AI_MODEL:gpt-4o}

space-ai:
  provider: ${SPACE_AI_PROVIDER:openai}

server:
  port: ${PORT:10000}

logging:
  level:
    root: WARN
    com.spaceai: INFO
    org.springframework.web: INFO
YMLEOF
echo "✅ application.yml (servlet mode) scritto"

# ════════════════════════════════════════════════════════
# STEP 2b: Su server la REPL da terminale non serve: disattivata
# ════════════════════════════════════════════════════════
CLIDIR="src/main/java/com/spaceai/cli"
mkdir -p "$CLIDIR"
cat > "$CLIDIR/SpaceAIRunner.java" << 'RUNEOF'
package com.spaceai.cli;

import com.spaceai.repl.ReplSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class SpaceAIRunner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(SpaceAIRunner.class);
    private final ReplSession replSession;

    public SpaceAIRunner(ReplSession replSession) {
        this.replSession = replSession;
    }

    @Override
    public void run(String... args) {
        log.info("Space AI web server pronto (REPL da terminale disattivata)");
    }
}
RUNEOF
echo "✅ SpaceAIRunner (senza REPL) scritto"

# ════════════════════════════════════════════════════════
# STEP 2c: Pagina di diagnostica TEMPORANEA /api/diag
# (mostra l'errore vero del provider AI; da rimuovere a problema risolto)
# ════════════════════════════════════════════════════════
WEBDIR="src/main/java/com/spaceai/web"
mkdir -p "$WEBDIR"
cat > "$WEBDIR/DiagController.java" << 'DIAGEOF'
package com.spaceai.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DIAGNOSTICA TEMPORANEA: GET /api/diag
 * Prova la stessa chiamata che fa la chat e mostra l'errore vero del provider.
 * Non mostra mai la chiave. Da RIMUOVERE quando il problema e' risolto.
 */
@RestController
@RequestMapping("/api")
public class DiagController {

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null ? d : v;
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        s = s.replace("\n", " ").replace("\r", " ");
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }

    private static String jsonEsc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @GetMapping("/diag")
    public Map<String, Object> diag() {
        Map<String, Object> out = new LinkedHashMap<>();
        String baseUrl = env("AI_BASE_URL", "https://api.groq.com/openai/v1");
        String key = env("AI_API_KEY", "");
        String model = env("AI_MODEL", "llama-3.3-70b-versatile");

        out.put("baseUrl", baseUrl);
        out.put("model", model);
        out.put("keyLength", key.length());
        out.put("keyPrefix", key.trim().length() >= 4 ? key.trim().substring(0, 4) : "");
        out.put("keyHasWhitespace", !key.equals(key.trim()));
        out.put("baseUrlHasWhitespace", !baseUrl.equals(baseUrl.trim()));
        out.put("modelHasWhitespace", !model.equals(model.trim()));

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;

        // 1) Elenco modelli disponibili con questa chiave
        try {
            HttpRequest r = HttpRequest.newBuilder(URI.create(base + "/models"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + key)
                .header("User-Agent", "SPACE-AI/4.0")
                .GET().build();
            HttpResponse<String> resp = http.send(r, HttpResponse.BodyHandlers.ofString());
            out.put("modelsHttpStatus", resp.statusCode());
            if (resp.statusCode() == 200) {
                List<String> ids = new ArrayList<>();
                Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(resp.body());
                while (m.find() && ids.size() < 40) ids.add(m.group(1));
                out.put("configuredModelExists", ids.contains(model.trim()));
                out.put("availableModels", ids);
            } else {
                out.put("modelsError", clip(resp.body(), 300));
            }
        } catch (Exception e) {
            out.put("modelsException", e.getClass().getSimpleName() + ": " + clip(e.getMessage(), 200));
        }

        // 2) Stessa chiamata della chat, minima
        try {
            String body = "{\"model\":\"" + jsonEsc(model) + "\",\"max_tokens\":5,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"ciao\"}]}";
            HttpRequest r = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .header("User-Agent", "SPACE-AI/4.0")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> resp = http.send(r, HttpResponse.BodyHandlers.ofString());
            out.put("chatHttpStatus", resp.statusCode());
            out.put("chatResponse", clip(resp.body(), 400));
        } catch (Exception e) {
            out.put("chatException", e.getClass().getSimpleName() + ": " + clip(e.getMessage(), 200));
        }
        return out;
    }
    private static Map<String, Object> probe(HttpClient http, HttpRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            HttpResponse<byte[]> resp = http.send(r, HttpResponse.BodyHandlers.ofByteArray());
            m.put("status", resp.statusCode());
            m.put("contentType", resp.headers().firstValue("content-type").orElse(""));
            m.put("bytes", resp.body().length);
            String ct = resp.headers().firstValue("content-type").orElse("");
            if (!ct.startsWith("image/")) {
                m.put("body", clip(new String(resp.body(), java.nio.charset.StandardCharsets.UTF_8), 250));
            }
        } catch (Exception e) {
            m.put("exception", e.getClass().getSimpleName() + ": " + clip(e.getMessage(), 160));
        }
        return m;
    }

    /** GET /api/diag/image : stato di ogni motore immagini (TEMPORANEO). */
    @GetMapping("/diag/image")
    public Map<String, Object> diagImage() {
        Map<String, Object> out = new LinkedHashMap<>();
        String pollKey = env("POLLINATIONS_API_KEY", "").trim();
        String hfKey = env("HF_TOKEN", "").trim();
        String repKey = env("REPLICATE_API_TOKEN", "").trim();
        out.put("hasPollinationsKey", !pollKey.isEmpty());
        out.put("hasHfToken", !hfKey.isEmpty());
        out.put("hasReplicateToken", !repKey.isEmpty());

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(
                "https://gen.pollinations.ai/image/a%20red%20apple?model=flux&width=256&height=256"))
                .timeout(Duration.ofSeconds(40)).header("User-Agent", "SPACE-AI/4.0");
            if (!pollKey.isEmpty()) b.header("Authorization", "Bearer " + pollKey);
            out.put("pollinationsNuovo", probe(http, b.GET().build()));
        } catch (Exception e) { out.put("pollinationsNuovo", e.getClass().getSimpleName()); }
        try {
            out.put("pollinationsVecchio", probe(http, HttpRequest.newBuilder(URI.create(
                "https://image.pollinations.ai/prompt/a%20red%20apple?width=256&height=256&nologo=true"))
                .timeout(Duration.ofSeconds(40)).header("User-Agent", "SPACE-AI/4.0").GET().build()));
        } catch (Exception e) { out.put("pollinationsVecchio", e.getClass().getSimpleName()); }
        if (!hfKey.isEmpty()) {
            try {
                out.put("huggingFace", probe(http, HttpRequest.newBuilder(URI.create(
                    "https://router.huggingface.co/hf-inference/models/black-forest-labs/FLUX.1-schnell"))
                    .timeout(Duration.ofSeconds(40))
                    .header("Authorization", "Bearer " + hfKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"inputs\":\"a red apple\"}")).build()));
            } catch (Exception e) { out.put("huggingFace", e.getClass().getSimpleName()); }
        }
        if (!repKey.isEmpty()) {
            try {
                out.put("replicateAccount", probe(http, HttpRequest.newBuilder(URI.create(
                    "https://api.replicate.com/v1/account"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + repKey).GET().build()));
            } catch (Exception e) { out.put("replicateAccount", e.getClass().getSimpleName()); }
        }
        return out;
    }
}
DIAGEOF
echo "✅ DiagController scritto"

# ════════════════════════════════════════════════════════
# STEP 3: Auto-fix ChatController bug riga 3441
# ════════════════════════════════════════════════════════
CTRL="src/main/java/com/spaceai/web/ChatController.java"
if [ -f "$CTRL" ]; then
    echo "🔧 Auto-fix ChatController..."
    python3 - << 'PYEOF'
import re, sys

path = "src/main/java/com/spaceai/web/ChatController.java"
try:
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    original = content

    # Fix: (String)X.getOrDefault(...) su Map<?,?>
    content = re.sub(
        r'\(String\)\s*(\w+)\.getOrDefault\(([^)]+)\)',
        lambda m: f'String.valueOf({m.group(1)}.getOrDefault({m.group(2)}))',
        content
    )
    # Fix: (String)X.get(...) su Map<?,?>
    content = re.sub(
        r'\(String\)\s*(\w+)\.get\(([^)]+)\)',
        lambda m: f'String.valueOf({m.group(1)}.get({m.group(2)}))',
        content
    )

    # Fix: Map<?,?>.getOrDefault("output","") non compila (riga 3441)
    content = content.replace(
        'Object _o = pr.getOrDefault("output","");',
        'Object _o = pr.get("output");'
    )

    if content != original:
        with open(path, "w", encoding="utf-8") as f:
            f.write(content)
        print("  ✅ Bug fix applicato")
    else:
        print("  ℹ️  File già corretto")

    lines = content.split('\n')
    if len(lines) > 3440:
        print(f"  Riga 3441: {lines[3440].strip()}")
except Exception as e:
    print(f"  ⚠️  Errore: {e}", file=sys.stderr)
PYEOF
fi

# ════════════════════════════════════════════════════════
# STEP 3b: Immagini piu fedeli al testo + modello Groq ritirato + variabili AI_*
# (blocco separato: se fallisce, il fix del bug riga 3441 resta applicato)
# ════════════════════════════════════════════════════════
if [ -f "$CTRL" ]; then
    echo "🎨 Patch immagini/video..."
    python3 - << 'IMGEOF'
import sys

path = "src/main/java/com/spaceai/web/ChatController.java"

JAVA_BLOCK = r'''private String enhancePromptForSD(String prompt) {
        // Traduzione fedele via LLM (nessun tag di stile aggiunto). Se non disponibile, traduttore base.
        String faithful = translatePromptFaithful(prompt);
        if (faithful != null) return faithful;
        return enhancePromptForSDLegacy(prompt);
    }

    private String translatePromptFaithful(String prompt) {
        try {
            if (prompt == null || prompt.isBlank()) return null;
            if (rateLimitUntil > System.currentTimeMillis()) return null;
            String sys = "You convert an image request into one precise English prompt for a text-to-image model. "
                + "Keep EVERY detail the user gave: subjects, how many, colors, clothing, objects, setting, time of day, style, mood, camera angle. "
                + "Do NOT add details, styles or quality words the user did not ask for. "
                + "Drop request words such as create an image of or draw. "
                + "Output ONLY the final English prompt on a single line, with no quotes and no comments.";
            String out = callLLM(sys, prompt, new java.util.ArrayList<>(),
                env("AI_BASE_URL", "https://api.groq.com/openai/v1"),
                env("AI_API_KEY", ""),
                env("AI_MODEL", "openai/gpt-oss-120b"), 300);
            if (out == null) return null;
            out = out.trim();
            if (out.length() > 1 && out.startsWith("\"") && out.endsWith("\"")) out = out.substring(1, out.length() - 1).trim();
            if (out.length() < 5 || out.indexOf('\n') >= 0 || out.length() > Math.max(400, prompt.length() * 4)) return null;
            log.info("Image prompt tradotto fedelmente: [{}] -> [{}]", prompt, out);
            return out;
        } catch (Exception e) {
            log.warn("Traduzione prompt via LLM fallita, uso traduttore base: {}", e.getMessage());
            return null;
        }
    }

    '''

NEW_ENGINES = r'''        // --- Motori nuovi: Pollinations (gen, con chiave) e Replicate FLUX schnell ---
        {
            String pk_n = env("POLLINATIONS_API_KEY", "").trim();
            if (!pk_n.isEmpty()) {
                try {
                    long seed_n = Math.abs(engPrompt.hashCode()) % 99999;
                    String enc_n = java.net.URLEncoder.encode(engPrompt, "UTF-8").replace("+", "%20");
                    String url_n = "https://gen.pollinations.ai/image/" + enc_n
                        + "?model=flux&width=1024&height=1024&nologo=true&seed=" + seed_n;
                    HttpHeaders hh_n = new HttpHeaders();
                    hh_n.setBearerAuth(pk_n);
                    hh_n.set("User-Agent", "SPACE-AI/4.0");
                    ResponseEntity<byte[]> r_n = imgClient.exchange(url_n,
                        org.springframework.http.HttpMethod.GET, new HttpEntity<>(hh_n), byte[].class);
                    if (r_n.getStatusCode().is2xxSuccessful() && r_n.getBody() != null && r_n.getBody().length > 5000) {
                        log.info("Pollinations (gen) OK: {} bytes", r_n.getBody().length);
                        return "IMAGE:" + java.util.Base64.getEncoder().encodeToString(r_n.getBody());
                    }
                    log.warn("Pollinations (gen) risposta insufficiente");
                } catch (Exception e_n) {
                    String st_n = (e_n instanceof org.springframework.web.client.HttpStatusCodeException)
                        ? String.valueOf(((org.springframework.web.client.HttpStatusCodeException) e_n).getStatusCode().value()) : "";
                    log.warn("Pollinations (gen) fallito: {} {}", e_n.getClass().getSimpleName(), st_n);
                }
            }
            String rk_n = env("REPLICATE_API_TOKEN", "").trim();
            if (!rk_n.isEmpty()) {
                try {
                    HttpHeaders rh_n = new HttpHeaders();
                    rh_n.setContentType(MediaType.APPLICATION_JSON);
                    rh_n.setBearerAuth(rk_n);
                    rh_n.set("Prefer", "wait");
                    ObjectNode in_n = MAPPER.createObjectNode();
                    in_n.put("prompt", engPrompt);
                    in_n.put("aspect_ratio", "1:1");
                    in_n.put("output_format", "jpg");
                    in_n.put("num_outputs", 1);
                    ObjectNode rq_n = MAPPER.createObjectNode();
                    rq_n.set("input", in_n);
                    ResponseEntity<String> rr_n = imgClient.postForEntity(
                        "https://api.replicate.com/v1/models/black-forest-labs/flux-schnell/predictions",
                        new HttpEntity<>(MAPPER.writeValueAsString(rq_n), rh_n), String.class);
                    JsonNode rj_n = MAPPER.readTree(rr_n.getBody());
                    JsonNode o_n = rj_n.path("output");
                    String ou_n = (o_n.isArray() && o_n.size() > 0) ? o_n.get(0).asText() : "";
                    if (!ou_n.isEmpty()) {
                        ResponseEntity<byte[]> ir_n = imgClient.getForEntity(ou_n, byte[].class);
                        if (ir_n.getBody() != null && ir_n.getBody().length > 3000) {
                            log.info("Replicate FLUX OK: {} bytes", ir_n.getBody().length);
                            return "IMAGE:" + java.util.Base64.getEncoder().encodeToString(ir_n.getBody());
                        }
                    }
                    log.warn("Replicate: nessuna immagine (stato {})", rj_n.path("status").asText());
                } catch (Exception e_n) {
                    String st_n = (e_n instanceof org.springframework.web.client.HttpStatusCodeException)
                        ? String.valueOf(((org.springframework.web.client.HttpStatusCodeException) e_n).getStatusCode().value()) : "";
                    log.warn("Replicate immagine fallito: {} {}", e_n.getClass().getSimpleName(), st_n);
                }
            }
        }

'''
ANCHOR = 'String[] pollinationModels = {"flux", "turbo", "flux-realism"};'

try:
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    def rep(old, new, label):
        global content
        n = content.count(old)
        if n:
            content = content.replace(old, new)
        print(f"  {label}: {n} punti")

    # --- Modello Groq ritirato + variabili GROQ_* mai impostate su Render ---
    rep('llama-3.3-70b-versatile', 'openai/gpt-oss-120b', 'modello ritirato sostituito')
    rep('env("GROQ_API_KEY","")',
        'env("AI_API_KEY",env("GROQ_API_KEY",""))', 'GROQ_API_KEY -> AI_API_KEY')
    rep('env("GROQ_BASE_URL","https://api.groq.com/openai/v1")',
        'env("AI_BASE_URL",env("GROQ_BASE_URL","https://api.groq.com/openai/v1"))', 'GROQ_BASE_URL -> AI_BASE_URL')
    rep('env("GROQ_MODEL","openai/gpt-oss-120b")',
        'env("AI_MODEL",env("GROQ_MODEL","openai/gpt-oss-120b"))', 'GROQ_MODEL -> AI_MODEL')

    # --- Immagini piu fedeli al testo ---
    rep('return eng + qualityTags;', 'return eng;', 'tag di stile forzati rimossi')
    rep('enhance=true', 'enhance=false', 'Pollinations enhance disattivato')
    rep('https://api-inference.huggingface.co/models/',
        'https://router.huggingface.co/hf-inference/models/', 'endpoint HuggingFace aggiornato')

    sig = 'private String enhancePromptForSD(String prompt) {'
    if sig in content and 'translatePromptFaithful' not in content:
        content = content.replace(sig, JAVA_BLOCK + 'private String enhancePromptForSDLegacy(String prompt) {', 1)
        print("  traduzione fedele via LLM: attivata")
    else:
        print("  traduzione fedele via LLM: gia presente o firma non trovata")

    # --- Motori immagini nuovi (prima dei vecchi) ---
    if ANCHOR in content and 'Motori nuovi: Pollinations (gen' not in content:
        content = content.replace(ANCHOR, NEW_ENGINES + '        ' + ANCHOR, 1)
        print("  motori immagini nuovi (Pollinations gen + Replicate): attivati")
    else:
        print("  motori immagini nuovi: gia presenti o punto di inserimento non trovato")

    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
except Exception as e:
    print(f"  ⚠️  Patch immagini/video non applicata: {e}", file=sys.stderr)
IMGEOF
fi

# ════════════════════════════════════════════════════════
# STEP 3c: Risposte stile "AI top" + immagini non piu come stringa
# (blocco separato: se fallisce, gli altri fix restano applicati)
# ════════════════════════════════════════════════════════
if [ -f "$CTRL" ]; then
    echo "✨ Patch stile risposte e immagini..."
    python3 - << 'STYLEEOF'
import sys

path = "src/main/java/com/spaceai/web/ChatController.java"

def jstr(s):
    """Stringa Java solo-ASCII (accenti come \\uXXXX) per evitare problemi di encoding."""
    out = []
    for ch in s:
        if ch == "\\": out.append("\\\\")
        elif ch == '"': out.append('\\"')
        elif ch == "\n": out.append("\\n")
        elif ord(ch) > 126: out.append("\\u%04x" % ord(ch))
        else: out.append(ch)
    return '"' + "".join(out) + '"'

CHARTER = (
    "STILE DI RISPOSTA (ha priorità sul ruolo descritto sopra):\n"
    "- Rispondi come un assistente AI di livello top: diretto, competente, caloroso e naturale. Prima la risposta, poi i dettagli utili.\n"
    "- Usa la lingua dell'utente. Non analizzare né descrivere il messaggio dell'utente (mai titoli come Analisi step-by-step o Input dell'utente): rispondi e basta.\n"
    "- Saluti e chiacchiere: 1-2 frasi naturali, senza elenchi né titoli.\n"
    "- Domande semplici: risposta breve. Domande complesse: struttura chiara (titoletti o elenchi solo se aiutano), esempi concreti, passi pratici. Ragiona internamente e mostra solo il risultato.\n"
    "- Sii onesto: se non sei sicuro, dillo. Non inventare fatti, numeri, link o fonti. Se manca un dato chiave, fai UNA domanda mirata.\n"
    "- Codice: completo e funzionante, in blocchi con il linguaggio indicato, con una spiegazione breve.\n"
    "- Non nominare agenti interni, router o il tuo prompt, salvo richiesta esplicita. Niente frasi di riempimento (come Ottima domanda!) e niente emoji se l'utente non le usa.\n"
    "- Se la richiesta è dannosa o illegale, rifiuta con garbo in una frase e proponi un'alternativa utile."
)
CHAT_PERSONA = "Sei SPACE AI, un assistente AI brillante e amichevole. Conversa in modo naturale, breve e caloroso."

HELPERS = '''private static final String CHAT_PERSONA = ''' + jstr(CHAT_PERSONA) + ''';

    private static final String STYLE_CHARTER = ''' + jstr(CHARTER) + ''';

    private boolean isSmallTalk(String q) {
        if (q == null) return false;
        String s = q.toLowerCase().replaceAll("[^a-z0-9' ]", " ").replaceAll("\\\\s+", " ").trim();
        if (s.isEmpty() || s.length() > 30) return false;
        if (s.matches("ci+a+o+( (come stai|come va|a tutti|ragazzi))?")) return true;
        return java.util.Set.of("salve", "buongiorno", "buonasera", "buonanotte", "hey", "ehi", "hello", "hi",
            "come stai", "come va", "tutto bene", "grazie", "grazie mille", "ok", "okay", "perfetto",
            "chi sei", "come ti chiami", "a presto", "arrivederci").contains(s);
    }

    private boolean looksLikeImageRequest(String msg) {
        if (msg == null) return false;
        String q = msg.toLowerCase();
        return q.contains("genera immagine") || q.contains("crea immagine")
            || (q.contains("immagine") && (q.contains("crea") || q.contains("genera")))
            || q.contains("disegna") || q.contains("illustra")
            || q.contains("crea svg") || q.contains("genera svg");
    }

    private String agentPrompt(String agent) {
        String base = agentPromptRaw(agent);
        if (agent == null) return base;
        switch (agent) {
            case "router": case "image_gen": case "spaces":
                return base;   // formato vincolato: nessuno stile aggiunto
            case "chat":
                return CHAT_PERSONA + " Data:" + today() + ". " + STYLE_CHARTER;
            default:
                return base + "\\n\\n" + STYLE_CHARTER;
        }
    }

    '''

SAFETY = r'''alResp.put("response",  agentLoopResult);
                        java.util.regex.Matcher imgM_n = java.util.regex.Pattern.compile("IMAGE:([A-Za-z0-9+/=]{2000,})").matcher(agentLoopResult);
                        if (imgM_n.find()) {
                            alResp.put("image", imgM_n.group(1));
                            alResp.put("imageType", "image/jpeg");
                            String txt_n = agentLoopResult.replace(imgM_n.group(0), "").trim();
                            alResp.put("response", txt_n.length() > 3 ? txt_n : "Ecco la tua immagine!");
                        }'''

try:
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    def rep(old, new, label, once=False):
        global content
        n = content.count(old)
        if n:
            content = content.replace(old, new, 1) if once else content.replace(old, new)
        print(f"  {label}: {n} punti")
        return n

    # --- 1) Stile delle risposte ---
    sig = "private String agentPrompt(String agent) {"
    if sig in content and "STYLE_CHARTER" not in content:
        content = content.replace(sig, HELPERS + "private String agentPromptRaw(String agent) {", 1)
        print("  stile risposte top-AI + chat naturale: attivato")
    else:
        print("  stile risposte: gia presente o firma non trovata")

    rep("Analisi step-by-step,logica formale. Rispondi in italiano.",
        "Ragiona con rigore su logica, analisi e decisioni; mostra i passaggi solo se la domanda li richiede. Rispondi in italiano.",
        "prompt REASONER reso naturale")

    rsig = "private List<String> routeQuery(String query, String baseUrl, String apiKey, String model) {"
    if rsig in content and "isSmallTalk(query)" not in content:
        content = content.replace(rsig, rsig + "\n        if (isSmallTalk(query)) return List.of(\"chat\");", 1)
        print("  saluti/chiacchiere: risposta diretta senza router: attivata")

    rep("String response = callLLMWithTemp(agentWithMemory, finalMsg, history, baseUrl, apiKey, model, 2500, temperature);",
        "String response = callLLMWithTemp(agentWithMemory, finalMsg, history, baseUrl, apiKey, model, 4096, temperature);",
        "spazio risposta (token) aumentato")

    # --- 2) Immagini restituite come stringa ---
    rep("if (needsAgentLoop(userMessage)) {",
        "if (needsAgentLoop(userMessage) && !looksLikeImageRequest(userMessage)) {",
        "richieste di immagini non passano piu dall'Agent Loop")
    if "imgM_n" not in content:
        rep('alResp.put("response",  agentLoopResult);', SAFETY,
            "rete di sicurezza: immagine dentro il testo -> immagine vera")
    else:
        print("  rete di sicurezza: gia presente")

    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
except Exception as e:
    print(f"  ⚠️  Patch stile/immagini non applicata: {e}", file=sys.stderr)
STYLEEOF
fi

# ════════════════════════════════════════════════════════
# STEP 4: Setup Java 21
# ════════════════════════════════════════════════════════
echo "📦 Setup Java 21..."
JAVA_URL="https://download.java.net/java/GA/jdk21.0.2/f2283984656d49d69e91c558476027ac/13/GPL/openjdk-21.0.2_linux-x64_bin.tar.gz"

if [ ! -d "$HOME/.java/jdk-21" ]; then
    mkdir -p "$HOME/.java"
    curl -fsSL "$JAVA_URL" | tar xz -C "$HOME/.java"
    mv "$HOME/.java"/jdk-21* "$HOME/.java/jdk-21" 2>/dev/null || true
fi

export JAVA_HOME="$HOME/.java/jdk-21"
export PATH="$JAVA_HOME/bin:$PATH"
echo "✅ Java 21"
java -version

mkdir -p .java_runtime
cp -r "$JAVA_HOME" .java_runtime/
echo "✅ Java copiato in $(pwd)/.java_runtime"

# ════════════════════════════════════════════════════════
# STEP 5: Setup Maven
# ════════════════════════════════════════════════════════
echo "📦 Setup Maven..."
MAVEN_URL="https://archive.apache.org/dist/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.tar.gz"

if [ ! -d "$HOME/.maven" ]; then
    mkdir -p "$HOME/.maven"
    curl -fsSL "$MAVEN_URL" | tar xz --strip-components=1 -C "$HOME/.maven"
fi

export PATH="$HOME/.maven/bin:$PATH"
echo "✅ Maven"
mvn -version

# ════════════════════════════════════════════════════════
# STEP 6: Build
# ════════════════════════════════════════════════════════
echo "🔨 Build Maven..."
mvn clean package -DskipTests

# Verifica: il JAR deve contenere la classe main, altrimenti fallisce subito
JAR=$(ls target/*.jar | grep -v '\.original$' | head -1)
if ! "$JAVA_HOME/bin/jar" tf "$JAR" | grep -q "BOOT-INF/classes/com/spaceai/SpaceAIApplication.class"; then
    echo "❌ ERRORE: SpaceAIApplication.class manca nel JAR (compilazione fallita?)"
    exit 1
fi
echo "✅ Build completata! Classe main presente nel JAR"
ls -lh target/*.jar 2>/dev/null
