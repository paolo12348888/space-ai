# build-dist.ps1 — Build della distribuzione SPACE AI (Windows)
# Utilizzo: .\packaging\build-dist.ps1 [-JavaHome "C:\Dev\jdk-25"] [-SkipBuild]

param(
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$SkipBuild
)

Write-Host ""
Write-Host "🌌  SPACE AI — Build distribuzione" -ForegroundColor Cyan
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkGray

if (-not $JavaHome) {
    Write-Host "❌ JAVA_HOME non impostato." -ForegroundColor Red
    Write-Host "   Usa: .\packaging\build-dist.ps1 -JavaHome 'C:\Dev\jdk-25'"
    exit 1
}

Write-Host "   JAVA_HOME : $JavaHome" -ForegroundColor White
Write-Host ""

$javaBin  = Join-Path $JavaHome "bin"
$jlink    = Join-Path $javaBin  "jlink.exe"
$java     = Join-Path $javaBin  "java.exe"

# ─── Build Maven ────────────────────────────────────────────────
if (-not $SkipBuild) {
    Write-Host "📦 Compilazione con Maven..." -ForegroundColor Yellow
    mvn clean package -DskipTests -q
    if ($LASTEXITCODE -ne 0) { Write-Host "❌ Build Maven fallita." -ForegroundColor Red; exit 1 }
    Write-Host "   ✅ Build Maven completata" -ForegroundColor Green
}

# Trova il JAR
$jarFile = Get-ChildItem -Path "target" -Filter "space-ai-*.jar" -Exclude "*-sources.jar" |
           Select-Object -First 1

if (-not $jarFile) {
    Write-Host "❌ JAR non trovato in target\. Esegui prima la build Maven." -ForegroundColor Red
    exit 1
}

$version = $jarFile.BaseName -replace "space-ai-", ""
Write-Host "   Versione : $version" -ForegroundColor White
Write-Host "   JAR      : $($jarFile.FullName)" -ForegroundColor White
Write-Host ""

# ─── Struttura dist ─────────────────────────────────────────────
$distDir = "dist"
Remove-Item -Recurse -Force $distDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$distDir\bin" | Out-Null
New-Item -ItemType Directory -Force -Path "$distDir\lib" | Out-Null

Copy-Item $jarFile.FullName -Destination "$distDir\lib\space-ai.jar"

# ─── jlink: JRE minimale ────────────────────────────────────────
Write-Host "🔧 Creazione JRE minimale con jlink..." -ForegroundColor Yellow

$modules = "java.base,java.logging,java.net.http,java.sql,java.xml,java.management,jdk.unsupported,java.desktop"

& $jlink `
    --module-path (Join-Path $JavaHome "jmods") `
    --add-modules $modules `
    --output (Join-Path $distDir "runtime") `
    --strip-debug `
    --no-header-files `
    --no-man-pages `
    --compress=2

if ($LASTEXITCODE -ne 0) { Write-Host "❌ jlink fallito." -ForegroundColor Red; exit 1 }
Write-Host "   ✅ JRE minimale creato" -ForegroundColor Green
Write-Host ""

# ─── Script di avvio Windows ────────────────────────────────────
Write-Host "📋 Creazione script di avvio..." -ForegroundColor Yellow

$launcher = @'
@echo off
:: SPACE AI — Script di avvio (Windows)
setlocal
set "DIR=%~dp0"
set "RUNTIME=%DIR%..\runtime"
set "JAR=%DIR%..\lib\space-ai.jar"

"%RUNTIME%\bin\java.exe" --enable-preview -Xmx512m -jar "%JAR%" %*
'@

$launcher | Out-File -FilePath "$distDir\bin\space-ai.cmd" -Encoding utf8
Write-Host "   ✅ Script di avvio creato" -ForegroundColor Green
Write-Host ""

# ─── Riepilogo ───────────────────────────────────────────────────
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor DarkGray
Write-Host "✅  SPACE AI $version — distribuzione completata!" -ForegroundColor Green
Write-Host ""
Write-Host "   Percorso: $(Resolve-Path $distDir)" -ForegroundColor White
Write-Host ""
Write-Host "🚀  Avvia con:" -ForegroundColor Cyan
Write-Host "    .\$distDir\bin\space-ai.cmd" -ForegroundColor White
Write-Host ""
