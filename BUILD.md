# Space AI Java — Guida alla build e installazione

## Prerequisiti

| Dipendenza | Versione | Descrizione |
|------|------|------|
| **JDK** | 25+ | Obbligatorio, consigliato [Oracle JDK 25](https://www.oracle.com/java/technologies/downloads/) o [OpenJDK 25](https://jdk.java.net/25/) |
| **Maven** | 3.9+ | Obbligatorio，[Link per il download](https://maven.apache.org/download.cgi) |
| **API Key** | - | Chiave per OpenAI / Anthropic / API compatibili |

> ⚠️ JDK 25 è obbligatorio; il progetto utilizza funzionalità in anteprima (es. pattern matching, string templates).

---

## Avvio rapido (modalità sviluppo)

Adatto per sviluppo e debug, eseguito direttamente tramite Maven:

```bash
# 1. Imposta JDK
export JAVA_HOME=/path/to/jdk-25    # Linux/macOS
set JAVA_HOME=C:\Dev\jdk-25.0.2     # Windows

# 2. Imposta API Key
export AI_API_KEY=your-api-key       # Linux/macOS
set AI_API_KEY=your-api-key          # Windows

# 3. Esegui
mvn spring-boot:run
```

> 📌 In modalità sviluppo, la directory di lavoro (dove opera l'AI) è la directory in cui viene eseguito il comando `mvn`.

---

## Costruisci la distribuzione (consigliato per uso quotidiano)

La distribuzione utilizza **jlink** per creare un JRE minimale, impacchettato come eseguibile standalone senza necessità di installare JDK sulla macchina target.

### Windows

```powershell
# Costruisci
.\packaging\build-dist.ps1 -JavaHome "C:\Dev\jdk-25.0.2"

# oppure salta la build Maven (se il jar è già disponibile)
.\packaging\build-dist.ps1 -JavaHome "C:\Dev\jdk-25.0.2" -SkipBuild
```

### Linux / macOS

```bash
# Costruisci
JAVA_HOME=/path/to/jdk-25 ./packaging/build-dist.sh

# oppure salta la build Maven
JAVA_HOME=/path/to/jdk-25 ./packaging/build-dist.sh --skip-build
```

### Output della build

```
dist/
├── bin/
│   ├── space-ai          # Script di avvio Unix (Linux/macOS)
│   └── space-ai.cmd      # Script di avvio Windows
├── lib/
│   └── space-ai-java.jar # Spring Boot fat jar (~71 MB)
└── runtime/                 # JRE minimale jlink (~49 MB)
    ├── bin/
    ├── conf/
    ├── lib/
    └── release
```

Dimensione totale circa **120 MB**（rispetto al JDK completo ~350 MB）。

---

## Installazione nel PATH di sistema

Dopo aver aggiunto `dist/bin` al PATH di sistema, puoi usare il comando `space-ai` da qualsiasi directory.

### Windows

**Metodo 1: CMD effetto temporaneo (terminale corrente)**
```cmd
set PATH=C:\path\to\space-ai\dist\bin;%PATH%
```

**Metodo 2: PowerShell effetto temporaneo (terminale corrente)**
```powershell
$env:PATH = "C:\path\to\space-ai\dist\bin;$env:PATH"
```

**Metodo 3: CMD effetto permanente (livello utente)**
```cmd
setx PATH "%PATH%;C:\path\to\space-ai\dist\bin"
```
> È necessario riaprire la finestra del terminale per applicare le modifiche.

**Metodo 4: PowerShell effetto permanente**
```powershell
$binPath = "C:\path\to\space-ai\dist\bin"
$currentPath = [Environment]::GetEnvironmentVariable("PATH", "User")
if ($currentPath -notmatch [regex]::Escape($binPath)) {
    [Environment]::SetEnvironmentVariable("PATH", "$currentPath;$binPath", "User")
    Write-Host "Added to PATH. Restart terminal to take effect."
}
```

### Linux

```bash
# Effetto temporaneo
export PATH="/path/to/space-ai/dist/bin:$PATH"

# Effetto permanente — aggiungi a ~/.bashrc o ~/.zshrc
echo 'export PATH="/path/to/space-ai/dist/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc
```

### macOS

```bash
# Effetto temporaneo
export PATH="/path/to/space-ai/dist/bin:$PATH"

# Effetto permanente — aggiungi a ~/.zshrc (la shell predefinita di macOS è zsh)
echo 'export PATH="/path/to/space-ai/dist/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

### Validazione

```bash
# Esegui da qualsiasi directory
space-ai           # Linux/macOS
space-ai.cmd       # Windows CMD
space-ai           # Windows PowerShell (trova automaticamente .cmd)
```

---

## Utilizzo in altre directory

Dopo l'installazione nel PATH, `space-ai` può essere avviato da qualsiasi directory. **La directory di lavoro dell'AI è la directory corrente al momento dell'avvio del comando**.

```bash
# Esempio: avvia dalla directory del progetto, l'AI leggerà automaticamente il contesto di quella directory
cd /path/to/my-project
space-ai

# L'AI:
# - Caricherà automaticamente ./SPACE.md (file di memoria del progetto, se esiste)
# - Leggerà le informazioni .git per ottenere il contesto del progetto
# - Tutte le operazioni sui file saranno basate sulla directory corrente
```

```bash
# Esempio: passare tra progetti diversi
cd ~/projects/web-app && space-ai      # operazione web-app progetto
cd ~/projects/api-server && space-ai   # operazione api-server progetto
```

### lavoroDirectoryDescrizione

|  | lavoroDirectory | Descrizione |
|------|----------|------|
| `cd /my-project && space-ai` | `/my-project` | AI inquestoDirectorysottooperazioneFile |
| avviodopoStrumento | CorrenteDirectory | `bash`, `file_read` ecc.basato suavvioDirectory |
| `SPACE.md` caricato | Directory corrente + `~/.space-ai/` | livello progetto + globale, unione automatica |
| contesto Git | CorrenteDirectory `.git` | AutomaticamenterilevaBranch、Stato、 |

---

## Variabili d'ambiente

|  | Obbligatorio | Descrizione | Predefinito |
|------|------|------|--------|
| `AI_API_KEY` | ✅ | chiave API | - |
| `SPACE_AI_PROVIDER` | ❌ | Provider: `openai` (DeepSeek/Qwen/ModelScope) o `anthropic` | `openai` |
| `AI_BASE_URL` | ❌ | API base URL | Providerdiverso |
| `AI_MODEL` | ❌ | Modellonome | Providerdiverso |
| `AI_MAX_TOKENS` | ❌ | Massimogenera Token  | `8096` |
| `SPACE_AI_VIM` | ❌ | Abilitato Vim modificaModalità | `0` |
| `SPACE_AI_CONTEXT_WINDOW` | ❌ | finestra di contestodimensione | `200000` |

verràConfigurazionea shell profile in：

```bash
# ~/.bashrc o ~/.zshrc
export AI_API_KEY="sk-your-key-here"
export SPACE_AI_PROVIDER="openai"
export AI_BASE_URL="https://api.deepseek.com"
export AI_MODEL="deepseek-chat"
```

Windows può `setx` persistenza：
```cmd
setx AI_API_KEY "sk-your-key-here"
setx SPACE_AI_PROVIDER "openai"
```

---

## piattaformaBuildAttenzione

jlink crea JRE sì **piattaformaCorrelato** 。in Windows sopraBuild `dist/` in Windows sopraEsecuzione。

SerichiedecomepiùpiattaformaBuild：

| Buildpiattaforma | output | puòEsecuzionepiattaforma |
|----------|------|------------|
| Windows x64 | `dist/runtime/` (Windows JRE) | Windows x64 |
| Linux x64 | `dist/runtime/` (Linux JRE) | Linux x64 |
| macOS ARM | `dist/runtime/` (macOS JRE) | macOS ARM (M1/M2/M3) |
| macOS x64 | `dist/runtime/` (macOS JRE) | macOS Intel |

> 💡 `lib/space-ai.jar` è multipiattaforma,  `runtime/` richiede build su ogni piattaforma。
>
> Sein CI/CD inBuild，puòconin GitHub Actions in matrix strategy dividiin `ubuntu-latest`, `windows-latest`, `macos-latest` sopraBuild。

---

## domande frequenti

### Q: avvio "OpenAI API key must be set"

Imposta `AI_API_KEY` Variabili d'ambiente：
```bash
export AI_API_KEY="your-key"
```

### Q: Windows terminalein

avviogiàAutomaticamenteEsegui `chcp 65001`。Seancorac'è，ManualmenteEsecuzione：
```cmd
chcp 65001
```

### Q: come usare Anthropic API nonsì OpenAI?

```bash
export SPACE_AI_PROVIDER="openai"    # default: openai compatibile
export AI_API_KEY="sk-ant-your-key"
export AI_MODEL="deepseek-chat"       # oppure deepseek-reasoner, qwen-plus, ecc.
```

### Q: come usare DeepSeek / Azure OpenAI ecc. API?

```bash
export SPACE_AI_PROVIDER="openai"
export AI_BASE_URL="https://api.deepseek.com"
export AI_MODEL="deepseek-chat"
export AI_API_KEY="your-deepseek-key"
```

### Q: dist/ Directorypuòconcopiaaaltri?

puòcon，del serveroperazioneSistemae CPU architetturaeBuild。`dist/` sìpackage，nonrichiede JDK。

### Q: Aggiornamento?

nuovoesegueBuildsovrascrive `dist/` Directorypuò：
```bash
git pull
./packaging/build-dist.sh        # oppure .ps1
```
