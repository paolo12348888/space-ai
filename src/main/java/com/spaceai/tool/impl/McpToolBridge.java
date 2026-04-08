package com.spaceai.tool.impl;

import com.spaceai.mcp.McpClient;
import com.spaceai.mcp.McpException;
import com.spaceai.mcp.McpManager;
import com.spaceai.tool.Tool;
import com.spaceai.tool.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * MCP Strumentoponte —— verrà MCP ServizioremotoStrumentopontecomelocale {@link Tool} istanza.
 * <p>
 * Permette al modello AI di chiamare gli strumenti sul server MCP come se fossero strumenti locali.
 * ogni {@code McpToolBridge} istanzaCorrisponde aun MCP ServiziosopraunStrumento。
 * <p>
 * pontestream：
 * <ol>
 *   <li>da {@link McpClient.McpTool} Strumentodefinizione（nome、descrizione、Parametri schema）</li>
 *   <li>Implementazione {@link Tool} Interfaccia，rendendolo registrabile in {@link com.spaceai.tool.ToolRegistry}</li>
 *   <li>eseguetramite {@link ToolContext} in {@link McpManager} daaCorrisponde a MCP Servizio</li>
 * </ol>
 *
 * @see McpManager
 * @see McpClient.McpTool
 */
public class McpToolBridge implements Tool {

    /** MCP gestorein ToolContext inArchiviazione */
    public static final String MCP_MANAGER_KEY = "MCP_MANAGER";

    /** Strumento MCP Servizionome server */
    private final String serverName;

    /** MCP Nome dello strumento（in MCP Serviziosopranome） */
    private final String mcpToolName;

    /** pontedopolocaleNome dello strumento（formato：mcp__{serverName}__{toolName}） */
    private final String bridgedName;

    /** MCP Strumentodescrizione */
    private final String mcpDescription;

    /** MCP StrumentoInputParametri JSON Schema（ JsonNode） */
    private final JsonNode mcpInputSchema;

    /** Cache inputSchema JSON stringa */
    private final String inputSchemaString;

    /**
     * Crea MCP Strumentoponteistanza.
     *
     * @param serverName MCP Servizionome server
     * @param mcpTool    MCP Strumentodefinizione
     */
    public McpToolBridge(String serverName, McpClient.McpTool mcpTool) {
        this.serverName = Objects.requireNonNull(serverName, "Server name cannot be null");
        Objects.requireNonNull(mcpTool, "MCP tool definition cannot be null");

        this.mcpToolName = mcpTool.name();
        this.mcpDescription = mcpTool.description();
        this.mcpInputSchema = mcpTool.inputSchema();

        // generapontenome：mcp__{serverName}__{toolName}
        // separato con doppio underscore per evitare conflitti con i nomi degli strumenti locali
        this.bridgedName = "mcp__" + sanitizeName(serverName) + "__" + sanitizeName(mcpToolName);

        // Serializzazione inputSchema
        this.inputSchemaString = buildInputSchema();
    }

    @Override
    public String name() {
        return bridgedName;
    }

    @Override
    public String description() {
        return String.format("[MCP:%s] %s", serverName, mcpDescription);
    }

    @Override
    public String inputSchema() {
        return inputSchemaString;
    }

    /**
     * Esegui MCP remotochiamata strumento。
     * <p>
     * tramite {@link ToolContext} inArchiviazione {@link McpManager} istanza
     * dachiamataaCorrisponde a MCP Servizio.
     *
     * @param input   analisidopoInputParametri
     * @param context Strumentoeseguecontesto（Obbligatoriopackage {@code MCP_MANAGER} ）
     * @return MCP StrumentoesegueRisultato
     */
    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        // ottieni McpManager dal contesto
        McpManager mcpManager = context.get(MCP_MANAGER_KEY);
        if (mcpManager == null) {
            return "Error: MCP manager not registered in context (key=" + MCP_MANAGER_KEY + ")";
        }

        try {
            return mcpManager.callTool(serverName, mcpToolName, input);
        } catch (McpException e) {
            return "MCP tool call failed [" + serverName + "/" + mcpToolName + "]: " + e.getMessage();
        }
    }

    @Override
    public boolean isReadOnly() {
        // MCP Strumentolettura/scritturaattributonon ancora，contrassegna come
        return false;
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "🔌 [MCP:" + serverName + "] " + mcpToolName;
    }

    /**
     * Ottieni MCP Servizionome server。
     */
    public String getServerName() {
        return serverName;
    }

    /**
     * Ottieni MCP Strumentonome。
     */
    public String getMcpToolName() {
        return mcpToolName;
    }

    /**
     * crea strumenti ponte in batch dalle definizioni degli strumenti MCP.
     *
     * @param serverName Servizionome server
     * @param mcpTools   MCP StrumentoInsieme
     * @return ponteStrumentoLista
     */
    public static List<McpToolBridge> createBridges(String serverName,
                                                     Collection<McpClient.McpTool> mcpTools) {
        return mcpTools.stream()
                .map(tool -> new McpToolBridge(serverName, tool))
                .toList();
    }

    /**
     * Costruisci inputSchema JSON stringa。
     * <p>
     * Se MCP Strumentofornisce inputSchema，direttamente；
     * nogeneraunParametri schema。
     */
    private String buildInputSchema() {
        if (mcpInputSchema != null && !mcpInputSchema.isNull()) {
            return mcpInputSchema.toString();
        }
        // fallback: accetta qualsiasi oggetto JSON
        return """
                {
                  "type": "object",
                  "properties": {},
                  "additionalProperties": true
                }""";
    }

    /**
     * nome，sostituiscecaratterecomesotto。
     * conserva lettere, numeri, trattini e underscore.
     */
    private static String sanitizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    @Override
    public String toString() {
        return "McpToolBridge{" +
                "server='" + serverName + '\'' +
                ", tool='" + mcpToolName + '\'' +
                ", bridgedAs='" + bridgedName + '\'' +
                '}';
    }
}
