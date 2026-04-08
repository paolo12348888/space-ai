package com.spaceai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP  — più MCP ServizioConnessione。
 * <p>
 * ：
 * <ul>
 *   <li>carica le definizioni del server MCP dal file di configurazione</li>
 *   <li>ServizioConnessioneCiclo di vita（Connessione、Disconnessione、）</li>
 *   <li>Aggregazionec'èServiziodel serverStrumentoerisorsesopralivello</li>
 *   <li>dachiamata strumentoapositivoServizio</li>
 * </ul>
 * <p>
 * File di configurazioneformato（{@code mcp.json}）：
 * <pre>{@code
 * {
 *   "servers": {
 *     "server-name": {
 *       "command": "npx",
 *       "args": ["-y", "@modelcontextprotocol/server-filesystem"],
 *       "env": { "KEY": "VALUE" }
 *     }
 *   }
 * }
 * }</pre>
 *
 * @see McpClient
 * @see StdioTransport
 */
public class McpManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** percorso del file di configurazione globale: ~/.space-ai-java/mcp.json */
    private static final String GLOBAL_CONFIG = ".space-ai-java/mcp.json";

    /** livello progettoFile di configurazione */
    private static final String PROJECT_CONFIG = ".mcp.json";

    /** giàConnessione MCP ：serverName -> McpClient */
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();

    /** Nome dello strumentoaServizionome serverMappa：toolName -> serverName（indachiamata） */
    private final Map<String, String> toolToServer = new ConcurrentHashMap<>();

    /**
     * carica e connette tutti i server MCP dal file di configurazione.
     * <p>
     * Priorità:
     * <ol>
     *   <li>livello progettoFile di configurazione：CorrentelavoroDirectorysotto {@code .mcp.json}</li>
     *   <li>file di configurazione globale: {@code ~/.space-ai-java/mcp.json}</li>
     * </ol>
     * I server nei due file di configurazione vengono caricati e uniti.
     */
    public void loadFromConfig() {
        // livello progettoConfigurazione
        Path projectConfig = Path.of(System.getProperty("user.dir"), PROJECT_CONFIG);
        if (Files.exists(projectConfig)) {
            loadConfigFile(projectConfig, "project");
        }

        // configurazione globale
        Path globalConfig = Path.of(System.getProperty("user.home"), GLOBAL_CONFIG);
        if (Files.exists(globalConfig)) {
            loadConfigFile(globalConfig, "global");
        }

        if (clients.isEmpty()) {
            log.debug("No MCP config file found or no server definitions");
        }
    }

    /**
     * caricasingoloFile di configurazionein MCP Serviziodefinizione。
     */
    private void loadConfigFile(Path configPath, String label) {
        log.info("Loading {} MCP config: {}", label, configPath);

        try {
            String content = Files.readString(configPath);
            JsonNode root = MAPPER.readTree(content);

            JsonNode serversNode = root.get("servers");
            if (serversNode == null || !serversNode.isObject()) {
                log.warn("{} config file missing 'servers' field: {}", label, configPath);
                return;
            }

            Iterator<Map.Entry<String, JsonNode>> fields = serversNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String name = entry.getKey();
                JsonNode serverDef = entry.getValue();

                // saltagiàinServizio（livello progettoprioritàinglobale）
                if (clients.containsKey(name)) {
                    log.debug("MCP server '{}' already connected, skipping duplicate definition in {} config", name, label);
                    continue;
                }

                try {
                    String command = serverDef.get("command").asText();

                    List<String> args = new ArrayList<>();
                    if (serverDef.has("args") && serverDef.get("args").isArray()) {
                        for (JsonNode arg : serverDef.get("args")) {
                            args.add(arg.asText());
                        }
                    }

                    Map<String, String> env = new HashMap<>();
                    if (serverDef.has("env") && serverDef.get("env").isObject()) {
                        Iterator<Map.Entry<String, JsonNode>> envFields = serverDef.get("env").fields();
                        while (envFields.hasNext()) {
                            Map.Entry<String, JsonNode> envEntry = envFields.next();
                            env.put(envEntry.getKey(), envEntry.getValue().asText());
                        }
                    }

                    connect(name, command, args, env);
                } catch (Exception e) {
                    log.error("Failed to connect MCP server '{}' from config: {}", name, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Failed to read MCP config file: {}", configPath, e);
        }
    }

    /**
     * Connessionesingolo MCP Servizio.
     *
     * @param name    Servizionome serveridentificatore
     * @param command ServiziopuòesegueComando
     * @param args    ComandoParametriLista
     * @param env     Variabili d'ambiente（può essere {@code null}）
     * @return giàInizializzazione MCP 
     * @throws McpException ConnessioneoInizializzazioneFallimento
     */
    public McpClient connect(String name, String command, List<String> args, Map<String, String> env)
            throws McpException {
        // Segiàin，Disconnessione
        if (clients.containsKey(name)) {
            log.info("MCP server '{}' already exists, disconnecting old connection", name);
            try {
                disconnect(name);
            } catch (Exception e) {
                log.warn("Exception disconnecting old MCP connection '{}': {}", name, e.getMessage());
            }
        }

        log.info("Connecting MCP server '{}': {} {}", name, command, String.join(" ", args));

        // creatrasportolivelloeavvio（InizializzazioneFallimentorisorse）
        StdioTransport transport = new StdioTransport(command, args, env);
        McpClient client;
        try {
            transport.start();
            client = new McpClient(name, transport);
            client.initialize();
        } catch (Exception e) {
            // InizializzazioneFallimentoObbligatoriochiudetrasportolivello，sottoprocesso
            try {
                transport.close();
            } catch (Exception suppressed) {
                e.addSuppressed(suppressed);
            }
            throw (e instanceof McpException mcp) ? mcp
                    : new McpException("Failed to connect MCP server '" + name + "': " + e.getMessage(), e);
        }

        // registra
        clients.put(name, client);

        // stabilisceStrumento -> Serviziodel serverMappa
        for (McpClient.McpTool tool : client.getTools()) {
            String existingServer = toolToServer.get(tool.name());
            if (existingServer != null) {
                log.warn("MCP tool name conflict: '{}' exists in both server '{}' and '{}', using latter",
                        tool.name(), existingServer, name);
            }
            toolToServer.put(tool.name(), name);
        }

        log.info("MCP server '{}' connected successfully", name);
        return client;
    }

    /**
     * Disconnessione MCP ServizioConnessione。
     *
     * @param name Servizionome server
     * @throws McpException DisconnessioneFallimento
     */
    public void disconnect(String name) throws McpException {
        McpClient client = clients.remove(name);
        if (client == null) {
            throw new McpException("MCP server '" + name + "' does not exist");
        }

        // StrumentoMappa
        toolToServer.entrySet().removeIf(entry -> entry.getValue().equals(name));

        try {
            client.close();
            log.info("MCP server '{}' disconnected", name);
        } catch (Exception e) {
            throw new McpException("Exception disconnecting MCP server '" + name + "': " + e.getMessage(), e);
        }
    }

    /**
     * ottieni tuttigiàConnessione（Immutabile）。
     */
    public Map<String, McpClient> getClients() {
        return Collections.unmodifiableMap(clients);
    }

    /**
     * ottienispecificatoServiziodel server。
     *
     * @param name Servizionome server
     * @return istanza，senoninRestituisce {@link Optional#empty()}
     */
    public Optional<McpClient> getClient(String name) {
        return Optional.ofNullable(clients.get(name));
    }

    /**
     * ottieni tutti MCP Strumento（uniscec'èServiziodel serverStrumento）。
     *
     * @return c'ègiàscopreStrumentoLista
     */
    public List<McpClient.McpTool> getAllTools() {
        return clients.values().stream()
                .filter(McpClient::isInitialized)
                .flatMap(client -> client.getTools().stream())
                .toList();
    }

    /**
     * ottienispecificatoServiziodel serverStrumento。
     *
     * @param serverName Servizionome server
     * @return StrumentoLista，seServiziononinritornavuotoLista
     */
    public List<McpClient.McpTool> getServerTools(String serverName) {
        McpClient client = clients.get(serverName);
        if (client == null || !client.isInitialized()) {
            return List.of();
        }
        return List.copyOf(client.getTools());
    }

    /**
     * ottieni tutti MCP risorse（uniscec'èServiziodel serverrisorse）。
     *
     * @return c'ègiàscoprerisorseLista
     */
    public List<McpClient.McpResource> getAllResources() {
        return clients.values().stream()
                .filter(McpClient::isInitialized)
                .flatMap(client -> client.getResources().stream())
                .toList();
    }

    /**
     * ottienispecificatoServiziodel serverrisorse。
     *
     * @param serverName Servizionome server
     * @return risorseLista，seServiziononinritornavuotoLista
     */
    public List<McpClient.McpResource> getServerResources(String serverName) {
        McpClient client = clients.get(serverName);
        if (client == null || !client.isInitialized()) {
            return List.of();
        }
        return List.copyOf(client.getResources());
    }

    /**
     * chiamata MCP Strumento —— Automaticamentedaac'èquestoStrumentoServizio.
     *
     * @param toolName Nome dello strumento
     * @param args     Parametri dello strumento
     * @return StrumentoesegueRisultato
     * @throws McpException Strumentonon esiste o chiamata fallita
     */
    public String callTool(String toolName, Map<String, Object> args) throws McpException {
        String serverName = toolToServer.get(toolName);
        if (serverName == null) {
            throw new McpException("MCP tool not found: " + toolName);
        }
        return callTool(serverName, toolName, args);
    }

    /**
     * chiamataspecificatoServiziodel server MCP Strumento。
     *
     * @param serverName Servizionome server
     * @param toolName   Nome dello strumento
     * @param args       Parametri dello strumento
     * @return StrumentoesegueRisultato
     * @throws McpException Servizionon esiste o chiamata fallita
     */
    public String callTool(String serverName, String toolName, Map<String, Object> args)
            throws McpException {
        McpClient client = clients.get(serverName);
        if (client == null) {
            throw new McpException("MCP server '" + serverName + "' does not exist");
        }
        if (!client.isInitialized()) {
            throw new McpException("MCP server '" + serverName + "' not yet initialized");
        }
        return client.callTool(toolName, args);
    }

    /**
     * cercaStrumentoServizionome server。
     *
     * @param toolName Nome dello strumento
     * @return Servizionome server，senoninRestituisce {@link Optional#empty()}
     */
    public Optional<String> findServerForTool(String toolName) {
        return Optional.ofNullable(toolToServer.get(toolName));
    }

    /**
     * nuovocaricaFile di configurazioneec'èServizio.
     * <p>
     * prima disconnette tutte le connessioni esistenti, poi ricarica la configurazione.
     */
    public void reload() {
        log.info("Reloading MCP config...");

        // Disconnessionec'èc'èConnessione
        List<String> serverNames = new ArrayList<>(clients.keySet());
        for (String name : serverNames) {
            try {
                disconnect(name);
            } catch (Exception e) {
                log.warn("Failed to disconnect MCP server '{}' during reload: {}", name, e.getMessage());
            }
        }

        // nuovocarica
        loadFromConfig();
        log.info("MCP config reload complete: {} servers connected", clients.size());
    }

    /**
     * ottieniStatoriepilogo（Usato per /mcp ComandooStatovisualizza）。
     *
     * @return formato -izzatoStatoriepilogotesto
     */
    public String getSummary() {
        if (clients.isEmpty()) {
            return "  No connected MCP servers";
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
            String name = entry.getKey();
            McpClient client = entry.getValue();

            String status;
            if (client.isConnected() && client.isInitialized()) {
                status = "✅ Connected";
            } else if (client.isConnected()) {
                status = "🔄 Connecting";
            } else {
                status = "❌ Disconnected";
            }

            sb.append(String.format("  %-20s %s (%d tools, %d resources)%n",
                    name, status, client.getTools().size(), client.getResources().size()));
        }
        return sb.toString().stripTrailing();
    }

    @Override
    public void close() throws Exception {
        log.info("Closing all MCP connections...");
        List<Exception> errors = new ArrayList<>();

        for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                errors.add(e);
                log.error("Exception closing MCP server '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        clients.clear();
        toolToServer.clear();

        if (!errors.isEmpty()) {
            McpException ex = new McpException("Errors closing MCP manager: " + errors.size() + " errors");
            errors.forEach(ex::addSuppressed);
            throw ex;
        }

        log.info("All MCP connections closed");
    }
}
