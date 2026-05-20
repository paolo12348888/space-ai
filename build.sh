#!/bin/bash
set -e

echo "🌌 SPACE AI — Build su Render"
echo "================================"

HOME_DIR="/opt/render"
JAVA_HOME_DIR="$HOME_DIR/.java/jdk-21"
export JAVA_HOME="$JAVA_HOME_DIR"
export PATH="$JAVA_HOME/bin:$PATH"

# ── Java 21 ──────────────────────────────────────────
if [ ! -f "$JAVA_HOME/bin/java" ]; then
  echo "📦 Download Java 21..."
  mkdir -p "$HOME_DIR/.java"
  curl -sL "https://download.java.net/java/GA/jdk21.0.2/f2283984656d49d69e91c558476027ac/13/GPL/openjdk-21.0.2_linux-x64_bin.tar.gz" \
    -o /tmp/jdk21.tar.gz
  tar -xzf /tmp/jdk21.tar.gz -C "$HOME_DIR/.java/"
  mv "$HOME_DIR/.java/jdk-21.0.2" "$JAVA_HOME_DIR"
  rm /tmp/jdk21.tar.gz
  echo "✅ Java 21 installato"
fi
java -version

# Copia Java anche nella cartella del progetto
# così start.py lo trova anche al runtime
PROJECT_JAVA="$(pwd)/.java_runtime"
if [ ! -f "$PROJECT_JAVA/bin/java" ]; then
  echo "📦 Copia Java nella cartella progetto per il runtime..."
  mkdir -p "$PROJECT_JAVA"
  cp -r "$JAVA_HOME_DIR/"* "$PROJECT_JAVA/"
  echo "✅ Java copiato in $PROJECT_JAVA"
fi

# ── Maven ─────────────────────────────────────────────
MAVEN_HOME="$HOME_DIR/.maven"
export PATH="$MAVEN_HOME/bin:$PATH"

if [ ! -f "$MAVEN_HOME/bin/mvn" ]; then
  echo "📦 Download Maven..."
  mkdir -p "$MAVEN_HOME"
  curl -sL "https://archive.apache.org/dist/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.tar.gz" \
    -o /tmp/maven.tar.gz
  tar -xzf /tmp/maven.tar.gz -C "$MAVEN_HOME/" --strip-components=1
  rm /tmp/maven.tar.gz
  echo "✅ Maven installato"
fi
mvn -version

# ── Build ─────────────────────────────────────────────
echo "🔨 Build Maven..."
mvn clean package -DskipTests --no-transfer-progress

echo "✅ Build completata!"
ls -la target/space-ai-*.jar
