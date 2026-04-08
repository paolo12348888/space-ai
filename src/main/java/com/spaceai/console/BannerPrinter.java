package com.spaceai.console;

import java.io.PrintStream;
import java.util.regex.Pattern;

/**
 * Banner di avvio SPACE AI.
 * Riproduce il logo ufficiale: la "S" cometa con circuiti interni,
 * dentro un anello orbitale, su sfondo cosmico stellato.
 */
public class BannerPrinter {

    private static final String VERSION = "0.1.0-SNAPSHOT";
    private static final Pattern ANSI_PATTERN = Pattern.compile("\033\\[[0-9;]*m");

    // ── Colori ANSI ──────────────────────────────────────────────────────────
    private static final String RESET      = AnsiStyle.RESET;
    private static final String BOLD       = AnsiStyle.BOLD;
    private static final String DIM        = AnsiStyle.DIM;
    private static final String CYAN       = "\033[96m";
    private static final String DARK_CYAN  = "\033[36m";
    private static final String WHITE      = "\033[97m";
    private static final String STAR       = "\033[38;5;153m";

    /**
     * Banner completo ispirato al logo SPACE AI ufficiale.
     * La "S" cometa luminosa con circuiti interni, anello orbitale e campo stellare.
     */
    public static void printBoxed(PrintStream out, String provider, String model,
                                   String baseUrl, String workDir,
                                   int toolCount, int cmdCount, String termInfo) {
        out.println();

        // ── Stelle ──────────────────────────────────────────────────────────
        out.println(STAR + DIM + "    .  *     ·    ✦      *   ·      .     ✦    *    ·   " + RESET);
        out.println(STAR + DIM + "  ✦    ·   *    .      *    ✦    ·   *       .    ✦    " + RESET);

        // ── Anello orbitale (top) ────────────────────────────────────────────
        out.println(CYAN + "           · ─ ─ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━ ─ ─ · " + RESET);
        out.println(CYAN + "        · ─                                         ─ · " + RESET);

        // ── Cometa (la freccia luminosa che esce dall'anello) ───────────────
        out.println(CYAN + "      ─                                               ─ "
                + RESET + BOLD + WHITE + "  ✦──▶" + RESET);
        out.println();

        // ── La "S" con circuiti interni ──────────────────────────────────────
        out.println(CYAN + "     ─    " + RESET + BOLD + CYAN
                + "  ╔══════════════════════════════════╗  " + RESET + CYAN + "    ─");
        out.println(RESET + CYAN + "    ─    " + RESET + BOLD + CYAN
                + "  ║                                  ║  " + RESET + CYAN + "     ─");

        // Curva superiore della S
        out.println(RESET + CYAN + "   ─     " + RESET + BOLD + WHITE
                + "  ║   " + RESET + BOLD + CYAN
                + "  ╭─────────────────────╮  " + RESET + BOLD + WHITE
                + "  ║  " + RESET + CYAN + "      ─");
        out.println(RESET + CYAN + "  ─      " + RESET + BOLD + WHITE
                + "  ║   " + RESET + BOLD + CYAN + "  │" + RESET + DARK_CYAN
                + " ◈━━╸◦╺━╸◦╺━╸◦╺━━◈ " + RESET + BOLD + CYAN
                + "│  " + RESET + BOLD + WHITE + "  ║  " + RESET + CYAN + "       ─");
        out.println(RESET + CYAN + "  ─      " + RESET + BOLD + WHITE
                + "  ║   " + RESET + BOLD + CYAN + "  ╰────╮" + RESET + DARK_CYAN
                + "  ◈━━━━━━━━◈  " + RESET + BOLD + CYAN
                + "│  " + RESET + BOLD + WHITE + "  ║  " + RESET + CYAN + "       ─");

        // Centro della S
        out.println(RESET + CYAN + "   ─     " + RESET + BOLD + WHITE
                + "  ║   " + RESET + BOLD + CYAN
                + "       ╰──────────────╯   " + RESET + BOLD + WHITE
                + "  ║  " + RESET + CYAN + "      ─");
        out.println(RESET + CYAN + "   ─     " + RESET + BOLD + WHITE
                + "  ║   " + RESET + BOLD + CYAN
                + "  ╭──────────────╮         " + RESET + BOLD + WHITE
                + " ║  " + RESET + CYAN + "      ─");

        // Curva inferiore della S
        out.println(RESET + CYAN + "  ─      " + RESET + BOLD + WHITE
                + "  ║   " + RESET + BOLD + CYAN + "  │" + RESET + DARK_CYAN
                + " ◈╺━━◦╸━╺━◦╸━━╺◈ " + RESET + BOLD + CYAN
                + "│  " + RESET + BOLD + WHITE + "  ║  " + RESET + CYAN + "       ─");
        out.println(RESET + CYAN + "  ─      " + RESET + BOLD + WHITE
                + "  ║   " + RESET + BOLD + CYAN
                + "  ╰─────────────────────╯  " + RESET + BOLD + WHITE
                + "  ║  " + RESET + CYAN + "       ─");

        // ── Nome SPACE AI ────────────────────────────────────────────────────
        out.println(RESET + CYAN + "   ─     " + RESET + BOLD + WHITE
                + "  ║                                  ║  " + RESET + CYAN + "      ─");
        out.println(RESET + CYAN + "    ─    " + RESET + BOLD + WHITE
                + "  ║    " + RESET + BOLD + CYAN
                + "  ★  S P A C E   A I  ★  " + RESET + BOLD + WHITE
                + "   ║  " + RESET + CYAN + "     ─");
        out.println(RESET + CYAN + "     ─   " + RESET + BOLD + WHITE
                + "  ║  " + RESET + DIM + WHITE
                + " Il tuo compagno cosmico   v" + VERSION
                + RESET + BOLD + WHITE + "  ║  " + RESET + CYAN + "    ─");
        out.println(RESET + CYAN + "      ─  " + RESET + BOLD + CYAN
                + "  ╚══════════════════════════════════╝  " + RESET + CYAN + "   ─");

        // ── Anello orbitale (bottom) ──────────────────────────────────────────
        out.println(RESET + CYAN + "        · ─                                         ─ ·");
        out.println("           · ─ ─ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━ ─ ─ ·" + RESET);

        // ── Stelle ───────────────────────────────────────────────────────────
        out.println(STAR + DIM + "  ·    *     ✦    ·      *   .    ✦   ·    *    .   " + RESET);
        out.println();

        // ── Info sistema ──────────────────────────────────────────────────────
        out.println("  " + DIM + "┌──────────────────────────────────────────────────────────┐" + RESET);
        out.println("  " + DIM + "│" + RESET
                + "  " + DIM + "Provider : " + RESET + CYAN + BOLD + padRight(provider.toUpperCase(), 12) + RESET
                + "  " + DIM + "Modello: " + RESET + CYAN + trimTo(model, 26) + RESET
                + "   " + DIM + "│" + RESET);
        out.println("  " + DIM + "│" + RESET
                + "  " + DIM + "API URL  : " + RESET + DIM + trimTo(baseUrl, 49) + RESET
                + "  " + DIM + "│" + RESET);
        out.println("  " + DIM + "│" + RESET
                + "  " + DIM + "Work Dir : " + RESET + DIM + trimTo(workDir, 49) + RESET
                + "  " + DIM + "│" + RESET);
        out.println("  " + DIM + "│" + RESET
                + "  " + DIM + "Strumenti: " + RESET + AnsiStyle.green("" + toolCount) + RESET
                + DIM + "  •  Comandi: " + RESET + AnsiStyle.green("" + cmdCount) + RESET
                + DIM + "  •  " + trimTo(termInfo, 18) + RESET
                + "   " + DIM + "│" + RESET);
        out.println("  " + DIM + "└──────────────────────────────────────────────────────────┘" + RESET);
        out.println();
        out.println("  " + DIM + "🚀 Pronto per l'esplorazione cosmica.  " + RESET
                + "Digita " + BOLD + CYAN + "/help" + RESET + DIM + " per i comandi." + RESET);
        out.println();
    }

    /**
     * Banner compatto per terminali stretti o dumb.
     */
    public static void printCompact(PrintStream out) {
        out.println();
        out.println(BOLD + CYAN + "  ◈ · · ·  S P A C E   A I  · · · ◈" + RESET
                + DIM + "  v" + VERSION + RESET);
        out.println(DIM + "  🌌 Il tuo compagno di programmazione cosmico" + RESET);
        out.println(DIM + "  Digita /help per i comandi  •  Ctrl+D per uscire" + RESET);
        out.println();
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    private static String padRight(String s, int width) {
        if (s == null) s = "";
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }

    private static String trimTo(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    public static String getVersion() {
        return VERSION;
    }
}
