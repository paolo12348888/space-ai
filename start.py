#!/usr/bin/env python3
"""
SPACE AI — Start script per Render.com
Avvia il JAR Spring Boot in modalità web server (non CLI)
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

jar = jar_files[0]
print(f"Avvio {jar}...", flush=True)

# Java runtime
java_home = os.environ.get("JAVA_HOME", "")
if not java_home:
    # Usa il Java copiato dal build.sh
    local_java = os.path.join(os.getcwd(), ".java_runtime", "jdk-21")
    if os.path.exists(local_java):
        java_home = local_java
    else:
        java_home = os.path.expanduser("~/.java/jdk-21")

java_bin = os.path.join(java_home, "bin", "java") if java_home else "java"

# Porta da Render
port = os.environ.get("PORT", "8080")

# Comando Spring Boot in modalità web
cmd = [
    java_bin,
    "--enable-preview",
    "-Xmx512m",
    "-Xms256m",
    f"-Dserver.port={port}",
    "-Dspring.main.web-application-type=servlet",  # FORZA modalità web
    "-Dspring.profiles.active=default",
    "--enable-native-access=ALL-UNNAMED",
    "-Dfile.encoding=UTF-8",
    "-jar", jar
]

print(f"Comando: {' '.join(cmd[:4])} ... -jar {jar}", flush=True)
print(f"Porta: {port}", flush=True)

# Avvia il processo
proc = subprocess.run(cmd)
sys.exit(proc.returncode)
