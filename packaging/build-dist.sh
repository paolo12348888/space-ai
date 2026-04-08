#!/bin/bash
# build-dist.sh — Build della distribuzione SPACE AI (Linux / macOS)
set -e

# ─── Configurazione ───────────────────────────────────────────
JAVA_HOME_ARG="${JAVA_HOME:-}"
SKIP_BUILD=false

for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=true ;;
        JAVA_HOME=*)  JAVA_HOME_ARG="${arg#JAVA_HOME=}" ;;
    esac
done

if [[ -n "$JAVA_HOME_ARG" ]]; then
    export JAVA_HOME="$JAVA_HOME_ARG"
fi

if [[ -z "$JAVA_HOME" ]]; then
    echo "❌ JAVA_HOME non impostato. Usa: JAVA_HOME=/path/to/jdk-25 ./packaging/build-dist.sh"
    exit 1
fi

JAVA_BIN="$JAVA_HOME/bin"
JLINK="$JAVA_BIN/jlink"
JDEPS="$JAVA_BIN/jdeps"
JAVA="$JAVA_BIN/java"
JAVAC="$JAVA_BIN/javac"

echo ""
echo "🌌  SPACE AI — Build distribuzione"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "   JAVA_HOME : $JAVA_HOME"
echo ""

# ─── Build Maven ──────────────────────────────────────────────
if [[ "$SKIP_BUILD" == "false" ]]; then
    echo "📦 Compiling with Maven..."
    mvn clean package -DskipTests -q
    echo "   ✅ Build Maven completata"
fi

# Trova il JAR
JAR_FILE=$(find target -maxdepth 1 -name "space-ai-*.jar" ! -name "*-sources.jar" | head -1)
if [[ -z "$JAR_FILE" ]]; then
    echo "❌ JAR non trovato in target/. Esegui prima la build Maven."
    exit 1
fi

VERSION=$(echo "$JAR_FILE" | sed 's/target\/space-ai-\(.*\)\.jar/\1/')
echo "   Versione: $VERSION"
echo "   JAR     : $JAR_FILE"
echo ""

# ─── Crea struttura dist ──────────────────────────────────────
DIST_DIR="dist"
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR/bin" "$DIST_DIR/lib"

# Copia il JAR
cp "$JAR_FILE" "$DIST_DIR/lib/space-ai.jar"

# ─── jlink: JRE minimale ─────────────────────────────────────
echo "🔧 Creazione JRE minimale con jlink..."

MODULES="java.base,java.logging,java.net.http,java.sql,java.xml,java.management,jdk.unsupported,java.desktop"

"$JLINK" \
    --module-path "$JAVA_HOME/jmods" \
    --add-modules "$MODULES" \
    --output "$DIST_DIR/runtime" \
    --strip-debug \
    --no-header-files \
    --no-man-pages \
    --compress=2

echo "   ✅ JRE minimale creato"
echo ""

# ─── Script di avvio Linux/macOS ──────────────────────────────
echo "📋 Creazione script di avvio..."

cat > "$DIST_DIR/bin/space-ai" << 'LAUNCHER'
#!/bin/bash
# SPACE AI — Script di avvio (Linux / macOS)
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNTIME="$DIR/../runtime"
JAR="$DIR/../lib/space-ai.jar"

"$RUNTIME/bin/java" \
    --enable-preview \
    -Xmx512m \
    -jar "$JAR" "$@"
LAUNCHER

chmod +x "$DIST_DIR/bin/space-ai"
echo "   ✅ Script di avvio creato"
echo ""

# ─── Riepilogo ───────────────────────────────────────────────
DIST_SIZE=$(du -sh "$DIST_DIR" 2>/dev/null | cut -f1)
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "✅  SPACE AI $VERSION — distribuzione completata!"
echo ""
echo "   Dimensione: $DIST_SIZE"
echo "   Percorso  : $(pwd)/$DIST_DIR/"
echo ""
echo "🚀  Avvia con:"
echo "    $DIST_DIR/bin/space-ai"
echo ""
