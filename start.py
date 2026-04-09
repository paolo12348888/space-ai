import os, glob, sys, shutil, tarfile, urllib.request

print("🌌 SPACE AI — Avvio su Render (Web Mode)")

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
JAVA_DIR    = os.path.join(PROJECT_DIR, ".java_runtime")
JAVA_BIN    = os.path.join(JAVA_DIR, "bin/java")

def install_java():
    print("📦 Installo Java 21 per il runtime...")
    os.makedirs(JAVA_DIR, exist_ok=True)
    url = "https://download.java.net/java/GA/jdk21.0.2/f2283984656d49d69e91c558476027ac/13/GPL/openjdk-21.0.2_linux-x64_bin.tar.gz"
    tmp = "/tmp/jdk21_runtime.tar.gz"
    urllib.request.urlretrieve(url, tmp)
    with tarfile.open(tmp, "r:gz") as tar:
        tar.extractall("/tmp/jdk21_extracted")
    extracted = glob.glob("/tmp/jdk21_extracted/jdk-*")[0]
    for item in os.listdir(extracted):
        shutil.move(os.path.join(extracted, item), os.path.join(JAVA_DIR, item))
    os.remove(tmp)
    print("✅ Java 21 installato")

if not os.path.exists(JAVA_BIN):
    install_java()

if not os.path.exists(JAVA_BIN):
    print("❌ Installazione Java fallita!")
    sys.exit(1)

jars = glob.glob(os.path.join(PROJECT_DIR, "target/space-ai-*.jar"))
if not jars:
    print("❌ JAR non trovato!")
    sys.exit(1)

jar  = jars[0]
port = os.environ.get("PORT", "10000")
print(f"   JAR: {jar}  |  Porta: {port}  |  Java: {JAVA_BIN}")

cmd = [
    JAVA_BIN,
    "-Xmx400m", "-Xms128m",
    f"-Dserver.port={port}",
    "-Dspring.main.web-application-type=servlet",
    "-Dspring.main.banner-mode=off",
    "-jar", jar
]

os.execv(JAVA_BIN, cmd)
