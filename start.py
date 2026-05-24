#!/usr/bin/env python3
import os, subprocess, sys, glob

jar_files = glob.glob("target/*.jar")
if not jar_files:
    print("ERRORE: nessun JAR trovato", flush=True)
    sys.exit(1)

jar = sorted(jar_files)[-1]
port = os.environ.get("PORT", "10000")

# Trova Java
java_bin = "java"
for path in [".java_runtime/jdk-21/bin/java",
             os.path.expanduser("~/.java/jdk-21/bin/java")]:
    if os.path.exists(path):
        java_bin = path
        break

cmd = [
    java_bin,
    "--enable-preview",
    "-Xmx450m", "-Xms128m",
    f"-Dserver.port={port}",
    "-Dspring.main.web-application-type=servlet",
    "--enable-native-access=ALL-UNNAMED",
    "-Dfile.encoding=UTF-8",
    "-jar", jar
]

print(f"🚀 SPACE AI avvio web server porta {port}", flush=True)
sys.exit(subprocess.run(cmd).returncode)
