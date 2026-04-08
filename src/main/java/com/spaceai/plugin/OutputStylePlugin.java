package com.spaceai.plugin;

import com.spaceai.command.CommandContext;
import com.spaceai.command.SlashCommand;
import com.spaceai.console.AnsiStyle;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OutputstilePlugin —— fornisce /style ComandocambiaOutputstile。
 * <p>
 * sìuninternoEsempioPlugin，sistema pluginmodalità。
 * Utentepuòcontramite /style ComandoindiversoOutputstiletracambia。
 *
 * <h3>puòstile</h3>
 * <ul>
 *   <li><b>default</b> —— PredefinitocoloratoOutput，package ANSI eformato</li>
 *   <li><b>minimal</b> —— Output，nessunonessuno</li>
 *   <li><b>verbose</b> —— Output，packageesternoDebugeInformazione</li>
 *   <li><b>markdown</b> ——  Markdown Output，unisciesportaedividi</li>
 * </ul>
 *
 * <h3>Threadsicurezza</h3>
 * <p>Correntestile {@link AtomicReference} Archiviazione，supportaConcorrentelettura/scrittura。</p>
 */
public class OutputStylePlugin implements Plugin {

    /** c'èsupportastilenome */
    private static final Set<String> SUPPORTED_STYLES = Set.of(
            "default", "minimal", "verbose", "markdown"
    );

    /** CorrenteattivoOutputstile，atomicocitazioneThreadsicurezza */
    private final AtomicReference<String> currentStyle = new AtomicReference<>("default");

    /** Plugincontestocitazione */
    private PluginContext context;

    @Override
    public String id() {
        return "output-style";
    }

    @Override
    public String name() {
        return "Output Style";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public String description() {
        return "Custom output styles";
    }

    @Override
    public void initialize(PluginContext context) {
        this.context = context;
        context.getLogger().info("Output style plugin initialized, current style: {}", currentStyle.get());
    }

    @Override
    public List<SlashCommand> getCommands() {
        return List.of(new StyleCommand());
    }

    /**
     * ottieniCorrenteattivoOutputstilenome。
     *
     * @return stilenome（"default"、"minimal"、"verbose" o "markdown"）
     */
    public String getCurrentStyle() {
        return currentStyle.get();
    }

    /**
     * imposta lo stile di output tramite programmazione.
     *
     * @param style stilenome
     * @return seImpostazioniSuccesso（stilenomec'è）
     */
    public boolean setStyle(String style) {
        if (style != null && SUPPORTED_STYLES.contains(style.toLowerCase())) {
            currentStyle.set(style.toLowerCase());
            return true;
        }
        return false;
    }

    @Override
    public void destroy() {
        if (context != null) {
            context.getLogger().info("Output style plugin destroyed");
        }
    }

    // ========================================================================
    // Internoclasse：/style Comando
    // ========================================================================

    /**
     * /style Comando —— ocambiaOutputstile。
     * <p>
     * ：
     * <ul>
     *   <li>{@code /style} —— visualizzaCorrentestileec'èpuòstile</li>
     *   <li>{@code /style <name>} —— cambiaaspecificatostile</li>
     * </ul>
     */
    private class StyleCommand implements SlashCommand {

        @Override
        public String name() {
            return "style";
        }

        @Override
        public String description() {
            return "Switch output style (default/minimal/verbose/markdown)";
        }

        @Override
        public String execute(String args, CommandContext commandContext) {
            String trimmed = (args == null) ? "" : args.trim().toLowerCase();

            // nessunoParametri：visualizzaCorrentestileec'èpuòOpzioni
            if (trimmed.isEmpty()) {
                return showStyles();
            }

            // cambiastile
            if (!SUPPORTED_STYLES.contains(trimmed)) {
                return AnsiStyle.red("  ✗ Unknown style: " + trimmed) + "\n"
                        + AnsiStyle.dim("  Available styles: default, minimal, verbose, markdown");
            }

            String oldStyle = currentStyle.getAndSet(trimmed);

            // verràCorrentestile ToolContext condivisoStato，altriComponentelegge
            if (commandContext.agentLoop() != null) {
                try {
                    commandContext.agentLoop().getToolContext().set("OUTPUT_STYLE", trimmed);
                } catch (Exception ignored) {
                    // Gestisce
                }
            }

            if (context != null) {
                context.getLogger().info("Output style switched: {} → {}", oldStyle, trimmed);
            }

            return AnsiStyle.green("  ✓ Output style switched: ")
                    + AnsiStyle.bold(oldStyle)
                    + " → "
                    + AnsiStyle.bold(AnsiStyle.cyan(trimmed))
                    + "\n" + getStyleDescription(trimmed);
        }

        /**
         * visualizzaCorrentestileec'èpuòstile。
         */
        private String showStyles() {
            String active = currentStyle.get();
            StringBuilder sb = new StringBuilder();
            sb.append("\n");
            sb.append(AnsiStyle.bold("  🎨 Output Styles")).append("\n");
            sb.append("  ").append("─".repeat(40)).append("\n\n");

            for (String style : List.of("default", "minimal", "verbose", "markdown")) {
                boolean isActive = style.equals(active);
                String indicator = isActive ? AnsiStyle.green("● ") : AnsiStyle.dim("○ ");
                String styleName = isActive
                        ? AnsiStyle.bold(AnsiStyle.cyan(style))
                        : style;
                String desc = getStyleBrief(style);
                sb.append("  ").append(indicator).append(styleName)
                        .append(AnsiStyle.dim(" - " + desc)).append("\n");
            }

            sb.append("\n").append(AnsiStyle.dim("  Usage: /style <name>")).append("\n");
            return sb.toString();
        }

        /**
         * ottienistilebrevedescrizione。
         */
        private String getStyleBrief(String style) {
            return switch (style) {
                case "default" -> "Default colorful output";
                case "minimal" -> "Minimal output, no colors";
                case "verbose" -> "Verbose output with debug info";
                case "markdown" -> "Pure Markdown output";
                default -> "Unknown style";
            };
        }

        /**
         * ottienicambiadopostileDescrizione。
         */
        private String getStyleDescription(String style) {
            return switch (style) {
                case "default" -> AnsiStyle.dim("  Standard output mode with ANSI colors and formatting");
                case "minimal" -> AnsiStyle.dim("  Minimal output without colors, suitable for pipes and logs");
                case "verbose" -> AnsiStyle.dim("  Verbose output mode with timestamps and debug info");
                case "markdown" -> AnsiStyle.dim("  Pure Markdown format, suitable for export to documents");
                default -> "";
            };
        }
    }
}
