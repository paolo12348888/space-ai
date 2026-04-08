package com.spaceai.command.impl;

import com.spaceai.command.CommandContext;
import com.spaceai.command.SlashCommand;
import com.spaceai.console.AnsiStyle;
import com.spaceai.mcp.McpClient;
import com.spaceai.mcp.McpException;
import com.spaceai.mcp.McpManager;
import com.spaceai.tool.ToolRegistry;
import com.spaceai.tool.impl.McpToolBridge;

import java.util.*;

/**
 * /mcp Comando —— Gestisci MCP（Model Context Protocol）ServizioConnessione。
 * <p>
 * Comando：
 * <ul>
 *   <li>{@code /mcp} —— elenca tutti MCP ServizioeStato</li>
 *   <li>{@code /mcp connect <name> <command> [args...]} —— Connessionea MCP Servizio</li>
 *   <li>{@code /mcp disconnect <name>} —— Disconnessione MCP Servizio</li>
 *   <li>{@code /mcp tools [server]} —— Elenca MCP Strumento</li>
 *   <li>{@code /mcp resources [server]} —— Elenca MCP risorse</li>
 *   <li>{@code /mcp reload} —— ricarica dal file di configurazione</li>
 * </ul>
 */
public class McpCommand implements SlashCommand {

    @Override
    public String name() {
        return "mcp";
    }

    @Override
    public String description() {
        return "Manage MCP server connections";
    }

    @Override
    public String execute(String args, CommandContext context) {
        // non è possibile ottenere McpManager dal contesto del registro strumenti,
        // questo CommandContext。tramitereflectionoottieni。
        // realeImplementazionein，McpManager come CommandContext estensioneCampofornisce。
        // tramitec'èoclassemeccanismoottieni。
        McpManager manager = McpManagerHolder.getInstance();
        if (manager == null) {
            return AnsiStyle.red("  ❌ MCP manager not initialized");
        }

        String trimmed = args.strip();
        if (trimmed.isEmpty()) {
            return showStatus(manager);
        }

        String[] parts = trimmed.split("\\s+", 2);
        String subCommand = parts[0].toLowerCase();
        String subArgs = parts.length > 1 ? parts[1].strip() : "";

        return switch (subCommand) {
            case "connect" -> handleConnect(manager, subArgs, context);
            case "disconnect" -> handleDisconnect(manager, subArgs);
            case "tools" -> handleTools(manager, subArgs);
            case "resources" -> handleResources(manager, subArgs);
            case "reload" -> handleReload(manager, context);
            case "help" -> showHelp();
            default -> AnsiStyle.red("  Unknown subcommand: " + subCommand) + "\n" + showHelp();
        };
    }

    /**
     * visualizzac'è MCP ServizioStato。
     */
    private String showStatus(McpManager manager) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  🔌 MCP Server Status\n"));
        sb.append("  ").append("─".repeat(50)).append("\n\n");

        Map<String, McpClient> clients = manager.getClients();
        if (clients.isEmpty()) {
            sb.append("  No connected MCP servers\n\n");
            sb.append(AnsiStyle.dim("  Tip: Use /mcp connect <name> <command> [args] to connect\n"));
            sb.append(AnsiStyle.dim("  Or define servers in .mcp.json config file\n"));
            return sb.toString();
        }

        for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
            String name = entry.getKey();
            McpClient client = entry.getValue();

            String statusIcon;
            String statusText;
            if (client.isConnected() && client.isInitialized()) {
                statusIcon = "✅";
                statusText = AnsiStyle.green("Connected");
            } else if (client.isConnected()) {
                statusIcon = "🔄";
                statusText = AnsiStyle.yellow("Connecting");
            } else {
                statusIcon = "❌";
                statusText = AnsiStyle.red("Disconnected");
            }

            sb.append(String.format("  %s %-18s %s%n", statusIcon, AnsiStyle.bold(name), statusText));

            // visualizzaStrumentoerisorsequantità
            int toolCount = client.getTools().size();
            int resCount = client.getResources().size();
            sb.append(String.format("     %s%n",
                    AnsiStyle.dim(toolCount + " tools, " + resCount + " resources")));

            // visualizzaServizioInformazione
            if (client.getServerInfo() != null) {
                sb.append(String.format("     %s%n",
                        AnsiStyle.dim("Info: " + client.getServerInfo().toString())));
            }
        }

        sb.append("\n");
        sb.append(AnsiStyle.dim("  Total " + clients.size() + " servers, "
                + manager.getAllTools().size() + " tools\n"));

        return sb.toString();
    }

    /**
     * Gestisci /mcp connect Comando。
     */
    private String handleConnect(McpManager manager, String args, CommandContext context) {
        if (args.isEmpty()) {
            return AnsiStyle.red("  Usage: /mcp connect <name> <command> [args...]");
        }

        String[] parts = args.split("\\s+");
        if (parts.length < 2) {
            return AnsiStyle.red("  Usage: /mcp connect <name> <command> [args...]");
        }

        String name = parts[0];
        String command = parts[1];
        List<String> cmdArgs = parts.length > 2
                ? List.of(Arrays.copyOfRange(parts, 2, parts.length))
                : List.of();

        try {
            McpClient client = manager.connect(name, command, cmdArgs, null);

            // verrà MCP Strumentopontearegistro strumenti
            registerBridgedTools(client, name, context);

            StringBuilder sb = new StringBuilder();
            sb.append(AnsiStyle.green("  ✅ Connected to MCP server: " + name)).append("\n");
            sb.append(AnsiStyle.dim("     " + client.getTools().size() + " tools, "
                    + client.getResources().size() + " resources")).append("\n");

            // elencascopreStrumento
            if (!client.getTools().isEmpty()) {
                sb.append("\n  Tools:\n");
                for (McpClient.McpTool tool : client.getTools()) {
                    sb.append("    • ").append(tool.name());
                    if (!tool.description().isEmpty()) {
                        sb.append(AnsiStyle.dim(" - " + truncate(tool.description(), 60)));
                    }
                    sb.append("\n");
                }
            }

            return sb.toString();
        } catch (McpException e) {
            return AnsiStyle.red("  ❌ Connection failed: " + e.getMessage());
        }
    }

    /**
     * Gestisci /mcp disconnect Comando。
     */
    private String handleDisconnect(McpManager manager, String args) {
        if (args.isEmpty()) {
            return AnsiStyle.red("  Usage: /mcp disconnect <name>");
        }

        String name = args.split("\\s+")[0];
        try {
            manager.disconnect(name);
            return AnsiStyle.green("  ✅ Disconnected MCP server: " + name);
        } catch (McpException e) {
            return AnsiStyle.red("  ❌ Disconnect failed: " + e.getMessage());
        }
    }

    /**
     * Gestisci /mcp tools Comando。
     */
    private String handleTools(McpManager manager, String args) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  🛠️  MCP Tools\n"));
        sb.append("  ").append("─".repeat(50)).append("\n\n");

        String serverFilter = args.isEmpty() ? null : args.split("\\s+")[0];

        List<McpClient.McpTool> tools;
        if (serverFilter != null) {
            tools = manager.getServerTools(serverFilter);
            if (tools.isEmpty()) {
                return sb + "  Server '" + serverFilter + "' has no tools or does not exist\n";
            }
            sb.append(AnsiStyle.dim("  Server: " + serverFilter)).append("\n\n");
        } else {
            tools = manager.getAllTools();
            if (tools.isEmpty()) {
                return sb + "  No available MCP tools\n";
            }
        }

        for (McpClient.McpTool tool : tools) {
            sb.append("  • ").append(AnsiStyle.bold(tool.name())).append("\n");
            if (!tool.description().isEmpty()) {
                sb.append("    ").append(AnsiStyle.dim(tool.description())).append("\n");
            }
            if (tool.inputSchema() != null) {
                sb.append("    ").append(AnsiStyle.dim("Schema: " +
                        truncate(tool.inputSchema().toString(), 80))).append("\n");
            }
            sb.append("\n");
        }

        sb.append(AnsiStyle.dim("  Total " + tools.size() + " tools")).append("\n");
        return sb.toString();
    }

    /**
     * Gestisci /mcp resources Comando。
     */
    private String handleResources(McpManager manager, String args) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  📦 MCP Resources\n"));
        sb.append("  ").append("─".repeat(50)).append("\n\n");

        String serverFilter = args.isEmpty() ? null : args.split("\\s+")[0];

        List<McpClient.McpResource> resourceList;
        if (serverFilter != null) {
            resourceList = manager.getServerResources(serverFilter);
            if (resourceList.isEmpty()) {
                return sb + "  Server '" + serverFilter + "' has no resources or does not exist\n";
            }
            sb.append(AnsiStyle.dim("  Server: " + serverFilter)).append("\n\n");
        } else {
            resourceList = manager.getAllResources();
            if (resourceList.isEmpty()) {
                return sb + "  No available MCP resources\n";
            }
        }

        for (McpClient.McpResource resource : resourceList) {
            sb.append("  • ").append(AnsiStyle.bold(resource.name())).append("\n");
            sb.append("    URI: ").append(AnsiStyle.cyan(resource.uri())).append("\n");
            if (!resource.description().isEmpty()) {
                sb.append("    ").append(AnsiStyle.dim(resource.description())).append("\n");
            }
            sb.append("    MIME: ").append(AnsiStyle.dim(resource.mimeType())).append("\n\n");
        }

        sb.append(AnsiStyle.dim("  Total " + resourceList.size() + " resources")).append("\n");
        return sb.toString();
    }

    /**
     * Gestisci /mcp reload Comando。
     */
    private String handleReload(McpManager manager, CommandContext context) {
        try {
            manager.reload();

            // nuovopontec'èStrumento
            for (Map.Entry<String, McpClient> entry : manager.getClients().entrySet()) {
                registerBridgedTools(entry.getValue(), entry.getKey(), context);
            }

            return AnsiStyle.green("  ✅ MCP config reloaded: "
                    + manager.getClients().size() + " servers, "
                    + manager.getAllTools().size() + " tools");
        } catch (Exception e) {
            return AnsiStyle.red("  ❌ Reload failed: " + e.getMessage());
        }
    }

    /**
     * verrà MCP Strumentoponteregistra inregistro strumenti。
     */
    private void registerBridgedTools(McpClient client, String serverName, CommandContext context) {
        if (context.toolRegistry() == null) {
            return;
        }

        ToolRegistry registry = context.toolRegistry();
        List<McpToolBridge> bridges = McpToolBridge.createBridges(serverName, client.getTools());
        for (McpToolBridge bridge : bridges) {
            registry.register(bridge);
        }
    }

    /**
     * visualizzaInformazione。
     */
    private String showHelp() {
        return """
                
                  \033[1m🔌 MCP Command Help\033[0m
                  ──────────────────────────────────────
                
                  /mcp                                List all MCP server status
                  /mcp connect <name> <cmd> [args]    Connect to MCP server
                  /mcp disconnect <name>              Disconnect MCP server
                  /mcp tools [server]                 List MCP tools
                  /mcp resources [server]             List MCP resources
                  /mcp reload                         Reload from config file
                  /mcp help                           Show this help
                
                  Config files:
                    Project: .mcp.json
                    Global:  ~/.space-ai-java/mcp.json
                """;
    }

    /**
     * troncastringa。
     */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }

    // ========== McpManager c'è（，ComandoealtriComponente） ==========

    /**
     * MCP gestoreglobalec'è —— ininComandoeStrumentocondiviso McpManager istanza.
     * <p>
     * inapplicazioneavviotramite {@link #setInstance(McpManager)} inietta。
     * sìServizioposizionamentoModalità，dopopuòa Spring DI。
     */
    public static final class McpManagerHolder {

        private static volatile McpManager instance;

        private McpManagerHolder() {
        }

        /** Impostazioniistanza del gestore MCP globale (chiamato all'avvio dell'applicazione) */
        public static void setInstance(McpManager manager) {
            instance = manager;
        }

        /** ottieniglobale MCP gestoreistanza */
        public static McpManager getInstance() {
            return instance;
        }
    }
}
