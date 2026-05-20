#!/usr/bin/env bash
set -e

echo "🌌 SPACE AI — Build su Render"
echo "================================"

# ════════════════════════════════════════════════════════
# AUTO-FIX: risolve il bug Map<?,?> prima della compilazione
# Questo fix viene applicato automaticamente ad ogni build
# ════════════════════════════════════════════════════════
CTRL="src/main/java/com/spaceai/web/ChatController.java"
if [ -f "$CTRL" ]; then
    echo "🔧 Applicazione auto-fix ChatController..."
    
    # Fix 1: Cast (String) su Map<?,?> - incompatible types
    if grep -q '(String)pr\.getOrDefault("output","")' "$CTRL"; then
        sed -i 's|(String)pr\.getOrDefault("output","")|pr.getOrDefault("output","").toString()|g' "$CTRL"
        echo "  ✅ Fix Map cast applicato"
    fi
    
    # Fix 2: Pattern alternativo con spazi
    if grep -q '(String) pr\.getOrDefault' "$CTRL"; then
        sed -i 's|(String) pr\.getOrDefault("output","")|(pr.getOrDefault("output","") != null ? pr.getOrDefault("output","").toString() : "")|g' "$CTRL"
        echo "  ✅ Fix Map cast v2 applicato"
    fi
    
    # Fix 3: Sostituzione blocco completo con versione sicura
    python3 - << 'PYEOF'
import re, sys

with open("src/main/java/com/spaceai/web/ChatController.java", "r") as f:
    content = f.read()

# Pattern che causa l'errore
patterns = [
    ('(String)pr.getOrDefault("output","")',
     'String.valueOf(pr.getOrDefault("output", ""))'),
    ('(String) pr.getOrDefault("output","")',
     'String.valueOf(pr.getOrDefault("output", ""))'),
    ('(String)pr.getOrDefault("output", "")',
     'String.valueOf(pr.getOrDefault("output", ""))'),
]

fixed = False
for old, new in patterns:
    if old in content:
        content = content.replace(old, new)
        fixed = True
        print(f"  ✅ Python fix: '{old[:40]}...' → sicuro")

# Fix generale: qualsiasi (String) su wildcard Map getOrDefault
new_content = re.sub(
    r'\(String\)\s*(\w+)\.getOrDefault\(([^)]+)\)',
    lambda m: f'String.valueOf({m.group(1)}.getOrDefault({m.group(2)}))',
    content
)
if new_content != content:
    content = new_content
    fixed = True
    print("  ✅ Python regex fix applicato")

if fixed:
    with open("src/main/java/com/spaceai/web/ChatController.java", "w") as f:
        f.write(content)
    print("  ✅ File salvato")
else:
    print("  ℹ️  Nessun fix necessario")
PYEOF
    
    echo "🔍 Verifica riga 3441:"
    sed -n '3441p' "$CTRL"
fi

# ════════════════════════════════════════════════════════
# SETUP JAVA
# ════════════════════════════════════════════════════════
echo "📦 Download Java 21..."
JAVA_URL="https://download.java.net/java/GA/jdk21.0.2/f2283984656d49d69e91c558476027ac/13/GPL/openjdk-21.0.2_linux-x64_bin.tar.gz"

if [ ! -d "$HOME/.java/jdk-21" ]; then
    mkdir -p "$HOME/.java"
    curl -fsSL "$JAVA_URL" | tar xz -C "$HOME/.java"
    mv "$HOME/.java"/jdk-21* "$HOME/.java/jdk-21" 2>/dev/null || true
fi

export JAVA_HOME="$HOME/.java/jdk-21"
export PATH="$JAVA_HOME/bin:$PATH"
echo "✅ Java 21 installato"
java -version

echo "📦 Copia Java nella cartella progetto per il runtime..."
mkdir -p .java_runtime
cp -r "$JAVA_HOME" .java_runtime/
echo "✅ Java copiato in $(pwd)/.java_runtime"

# ════════════════════════════════════════════════════════
# SETUP MAVEN
# ════════════════════════════════════════════════════════
echo "📦 Download Maven..."
MAVEN_URL="https://archive.apache.org/dist/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.tar.gz"

if [ ! -d "$HOME/.maven" ]; then
    mkdir -p "$HOME/.maven"
    curl -fsSL "$MAVEN_URL" | tar xz --strip-components=1 -C "$HOME/.maven"
fi

export PATH="$HOME/.maven/bin:$PATH"
echo "✅ Maven installato"
mvn -version

# ════════════════════════════════════════════════════════
# BUILD
# ════════════════════════════════════════════════════════
echo "🔨 Build Maven..."
mvn clean package -DskipTests -q

echo ""
echo "✅ Build completata con successo!"
echo "📦 JAR: $(ls target/*.jar 2>/dev/null | head -1)"
