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
    def wrap(old, new, label):
        global content
        if new in content:
            print(f"  {label}: gia applicato")
            return
        rep(old, new, label)
    wrap('env("GROQ_API_KEY","")',
         'env("AI_API_KEY",env("GROQ_API_KEY",""))', 'GROQ_API_KEY -> AI_API_KEY')
    wrap('env("GROQ_BASE_URL","https://api.groq.com/openai/v1")',
         'env("AI_BASE_URL",env("GROQ_BASE_URL","https://api.groq.com/openai/v1"))', 'GROQ_BASE_URL -> AI_BASE_URL')
    wrap('env("GROQ_MODEL","openai/gpt-oss-120b")',
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

    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
except Exception as e:
    print(f"  ⚠️  Patch immagini/video non applicata: {e}", file=sys.stderr)
IMGEOF
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
