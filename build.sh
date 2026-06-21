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

echo "✅ Build completata!"
ls -lh target/*.jar 2>/dev/null
