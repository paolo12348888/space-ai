#!/usr/bin/env python3
"""
SPACE AI — Start script per Render.com
Avvia il JAR Spring Boot in modalità web server
"""
import os
import subprocess
import sys
import glob

# Trova il JAR compilato
jar_files = glob.glob("target/*.jar")
if not jar_files:
    print("ERRORE: nessun JAR trovato in target/", flush=True)
    sys.exit(1)

jar = sorted(jar_files)[-1]
port = os.environ.get("PORT", "10000")

# Trova Java — prima cerca quello copiato dal build.sh
java_bin = "java"
candidates = [
    ".java_runtime/jdk-21/bin/java",
    os.path.expanduser("~/.java/jdk-21/bin/java"),
    "/opt/render/project/src/.java_runtime/jdk-21/bin/java"
]
for path in candidates:
    if os.path.exists(path):
        java_bin = path
        break

cmd = [
    java_bin,
    "--enable-preview",
    "-Xmx320m",
    "-Xms64m",
    "-Xss512k",
    "-XX:+UseSerialGC",
    "-XX:MaxMetaspaceSize=160m",
    "-XX:ReservedCodeCacheSize=64m",
    "-XX:MaxDirectMemorySize=64m",
    "-XX:TieredStopAtLevel=1",
    "-XX:+ExitOnOutOfMemoryError",
    "-Djava.security.egd=file:/dev/./urandom",
    "-Dspring.jmx.enabled=false",
    f"-Dserver.port={port}",
    "-Dspring.main.web-application-type=servlet",
    "--enable-native-access=ALL-UNNAMED",
    "-Dfile.encoding=UTF-8",
    "-Dstdout.encoding=UTF-8",
    "-jar", jar
]

print(f"🚀 SPACE AI — avvio web server porta {port}", flush=True)
print(f"JAR: {jar}", flush=True)
print(f"Java: {java_bin}", flush=True)

result = subprocess.run(cmd)
print(f"⚠ JVM terminata con codice {result.returncode} "
      f"(-9 = ucciso da Render per memoria, 3 = OutOfMemoryError Java)", flush=True)
sys.exit(result.returncode)
