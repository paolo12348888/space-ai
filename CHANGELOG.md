# Registro delle modifiche

## [2026-04-02] - Aggiornamento stile visivo CLI + unificazione i18n inglese

- **Tipo di modifica**: Funzionepotenziamento + internazionalizzazione
- **Dettagli delle modifiche**:
    - **Ridisegno Banner**:
        - Banner di avvio con bordi `╭╮╰╯` (ispirato al logo SPACE AI)
        - Logo tazza caffè fumante ASCII + layout a due colonne (Logo a sinistra, Info a destra)
        - Calcolo automatico della larghezza visibile ANSI per garantire l'allineamento dei bordi
        - Lato destro mostra: Welcome / API URL / Protocol / Model / Work Dir / Tools & Commands
        - In modalità terminale stretto/dumb, degrada automaticamente al Banner compatto
    - **Aggiornamento rendering stato strumenti**:
        - Punto `●` colorato per identificare diversi tipi di messaggio (blu=strumento in esecuzione, verde=completato, rosso=errore, magenta=pensiero)
        - Prefisso `⎿` per mostrare i risultati dello strumento (stile SPACE AI)
        - Visualizzazione inline dei parametri strumento: `● ToolName(args)`
    - **Aggiornamento visualizzazione Thinking**:
        - Formato blocco tag `<thought>...</thought>` (stile Copilot CLI)
    - **Miglioramento risposta AI**:
        - Mostra prefisso `●` prima della risposta
        - Mostra `✻ Worked for Xs` dopo la risposta per le statistiche di durata
    - **Unificazione i18n inglese**:
        - Tutte le stringhe cinesi visibili all'utente in 46 file tradotte in inglese
        - Copre tutti i 28 comandi, 18 strumenti, moduli Core/MCP/Plugin/REPL/Context, ecc.
        - I prompt AI (compact/commit/review/security, ecc.) convertiti in inglese
        - I Javadoc e i commenti inline in cinese restano invariati
    - **Correzioni bug**:
        - `/help` ora ottiene dinamicamente tutti i comandi registrati (prima erano 6 hardcoded)
        - `CommandContext` aggiunto campo `commandRegistry`
        - `/history` correzione del nome (rimosso il prefisso `/` in eccesso)
        - Descrizioni di `/compact` e `/history` convertite in inglese
        - TODO.md rimosso dal controllo versione e aggiunto a .gitignore
- **Requisito correlato: 2.6 Esperienza interazione CLI, 4.2 Esperienza interazione

## [2026-04-03] - P2: MCPProtocollo + sistema plugin + gestione attività + CLIpotenziamento

- **Tipo di modifica**: Funzioneaggiunge
- **Dettagli delle modifiche**:
    - **A CLIpotenziamento**:
        - aggiunge `DiffRenderer` colorato diff renderer（、statriepilogo、unifiedformatoanalisi）
        - aggiunge 7  comandi: `/hooks`, `/review`(rev), `/stats`, `/branch`, `/rewind`, `/tag`, `/security-review`(sec)
        - `/review` supporta `--staged` eFilePercorsoParametri
        - `/branch` supporta save/load/list/delete ComandoConversazioneBranch
        - `/tag` supportaConversazioneposizionetage goto salto
        - `/security-review` Invia AI revisione della sicurezza（SQLinietta/XSS/Autenticazione/rileva）
    - **B attivitàSistema**:
        - aggiunge `TaskManager` dopogestore attività（Threadsicurezza、ConcurrentHashMap、CachedThreadPool）
        - aggiunge 4  strumenti: `TaskCreate`/`TaskGet`/`TaskList`/`TaskUpdate`
        - aggiunge `ConfigTool` Configurazionelettura/scritturaStrumento
        - supportaAutomaticamenteesegueModalitàeManualmenteStatoModalità
    - **C MCPProtocollo**:
        - aggiunge `mcp/` package: `McpClient`、`McpTransport`、`StdioTransport`、`McpManager`、`McpException`
        - MCP supporta JSON-RPC 2.0 Protocollo（initialize 、tools/list、tools/call、resources/read）
        - StdIO implementazione del trasporto (gestione sottoprocessi, lettura/scrittura asincrona, associazione richiesta-risposta CompletableFuture)
        - `McpManager` piùServizio（da `.mcp.json` Configurazionecarica、Ciclo di vita）
        - `McpToolBridge` verrà MCP Strumentopontecomelocale Tool（nomeformato: `mcp__{server}__{tool}`）
        - aggiunge `/mcp` Comando（connect/disconnect/tools/resources/reload）
    - **D sistema plugin**:
        - aggiunge `plugin/` package: `Plugin` Interfaccia、`PluginContext`、`PluginManager`
        - JAR caricamento plugin（URLClassLoader 、Manifest Plugin-Class legge）
        - supportaplugin globali (`~/.space-ai-java/plugins/`) eprogettoPlugin (`.space-ai/plugins/`)
        - aggiunge `OutputStylePlugin` integratoOutputstilePlugin（default/minimal/verbose/markdown）
        - aggiunge `/plugin` Comando（load/unload/reload/info）
    - **integrazione**: AppConfig registraTutto 18 Strumento + 28 Comando + TaskManager + McpManager + PluginManager
- **Requisito correlato**: 5.2 Funzionalità estese P2

## [2026-04-03] - P1: HookSistema + VimModalità + Bannerpotenziamento

- **Tipo di modifica**: Funzioneaggiunge
- **Dettagli delle modifiche**:
    - aggiunge `HookManager`: supporta PreToolUse/PostToolUse/PrePrompt/PostResponse 4  tipi di hook
    - Hook ordinamento per priorità (numero più basso = priorità più alta), PreToolUse può interrompere l'esecuzione dello strumento
    - AgentLoop integrazione Hook Sistemaa executeToolCalls() stream
    - ReplSession supporta Vim modificaModalità（`SPACE_AI_VIM=1` Variabili d'ambienteAbilitato）
    - Banner potenziamento：visualizzaComandoquantitàe Vim Modalitàidentificatore
    - fix: variabile isDumb duplicata
- **Requisito correlato**: 5.2 P1 importanteFunzione

## [2026-04-03] - P1: 3Comando + evidenziazione codice + barra di stato

- **Tipo di modifica**: Funzioneaggiunge
- **Dettagli delle modifiche**:
    - aggiunge 3  comandi: `/resume`（Conversazione）, `/export`（esportaMarkdown）, `/commit`（AIgeneracommit）
    - `MarkdownRenderer` ：supporta Java/JS/TS/Python/Bash/SQL 6 altoevidenzia
    - altoevidenziaGestisce：commento → stringa → annotazione → parola chiave → carattere → /null
    - aggiunge `StatusLine` barra di stato inferiore（Modello、token、costo、APIchiamata、lavoroDirectory）
    - AppConfig registratotale 19 Comando
- **Requisito correlato**: 5.2 P1 importanteFunzione

## [2026-04-03] - P0: 5Comando + 2Strumento + conferma permessi + Thinkingvisualizza

- **Tipo di modifica**: Funzioneaggiunge
- **Dettagli delle modifiche**:
    - aggiunge 5  comandi: `/diff`, `/version`(ver), `/skills`, `/memory`(mem), `/copy`
    - aggiunge `WebSearchTool`: DuckDuckGo HTML Ricerca（nessuno API Key）
    - aggiunge `AskUserQuestionTool`: AI Utente（tramite ToolContext Callbackpausa agent loop）
    - AgentLoop aggiungeconferma permessimeccanismo: chiamata strumentoprimasuggerimento [Y/n/always]
    - AgentLoop aggiunge Thinking contenutoevisualizza（da ChatResponse metadata legge）
    - aggiunge `PermissionRequest` record e `onPermissionRequest`/`onThinkingContent` Callback
    - ReplSession aggiunge `promptPermission()` UI e `readUserInputDuringAgentLoop()` metodo
- **Requisito correlato**: 5.2 P0 Funzionalità principali

## [2026-04-02] - Phase 5: output in streamingcon funzionalità avanzate

- **Tipo di modifica**: Funzioneaggiunge
- **Dettagli delle modifiche**:
    - **5A output in streaming**: AgentLoop aggiunge `runStreaming()` metodo， `chatModel.stream()` → `Flux<ChatResponse>`  token tempo realeOutputaterminale。streamFallimentoAutomaticamenteDegradoamodalità bloccante。principaleciclo/streamdue tipi diModalità
    - **5B Conversazionepersistenza**: aggiunge `ConversationPersistence` classe，Esci REPL AutomaticamentesalvaConversazionea `~/.space-ai-java/conversations/` Directory（JSON formato）。aggiunge `/history` ComandocronologiaConversazione
    - **5C /compact AI riepilogo**: potenziamento `/compact` Comando， AI generariepilogo conversazioneiniettacontesto，alternativasvuotacronologia。Sistemasuggerimento+AI riepilogo+dopoConversazione
    - **5D Input multi-riga**: JLine ConfigurazioneContinuazione con backslash（`\`），suggerimento `  ... `
- **Requisito correlato: 2.1 Ciclo REPL principale, 2.6 output in streaming

## [2026-04-02] -  provider APIsupporta

- **Tipo di modifica**: Funzioneaggiunge
- **Dettagli delle modifiche**:
    - supporta OpenAI e Anthropic  provider API，tramite `SPACE_AI_PROVIDER` Variabili d'ambientecambia
    - Variabili d'ambiente: `AI_API_KEY` / `AI_BASE_URL` / `AI_MODEL` comune a entrambi i provider
    - Banner mostra all'avvioCorrente Provider、Model、API URL
    - fix: ProviderInfo legge vecchie variabili d'ambiente bug
- **Requisito correlato: 2.1 Ciclo REPL principale

## [2026-04-02] - Phase 4: ComandoSistemacon arricchimento del contesto

- **Tipo di modifica**: Funzioneaggiunge
- **Dettagli delle modifiche**:
    - aggiunge `TokenTracker` vero token traccia（Modello）
    - aggiunge `SkillLoader` caricamento competenze（3 DirectoryScansione、YAML frontmatter analisi）
    - aggiunge `GitContext` contesto Gitraccolta（Branch、Stato、）
    - aggiunge 4  comandi: `/init`, `/status`, `/context`, `/config`
    - potenziamento 3  comandi: `/cost`（vero token）, `/model`（puòcambia）, `/compact`（visualizza）
    - `SystemPromptBuilder` integrazione Skills e contesto Git
    - `AgentLoop` Registra token utilizzo
- **Requisito correlato: 2.4 Sistema comandi, 2.5 costruzione contesto

## [2026-04-02] - Phase 3: completoStrumento

- **Tipo di modifica**: Funzioneaggiunge
- **Dettagli delle modifiche**:
    - aggiunge 5  strumenti: `ListFilesTool`, `WebFetchTool`, `TodoWriteTool`, `AgentTool`, `NotebookEditTool`
    - fix: errore di compilazione `WebFetchTool` (replaceAll lambda → Pattern matcher)
    - `AgentTool`  Agent factorytramite `ToolContext` inietta
- **Requisito correlato: 2.2 Sistema strumenti

## [2026-04-02] - Phase 2: JLine interazione terminalepotenziamento

- **Tipo di modifica**: Funzioneaggiunge
- **Dettagli delle modifiche**:
    - REPL riscritto con JLine 3 (editing riga, cronologia, completamento Tab)
    - aggiunge `SpaceAICompleter` Tab completamento
    - aggiunge `/model`, `/compact`, `/cost` Comando
    - JLine FFM terminalesupporta（JDK 22+）
    - Windows terminale UTF-8 Codifica（PrintStream + JVM Parametri + avvio）
    - `run.ps1` / `run.bat` avvio
- **Requisito correlato: 2.3 interazione terminale

## [2026-04-02] - Phase 1: progettoebaseciclo

- **Tipo di modifica**: Funzioneaggiunge
- **Dettagli delle modifiche**:
    - Maven progetto: Spring Boot 4.1.0-M2, Spring AI 2.0.0-M4, JDK 25
    - principale `AgentLoop` chiamata strumentociclo（ChatModel + ManualmenteStrumentociclo）
    - 6  strumenti base: `BashTool`, `FileReadTool`, `FileWriteTool`, `FileEditTool`, `GlobTool`, `GrepTool`
    - Componente: `BannerPrinter`, `ToolStatusRenderer`, `ThinkingRenderer`, `SpinnerAnimation`, `MarkdownRenderer`
    - ComandoSistema: `SlashCommand` Interfaccia, `CommandRegistry`, `/help`, `/clear`, `/exit`
    - fix: compatibilità Spring AI: upgrade Spring Boot 4.x, conflitto ChatModel Bean, memoria JVM
- **Requisito correlato**: Tutto

## [2026-04-02] - progettoInizializzazioneeanalisi

- **Tipo di modifica**: Aggiornamento
- **Dettagli delle modifiche**:
    - Crea `Documento dei requisiti.md`，completamentoanalisiedesign
    - Crea `CHANGELOG.md`，recordprogettomodifichecronologia
    - completamento space-ai TypeScript codice sorgenteanalisi logica principale
    - completamento space-ai-learn Java riferimentoImplementazionearchitetturaanalisi
    -  Java Stack tecnologico：JDK 25 + Spring Boot 4.1.0-M2 + Spring AI 2.0.0-M4 + JLine 3 + Picocli
    - designpackageeclasseMapparelazione
    - dividi 6 Implementazionefase
- **Requisito correlato**: Tutto
