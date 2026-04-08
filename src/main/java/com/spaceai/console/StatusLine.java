package com.spaceai.console;

import com.spaceai.core.TokenTracker;

import java.io.PrintStream;

/**
 * barra di stato inferioreesegue rendering — Corrisponde a space-ai  StatusLine Componente。
 * <p>
 * interminalevisualizza：Modello、Token /costo、lavoroDirectoryecc.StatoInformazione。
 * utilizza sequenze di escape ANSI per controllare la posizione del cursore, aggiorna la barra di stato dopo ogni output.
 * <p>
 * Attenzione：Soloin dumb terminalesottoAbilitato，dumb terminalenonsupporta。
 */
public class StatusLine {

    private final PrintStream out;
    private volatile boolean enabled = false;
    private volatile String modelName = "";
    private volatile TokenTracker tokenTracker;
    private volatile String workDir = "";

    public StatusLine(PrintStream out) {
        this.out = out;
    }

    /** Abilitatobarra di stato */
    public void enable(String model, TokenTracker tracker) {
        this.modelName = model;
        this.tokenTracker = tracker;
        this.workDir = abbreviatePath(System.getProperty("user.dir"));
        this.enabled = true;
    }

    /** Disabilitatobarra di stato */
    public void disable() {
        this.enabled = false;
        clearStatusLine();
    }

    /**
     * aggiornabarra di stato inferiorevisualizza。
     * <p>
     * utilizza sequenze di escape ANSI:
     * - salva posizione cursore
     * - a
     * - OutputStatoInformazione
     * - posizione
     */
    public void refresh() {
        if (!enabled || tokenTracker == null) return;

        String status = buildStatusText();

        // salva cursore → vai all'ultima riga → pulisci riga → scrivi stato → ripristina cursore
        out.print("\033[s");              // salva cursore
        out.print("\033[999;1H");         // adopouna riga
        out.print("\033[2K");             // cancellaquesto
        out.print(status);
        out.print("\033[u");              // 
        out.flush();
    }

    /**
     * esegue renderinguna rigaStatoriepilogo（non usa，unisciinsuggerimentoprimavisualizza）。
     * sìsicurezzaalternativa，nonterminalescorrimento。
     */
    public String renderInline() {
        if (!enabled || tokenTracker == null) return "";
        return buildStatusText();
    }

    private String buildStatusText() {
        long inputTokens = tokenTracker.getInputTokens();
        long outputTokens = tokenTracker.getOutputTokens();
        double cost = tokenTracker.estimateCost();
        long apiCalls = tokenTracker.getApiCallCount();

        StringBuilder sb = new StringBuilder();

        // Stato
        sb.append(AnsiStyle.DIM);

        // Modello
        sb.append(" ").append(modelName);

        // Token  + finestra di contesto
        sb.append("  │  ↑").append(TokenTracker.formatTokens(inputTokens));
        sb.append(" ↓").append(TokenTracker.formatTokens(outputTokens));

        // finestra di contestopercentuale di utilizzo (con colori)
        double usagePct = tokenTracker.getUsagePercentage();
        if (usagePct > 0) {
            String pctStr = String.format(" %.0f%%", usagePct * 100);
            var warningState = tokenTracker.getTokenWarningState();
            sb.append(AnsiStyle.RESET); // prima resetta, poi applica il colore
            sb.append(switch (warningState) {
                case NORMAL -> AnsiStyle.DIM + AnsiStyle.GREEN + pctStr;
                case WARNING -> AnsiStyle.BOLD + AnsiStyle.YELLOW + pctStr;
                case ERROR -> AnsiStyle.BOLD + AnsiStyle.RED + pctStr;
                case BLOCKING -> AnsiStyle.BOLD + AnsiStyle.RED + "⚠" + pctStr;
            });
            sb.append(AnsiStyle.RESET).append(AnsiStyle.DIM);
        }

        // costo
        if (cost > 0) {
            sb.append(String.format("  $%.4f", cost));
        }

        // API chiamataconteggio
        sb.append("  │  ").append(apiCalls).append(" calls");

        // lavoroDirectory
        sb.append("  │  ").append(workDir);

        sb.append(AnsiStyle.RESET);

        return sb.toString();
    }

    /** cancellabarra di stato */
    private void clearStatusLine() {
        out.print("\033[s\033[999;1H\033[2K\033[u");
        out.flush();
    }

    /** abbreviazionePercorso：verrà home Directorysostituiscecome ~ */
    private String abbreviatePath(String path) {
        if (path == null) return "";
        String home = System.getProperty("user.home");
        if (path.startsWith(home)) {
            return "~" + path.substring(home.length());
        }
        // lungotronca
        if (path.length() > 40) {
            return "..." + path.substring(path.length() - 37);
        }
        return path;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
