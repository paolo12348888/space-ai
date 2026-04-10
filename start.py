import os, glob, sys, shutil

print("🌌 SPACE AI — Avvio su Render")

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))

# Cerca Java in tutti i path possibili senza scaricarlo
java_candidates = [
    "/usr/bin/java",
    "/usr/local/bin/java",
    "/usr/lib/jvm/java-21-openjdk-amd64/bin/java",
    "/usr/lib/jvm/java-21/bin/java",
    "/usr/lib/jvm/java-17-openjdk-amd64/bin/java",
    "/usr/lib/jvm/temurin-21/bin/java",
    os.path.join(PROJECT_DIR, ".java_runtime/bin/java"),
    os.path.join(os.path.expanduser("~"), ".java_runtime/bin/java"),
]

java_bin = None
for c in java_candidates:
    if os.path.isfile(c) and os.access(c, os.X_OK):
        java_bin = c
        break

if not java_bin:
    java_bin = shutil.which("java")

if not java_bin:
    # Ultima risorsa: cerca nella build directory (installato durante build.sh)
    for root, dirs, files in os.walk("/opt/render"):
        if "java" in files:
            candidate = os.path.join(root, "java")
            if os.access(candidate, os.X_OK):
                java_bin = candidate
                break

if not java_bin:
    print("❌ Java non trovato! Assicurati che build.sh installi Java correttamente.")
    sys.exit(1)

print(f"   Java: {java_bin}")

jars = glob.glob(os.path.join(PROJECT_DIR, "target/space-ai-*.jar"))
if not jars:
    print("❌ JAR non trovato in target/")
    sys.exit(1)

jar  = jars[0]
port = os.environ.get("PORT", "10000")
print(f"   JAR: {jar}  |  Porta: {port}")

cmd = [
    java_bin,
    "-Xmx400m", "-Xms128m",
    f"-Dserver.port={port}",
    "-Dspring.main.web-application-type=servlet",
    "-Dspring.main.banner-mode=off",
    "-jar", jar
]

os.execv(java_bin, cmd)
