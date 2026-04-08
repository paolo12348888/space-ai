package com.spaceai.repl;

import com.spaceai.config.AppConfig.ProviderInfo;
import com.spaceai.command.CommandContext;
import com.spaceai.command.CommandRegistry;
import com.spaceai.console.*;
import com.spaceai.core.AgentLoop;
import com.spaceai.core.ConversationPersistence;
import com.spaceai.permission.DangerousPatterns;
import com.spaceai.permission.PermissionTypes.PermissionChoice;
import com.spaceai.permission.PermissionTypes.PermissionDecision;
import com.spaceai.tool.ToolRegistry;
import com.spaceai.tool.impl.AskUserQuestionTool;
import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;
import java.util.function.Function;

/**
 * Gestore della sessione REPL — corrisponde a space-ai/src/REPL.tsx.
 * <p>
 * Utilizza JLine 3 per un'esperienza di interazione terminale ricca:
 * <ul>
 *   <li>Editing di riga (spostamento cursore, cancellazione, incolla)</li>
 *   <li>Cronologia (navigazione con frecce su/giù, persistenza su file)</li>
 *   <li>Completamento Tab (comandi slash, nomi strumenti)</li>
 *   <li>Gestione segnali (Ctrl+C annulla input corrente, Ctrl+D esce)</li>
 * </ul>
 * Degrada automaticamente alla modalità Scanner se l'inizializzazione di JLine fallisce.
 */
public class ReplSession {

    private static final Logger log = LoggerFactory.getLogger(ReplSession.class);

    private final AgentLoop agentLoop;
    private final ToolRegistry toolRegistry;
    private final CommandRegistry commandRegistry;
    private final ProviderInfo providerInfo;
    private final ConversationPersistence persistence;
    private final PrintStream out;
    private final ToolStatusRenderer toolStatusRenderer;
    private final MarkdownRenderer markdownRenderer;
    private final SpinnerAnimation spinner;
    private final ThinkingRenderer thinkingRenderer;
    private final StatusLine statusLine;

    /** Riepilogo conversazione (prime 40 lettere del primo input utente) */
    private String conversationSummary = "";
    private volatile boolean running = true;

    /** Tracciamento a capo dello streaming: condiviso tra rendering strumenti e callback streaming, garantisce indentazione coerente */
    private volatile boolean streamNewLine = false;

    /** Buffer di riga streaming: accumula i token fino al newline, poi esegue il rendering Markdown */
    private final StringBuilder streamLineBuffer = new StringBuilder();
    /** Stato rendering Markdown in streaming: tracciamento blocchi di codice su più righe */
    private MarkdownRenderer.StreamState streamMdState = new MarkdownRenderer.StreamState();

    /** LineReader attivo corrente (usato in modalità JLine per AskUser e conferma permessi) */
    private volatile LineReader activeReader;
    /** Scanner attivo corrente (usato in modalità Scanner per AskUser e conferma permessi) */
    private volatile Scanner activeScanner;

    public ReplSession(AgentLoop agentLoop,
                       ToolRegistry toolRegistry,
                       CommandRegistry commandRegistry,
                       ProviderInfo providerInfo) {
        this.agentLoop = agentLoop;
        this.toolRegistry = toolRegistry;
        this.commandRegistry = commandRegistry;
        this.providerInfo = providerInfo;
        this.persistence = new ConversationPersistence();
        // Forza l'output in codifica UTF-8 per garantire la corretta visualizzazione di emoji e altri caratteri Unicode nel terminale Windows
        this.out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        this.toolStatusRenderer = new ToolStatusRenderer(out);
        this.markdownRenderer = new MarkdownRenderer(out);
        this.spinner = new SpinnerAnimation(out);
        this.thinkingRenderer = new ThinkingRenderer(out);
        this.statusLine = new StatusLine(out);

        setupAgentCallbacks();
        setupToolContextCallbacks();
    }

    /** Registra i callback ToolContext (input utente AskUser) */
    private void setupToolContextCallbacks() {
        // Registra il callback di input utente richiesto da AskUserQuestionTool
        var toolContext = agentLoop.getToolContext();
        if (toolContext != null) {
            toolContext.set(AskUserQuestionTool.USER_INPUT_CALLBACK,
                    (Function<String, String>) this::readUserInputDuringAgentLoop);
        }
    }

    /** Registra i callback degli eventi AgentLoop, guida il rendering della UI della console */
    private void setupAgentCallbacks() {
        agentLoop.setOnToolEvent(event -> {
            switch (event.phase()) {
                case START -> {
                    spinner.stop();
                    // Svuota il buffer di riga (il testo AI potrebbe non avere newline finale prima della chiamata strumento)
                    flushStreamLineBuffer();
                    if (!streamNewLine) {
                        out.println();
                    }
                    toolStatusRenderer.renderStart(event.toolName(), event.arguments());
                    streamNewLine = true; // L'output del rendering strumento termina con println
                }
                case END -> {
                    toolStatusRenderer.renderEnd(event.toolName(), event.result());
                    streamNewLine = true; // L'output del rendering strumento termina con println，sottounstream token richiede
                }
            }
        });

        // output in streamingun token a：Ferma spinner → Stampa ● prefisso
        agentLoop.setOnStreamStart(() -> {
            spinner.stop();
            // Stampa il prefisso ● all'inizio di ogni iterazione streaming (anche la continuazione dopo le chiamate strumento ottiene un nuovo ●)
            if (streamNewLine) {
                out.println(); // Lascia una riga vuota prima dell'output precedente
            }
            out.print(AnsiStyle.BRIGHT_CYAN + "  ● " + AnsiStyle.RESET);
            streamNewLine = false;
        });

        agentLoop.setOnAssistantMessage(text -> {
            // Callback modalità bloccante: in modalità streaming l'output è in tempo reale tramite onToken, questo callback non viene attivato
        });

        // Callback conferma permessi: richiede conferma utente prima dell'esecuzione degli strumenti non in sola lettura
        agentLoop.setOnPermissionRequest(request -> {
            spinner.stop();
            return promptPermission(request);
        });

        // Thinking contenutoCallback：Mostra AI 
        agentLoop.setOnThinkingContent(thinkingText -> {
            spinner.stop();
            thinkingRenderer.render(thinkingText);
        });
    }

    /**
     * Avvia il REPL — preferisce JLine, degrada a Scanner in caso di errore.
     */
    public void start() {
        try {
            startWithJLine();
        } catch (Exception e) {
            log.warn("JLine initialization failed, downgrading to Scanner mode: {}", e.getMessage());
            startWithScanner();
        }
    }

    // ==================== JLine Modalità ====================

    private void startWithJLine() throws IOException {
        Path historyDir = Path.of(System.getProperty("user.home"), ".space-ai-java");
        Files.createDirectories(historyDir);

        try (Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .streams(System.in, System.out)
                .build()) {

            boolean isDumb = "dumb".equals(terminal.getType());
            if (isDumb) {
                log.info("Dumb terminal mode, use Windows Terminal / PowerShell / cmd for full experience");
            }

            // Configurazione Parser：supportaContinuazione con backslash (\) e blocco triple-virgolette (""")
            DefaultParser parser = new DefaultParser();
            parser.setEscapeChars(new char[]{'\\'}); // Continuazione con backslash

            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .parser(parser)
                    .completer(new SpaceAICompleter(commandRegistry, toolRegistry))
                    .variable(LineReader.HISTORY_FILE, historyDir.resolve("history"))
                    .variable(LineReader.HISTORY_SIZE, 1000)
                    .variable(LineReader.SECONDARY_PROMPT_PATTERN, "%P  ... ")
                    .option(LineReader.Option.CASE_INSENSITIVE, true)
                    .option(LineReader.Option.AUTO_LIST, true)
                    .build();

            // Supporto modalità Vim: abilitato tramite variabile d'ambiente SPACE_AI_VIM=1 o configurazione
            String vimMode = System.getenv("SPACE_AI_VIM");
            if ("1".equals(vimMode) || "true".equalsIgnoreCase(vimMode)) {
                reader.setVariable(LineReader.EDITING_MODE, "vi");
                log.info("Vim editing mode enabled");
            }

            // Prompt principale
            String prompt = new AttributedStringBuilder()
                    .style(AttributedStyle.BOLD.foreground(AttributedStyle.CYAN))
                    .append("❯ ")
                    .style(AttributedStyle.DEFAULT)
                    .toAnsi(terminal);

            printBanner(terminal);

            // Imposta il reader attivo per AskUser e la conferma dei permessi
            this.activeReader = reader;

            // Abilita la barra di stato inferiore per terminali non dumb
            if (!isDumb) {
                statusLine.enable(providerInfo.model(), agentLoop.getTokenTracker());
            }

            CommandContext cmdContext = new CommandContext(agentLoop, toolRegistry, commandRegistry, out, () -> running = false);

            while (running) {
                String input;
                try {
                    input = reader.readLine(prompt).strip();
                } catch (UserInterruptException e) {
                    spinner.stop();
                    out.println(AnsiStyle.dim("  ^C"));
                    continue;
                } catch (EndOfFileException e) {
                    break;
                }

                if (input.isEmpty()) {
                    continue;
                }

                handleInput(input, cmdContext);
            }

            saveConversation();
            out.println(AnsiStyle.dim("\n  Goodbye! 👋\n"));
        }
    }

    /** Stampa il Banner di avvio (modalità JLine) */
    private void printBanner(Terminal terminal) {
        boolean isDumb = "dumb".equals(terminal.getType());
        int w = terminal.getWidth();
        int h = terminal.getHeight();
        String termInfo = terminal.getType();
        if (w > 0 && h > 0) {
            termInfo += " (" + w + "×" + h + ")";
        }

        // Vim Modalitàidentificatore
        String vimMode = System.getenv("SPACE_AI_VIM");
        if ("1".equals(vimMode) || "true".equalsIgnoreCase(vimMode)) {
            termInfo += " [vim]";
        }

        if (isDumb || w < 60) {
            // Usa Banner compatto per terminali stretti/dumb
            BannerPrinter.printCompact(out);
            out.println(AnsiStyle.dim("  Provider: ") + AnsiStyle.cyan(providerInfo.provider().toUpperCase())
                    + AnsiStyle.dim("  Model: ") + AnsiStyle.cyan(providerInfo.model()));
            out.println(AnsiStyle.dim("  Work Dir: " + System.getProperty("user.dir")));
            if (isDumb) {
                out.println(AnsiStyle.yellow("  ⚠ Dumb terminal mode: run in Windows Terminal / PowerShell for best experience"));
            }
        } else {
            // Usa il Banner con bordi per terminali standard
            BannerPrinter.printBoxed(out,
                    providerInfo.provider(),
                    providerInfo.model(),
                    providerInfo.baseUrl(),
                    System.getProperty("user.dir"),
                    toolRegistry.size(),
                    commandRegistry.getCommands().size(),
                    termInfo);
        }

        out.println();
    }

    // ==================== Scanner DegradoModalità ====================

    private void startWithScanner() {
        BannerPrinter.printCompact(out);
        out.println(AnsiStyle.dim("  Provider: ") + AnsiStyle.cyan(providerInfo.provider().toUpperCase())
                + AnsiStyle.dim("  Model: ") + AnsiStyle.cyan(providerInfo.model()));
        out.println(AnsiStyle.dim("  API URL:  ") + AnsiStyle.cyan(providerInfo.baseUrl()));
        out.println(AnsiStyle.dim("  Work Dir: " + System.getProperty("user.dir")));
        out.println(AnsiStyle.dim("  Tools: " + toolRegistry.size() + " registered"));
        out.println(AnsiStyle.dim("  Mode: Scanner (basic input)"));
        out.println();

        Scanner scanner = new Scanner(System.in);
        this.activeScanner = scanner;
        CommandContext cmdContext = new CommandContext(agentLoop, toolRegistry, commandRegistry, out, () -> running = false);

        while (running) {
            out.print(AnsiStyle.BOLD + AnsiStyle.BRIGHT_CYAN + "❯ " + AnsiStyle.RESET);
            out.flush();

            String input;
            try {
                if (!scanner.hasNextLine()) break;
                input = scanner.nextLine().strip();
            } catch (Exception e) {
                break;
            }

            if (input.isEmpty()) continue;

            handleInput(input, cmdContext);
        }

        saveConversation();
        out.println(AnsiStyle.dim("\n  Goodbye! 👋\n"));
    }

    /** Gestisce l'input utente (distribuzione comandi o chiamata Agent) */
    private void handleInput(String input, CommandContext cmdContext) {
        // comando slash
        if (commandRegistry.isCommand(input)) {
            var result = commandRegistry.dispatch(input, cmdContext);
            result.ifPresent(out::println);
            out.println();
            return;
        }

        // Registra il riepilogo della conversazione (prime 40 lettere del primo input utente)
        if (conversationSummary.isEmpty()) {
            conversationSummary = input.length() > 40 ? input.substring(0, 40) : input;
        }

        // Ciclo Agent (output streaming + rendering Markdown con buffer di riga)
        try {
            spinner.start("Thinking...");
            streamNewLine = true; // spinner dopo lo stop, onStreamStart stamperà il prefisso ●
            streamLineBuffer.setLength(0); // Resetta il buffer di riga
            streamMdState = new MarkdownRenderer.StreamState(); // Resetta lo stato Markdown

            long startTime = System.currentTimeMillis();

            String response = agentLoop.runStreaming(input, token -> {
                for (int i = 0; i < token.length(); i++) {
                    char c = token.charAt(i);
                    if (c == '\n') {
                        // Riga completata → rendering Markdown e output
                        if (streamNewLine) {
                            out.print("    "); // Indentazione continuazione riga (allineata al testo dopo ●)
                            streamNewLine = false;
                        }
                        String rendered = markdownRenderer.renderStreamingLine(streamLineBuffer.toString(), streamMdState);
                        out.println(rendered);
                        streamLineBuffer.setLength(0);
                        streamNewLine = true;
                    } else {
                        streamLineBuffer.append(c);
                    }
                }
                out.flush();
            });

            // Svuota il buffer residuo (l'ultima riga potrebbe non avere newline finale)
            flushStreamLineBuffer();

            spinner.stop();
            out.println(); // output in streamingterminadopoa capo

            // Mostra il tempo trascorso
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            if (elapsed > 0) {
                out.println(AnsiStyle.DIM + "  ✻ Worked for " + elapsed + "s" + AnsiStyle.RESET);
            }

            // Aggiorna la barra di stato inferiore (mostra l'ultimo utilizzo token)
            if (statusLine.isEnabled()) {
                out.println(statusLine.renderInline());
            }
            out.println();
        } catch (Exception e) {
            spinner.stop();
            out.println(AnsiStyle.RED + "\n  ● Error: " + AnsiStyle.RESET + e.getMessage());
            log.error("Agent loop exception", e);
            out.println();
        }
    }

    /**
     * Svuota il buffer di riga streaming — renderizza e stampa il contenuto non ancora emesso.
     * Chiamato prima delle chiamate strumento e dopo la fine dello streaming, per prevenire la perdita di testo AI.
     */
    private void flushStreamLineBuffer() {
        if (!streamLineBuffer.isEmpty()) {
            if (streamNewLine) {
                out.print("    ");
                streamNewLine = false;
            }
            String rendered = markdownRenderer.renderStreamingLine(streamLineBuffer.toString(), streamMdState);
            out.print(rendered);
            streamLineBuffer.setLength(0);
        }
    }

    /** Salva la cronologia della conversazione all'uscita */
    private void saveConversation() {
        var history = agentLoop.getMessageHistory();
        // Salva solo se c'è contenuto effettivo di conversazione (almeno prompt di sistema + messaggio utente + risposta assistente)
        if (history.size() > 2) {
            var file = persistence.save(history, conversationSummary);
            if (file != null) {
                out.println(AnsiStyle.dim("  💾 Conversation saved: " + file.getFileName()));
            }
        }
    }

    /** Ottieni il gestore di persistenza della conversazione */
    public ConversationPersistence getPersistence() {
        return persistence;
    }

    public void stop() {
        running = false;
    }

    // ==================== UI conferma permessi ====================

    /**
     * Mostra il prompt di conferma permessi e attende l'input dell'utente.
     * Supporta 4 opzioni: Y(consenti una volta) / A(consenti sempre) / N(nega) / D(nega sempre).
     */
    private PermissionChoice promptPermission(AgentLoop.PermissionRequest request) {
        out.println();

        // Controlla se è un comando pericoloso
        PermissionDecision decision = request.decision();
        boolean isDangerous = (decision != null && decision.reason() != null
                && decision.reason().startsWith("⚠ DANGEROUS"));

        if (isDangerous) {
            out.println(AnsiStyle.red("  ⚠ DANGEROUS Operation"));
        } else {
            out.println(AnsiStyle.yellow("  ⚠ Permission Required"));
        }
        out.println("  " + "─".repeat(50));
        out.println("  " + AnsiStyle.bold("Tool: ") + AnsiStyle.cyan(request.toolName()));
        out.println("  " + AnsiStyle.bold("Action: ") + request.activityDescription());

        // Mostra il riepilogo dei parametri (tronca i parametri troppo lunghi)
        String argsPreview = request.arguments();
        if (argsPreview != null && argsPreview.length() > 200) {
            argsPreview = argsPreview.substring(0, 200) + "...";
        }
        if (argsPreview != null && !argsPreview.isBlank()) {
            out.println("  " + AnsiStyle.dim("Args: " + argsPreview));
        }

        // Mostra la regola suggerita
        String suggestedRule = null;
        if (decision != null && decision.commandPrefix() != null) {
            suggestedRule = request.toolName() + "(" + decision.commandPrefix() + ":*)";
        }

        out.println("  " + "─".repeat(50));
        out.println("  " + AnsiStyle.green("[Y]") + " Allow once");
        if (suggestedRule != null && !isDangerous) {
            out.println("  " + AnsiStyle.green("[A]") + " Always allow " + AnsiStyle.cyan(suggestedRule));
        }
        out.println("  " + AnsiStyle.red("[N]") + " Deny");
        if (suggestedRule != null) {
            out.println("  " + AnsiStyle.red("[D]") + " Always deny this pattern");
        }
        out.print("  " + AnsiStyle.bold("Choice") + AnsiStyle.dim(" [Y/a/n/d] ") + AnsiStyle.BOLD + AnsiStyle.BRIGHT_CYAN + "→ " + AnsiStyle.RESET);
        out.flush();

        String answer = readLineForPermission();
        if (answer == null) return PermissionChoice.DENY_ONCE;

        answer = answer.strip().toLowerCase();

        return switch (answer) {
            case "a", "always" -> {
                out.println(AnsiStyle.green("  ✓ Rule saved: always allow " +
                        (suggestedRule != null ? suggestedRule : request.toolName())));
                yield PermissionChoice.ALWAYS_ALLOW;
            }
            case "d" -> {
                out.println(AnsiStyle.red("  ✗ Rule saved: always deny " +
                        (suggestedRule != null ? suggestedRule : request.toolName())));
                yield PermissionChoice.ALWAYS_DENY;
            }
            case "n", "no" -> {
                out.println(AnsiStyle.red("  ✗ Operation denied"));
                yield PermissionChoice.DENY_ONCE;
            }
            default -> PermissionChoice.ALLOW_ONCE; // vuotostringa、y、yes → consente
        };
    }

    /** Legge l'input utente per la conferma permessi (compatibile con modalità JLine e Scanner) */
    private String readLineForPermission() {
        try {
            if (activeReader != null) {
                return activeReader.readLine();
            } else if (activeScanner != null && activeScanner.hasNextLine()) {
                return activeScanner.nextLine();
            }
        } catch (Exception e) {
            log.debug("Permission confirmation input exception: {}", e.getMessage());
        }
        return null;
    }

    // ==================== Callback strumento AskUser ====================

    /**
     * Legge l'input utente durante l'esecuzione del ciclo Agent.
     * Utilizzato da AskUserQuestionTool tramite il callback ToolContext.
     */
    private String readUserInputDuringAgentLoop(String prompt) {
        spinner.stop();
        out.print(prompt);
        out.print("  " + AnsiStyle.BOLD + AnsiStyle.BRIGHT_CYAN + "→ " + AnsiStyle.RESET);
        out.flush();

        try {
            if (activeReader != null) {
                return activeReader.readLine();
            } else if (activeScanner != null && activeScanner.hasNextLine()) {
                return activeScanner.nextLine();
            }
        } catch (UserInterruptException e) {
            return "(User cancelled)";
        } catch (Exception e) {
            log.debug("User input read exception: {}", e.getMessage());
        }
        return null;
    }
}
