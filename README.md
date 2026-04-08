# 🌌 SPACE AI

**Il tuo compagno di programmazione cosmico**

Un assistente AI potente da terminale, scritto in Java con Spring AI e ottimizzato per i modelli open source (DeepSeek, Qwen, GPT-4o, Anthropic, ecc.).

---

## ✨ Caratteristiche

- **🚀 Interfaccia CLI moderna** con banner cosmico ispirato al logo SPACE AI
- **🤖 Supporto nativo** per LLM open source tramite API OpenAI-compatible
- **🛠️ 18 strumenti integrati**: esecuzione comandi bash, operazioni su file, ricerca web, gestione task, ecc.
- **📋 28 comandi slash** (`/help`, `/compact`, `/commit`, `/diff`, `/branch`, `/security-review`, ...)
- **🧠 Memoria intelligente** con compressione automatica a 3 livelli
- **🔒 Sistema di permessi avanzato** con rilevamento pattern pericolosi
- **🔌 Sistema di plugin** e supporto MCP (Model Context Protocol)
- **📝 Gestione task** automatica e manuale
- **🌟 Hook system** per personalizzazioni pre/post tool e prompt
- **📦 Distribuzione minimale** con jlink (~120 MB totali)
- **🌍 Interfaccia completamente in italiano**

---

## 🚀 Avvio rapido

### 1. Prerequisiti

| Dipendenza | Versione | Note |
|------------|----------|------|
| **JDK** | 25+ | Obbligatorio |
| **Maven** | 3.9+ | Obbligatorio |
| **API Key** | — | OpenAI / DeepSeek / Anthropic / compatibile |

### 2. Configurazione DeepSeek (consigliata)

```bash
export SPACE_AI_PROVIDER=openai
export AI_BASE_URL=https://api.deepseek.com
export AI_API_KEY=sk-la-tua-chiave-deepseek
export AI_MODEL=deepseek-chat
```

### 3. Configurazione Anthropic (opzionale — solo se vuoi usare i modelli Anthropic)

```bash
export SPACE_AI_PROVIDER=anthropic
export AI_API_KEY=sk-ant-la-tua-chiave
export AI_MODEL=claude-sonnet-4-20250514  # ID modello API Anthropic
```

### 4. Avvio in modalità sviluppo

```bash
mvn spring-boot:run
```

### 5. Build distribuzione standalone

```bash
# Linux / macOS
JAVA_HOME=/path/to/jdk-25 ./packaging/build-dist.sh

# Windows
.\packaging\build-dist.ps1 -JavaHome "C:\Dev\jdk-25"
```

Dopo la build:

```bash
./dist/bin/space-ai          # Linux / macOS
.\dist\bin\space-ai.cmd      # Windows
```

---

## 🎯 Utilizzo

### Conversazione diretta

```
❯ Analizza l'architettura di questo progetto
❯ Scrivi un test unitario per la classe AgentLoop
❯ Trova tutti i bug nel file PermissionRuleEngine.java
```

### Input multi-riga

Aggiungi `\` alla fine della riga per continuare:

```
❯ Refactora questo metodo, \
  usa stream al posto dei cicli for, \
  aggiungi javadoc completo
```

### Comandi principali

| Comando | Alias | Descrizione |
|---------|-------|-------------|
| `/help` | | Mostra tutti i comandi disponibili |
| `/clear` | | Cancella la cronologia della conversazione |
| `/compact` | | Compressione AI del contesto (sistema a 3 livelli) |
| `/cost` | | Mostra utilizzo Token e costi |
| `/model [nome]` | | Visualizza / cambia modello |
| `/status` | | Mostra stato della sessione |
| `/context` | | Mostra contesto caricato |
| `/config` | | Visualizza la configurazione attiva |
| `/init` | | Inizializza il file SPACE.md nel progetto |
| `/exit` | `/quit` | Esci |

#### Comandi P0

| Comando | Alias | Descrizione |
|---------|-------|-------------|
| `/diff` | | Mostra modifiche Git (supporta `--staged`, `--stat`) |
| `/version` | `/ver` | Versione e informazioni sull'ambiente |
| `/skills` | | Elenca le competenze caricate |
| `/memory` | `/mem` | Visualizza / modifica il file SPACE.md |
| `/copy` | | Copia l'ultima risposta negli appunti |

#### Comandi P1

| Comando | Alias | Descrizione |
|---------|-------|-------------|
| `/history` | | Elenca la cronologia delle conversazioni salvate |
| `/resume` | | Ripristina una conversazione salvata |
| `/export` | | Esporta la conversazione in file Markdown |
| `/commit` | | Genera automaticamente un messaggio di commit con AI |

#### Comandi P2

| Comando | Alias | Descrizione |
|---------|-------|-------------|
| `/hooks` | | Visualizza gli Hook registrati |
| `/review` | `/rev` | Revisione codice AI (supporta `--staged`, percorso file) |
| `/stats` | | Statistiche utilizzo (Token, costi, chiamate API, durata) |
| `/branch` | | Branch di conversazione (`save/load/list/delete`) |
| `/rewind [n]` | | Torna indietro nella cronologia (predefinito: 1 turno) |
| `/tag` | | Tag di conversazione (`<n>/list/goto <n>`) |
| `/security-review` | | Revisione sicurezza AI del codice |
| `/mcp` | | Gestisci i server MCP connessi |
| `/plugin` | | Gestisci i plugin caricati |

---

## 📦 Stack tecnologico

| Componente | Versione | Utilizzo |
|------------|----------|----------|
| JDK | 25 | Runtime |
| Spring Boot | 4.1.0-M2 | Framework applicativo |
| Spring AI | 2.0.0-M4 | Chiamata modello AI |
| JLine 3 | 3.28.0 | Interazione terminale |
| Picocli | 4.7.6 | Analisi comandi CLI |

---

## ⚙️ Variabili d'ambiente

| Variabile | Predefinito | Descrizione |
|-----------|-------------|-------------|
| `SPACE_AI_PROVIDER` | `openai` | Provider: `openai` (DeepSeek/Qwen/ModelScope) o `anthropic` |
| `AI_API_KEY` | — | Chiave API (obbligatoria) |
| `AI_BASE_URL` | `https://api.deepseek.com` | Endpoint API (DeepSeek default) |
| `AI_MODEL` | `deepseek-chat` | Nome del modello |
| `AI_MAX_TOKENS` | `8096` | Token massimi per risposta |
| `SPACE_AI_VIM` | `0` | Abilita modalità Vim (`1` per attivare) |

---

## 📚 Documentazione

- **[BUILD.md](BUILD.md)** — Guida completa alla build e all'installazione
- **[CHANGELOG.md](CHANGELOG.md)** — Registro delle modifiche
- **[REQUISITI.md](REQUISITI.md)** — Documento dei requisiti tecnici

---

## 🌌 Progetto

SPACE AI è un assistente CLI potente per sviluppatori, con un'identità cosmica unica ispirata alla "S" cometa luminosa tra le stelle.
