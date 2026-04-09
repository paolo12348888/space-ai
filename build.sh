#!/bin/bash
set -e

echo "🌌 SPACE AI — Build su Render"
echo "================================"

# Directory home su Render
HOME_DIR="/opt/render"

# ── Java 21 ──────────────────────────────────────────────────
export JAVA_HOME="$HOME_DIR/.java/jdk-21"
export PATH="$JAVA_HOME/bin:$PATH"

if [ ! -f "$JAVA_HOME/bin/java" ]; then
  echo "📦 Download Java 21..."
  mkdir -p "$HOME_DIR/.java"
  curl -sL "https://download.java.net/java/GA/jdk21.0.2/f2283984656d49d69e91c558476027ac/13/GPL/openjdk-21.0.2_linux-x64_bin.tar.gz" \
    -o /tmp/jdk21.tar.gz
  tar -xzf /tmp/jdk21.tar.gz -C "$HOME_DIR/.java/"
  mv "$HOME_DIR/.java/jdk-21.0.2" "$JAVA_HOME"
  rm /tmp/jdk21.tar.gz
  echo "✅ Java 21 installato"
fi
java -version

# ── Maven ─────────────────────────────────────────────────────
export MAVEN_HOME="$HOME_DIR/.maven"
export PATH="$MAVEN_HOME/bin:$PATH"

if [ ! -f "$MAVEN_HOME/bin/mvn" ]; then
  echo "📦 Download Maven..."
  mkdir -p "$HOME_DIR/.maven"
  curl -sL "https://archive.apache.org/dist/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.tar.gz" \
    -o /tmp/maven.tar.gz
  tar -xzf /tmp/maven.tar.gz -C "$HOME_DIR/.maven/" --strip-components=1
  rm /tmp/maven.tar.gz
  echo "✅ Maven installato"
fi
mvn -version

# ── Build ─────────────────────────────────────────────────────
echo "🔨 Build Maven..."
mvn clean package -DskipTests --no-transfer-progress

echo "✅ Build completata!"
ls -la target/space-ai-*.jar
