package com.spaceai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP  —— Corrisponde a space-ai in mcp/ modulo。
 * <p>
 * negativoesingolo MCP Serviziodel servercompletoCiclo di vita：
 * <ol>
 *   <li>tramite {@link McpTransport} stabilisceConnessione</li>
 *   <li>Invia {@code initialize} Richiesta</li>
 *   <li>scopreServiziofornito dalStrumento（{@code tools/list}）erisorse（{@code resources/list}）</li>
 *   <li>chiamataStrumento（{@code tools/call}）eleggerisorse（{@code resources/read}）</li>
 * </ol>
 * <p>
 * MCP Protocollocomunica in formato JSON-RPC 2.0.
 *
 * @see McpTransport
 * @see McpManager
 */
public class McpClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** JSON-RPC Richiesta ID generatore */
    private final AtomicInteger idCounter = new AtomicInteger(1);

    /** Servizionome serveridentificatore */
    private final String serverName;

    /** livellotrasportolivello */
    private final McpTransport transport;

    /** giàscopreStrumentoInsieme：toolName -> McpTool */
    private final Map<String, McpTool> tools = new ConcurrentHashMap<>();

    /** giàscoprerisorseInsieme：uri -> McpResource */
    private final Map<String, McpResource> resources = new ConcurrentHashMap<>();

    /** ServizioInformazione */
    private volatile JsonNode serverCapabilities;

    /** ServizioInformazione */
    private volatile JsonNode serverInfo;

    /** secompletatoInizializza */
    private volatile boolean initialized = false;

    /**
     * Crea MCP 。
     *
     * @param serverName Servizioidentificatorenome
     * @param transport  trasportolivelloImplementazione
     */
    public McpClient(String serverName, McpTransport transport) {
        this.serverName = Objects.requireNonNull(serverName, "Server name cannot be null");
        this.transport = Objects.requireNonNull(transport, "Transport cannot be null");
    }

    /**
     * InizializzazioneConnessione —— MCP Protocollostream。
     * <p>
     * passo：
     * <ol>
     *   <li>Invia {@code initialize} Richiesta，eProtocolloVersione</li>
     *   <li>analisiServizioritornaInformazione</li>
     *   <li>Invia {@code notifications/initialized} notifica</li>
     *   <li>scopreServiziofornito dalStrumentoerisorse</li>
     * </ol>
     *
     * @throws McpException InizializzazioneFallimento
     */
    public void initialize() throws McpException {
        log.info("Initializing MCP server '{}'...", serverName);

        // 1. Invia initialize Richiesta
        int initId = nextId();
        var initRequest = Map.of(
                "jsonrpc", "2.0",
                "id", initId,
                "method", "initialize",
                "params", Map.of(
                        "protocolVersion", "2024-11-05",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of(
                                "name", "space-ai-java",
                                "version", "1.0.0"
                        )
                )
        );

        JsonNode response;
        try {
            response = transport.sendRequest(MAPPER.writeValueAsString(initRequest));
        } catch (Exception e) {
            throw new McpException("MCP initialize request failed: " + e.getMessage(), e);
        }

        // 2. analisiServizio
        JsonNode result = response.get("result");
        if (result != null) {
            serverCapabilities = result.get("capabilities");
            serverInfo = result.get("serverInfo");
            String serverVersion = result.has("protocolVersion")
                    ? result.get("protocolVersion").asText() : "unknown";
            log.info("MCP server '{}' protocol version: {}", serverName, serverVersion);
            if (serverInfo != null) {
                log.info("MCP server info: {}", serverInfo);
            }
        }

        // 3. Invia initialized notifica
        var initializedNotif = Map.of(
                "jsonrpc", "2.0",
                "method", "notifications/initialized"
        );
        try {
            transport.sendNotification(MAPPER.writeValueAsString(initializedNotif));
        } catch (Exception e) {
            throw new McpException("Failed to send initialized notification: " + e.getMessage(), e);
        }

        // 4. scopreStrumento
        discoverTools();

        // 5. scoprerisorse
        discoverResources();

        initialized = true;
        log.info("MCP server '{}' initialization complete: {} tools, {} resources",
                serverName, tools.size(), resources.size());
    }

    /**
     * scopreServiziofornito dalStrumento —— Invia {@code tools/list} Richiesta。
     */
    private void discoverTools() throws McpException {
        // controllaServiziosesupporta tools 
        if (serverCapabilities != null
                && serverCapabilities.has("tools")
                && serverCapabilities.get("tools").isObject()) {
            // ServiziosupportaStrumento
        } else if (serverCapabilities != null && !serverCapabilities.has("tools")) {
            log.debug("MCP server '{}' did not declare tools capability, attempting discovery", serverName);
        }

        int id = nextId();
        var request = Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "method", "tools/list",
                "params", Map.of()
        );

        try {
            JsonNode response = transport.sendRequest(MAPPER.writeValueAsString(request));
            JsonNode result = response.get("result");
            if (result != null && result.has("tools")) {
                JsonNode toolsNode = result.get("tools");
                if (toolsNode != null && toolsNode.isArray()) {
                    for (JsonNode toolNode : toolsNode) {
                        String name = toolNode.get("name").asText();
                        String description = toolNode.has("description")
                                ? toolNode.get("description").asText() : "";
                        JsonNode inputSchema = toolNode.get("inputSchema");

                        tools.put(name, new McpTool(name, description, inputSchema));
                        log.debug("Discovered MCP tool: {} - {}", name, description);
                    }
                }
            }
        } catch (McpException e) {
            // tools/list puònonsupporta，recordAvvisomanoninInizializzazione
            if (e.isJsonRpcError() && e.getErrorCode() == -32601) {
                log.debug("MCP server '{}' does not support tools/list", serverName);
            } else {
                log.warn("Failed to discover MCP tools: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("MCP tool discovery serialization exception: {}", e.getMessage());
        }
    }

    /**
     * scopreServiziofornito dalrisorse —— Invia {@code resources/list} Richiesta。
     */
    private void discoverResources() throws McpException {
        // controllaServiziosesupporta resources 
        if (serverCapabilities != null
                && serverCapabilities.has("resources")
                && serverCapabilities.get("resources").isObject()) {
            // Serviziosupportarisorse
        } else if (serverCapabilities != null && !serverCapabilities.has("resources")) {
            log.debug("MCP server '{}' did not declare resources capability, skipping discovery", serverName);
            return;
        }

        int id = nextId();
        var request = Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "method", "resources/list",
                "params", Map.of()
        );

        try {
            JsonNode response = transport.sendRequest(MAPPER.writeValueAsString(request));
            JsonNode result = response.get("result");
            if (result != null && result.has("resources")) {
                JsonNode resourcesNode = result.get("resources");
                if (resourcesNode != null && resourcesNode.isArray()) {
                    for (JsonNode resNode : resourcesNode) {
                        String uri = resNode.get("uri").asText();
                        String name = resNode.has("name") ? resNode.get("name").asText() : uri;
                        String description = resNode.has("description")
                                ? resNode.get("description").asText() : "";
                        String mimeType = resNode.has("mimeType")
                                ? resNode.get("mimeType").asText() : "text/plain";

                        resources.put(uri, new McpResource(uri, name, description, mimeType));
                        log.debug("Discovered MCP resource: {} ({})", name, uri);
                    }
                }
            }
        } catch (McpException e) {
            if (e.isJsonRpcError() && e.getErrorCode() == -32601) {
                log.debug("MCP server '{}' does not support resources/list", serverName);
            } else {
                log.warn("Failed to discover MCP resources: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("MCP resource discovery serialization exception: {}", e.getMessage());
        }
    }

    /**
     * chiamata MCP Strumento —— Invia {@code tools/call} Richiesta。
     *
     * @param toolName  Nome dello strumento
     * @param arguments Parametri dello strumento（su）
     * @return StrumentoesegueRisultatotesto
     * @throws McpException chiamataFallimentooStrumentononin
     */
    public String callTool(String toolName, Map<String, Object> arguments) throws McpException {
        if (!initialized) {
            throw new McpException("MCP client not yet initialized");
        }
        if (!tools.containsKey(toolName)) {
            throw new McpException("MCP tool does not exist: " + toolName);
        }

        int id = nextId();
        var request = Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "method", "tools/call",
                "params", Map.of(
                        "name", toolName,
                        "arguments", arguments != null ? arguments : Map.of()
                )
        );

        try {
            log.debug("Calling MCP tool: {} (args: {})", toolName, arguments);
            JsonNode response = transport.sendRequest(MAPPER.writeValueAsString(request));
            JsonNode result = response.get("result");

            if (result == null) {
                return "";
            }

            // MCP tools/call Restituisce { content: [{ type: "text", text: "..." }, ...] }
            if (result.has("content")) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode contentItem : result.get("content")) {
                    String type = contentItem.has("type") ? contentItem.get("type").asText() : "text";
                    if ("text".equals(type) && contentItem.has("text")) {
                        if (!sb.isEmpty()) {
                            sb.append("\n");
                        }
                        sb.append(contentItem.get("text").asText());
                    }
                }

                // Controlla isError 
                if (result.has("isError") && result.get("isError").asBoolean()) {
                    throw new McpException("MCP tool '" + toolName + "' execution error: " + sb);
                }

                return sb.toString();
            }

            // fallback: restituisce direttamente la forma testuale del result
            return result.toString();

        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            throw new McpException("Failed to call MCP tool '" + toolName + "': " + e.getMessage(), e);
        }
    }

    /**
     * Leggi MCP risorse —— Invia {@code resources/read} Richiesta。
     *
     * @param uri risorse URI
     * @return risorsecontenutotesto
     * @throws McpException leggeFallimentoorisorsenonin
     */
    public String readResource(String uri) throws McpException {
        if (!initialized) {
            throw new McpException("MCP client not yet initialized");
        }

        int id = nextId();
        var request = Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "method", "resources/read",
                "params", Map.of("uri", uri)
        );

        try {
            log.debug("Reading MCP resource: {}", uri);
            JsonNode response = transport.sendRequest(MAPPER.writeValueAsString(request));
            JsonNode result = response.get("result");

            if (result == null) {
                return "";
            }

            // MCP resources/read Restituisce { contents: [{ uri, text/blob }] }
            if (result.has("contents")) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode contentItem : result.get("contents")) {
                    if (contentItem.has("text")) {
                        if (!sb.isEmpty()) {
                            sb.append("\n");
                        }
                        sb.append(contentItem.get("text").asText());
                    }
                }
                return sb.toString();
            }

            return result.toString();

        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            throw new McpException("Failed to read MCP resource '" + uri + "': " + e.getMessage(), e);
        }
    }

    /**
     * ottieni tuttigiàscopreStrumento（Immutabile）。
     */
    public Collection<McpTool> getTools() {
        return Collections.unmodifiableCollection(tools.values());
    }

    /**
     * ottieni tuttigiàscoprerisorse（Immutabile）。
     */
    public Collection<McpResource> getResources() {
        return Collections.unmodifiableCollection(resources.values());
    }

    /**
     * nomecercaStrumento。
     *
     * @param toolName Nome dello strumento
     * @return Strumentodefinizione，senoninRestituisce {@link Optional#empty()}
     */
    public Optional<McpTool> findTool(String toolName) {
        return Optional.ofNullable(tools.get(toolName));
    }

    /** ottieniServizionome serveridentificatore */
    public String getServerName() {
        return serverName;
    }

    /** secompletatoInizializza */
    public boolean isInitialized() {
        return initialized;
    }

    /** se il livello di trasporto è ancora connesso */
    public boolean isConnected() {
        return transport.isConnected();
    }

    /** ottieniServizioInformazione */
    public JsonNode getServerCapabilities() {
        return serverCapabilities;
    }

    /** ottieniServizioInformazione */
    public JsonNode getServerInfo() {
        return serverInfo;
    }

    @Override
    public void close() throws Exception {
        initialized = false;
        tools.clear();
        resources.clear();
        transport.close();
        log.info("MCP client '{}' closed", serverName);
    }

    /** generasottoun JSON-RPC Richiesta ID */
    private int nextId() {
        return idCounter.getAndIncrement();
    }

    // ========== InternorecordTipo ==========

    /**
     * MCP Strumentodefinizione —— ServiziopuòchiamataStrumento。
     *
     * @param name        Nome dello strumento
     * @param description Strumentodescrizione
     * @param inputSchema InputParametri JSON Schema
     */
    public record McpTool(String name, String description, JsonNode inputSchema) {
    }

    /**
     * MCP risorsedefinizione —— Serviziopuòleggerisorse。
     *
     * @param uri         risorse URI
     * @param name        risorsenome
     * @param description risorsedescrizione
     * @param mimeType    MIME Tipo
     */
    public record McpResource(String uri, String name, String description, String mimeType) {
    }
}
