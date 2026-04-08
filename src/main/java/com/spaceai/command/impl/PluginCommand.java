package com.spaceai.command.impl;

import com.spaceai.command.CommandContext;
import com.spaceai.command.SlashCommand;
import com.spaceai.console.AnsiStyle;
import com.spaceai.plugin.Plugin;
import com.spaceai.plugin.PluginManager;
import com.spaceai.plugin.PluginManager.PluginInfo;
import com.spaceai.tool.Tool;

import java.nio.file.Path;
import java.util.List;

/**
 * /plugin Comando —— caricatoPlugin。
 * <p>
 * Comando：
 * <ul>
 *   <li>{@code /plugin} —— elenca tutticaricatoPlugin</li>
 *   <li>{@code /plugin load <path>} —— carica il plugin dal percorso JAR</li>
 *   <li>{@code /plugin unload <id>} —— disinstalla specificatoPlugin</li>
 *   <li>{@code /plugin reload} —— c'èPlugin</li>
 *   <li>{@code /plugin info <id>} —— visualizzaPluginInformazione</li>
 * </ul>
 * <p>
 * tramite {@link com.spaceai.tool.ToolContext} in key come
 * {@code "PLUGIN_MANAGER"} condivisoStatoOttieni {@link PluginManager} istanza.
 */
public class PluginCommand implements SlashCommand {

    @Override
    public String name() {
        return "plugin";
    }

    @Override
    public String description() {
        return "Manage loaded plugins";
    }

    @Override
    public List<String> aliases() {
        return List.of("plugins");
    }

    @Override
    public String execute(String args, CommandContext context) {
        PluginManager manager = getPluginManager(context);
        if (manager == null) {
            return AnsiStyle.red("  ✗ Plugin system not initialized");
        }

        String trimmed = (args == null) ? "" : args.trim();

        // nessunoParametri：elenca tuttiPlugin
        if (trimmed.isEmpty()) {
            return listPlugins(manager);
        }

        // analisiComando
        String[] parts = trimmed.split("\\s+", 2);
        String subCommand = parts[0].toLowerCase();
        String subArgs = (parts.length > 1) ? parts[1].trim() : "";

        return switch (subCommand) {
            case "load" -> loadPlugin(manager, subArgs);
            case "unload" -> unloadPlugin(manager, subArgs);
            case "reload" -> reloadPlugins(manager);
            case "info" -> pluginInfo(manager, subArgs);
            default -> AnsiStyle.yellow("  Unknown subcommand: " + subCommand) + "\n"
                    + usageHelp();
        };
    }

    /**
     * elenca tutticaricatoPlugin。
     */
    private String listPlugins(PluginManager manager) {
        List<PluginInfo> plugins = manager.getPlugins();
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  🔌 Loaded Plugins")).append("\n");
        sb.append("  ").append("─".repeat(50)).append("\n\n");

        if (plugins.isEmpty()) {
            sb.append(AnsiStyle.dim("  No plugins loaded.")).append("\n");
            sb.append(AnsiStyle.dim("  Place JAR files in ~/.space-ai-java/plugins/ to load them.")).append("\n");
        } else {
            for (PluginInfo info : plugins) {
                Plugin p = info.plugin();
                String scopeBadge = scopeColor(info.scope());
                sb.append(String.format("  %s %s %s%n",
                        AnsiStyle.bold(p.name()),
                        AnsiStyle.dim("v" + p.version()),
                        scopeBadge));
                sb.append(String.format("    ID: %s | %s%n",
                        AnsiStyle.cyan(p.id()),
                        p.description()));
                sb.append(String.format("    Tools: %d | Commands: %d%n",
                        p.getTools().size(),
                        p.getCommands().size()));
                sb.append("\n");
            }
            sb.append(AnsiStyle.dim(String.format("  Total %d plugins", plugins.size()))).append("\n");
        }
        return sb.toString();
    }

    /**
     * carica il plugin dal percorso JAR。
     */
    private String loadPlugin(PluginManager manager, String pathStr) {
        if (pathStr.isEmpty()) {
            return AnsiStyle.yellow("  Usage: /plugin load <jar-path>");
        }
        Path jarPath = Path.of(pathStr);
        boolean success = manager.loadPlugin(jarPath);
        if (success) {
            return AnsiStyle.green("  ✓ Plugin loaded: " + jarPath.getFileName());
        } else {
            return AnsiStyle.red("  ✗ Plugin load failed: " + jarPath.getFileName())
                    + "\n" + AnsiStyle.dim("  Please check if JAR contains a valid Plugin-Class attribute");
        }
    }

    /**
     * disinstalla specificato ID Plugin。
     */
    private String unloadPlugin(PluginManager manager, String pluginId) {
        if (pluginId.isEmpty()) {
            return AnsiStyle.yellow("  Usage: /plugin unload <plugin-id>");
        }
        boolean success = manager.unload(pluginId);
        if (success) {
            return AnsiStyle.green("  ✓ Plugin unloaded: " + pluginId);
        } else {
            return AnsiStyle.red("  ✗ Plugin not found: " + pluginId);
        }
    }

    /**
     * c'èPlugin（Tuttodisinstalla，nuovoScansionecarica）。
     */
    private String reloadPlugins(PluginManager manager) {
        int beforeCount = manager.getPlugins().size();
        manager.shutdown();
        manager.loadAll();
        int afterCount = manager.getPlugins().size();
        return AnsiStyle.green(
                String.format("  ✓ Plugins reloaded (before: %d, now: %d)", beforeCount, afterCount));
    }

    /**
     * visualizzaspecificatoPluginInformazione。
     */
    private String pluginInfo(PluginManager manager, String pluginId) {
        if (pluginId.isEmpty()) {
            return AnsiStyle.yellow("  Usage: /plugin info <plugin-id>");
        }

        PluginInfo info = manager.findPlugin(pluginId);
        if (info == null) {
            return AnsiStyle.red("  ✗ Plugin not found: " + pluginId);
        }

        Plugin p = info.plugin();
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  🔌 Plugin Details")).append("\n");
        sb.append("  ").append("─".repeat(40)).append("\n\n");

        sb.append("  ").append(AnsiStyle.bold("Name:        ")).append(p.name()).append("\n");
        sb.append("  ").append(AnsiStyle.bold("ID:          ")).append(AnsiStyle.cyan(p.id())).append("\n");
        sb.append("  ").append(AnsiStyle.bold("Version:     ")).append(p.version()).append("\n");
        sb.append("  ").append(AnsiStyle.bold("Description: ")).append(p.description()).append("\n");
        sb.append("  ").append(AnsiStyle.bold("Scope:       ")).append(scopeColor(info.scope())).append("\n");
        sb.append("  ").append(AnsiStyle.bold("JAR:         "))
                .append(AnsiStyle.dim(info.jarPath() != null ? info.jarPath().toString() : "built-in"))
                .append("\n");

        // StrumentoLista
        List<Tool> tools = p.getTools();
        sb.append("\n  ").append(AnsiStyle.bold("Tools (" + tools.size() + "):")).append("\n");
        if (tools.isEmpty()) {
            sb.append(AnsiStyle.dim("    (none)")).append("\n");
        } else {
            for (Tool tool : tools) {
                sb.append("    • ").append(AnsiStyle.cyan(tool.name()))
                        .append(" - ").append(tool.description()).append("\n");
            }
        }

        // ComandoLista
        List<SlashCommand> commands = p.getCommands();
        sb.append("\n  ").append(AnsiStyle.bold("Commands (" + commands.size() + "):")).append("\n");
        if (commands.isEmpty()) {
            sb.append(AnsiStyle.dim("    (none)")).append("\n");
        } else {
            for (SlashCommand cmd : commands) {
                sb.append("    • ").append(AnsiStyle.green("/" + cmd.name()))
                        .append(" - ").append(cmd.description()).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Ottieni l'istanza PluginManager da CommandContext.
     * <p>
     * tramite AgentLoop → ToolContext → condivisoStato（key: "PLUGIN_MANAGER"）ottieni。
     *
     * @param context Contesto di esecuzione del comando
     * @return PluginManager istanza，non ancoraaRestituisce null
     */
    private PluginManager getPluginManager(CommandContext context) {
        if (context.agentLoop() == null) {
            return null;
        }
        try {
            Object manager = context.agentLoop().getToolContext().get("PLUGIN_MANAGER");
            if (manager instanceof PluginManager pm) {
                return pm;
            }
        } catch (Exception ignored) {
            // ToolContext PLUGIN_MANAGER potrebbe non essere registrato in
        }
        return null;
    }

    /**
     * colora i tag di scope.
     */
    private String scopeColor(String scope) {
        return switch (scope) {
            case "global" -> AnsiStyle.blue("[global]");
            case "project" -> AnsiStyle.green("[project]");
            case "dynamic" -> AnsiStyle.magenta("[dynamic]");
            default -> AnsiStyle.dim("[" + scope + "]");
        };
    }

    /**
     * testo della guida all'uso.
     */
    private String usageHelp() {
        return AnsiStyle.dim("""
                  Usage:
                    /plugin              List all plugins
                    /plugin load <path>  Load JAR plugin
                    /plugin unload <id>  Unload plugin
                    /plugin reload       Reload all plugins
                    /plugin info <id>    View plugin details""");
    }
}
