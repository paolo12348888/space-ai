package com.spaceai.console;

import java.io.PrintStream;

/**
 * Thinking contenutoesegue rendering — Corrisponde a space-ai/src/components/Thinking.tsx。
 * <p>
 *  ●  + &lt;thought&gt; tagstileMostra AI （riferimento Copilot CLI）。
 */
public class ThinkingRenderer {

    private final PrintStream out;

    public ThinkingRenderer(PrintStream out) {
        this.out = out;
    }

    /** Esegui rendering thinking contenuto（Copilot CLI  &lt;thought&gt; tag） */
    public void render(String thinkingContent) {
        if (thinkingContent == null || thinkingContent.isBlank()) {
            return;
        }

        out.println();
        out.println(AnsiStyle.BRIGHT_MAGENTA + "  ● " + AnsiStyle.DIM + "<thought>" + AnsiStyle.RESET);

        // Mostra thinking contenuto
        for (String line : thinkingContent.lines().toList()) {
            out.println(AnsiStyle.DIM + "    " + line + AnsiStyle.RESET);
        }

        out.println(AnsiStyle.DIM + "    </thought>" + AnsiStyle.RESET);
        out.println();
    }

    /** Esegui rendering thinking inizia */
    public void renderStart() {
        out.print(AnsiStyle.BRIGHT_MAGENTA + "  ● " + AnsiStyle.DIM + AnsiStyle.ITALIC
                + "Thinking..." + AnsiStyle.RESET);
    }

    /** Esegui rendering thinking termina */
    public void renderEnd() {
        out.println(AnsiStyle.clearLine());
    }
}
