# Space AI Java – Riscrittura

## 1. Contesto dei requisiti

SPACE AI è uno strumento di assistenza CLI per la programmazione basato su modelli AI avanzati. La versione originale è implementata con TypeScript + Ink (framework React per terminale), con circa **2.289 file** distribuiti in **38 moduli**, offrendo una ricca esperienza di interazione terminale, incluse chiamate strumento, comandi slash, caricamento memoria SPACE.md, sistema Skills, ecc.

L'obiettivo di questo progetto è sviluppare SPACE AI usando **Java + Spring AI**, implementando un assistente AI da riga di comando completo e con buona esperienza di interazione. Si fa riferimento alle pratiche Spring AI del progetto `space-ai-learn` (ciclo esplicito AgentLoop, protocollo strumenti AgentTool, ecc.), ma con un'esperienza di interazione CLI più completa.

### Obiettivi di business
- Comprendere a fondo la progettazione dell'architettura e i principi core degli assistenti CLI AI moderni
- Usare l'ecosistema Java (Spring AI + JLine + Picocli) per implementare funzionalità equivalenti
- Fornire un sistema di strumenti e comandi estensibile
- Supportare un'esperienza di interazione terminale completa (banner, box di input, visualizzazione thinking, stato strumenti, ecc.)

## 2. Descrizione delle funzionalità

### 2.1 Ciclo REPL principale

**Corrisponde acodice sorgente**: `space-ai/src/screens/REPL.tsx`, `space-ai/src/query.ts`
**Java design**: `AgentLoop.java`, `ReplSession.java`

Input utente → rilevamento comando slash → chiamata AI → ciclo chiamate strumento → output risultati → attesa prossimo input

Requisiti chiave:
- Usare `ChatModel` (non `ChatClient.call()`) per il ciclo REPL personalizzato
- `ChatClient.call()` ha un ciclo strumenti integrato ma non abbastanza flessibile; non permette di inserire logica personalizzata tra ogni chiamata strumento (come controllo permessi, visualizzazione stato, aggiornamento progresso)
- Controllo esplicito di ogni round: costruisce Prompt → chiama il modello → rileva chiamate strumento → esegue strumenti → raccoglie risultati → continua o termina
- Supporta un limite massimo di iterazioni (per prevenire loop infiniti, predefinito 50 round)
- Gestione della cronologia messaggi (ciclo UserMessage → AssistantMessage → ToolResponseMessage)

### 2.2 StrumentoSistema

**Corrisponde acodice sorgente**: `space-ai/src/Tool.ts`, `space-ai/src/tools.ts`, `space-ai/src/tools/*`
**Java design**: `Tool.java` Interfaccia, `ToolRegistry.java`, `tools/impl/*`

Definizione dell'interfaccia del protocollo strumenti:
```
Tool {
  name()          - identificatore univoco
  description()   - descrizione per l'LLM
  inputSchema()   - definizione parametri JSON Schema
  execute()       - logica di esecuzione
  checkPermission() - controllo permessi preliminare
  isEnabled()     - se abilitato
  isReadOnly()    - se in sola lettura
  activityDescription() - descrizione del progresso leggibile
}
```

prima faseImplementazioneStrumento（principaleStrumento）：

| Strumento | Funzione | Corrisponde acodice sorgente |
|-------|------|---------|
| BashTool | Esegui Shell/Cmd Comando | tools/BashTool/ |
| FileReadTool | leggeFilecontenuto | tools/FileReadTool/ |
| FileWriteTool | scriveFile | tools/FileWriteTool/ |
| FileEditTool | cercasostituiscemodifica | tools/FileEditTool/ |
| GlobTool | FileRicerca | tools/GlobTool/ |
| GrepTool | FilecontenutopositivoRicerca | tools/GrepTool/ |
| WebFetchTool | Ottieni URL contenuto | tools/WebFetchTool/ |
| TodoWriteTool | Da fare | tools/TodoWriteTool/ |

### 2.3 comando slashSistema

**Corrisponde acodice sorgente**: `space-ai/src/commands.ts`, `space-ai/src/commands/*`
**Java design**: `SlashCommand.java` Interfaccia, `CommandRegistry.java`, `commands/impl/*`

ComandoInterfacciadefinizione：
```
SlashCommand {
  name()         - Comandonome（non /）
  aliases()      - AliasLista
  description()  - Descrizione del comando
  execute()      - logica di esecuzione
  isEnabled()    - seAbilitato
}
```

prima faseImplementazioneComando：

| Comando | Funzione | Corrisponde acodice sorgente |
|------|------|---------|
| /help | visualizzaInformazione | commands/help/ |
| /clear | cancellacronologia conversazione | commands/clear/ |
| /compact | Compressionecontesto | commands/compact/ |
| /exit | Esciapplicazione | commands/exit/ |
| /cost | Mostra Token consumo | commands/cost/ |
| /model | cambiaModello | commands/model/ |
| /memory | Gestisci SPACE.md | commands/memory/ |
| /context | visualizzaCorrentecontesto | commands/context/ |

### 2.4 contestocon la costruzione del prompt di sistema

**Corrisponde acodice sorgente**: `space-ai/src/context.ts`, `space-ai/src/constants/prompts.ts`, `space-ai/src/utils/spacemd.ts`
**Java design**: `ContextBuilder.java`, `SystemPromptBuilder.java`, `SpaceMdLoader.java`

Sistemaprompt：
```
1. baseSistemasuggerimento（Ruolodefinizione、comportamentoRegola）
2. Strumentoguida all'uso (suggerimenti per ogni strumento)
3. Utentecontesto
   ├── SPACE.md contenuto（livello progetto + Utente + livello amministrativo）
   ├── Corrente
   └── personalizzato
4. Sistemacontesto
   ├── stato Git（Branch、、modificheFile）
   ├── operazioneSistemaInformazione
   └── lavoroDirectory
5. CompetenzaLista（può skills）
```

SPACE.md caricapriorità（dabassoaalto）：
1. livello amministrativo: `/etc/space-ai/SPACE.md`（Linux）oecc.posizione
2. Utente: `~/.space-ai/SPACE.md`
3. livello progetto: `./SPACE.md` o `./.space-ai/SPACE.md`
4. livello locale: `./SPACE.local.md`

### 2.5 Skills sistema competenze

**Corrisponde acodice sorgente**: `space-ai/src/skills/`, `space-ai/src/skills/bundledSkills.ts`
**Java design**: `SkillLoader.java`, `skills/` risorseDirectory

Competenzadefinizioneformato（Markdown + YAML Frontmatter）：
```yaml
---
name: "Competenzanome"
description: "Competenzadescrizione"
allowed-tools: [BashTool, FileReadTool]
---
Competenzacontenuto（Markdown formato）
```

caricamento competenzePosizione:
1. Utenteglobale: `~/.space-ai/skills/`
2. livello progetto: `.space-ai/skills/`
3. integrato: preconfezionato nell'applicazione

### 2.6 Interazione UI terminale

**Corrisponde acodice sorgente**: `space-ai/src/components/`, `space-ai/src/ink/`
**Java design**: `console/` package（ JLine 3 Implementazione）

#### Banner visualizza
avvioMostra ASCII Art Logo + VersioneInformazione + ModelloInformazione + lavoroDirectory

#### Inputsuggerimento
- supportaInput multi-riga（Shift+Enter a capo）
- Inputcronologia（soprasotto）
- Comandocompletamento（Tab ）
- supportacomando slashAutomaticamentecompletamento

#### Thinking visualizza
- AI tempo realevisualizza
- comprimibile/espandibile
- contrassegnato con il prefisso `∴ Thinking...`

#### chiamata strumentoStato
- StrumentoesegueinMostra Spinner animazione
- visualizzaNome dello strumentoeParametririepilogo
- completamentodopoMostra ● Successo/● FallimentoStato
- supportatempo realeprogressoAggiornamento

#### rendering Markdown
- blocco di codicealtoevidenzia
- titolo、Lista、collegamentoformato
- adattamento alla larghezza del terminale

### 2.7 output in streaming

**Corrisponde acodice sorgente**: `space-ai/src/services/api/`, `space-ai/src/query.ts`
**Java design**: `StreamingAgentLoop.java`, `StreamingHandler.java`

- usa il metodo `ChatModel.stream()` di Spring AI
- Restituisce `Flux<ChatResponse>` streamRisposta
- tempo realeEsegui rendering AI testo
- tempo realeGestiscechiamata strumentoParametri（partial JSON）

### 2.8 Configurazione

**Corrisponde acodice sorgente**: `space-ai/src/commands/config/`
**Java design**: `application.yml` + `AppConfig.java`

Configurazione：
- API Key（Variabili d'ambienteoFile di configurazione）
- Base URL（supportapersonalizzato API ）
- Modello predefinito: configurabile tramite variabile AI_MODEL
- Massimo Token 
- lavoroDirectory
- tema (scuro/chiaro)

## 3. Implementazione

### 3.1 Stack tecnologico

| Componente |  | Descrizione |
|------|---------|------|
| Runtime | JDK 25 | Più recente JDK，supportathread virtuali |
| Framework | Spring Boot 3.5.x | baseFramework |
| AI | Spring AI 2.0.0-M4 | Chiamata modello AI |
| CLI Analizza | Picocli 4.7+ | ComandoParametriAnalizza |
| interazione terminale | JLine 3.28+ | modifica、cronologia、completamento |
| HTTP | Spring WebFlux | output in streaming（Reactor） |
| Costruisci | Maven | progettoCostruisci |

### 3.2 architetturadesign

```
┌─────────────────────────────────────────────┐
│  CLI livello (Picocli)                           │
│  SpaceAICommand → analisiParametri → avvioapplicazione     │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│  REPL livello (JLine 3)                          │
│  ReplSession → UtenteInput → Comandodividi           │
│  ├── comando slash → CommandRegistry → direttamenteEsegui   │
│  └── Conversazionecontenuto → AgentLoop → AI chiamata          │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│  Agent ciclolivello (Spring AI ChatModel)          │
│  AgentLoop.run(input)                        │
│  ├── Costruisci Prompt (System + History + User)   │
│  ├── chiamata ChatModel.call/stream()            │
│  ├── rilevachiamata strumento → ToolRegistry.execute()   │
│  ├── raccoglie i risultati → cronologia messaggiaggiunge                  │
│  └── cicloanessunochiamata strumento                       │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│  Strumentolivello                                      │
│  ToolRegistry                                │
│  ├── BashTool, FileReadTool, ...             │
│  ├── controllo permessi                                │
│  └── RisultatoFormatta                              │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│  esegue renderinglivello (ConsoleRenderer)                    │
│  ├── BannerPrinter (avvio)                │
│  ├── ThinkingRenderer ()             │
│  ├── ToolStatusRenderer (StrumentoStato)           │
│  ├── MarkdownRenderer (Markdownesegue rendering)         │
│  └── SpinnerAnimation (caricaanimazione)             │
└─────────────────────────────────────────────┘
```

### 3.3 chiusodesign

1. **ChatModel vs ChatClient**: ciclo personalizzato con ChatModel
   - ChatClient.call() completoStrumentociclo，manon puòininInserimentopersonalizzatologica
   - ChatModel.call() ritornaRisposta，puòconchiamata strumentostream
   - riferimento space-ai-learn  AgentLoop ImplementazioneModalità

2. **registrazione strumentimodalità**: Modalitàsupporta
   - `Tool` Interfaccia（ProtocolloModalità，unisciStrumento）
   - `@Tool` annotazione（Modalità，unisciStrumento）
   - `ToolCallback` adattatorepontedue tipi diModalità

3. **cronologia messaggi**: Gestisci `List<Message>`
   - nonDipendenza Spring AI  ChatMemory
   - messaggioformatoetronca

4. **terminale UI**: JLine 3 + ANSI escape
   - non usa React modello di rendering（approccio originale Ink）
   - direttamenteoperazioneterminaleOutputstream
   - supporta Windows Terminal / PowerShell

## 4. 

### 4.1 baseFunzione
- [x] applicazionepuòpositivoavvioeMostra Banner
- [x] supportatramiteVariabili d'ambienteoFile di configurazioneImposta API Key eModello
- [x] REPL ciclopositivolavoro：Input →  AI 
- [x] chiamata strumentociclopositivo：AI puòconchiamataStrumentoeottieniRisultato
- [x] menosupporta 8  principaliStrumento（reale 18 )
- [x] menosupporta 8 comando slash（reale 28 )

### 4.2 esperienza di interazione
- [x] Banner visualizzapositivo（Logo + Versione + ModelloInformazione + Provider + URL）
- [x] Inputsuggerimentosupportamodificaecronologiarecord
- [x] Thinking visibile（Spinner animazione + "Thinking..." suggerimento）
- [x] chiamata strumentoMostra Spinner animazioneeStato
- [x] supporta ANSI Output
- [x] Markdown contenutoformato

### 4.3 contestoFunzione
- [x] caricaprogetto SPACE.md
- [x] caricaUtente SPACE.md
- [x] contesto GitInformazionepositivoraccolta
- [x] Sistemapromptpackagec'èInformazione

### 4.4 qualità del codice
- [x] Maven nessunoErrore
- [x] struttura del codice chiara, suddivisione dei package ragionevole
- [x] chiusologicac'èincommento
- [x] forniscecompleto（README.md）

### 4.5 giàFunzione
- [x] output in streaming（Flux<ChatResponse>  token tempo realevisualizza）
- [x] cronologia conversazionepersistenza（JSON formatoAutomaticamentesalva/carica）
- [x] AI contestoCompressione（/compact generariepilogo）
- [x] Input multi-rigasupporta（Continuazione con backslash）
- [x]  provider API（OpenAI + Anthropic cambia）
- [x] /history ComandosalvaConversazione

---

## 5. ImplementazioneStato

### 5.1 giàImplementazioneFunzione

| classe | Funzione | Stato |
|------|------|------|
| **REPL principale** | ChatModel personalizzatociclo | ✅ |
| | output in streaming (Flux) | ✅ |
| | modalità bloccanteDegrado | ✅ |
| | MassimoiterazioneLimitazione (50) | ✅ |
| | cronologia messaggiGestisci | ✅ |
| | Conversazionepersistenza | ✅ |
| **StrumentoSistema (18)** | BashTool | ✅ |
| | FileReadTool | ✅ |
| | FileWriteTool | ✅ |
| | FileEditTool | ✅ |
| | GlobTool | ✅ |
| | GrepTool | ✅ |
| | WebFetchTool | ✅ |
| | TodoWriteTool | ✅ |
| | ListFilesTool | ✅ |
| | AgentTool | ✅ |
| | NotebookEditTool | ✅ |
| | WebSearchTool (P0) | ✅ |
| | AskUserQuestionTool (P0) | ✅ |
| | TaskCreate/Get/List/Update (P2) | ✅ |
| | ConfigTool (P2) | ✅ |
| | McpToolBridge (P2) | ✅ |
| **ComandoSistema (28)** | Base: /help, /clear, /exit e altri 11 | ✅ |
| | P0: /diff, /version, /skills, /memory, /copy | ✅ |
| | P1: /resume, /export, /commit | ✅ |
| | P2: /hooks, /review, /stats, /branch, /rewind, /tag | ✅ |
| | P2: /security-review, /mcp, /plugin | ✅ |
| **contesto** | SPACE.md Carica | ✅ |
| | Skills caricamento competenze | ✅ |
| | contesto GitRaccogli | ✅ |
| | SistemapromptCostruisci | ✅ |
| **terminale UI** | Banner + Provider Informazione | ✅ |
| | JLine modifica/cronologia/Tabcompletamento | ✅ |
| | Input multi-riga | ✅ |
| | Spinner animazione | ✅ |
| | StrumentoStatoEsegui rendering | ✅ |
| | ANSI  | ✅ |
| | rendering Markdown | ✅ |
| | evidenziazione sintassi (P1) | ✅ |
| | barra di stato inferiore (P1) | ✅ |
| | DiffRenderer coloratodiff (P2) | ✅ |
| **Configurazione** |  provider APIcambia | ✅ |
| | Variabili d'ambiente | ✅ |
| | Token/costoTraccia | ✅ |
| **Miglioramenti principali P0** | conferma permessimeccanismo | ✅ |
| | Thinking contenutoMostra | ✅ |
| **Miglioramenti esperienza P1** | Hook Sistema (4 tipi di hook) | ✅ |
| | Vim ModalitàInput | ✅ |
| **P2 estensione** | MCP  (JSON-RPC/StdIO) | ✅ |
| | MCP piùServizioGestisci | ✅ |
| | sistema plugin (JARcarica) | ✅ |
| | gestione attivitàSistema | ✅ |
| | ConversazioneBranch/tag/fallback | ✅ |
| **CLI stile** | bordo Banner (╭╮╰╯ + Logo) | ✅ |
| | colorato ● messaggioidentificatore | ✅ |
| | ⎿ StrumentoRisultatoprefisso | ✅ |
| | `<thought>` tagMostra | ✅ |
| | ✻  | ✅ |
| **i18n** | Utentevisibilestringa | ✅ |

### 5.2 non ancoraImplementazioneFunzione

> ⚠️ P0、P1、P2 giàTuttoImplementazione。consottoSoloElenca P3（salta）progetto。

#### 🔴 P0 principale — ✅ giàTuttoImplementazione

#### 🟡 P1 importante — ✅ giàTuttoImplementazione

#### 🟢 P2 estensione — ✅ giàTuttoImplementazione

#### ⚪ P3 salta（nonUsato per Java CLI）

| Funzione |  |
|------|------|
| Ink/React UI Framework | Java  JLine alternativa |
| IDE ponte (bridge/) | richiede IDE Plugin |
| Funzione (voice/) | piattaforma |
| remotoSessione (remote/) | richiedeServizio |
|  (sandbox/) | richiedeContainerizzazione |
|  (telemetry/) | Funzionalità principali |

---

## appendice A：space-ai codice sorgentemodulosovrascrive

### A.1 modulo

| modulo | File | Descrizione |
|------|--------|------|
| utils/ | 564 | Strumento（Permesso、Shell、Git、File、Plugin、Configurazioneecc.) |
| components/ | 389 | React UI Componente（messaggio、Input、designSistema、ecc.) |
| commands/ | 207 | comando slashInsieme（79+ Comando） |
| tools/ | 184 | AI StrumentoSistema（40  strumenti) |
| services/ | 130 | Livello servizi di business（API、MCP、OAuth、Compressione、analisiecc.) |
| hooks/ | 104 | React Hooks（UIinterazione, sessione, permessi, notifiche, ecc.) |
| ink/ | 96 | personalizzato Ink terminale UI Framework |
| bridge/ | 31 | IDE/modificaintegrazionepontelivello |
| constants/ | 21 | costanti globali (prompt, limiti, codici errore, ecc.) |
| skills/ | 20 | CompetenzaSistema（17 integratoCompetenza） |
| cli/ | 19 | CLI OutputGestisceetrasportolivello |
| keybindings/ | 14 | veloceSistema |
| tasks/ | 12 | dopoattivitàesegueSistema |
| types/ | 11 | TypeScript Tipodefinizione |
| migrations/ | 11 | Migrazione dati |
| context/ | 9 | React Context（Stato、notifica、ecc.) |
| memdir/ | 8 | memoria/memoriaSistema |
| entrypoints/ | 8 | applicazione（CLI、MCP、SDK） |
| buddy/ | 6 | sistema ruoli compagno/assistente |
| state/ | 6 | gestione stato globale |
| vim/ | 5 | Vim modificaModalità |
| native-ts/ | 4 | estensione（C++ ） |
| query/ | 4 | QuerySistema（Tokenecc.) |
| remote/ | 4 | remotoConnessioneGestisci |
| screens/ | 3 | Componente（REPL、Doctor、Resume） |
| server/ | 3 | Servizio |
| upstreamproxy/ | 2 | proxy e relay |
| plugins/ | 2 | sistema plugin |
| livelloFile | 18 | main.tsx、context.ts、Tool.ts ecc.principalepunto di ingresso |
| altri(4) | 4 | coordinator/、schemas/、outputStyles/、voice/ ecc. |
| **totale** | **~2,289** | |

### A.2 modulosovrascrivelivellodividi

ognimodulo Java prioritàdividicomelivello：

- 🔴 **P0 principale（Must）**: nonImplementazioneSistemanon puòEsecuzione
- 🟡 **P1 importante（Should）**: principaleUtente
- 🟢 **P2 estensione（Could）**: potenziamentoFunzione，puòdopoiterazione
- ⚪ **P3 salta（Skip）**: piattaformaononUsato per Java Versione

---

### A.3 livelloFile（18  file）

| File | livello | Java Corrisponde a | Descrizione |
|------|------|----------|------|
| main.tsx | 🔴 P0 | SpaceAIRunner | applicazioneavvio（Inizializzazionecontesto、caricaStrumento、avvioREPL） |
| context.ts | 🔴 P0 | ContextBuilder | raccoltaSistemaeUtentecontesto（GitStato、SPACE.md、） |
| Tool.ts | 🔴 P0 | Tool.java Interfaccia | StrumentoProtocollodefinizione（name、description、inputSchema、callecc.) |
| tools.ts | 🔴 P0 | ToolRegistry | StrumentoInsiemeregistraeCarica |
| commands.ts | 🔴 P0 | CommandRegistry | comando slashInsiemeRegistra |
| Task.ts | 🟡 P1 | TaskDefinition | definizione classe attività |
| tasks.ts | 🟡 P1 | TaskManager | gestione attività |
| query.ts | 🔴 P0 | AgentLoop.query() | AI Queryingresso (costruzione messaggi, chiamata API, gestione streaming) |
| QueryEngine.ts | 🔴 P0 | AgentLoop | Queryesegue（Strumentociclo） |
| setup.ts | 🔴 P0 | AppConfig | InizializzazioneeConfigurazione |
| history.ts | 🟡 P1 | SessionHistory | cronologia conversazionepersistenza |
| ink.ts | 🔴 P0 | ConsoleRenderer | terminaleesegue rendering（Java  JLine alternativa） |
| replLauncher.tsx | 🔴 P0 | ReplSession | REPL avvio |
| cost-tracker.ts | 🟡 P1 | CostTracker | Token/costoTraccia |
| costHook.ts | 🟡 P1 | (integra inCostTracker) | costo |
| dialogLaunchers.tsx | 🟢 P2 | - | Conversazioneavvio（React） |
| interactiveHelpers.tsx | 🟡 P1 | InputHandler | assistenza interattiva |
| projectOnboardingState.ts | 🟢 P2 | - | progettoStato |

---

### A.4 entrypoints/ modulo（8  file）

| File | livello | Java Corrisponde a | Descrizione |
|------|------|----------|------|
| cli.tsx | 🔴 P0 | SpaceAIApplication | CLI Punto ingresso (analisi parametri, percorso rapido, distribuzione modalità) |
| init.ts | 🟡 P1 | AppInitializer | Inizializzazionepunto di ingresso |
| mcp.ts | 🟢 P2 | - | MCP ServizioModalità |
| agentSdkTypes.ts | ⚪ P3 | - | Agent SDK Tipo |
| sandboxTypes.ts | ⚪ P3 | - | Tipo |
| sdk/*.ts | ⚪ P3 | - | SDK principale（3 file） |

---

### A.5 tools/ modulo（184  file，40  strumenti)

#### 🔴 P0 principaleStrumento（Codifica，11 )

| # | Strumento | Directory | File | Java classe | Descrizione |
|---|-------|------|--------|---------|------|
| 1 | Bash | BashTool/ | 18 | BashTool | Esegui Shell/Cmd Comando，Permesso、 |
| 2 | FileRead | FileReadTool/ | 5 | FileReadTool | leggeFile（testo/PDF/immagine），Token |
| 3 | FileEdit | FileEditTool/ | 6 | FileEditTool | Filemodifica（hunks、visualizza） |
| 4 | FileWrite | FileWriteTool/ | 3 | FileWriteTool | scriveFile，Permessovalidazione |
| 5 | Glob | GlobTool/ | 3 | GlobTool | FileRicerca |
| 6 | Grep | GrepTool/ | 3 | GrepTool | basato su ripgrep codiceRicerca |
| 7 | WebFetch | WebFetchTool/ | 5 | WebFetchTool | ottienicontenuto |
| 8 | WebSearch | WebSearchTool/ | 3 | WebSearchTool | ReteRicerca |
| 9 | AskUserQuestion | AskUserQuestionTool/ | 2 | AskUserTool | Utente（più/Input） |
| 10 | Agent | AgentTool/ | 20 | AgentTool | proxy/agentecreaeEsecuzione |
| 11 | PowerShell | PowerShellTool/ | 14 | (unisceaBashTool) | Windows PowerShell Esegui |

#### 🟡 P1 importanteStrumento（，8 )

| # | Strumento | Directory | File | Java classe | Descrizione |
|---|-------|------|--------|---------|------|
| 12 | TodoWrite | TodoWriteTool/ | 3 | TodoWriteTool | Sessione |
| 13 | Skill | SkillTool/ | 4 | SkillTool | esegueCompetenza/CompetenzaComando |
| 14 | BriefTool | BriefTool/ | 5 | BriefTool | Utenteinviamessaggio |
| 15 | NotebookEdit | NotebookEditTool/ | 4 | - | Jupyter modifica |
| 16 | TaskStop | TaskStopTool/ | 3 | TaskStopTool | ferma le attività in background |
| 17 | TaskOutput | TaskOutputTool/ | 2 | TaskOutputTool | ottieniattivitàOutput |
| 18 | EnterPlanMode | EnterPlanModeTool/ | 4 | PlanModeTool | Modalità |
| 19 | ExitPlanMode | ExitPlanModeTool/ | 4 | PlanModeTool | EsciModalità |

#### 🟢 P2 estensioneStrumento（14 )

| # | Strumento | Directory | File | Descrizione |
|---|-------|------|--------|------|
| 20 | LSP | LSPTool/ | 6 | ServizioProtocollo |
| 21 | MCPTool | MCPTool/ | 4 | MCP chiamata strumento |
| 22 | ListMcpResources | ListMcpResourcesTool/ | 3 |  MCP risorse |
| 23 | ReadMcpResource | ReadMcpResourceTool/ | 3 | Leggi MCP risorse |
| 24 | McpAuth | McpAuthTool/ | 1 | MCP OAuth Autenticazione |
| 25 | Config | ConfigTool/ | 5 | ottieni/ImpostazioniConfigurazione |
| 26 | ToolSearch | ToolSearchTool/ | 3 | RicercalatenzacaricaStrumento |
| 27 | EnterWorktree | EnterWorktreeTool/ | 4 | Git lavoro |
| 28 | ExitWorktree | ExitWorktreeTool/ | 4 | Escilavoro |
| 29 | TaskCreate | TaskCreateTool/ | 3 | creadopoattività |
| 30 | TaskGet | TaskGetTool/ | 3 | ottieniattivitàInformazione |
| 31 | TaskList | TaskListTool/ | 3 | elencadopoattività |
| 32 | TaskUpdate | TaskUpdateTool/ | 3 | Aggiornamentostato attività |
| 33 | SendMessage | SendMessageTool/ | 4 | messaggi tra agenti |

#### ⚪ P3 saltaStrumento（7 ,piattaforma/Funzione）

| # | Strumento | Descrizione |
|---|-------|------|
| 34 | REPL | Ant Internodedicato |
| 35 | TeamCreate/Delete | Agent Swarm Modalità |
| 36 | RemoteTrigger | remotoattiva |
| 37 | ScheduleCron(3) | Cron job schedulati |
| 38 | Sleep | latenzaattende |
| 39 | SyntheticOutput | Output |
| 40 | TestStrumento | TestingPermissionTool |

---

### A.6 commands/ modulo（207  file，79+ Comando）

#### 🔴 P0 principaleComando（Utentebaseoperazione，14 )

| Comando | Alias | Java classe | Descrizione |
|------|------|---------|------|
| /help | - | HelpCommand | visualizzaepuòComandoLista |
| /clear | reset, new | ClearCommand | cancellacronologia conversazione |
| /compact | - | CompactCommand | Compressionecontesto（conserva riepilogo) |
| /exit | quit | ExitCommand | Esciapplicazione |
| /model | - | ModelCommand | cambia AI Modello |
| /config | settings | ConfigCommand | apreConfigurazionepannello |
| /context | - | ContextCommand | visualizzaCorrentecontesto |
| /memory | - | MemoryCommand | modifica SPACE.md memoriaFile |
| /cost | - | CostCommand | visualizzacostoe Token consumo |
| /status | - | StatusCommand | visualizzaStato（Versione、Modello、） |
| /diff | - | DiffCommand | non ancoramodifiche |
| /copy | - | CopyCommand | copiaa |
| /version | - | VersionCommand | visualizzaVersione |
| /skills | - | SkillsCommand | elencapuòCompetenza |

#### 🟡 P1 importanteComando（Funzione，16 )

| Comando | Alias | Java classe | Descrizione |
|------|------|---------|------|
| /commit | - | CommitCommand | Crea git commit |
| /resume | continue | ResumeCommand | primaConversazione |
| /add-dir | - | AddDirCommand | aggiungelavoroDirectory |
| /theme | - | ThemeCommand | cambiatema (scuro/chiaro) |
| /plan | - | PlanCommand | /Modalità |
| /permissions | allowed-tools | PermissionsCommand | Strumentoregola permessi |
| /export | - | ExportCommand | esportaConversazioneaFile |
| /rename | - | RenameCommand | CorrenteConversazione |
| /effort | - | EffortCommand | ImpostazioniModellolivello |
| /fast | - | FastCommand | cambiaveloceModalità |
| /vim | - | VimCommand | cambia Vim/modificaModalità |
| /keybindings | - | KeybindingsCommand | veloceConfigurazione |
| /files | - | FilesCommand | elencaCorrentecontestoinFile |
| /doctor | - | DoctorCommand | eImposta |
| /tasks | bashes | TasksCommand | dopoattività |
| /usage | - | UsageCommand | visualizzaLimitazione |

#### 🟢 P2 estensioneComando（22 )

| Comando | Descrizione |
|------|------|
| /review |  Pull Request |
| /commit-push-pr | 、eaperto PR |
| /branch | creaConversazioneBranch |
| /rewind | fallbackaprimacontrolla |
| /mcp | Gestisci MCP Servizio |
| /hooks | Configurazione |
| /login, /logout | / |
| /color | Impostazionisuggerimento |
| /ide | Gestisci IDE integrazione |
| /init | Inizializzazioneprogetto |
| /feedback |  |
| /share | dividiConversazione |
| /session | remotoSessione |
| /plugin | Plugin |
| /pr-comments | Ottieni PR  |
| /release-notes | Descrizione |
| /security-review | revisione della sicurezza |
| /desktop | a SPACE AI Desktop |
| /mobile | visualizza |
| /sandbox | Imposta |
| /stats | statistiche di utilizzo |
| /tag | Sessionetag |

#### ⚪ P3 saltaComando（27+ ,Interno/Debug/piattaforma）

debug-tool-call, heapdump, perf-issue, bridge-kick, ant-trace, mock-limits,
backfill-sessions, break-cache, autofix-pr, bughunter,
teleport, stickers, ultraplan, upgrade, install-github-app, install-slack-app,
chrome, voice, brief, proactive, assistant, remote-control, statusline,
thinkback, thinkback-play, oauth-refresh ecc.

---

### A.7 services/ modulo（130  file，20 sottomodulo）

| modulo | File | livello | Java Corrisponde a | Descrizione |
|--------|--------|------|----------|------|
| api/ | 19 | 🔴 P0 | AiService / ApiClient | AI API chiamata（Anthropic SDK、Riprova、ErroreGestisce） |
| tools/ | 4 | 🔴 P0 | ToolExecutor | StrumentoesegueFramework（StreamingToolExecutor、Strumento） |
| compact/ | 10 | 🟡 P1 | CompactService | contestoCompressione（Automaticamente/ManualmenteCompressione、messaggioraggruppamento） |
| mcp/ | 22 | 🟢 P2 | McpService | MCP （StdIO/SSE/HTTP trasporto、Autenticazione） |
| oauth/ | 5 | 🟢 P2 | OAuthService | OAuth Autenticazionestream |
| analytics/ | 10 | ⚪ P3 | - | analisie（Datadog、GrowthBook） |
| lsp/ | 6 | 🟢 P2 | LspService | Serviziointegrazione |
| SessionMemory/ | 3 | 🟡 P1 | SessionMemoryService | SessionememoriaGestisci |
| extractMemories/ | 2 | 🟡 P1 | MemoryExtractor | estrae memoria dalla conversazione |
| autoDream/ | 4 | ⚪ P3 | - | Automaticamentemotore di esecuzione attività |
| plugins/ | alcuni | 🟢 P2 | PluginService | PluginServizioInterfaccia |
| PromptSuggestion/ | alcuni | 🟢 P2 | - | prompt |
| tips/ | alcuni | ⚪ P3 | - | suggerimento |
| remoteManagedSettings/ | alcuni | ⚪ P3 | - | remotoImposta |
| settingsSync/ | alcuni | ⚪ P3 | - | ImpostazioniSincrono |
| teamMemorySync/ | alcuni | ⚪ P3 | - | memoriaSincrono |
| AgentSummary/ | alcuni | 🟢 P2 | - | proxy/agenteriepilogogenera |
| tokenEstimation.ts | 1 | 🟡 P1 | TokenEstimator | Token quantitàStima |
| voice*.ts | 3 | ⚪ P3 | - | Funzione |
| rateLimitMessages.ts | 1 | 🟡 P1 | RateLimitHandler | Limitazionemessaggio |

---

### A.8 components/ modulo（389  file）

>  React + Ink esegue renderingterminale UI，Java Versione JLine 3 + ANSI alternativa，nondirettamenteMappa React Componente，sìil suo **esegue renderinglogica**  Java Implementazione。

| modulo/Componente | livello | Java Corrisponde a | Descrizione |
|------------|------|----------|------|
| LogoV2/ (Banner) | 🔴 P0 | BannerPrinter | Avvia Banner（ASCII Art + Versione + Modello） |
| PromptInput/ | 🔴 P0 | InputPrompt | ComandoInput（più、cronologia、completamento） |
| Messages.tsx | 🔴 P0 | MessageRenderer | messaggioListaEsegui rendering |
| messages/AssistantThinkingMessage | 🔴 P0 | ThinkingRenderer | Thinking visualizza（∴ Thinking...） |
| messages/AssistantToolUseMessage | 🔴 P0 | ToolStatusRenderer | chiamata strumentoStatoMostra |
| Spinner.tsx | 🔴 P0 | SpinnerAnimation | caricaanimazione（shimmer effetto） |
| ToolUseLoader.tsx | 🔴 P0 | (integra inToolStatusRenderer) | Strumentocarica（●Successo/●Fallimento） |
| StatusLine.tsx | 🟡 P1 | StatusLineRenderer | barra di stato inferiore（Modello、modalità permessi、cwd） |
| AgentProgressLine.tsx | 🟡 P1 | AgentProgressRenderer | Agent esegueprogresso（） |
| TextInput.tsx | 🔴 P0 | (integra inInputPrompt) | testoInput（VimModalitàsupporta） |
| design-system/ | 🟡 P1 | AnsiStyle | designSistema（colore tema、ThemedBox/Text） |
| HighlightedCode/ | 🟡 P1 | MarkdownRenderer | evidenziazione sintassi |
| diff/ | 🟢 P2 | DiffRenderer | Mostra |
| permissions/ | 🟡 P1 | PermissionPrompt | PermessoRichiestaConversazione |
| HelpV2/ | 🟡 P1 | (integra inHelpCommand) | pannello |
| mcp/ | 🟢 P2 | - | MCP Servizio UI |
| agents/ | 🟢 P2 | - | piùproxy/agenteGestisci UI |
| tasks/ | 🟡 P1 | TaskListRenderer | lista attività UI |
| wizard/ | ⚪ P3 | - | Componente |
| sandbox/ | ⚪ P3 | - |  UI |
| grove/ | ⚪ P3 | - | Grove Framework UI |

---

### A.9 ink/ modulo（96  file）

> personalizzato Ink terminale UI Framework（React reconciler + Yoga layout）。Java VersionenonquestoFramework，sì JLine 3 direttamenteImplementazioneterminaleesegue rendering。

| modulo | livello | Java Corrisponde a | Descrizione |
|--------|------|----------|------|
| esegue rendering | ⚪ P3 | - | React reconciler + Yoga layout → non |
| components/ (Box, Text...) | ⚪ P3 | - | baseUIComponente → JLine direttamenteOutputalternativa |
| hooks/ (useInput, useAnimationFrame...) | ⚪ P3 | - | React Hooks → Java Eventocicloalternativa |
| events/ | 🔴 P0 | JLine KeyMap | /Evento → JLine EventoGestisci |
| terminal.ts | 🔴 P0 | JLine Terminal | interazione terminale → JLine Terminal |
| colorize.ts | 🔴 P0 | AnsiStyle | colorazione → ANSI escape |
| stringWidth.ts | 🟡 P1 | StringWidthUtil | stringalarghezza（CJKsupporta） |
| wrap-text.ts | 🟡 P1 | TextWrapper | testoa capo |

---

### A.10 hooks/ modulo（104  file）

> React Hooks ModalitàNon portare direttamente. Estrai la logica di business chiave in classi di servizio Java.

| chiuso Hook | livello | Java Corrisponde a | Descrizione |
|-----------|------|----------|------|
| useMainLoopModel | 🔴 P0 | AgentLoop | Ciclo principale gestione modello |
| useCommandQueue | 🔴 P0 | CommandQueue | ComandoCoda |
| useCanUseTool | 🔴 P0 | PermissionChecker | Strumentocontrollo permessi |
| useMergedTools | 🔴 P0 | ToolRegistry | Strumentounisce（integrato+MCP） |
| useMergedCommands | 🔴 P0 | CommandRegistry | Comandounisce |
| useTextInput | 🔴 P0 | InputPrompt | testoInputGestisci |
| useArrowKeyHistory | 🟡 P1 | (JLine integrato) | cronologia |
| useVimInput | 🟡 P1 | VimModeHandler | Vim ModalitàInput |
| useGlobalKeybindings | 🟡 P1 | KeybindingManager | scorciatoie globali |
| useElapsedTime | 🟡 P1 | CostTracker | Calcola |
| useIDEIntegration | ⚪ P3 | - | IDE integrazione |
| useVoice* | ⚪ P3 | - | Correlato |
| useRemoteSession | ⚪ P3 | - | remotoSessione |
| useTeleportResume | ⚪ P3 | - | ripristino trasmissione |
| notifs/ (16) | 🟢 P2 | NotificationService | Notifiche varie |

---

### A.11 utils/ modulo（564  file，31 sottoDirectory）

| modulo | File | livello | Java Corrisponde a | Descrizione |
|--------|--------|------|----------|------|
| shell/ | 10 | 🔴 P0 | ShellExecutor | Shell ComandoesegueFramework |
| permissions/ | 22 | 🔴 P0 | PermissionService | PermessoValidazione（dividiclasse、Regolaanalisi、schemi pericolosi） |
| spacemd.ts | 1 | 🔴 P0 | SpaceMdLoader | SPACE.md cercaecarica（ @include） |
| systemPrompt.ts | 1 | 🔴 P0 | SystemPromptBuilder | SistemapromptCostruisci |
| config.ts | 1 | 🔴 P0 | ConfigManager | ConfigurazionecaricaGestisci |
| git/ | 3 | 🔴 P0 | GitContext | Git operazione（Branch、Stato、Log） |
| file.ts + fileRead*.ts | 5 | 🔴 P0 | FileUtils | FileoperazioneStrumento |
| model/ | 5 | 🟡 P1 | ModelSelector | ModelloselezioneeCalcola |
| hooks/ | 16 | 🟡 P1 | HookExecutor | Sistema（PreToolUse/PostToolUse） |
| plugins/ | 38 | 🟢 P2 | PluginLoader | caricamento plugineGestisci |
| skills/ | 1 | 🟡 P1 | SkillWatcher | Competenzamodificherileva |
| todo/ | alcuni | 🟡 P1 | TodoManager | TODO attivitàTraccia |
| messages/ | 2 | 🔴 P0 | MessageMapper | messaggioformatoMappa |
| json.ts, markdown.ts | 2 | 🟡 P1 | FormatUtils | datiformatoGestisci |
| diff.ts, gitDiff.ts | 2 | 🟡 P1 | DiffUtils |  |
| auth.ts, oauth/ | 3 | 🟢 P2 | AuthUtils | AutenticazioneStrumento |
| telemetry/ | 9 | ⚪ P3 | - | eTraccia |
| sandbox/ | 2 | ⚪ P3 | - |  |
| swarm/ | alcuni | ⚪ P3 | - | Swarm piùproxy/agente |
| computerUse/ | 14 | ⚪ P3 | - |  |
| teleport/ | alcuni | ⚪ P3 | - | Sessione |
| suggestions/ | alcuni | 🟢 P2 | - | Sistema |

---

### A.12 altrimodulo

| modulo | File | livello | Java Corrisponde a | Descrizione |
|------|--------|------|----------|------|
| skills/ | 20 | 🟡 P1 | SkillLoader + skills/ | sistema competenze（17  competenze integrate, caricamento da disco) |
| state/ | 6 | 🔴 P0 | SessionState | gestione stato globale |
| constants/ | 21 | 🔴 P0 | Constants + Prompts | eSistemapromptdefinizione |
| types/ | 11 | 🔴 P0 | (dividiaInterfaccia) | Tipodefinizione |
| screens/ | 3 | 🔴 P0 | ReplSession | REPL/Doctor/Resume  |
| cli/ | 19 | 🔴 P0 | CliOutput | CLI OutputGestisci |
| context/ | 9 | 🟡 P1 | (Spring Contextalternativa) | React Context StatoGestisci |
| keybindings/ | 14 | 🟡 P1 | KeybindingManager | veloceSistema |
| memdir/ | 8 | 🟡 P1 | MemoryManager | memoria/memoriaCerca |
| tasks/ | 12 | 🟡 P1 | TaskExecutor | dopoattivitàEsegui |
| query/ | 4 | 🔴 P0 | AgentLoop | QuerySistema、Token |
| vim/ | 5 | 🟡 P1 | VimModeHandler | Vim modificaModalità |
| migrations/ | 11 | ⚪ P3 | - | Migrazione dati |
| bridge/ | 31 | ⚪ P3 | - | IDE ponte |
| buddy/ | 6 | ⚪ P3 | - | sistema compagno |
| coordinator/ | 1 | 🟢 P2 | - | piùproxy/agentecoordinamento |
| remote/ | 4 | ⚪ P3 | - | remotoConnessione |
| server/ | 3 | ⚪ P3 | - | Servizio |
| native-ts/ | 4 | ⚪ P3 | - |  C++ estensione |
| upstreamproxy/ | 2 | ⚪ P3 | - | relay proxy |
| plugins/ | 2 | 🟢 P2 | PluginManager | sistema pluginFramework |
| outputStyles/ | 1 | 🟢 P2 | OutputStyleLoader | personalizzatoOutputstile |
| voice/ | 1 | ⚪ P3 | - | Modalità |
| moreright/ | 1 | ⚪ P3 | - | UI potenziamento |
| bootstrap/ | 1 | 🔴 P0 | (AppConfig) | avvioStato |
| assistant/ | 1 | 🟡 P1 | SessionHistoryApi | Sessionecronologia API |
| schemas/ | 1 | 🔴 P0 | (dividiaSchema) | datiModalità |

---

### A.13 sovrascrive

| livello | modulo/Componente | Descrizione |
|------|-----------|------|
| 🔴 P0 principale | ~45 | nonImplementazioneSistemanon puòEsecuzione |
| 🟡 P1 importante | ~35 | principaleUtente |
| 🟢 P2 estensione | ~25 | potenziamentoFunzione，puòdopoiterazione |
| ⚪ P3 salta | ~30 | piattaformaonon |

### Java classe

| livello | classe | Descrizione |
|------|---------|------|
| CLI livello | 3-5 | Application、Command、Runner |
| principalelivello | 5-8 | AgentLoop、MessageHistory、SessionState ecc. |
| Strumentolivello | 12-15 | 11  principaliStrumento + Registry + Callback |
| Comandolivello | 16-20 | 14  principaliComando + Registry + Interfaccia |
| contestolivello | 6-8 | ContextBuilder、PromptBuilder、SpaceMd、Git、Skill |
| esegue renderinglivello | 8-12 | Console、Banner、Thinking、ToolStatus、Markdown、Spinner ecc. |
| Configurazionelivello | 3-5 | AppConfig、AiConfig、ToolConfig |
| **totale** | **55-75** | |
