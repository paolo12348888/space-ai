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
