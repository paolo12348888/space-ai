#!/usr/bin/env bash
set -e
echo "🌌 SPACE AI — Build su Render"
echo "================================"

# ════════════════════════════════════════
# AUTO-FIX prima della compilazione
# ════════════════════════════════════════
CTRL="src/main/java/com/spaceai/web/ChatController.java"
if [ -f "$CTRL" ]; then
  echo "🔧 Auto-fix ChatController..."
  python3 << 'PYEOF'
import re, sys
f = "src/main/java/com/spaceai/web/ChatController.java"
c = open(f).read()
# Fix 1: (String)X.getOrDefault(...) su Map<?,?>
c2 = re.sub(r'\(String\)\s*(\w+)\.getOrDefault\(([^)]+)\)',
            lambda m: f'String.valueOf({m.group(1)}.getOrDefault({m.group(2)}))', c)
# Fix 2: (String)X.get(...) su Map<?,?>  
c2 = re.sub(r'\(String\)\s*(\w+)\.get\(([^)]+)\)\s*(?=;|\))',
            lambda m: f'String.valueOf({m.group(1)}.get({m.group(2)}))', c2)
if c2 != c:
    open(f, 'w').write(c2)
    lines_changed = sum(1 for a,b in zip(c.split('\n'),c2.split('\n')) if a!=b)
    print(f"  ✅ Fixate {lines_changed} righe")
else:
    print("  ℹ️  File già corretto")
print(f"  Riga 3441: {c2.split(chr(10))[3440].strip()}")
PYEOF
fi

# ════════════════════════════════════════
# JAVA
# ════════════════════════════════════════
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

# ════════════════════════════════════════
# MAVEN
# ════════════════════════════════════════
echo "📦 Download Maven..."
MAVEN_URL="https://archive.apache.org/dist/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.tar.gz"
if [ ! -d "$HOME/.maven" ]; then
  mkdir -p "$HOME/.maven"
  curl -fsSL "$MAVEN_URL" | tar xz --strip-components=1 -C "$HOME/.maven"
fi
export PATH="$HOME/.maven/bin:$PATH"
echo "✅ Maven installato"
mvn -version

# ════════════════════════════════════════
# BUILD
# ════════════════════════════════════════
echo "🔨 Build Maven..."
mvn clean package -DskipTests -q
echo "✅ Build completata!"
