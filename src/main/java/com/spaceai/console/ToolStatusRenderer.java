package com.spaceai.console;

import java.io.PrintStream;

/**
 * chiamata strumentoStatoesegue rendering — Corrisponde a space-ai/src/components/ToolStatus.tsx。
 * <p>
 * identifica lo stato delle chiamate strumento con ● colorato, mostra i risultati con ⎿ (stile SPACE AI).
 */
public class ToolStatusRenderer {

    private final PrintStream out;

    public ToolStatusRenderer(PrintStream out) {
        this.out = out;
    }

    /** esegue renderingchiamata strumentoinizia */
    public void renderStart(String toolName, String args) {
        out.println();
        out.print(AnsiStyle.BRIGHT_BLUE + "  ● " + AnsiStyle.BOLD + toolName + AnsiStyle.RESET);

        // evisualizzabreveParametri
        if (args != null && !args.isBlank()) {
            String summary = extractSummary(toolName, args);
            if (summary != null) {
                out.print(AnsiStyle.dim("(" + summary + ")"));
            }
        }
        out.println(AnsiStyle.dim("  running..."));
    }

    /** esegue renderingchiamata strumentocompletamento */
    public void renderEnd(String toolName, String result) {
        // troncalungoRisultato
        String display = result;
        if (display != null && display.length() > 500) {
            display = display.substring(0, 497) + "...";
        }

        out.println(AnsiStyle.GREEN + "  ● " + AnsiStyle.BOLD + toolName + AnsiStyle.RESET
                + AnsiStyle.dim("  done"));

        if (display != null && !display.isBlank()) {
            // mostra i risultati con prefisso ⎿ (stile SPACE AI)
            String[] lines = display.lines().toArray(String[]::new);
            for (int i = 0; i < lines.length; i++) {
                if (i == 0) {
                    out.println(AnsiStyle.DIM + "  ⎿  " + lines[i] + AnsiStyle.RESET);
                } else {
                    out.println(AnsiStyle.DIM + "     " + lines[i] + AnsiStyle.RESET);
                }
            }
        }
    }

    /** esegue renderingStrumentoErrore */
    public void renderError(String toolName, String error) {
        out.println(AnsiStyle.RED + "  ● " + AnsiStyle.BOLD + toolName + AnsiStyle.RESET
                + AnsiStyle.red("  error"));
        if (error != null) {
            out.println(AnsiStyle.DIM + "  ⎿  " + AnsiStyle.RED + error + AnsiStyle.RESET);
        }
    }

    /** estrae un riepilogo leggibile dai parametri JSON */
    private String extractSummary(String toolName, String args) {
        try {
            if (args.contains("\"command\"")) {
                int start = args.indexOf("\"command\"");
                int valStart = args.indexOf("\"", start + 10) + 1;
                int valEnd = args.indexOf("\"", valStart);
                if (valStart > 0 && valEnd > valStart) {
                    String cmd = args.substring(valStart, Math.min(valEnd, valStart + 60));
                    return "$ " + cmd;
                }
            }
            if (args.contains("\"file_path\"")) {
                int start = args.indexOf("\"file_path\"");
                int valStart = args.indexOf("\"", start + 12) + 1;
                int valEnd = args.indexOf("\"", valStart);
                if (valStart > 0 && valEnd > valStart) {
                    return args.substring(valStart, valEnd);
                }
            }
            if (args.contains("\"pattern\"")) {
                int start = args.indexOf("\"pattern\"");
                int valStart = args.indexOf("\"", start + 10) + 1;
                int valEnd = args.indexOf("\"", valStart);
                if (valStart > 0 && valEnd > valStart) {
                    return "pattern: " + args.substring(valStart, valEnd);
                }
            }
            if (args.contains("\"query\"")) {
                int start = args.indexOf("\"query\"");
                int valStart = args.indexOf("\"", start + 8) + 1;
                int valEnd = args.indexOf("\"", valStart);
                if (valStart > 0 && valEnd > valStart) {
                    return "\"" + args.substring(valStart, Math.min(valEnd, valStart + 60)) + "\"";
                }
            }
        } catch (Exception e) {
            // IgnoraanalisiErrore
        }
        return null;
    }
}
