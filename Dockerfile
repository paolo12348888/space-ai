# ─────────────────────────────────────────────────────────────
# SPACE AI — Dockerfile per Render
# Build multi-stage: Maven build → JRE minimale
# ─────────────────────────────────────────────────────────────

# ── Stage 1: Build con Maven + JDK 25 ────────────────────────
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /build

# Copia prima il pom.xml per sfruttare la cache Docker
COPY pom.xml .
COPY src ./src

# Build Maven (skip test per velocizzare il deploy)
RUN apt-get update -q && apt-get install -y -q maven && \
    mvn clean package -DskipTests --no-transfer-progress

# ── Stage 2: Runtime minimale ────────────────────────────────
FROM eclipse-temurin:25-jre AS runtime

WORKDIR /app

# Copia il JAR compilato
COPY --from=builder /build/target/space-ai-*.jar app.jar

# Porta HTTP per il health-check di Render
EXPOSE 8080

# Variabili d'ambiente di default (override da Render Environment)
ENV SPACE_AI_PROVIDER=openai
ENV AI_BASE_URL=https://api-inference.modelscope.cn/v1
ENV AI_MODEL=deepseek-ai/DeepSeek-R1-Distill-Qwen-7B
ENV SERVER_PORT=8080
ENV SPRING_WEB_TYPE=servlet

# Avvio con modalità web abilitata per Render
ENTRYPOINT ["java", \
  "--enable-preview", \
  "-Xmx400m", \
  "-Dserver.port=8080", \
  "-Dspring.profiles.active=render", \
  "-jar", "app.jar"]
