package com.spaceai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * basato su StdIO  MCP implementazione del trasporto —— tramitesottoprocesso stdin/stdout esegue JSON-RPC 。
 * <p>
 * lavoroprincipio：
 * <ol>
 *   <li>avvioEsterno MCP Servizio（ {@code npx -y @modelcontextprotocol/server-filesystem}）</li>
 *   <li>tramite stdin Invia JSON-RPC messaggio（ JSON）</li>
 *   <li>tramiteThreadda stdout AsincronoleggeRisposta</li>
 *   <li>usa {@link CompletableFuture} per l'associazione richiesta-risposta per campo {@code id}</li>
 * </ol>
 * <p>
 * messaggiodividi： JSON-RPC messaggiouna riga，con {@code \n} dividi。
 */
public class StdioTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(StdioTransport.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** RichiestaPredefinitoTimeout（secondi） */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** MCP Serviziosottoprocesso */
    private Process process;

    /** sottoprocesso stdin scrivestream */
    private BufferedWriter processStdin;

    /** AsincronoleggeThread（stdout） */
    private Thread readerThread;

    /** AsincronoleggeThread（stderr） */
    private Thread stderrThread;

    /** corrispondeRichiesta：id(String) -> CompletableFuture */
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pendingRequests =
            new ConcurrentHashMap<>();

    /** avvioComando */
    private final String command;

    /** avvioParametri */
    private final List<String> args;

    /** Variabili d'ambiente（Opzionale） */
    private final Map<String, String> env;

    /** ConnessioneStato */
    private volatile boolean connected = false;

    /**
     * Crea StdIO istanza di trasporto.
     *
     * @param command ServiziopuòesegueComando（ "npx"）
     * @param args    ComandoParametriLista
     * @param env     esternoVariabili d'ambiente，può essere {@code null}
     */
    public StdioTransport(String command, List<String> args, Map<String, String> env) {
        this.command = command;
        this.args = args != null ? List.copyOf(args) : List.of();
        this.env = env != null ? Map.copyOf(env) : Map.of();
    }

    /**
     * Avvia MCP Serviziosottoprocessoeinizia stdout。
     *
     * @throws McpException avvioFallimento
     */
    public void start() throws McpException {
        try {
            // BuildComando
            var cmdList = new java.util.ArrayList<String>();
            cmdList.add(command);
            cmdList.addAll(args);

            log.info("Starting MCP server process: {}", String.join(" ", cmdList));

            ProcessBuilder pb = new ProcessBuilder(cmdList);
            pb.redirectErrorStream(false); // stderr Gestisce

            // ImpostazioniVariabili d'ambiente
            if (!env.isEmpty()) {
                pb.environment().putAll(env);
            }

            process = pb.start();

            // Inizializza stdin scrive
            processStdin = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

            // Avvia stdout AsincronoleggeThread
            readerThread = Thread.ofVirtual().name("mcp-stdio-reader").start(this::readLoop);

            // Avvia stderr LogThread（SolorecordLog，noneProtocollo）
            stderrThread = Thread.ofVirtual().name("mcp-stdio-stderr").start(this::stderrLoop);

            connected = true;
            log.info("MCP server process started (PID: {})", process.pid());

        } catch (IOException e) {
            throw new McpException("Failed to start MCP server process: " + e.getMessage(), e);
        }
    }

    /**
     * stdout leggeciclo —— Leggi JSON-RPC RispostaedividiaCorrisponde a Future。
     */
    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                try {
                    JsonNode message = MAPPER.readTree(line);
                    handleMessage(message);
                } catch (Exception e) {
                    log.warn("Failed to parse MCP response: {}", line, e);
                }
            }
        } catch (IOException e) {
            if (connected) {
                log.warn("MCP stdout read interrupted: {}", e.getMessage());
            }
        } finally {
            connected = false;
            // c'èattendeinRichiesta
            pendingRequests.forEach((id, future) ->
                    future.completeExceptionally(new McpException("MCP connection disconnected")));
            pendingRequests.clear();
        }
    }

    /**
     * stderr leggeciclo —— verràServizio stderr OutputrecordcomeLog。
     */
    private void stderrLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("[MCP stderr] {}", line);
            }
        } catch (IOException e) {
            // stderr leggetermina，Ignora
        }
    }

    /**
     * Gestisceda stdout legge JSON-RPC messaggio。
     * <p>
     * semessaggiopackage {@code id} Campo，corrispondeaCorrisponde aGestisceRichiesta；
     * semessaggiocomenotifica（nessuno {@code id}），recordLog。
     */
    private void handleMessage(JsonNode message) {
        // controllasec'è id Campo（Rispostamessaggio）
        JsonNode idNode = message.get("id");
        if (idNode != null && !idNode.isNull()) {
            // come String， Integer/String TipononcorrispondecercaFallimento
            String id = idNode.asText();

            CompletableFuture<JsonNode> future = pendingRequests.remove(id);
            if (future != null) {
                future.complete(message);
            } else {
                log.warn("Received unmatched MCP response (id={}): {}", id, message);
            }
        } else {
            // Servizionotifica（ notifications/tools/list_changed）
            String method = message.has("method") ? message.get("method").asText() : "unknown";
            log.debug("Received MCP server notification: {}", method);
        }
    }

    @Override
    public JsonNode sendRequest(String jsonRpcRequest) throws McpException {
        if (!connected) {
            throw new McpException("MCP transport not connected");
        }

        String id = null;
        try {
            // analisiRichiesta id（come String）
            JsonNode requestNode = MAPPER.readTree(jsonRpcRequest);
            JsonNode idNode = requestNode.get("id");
            if (idNode == null || idNode.isNull()) {
                throw new McpException("JSON-RPC request missing id field");
            }
            id = idNode.asText();

            // Registra Future
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            pendingRequests.put(id, future);

            // Scrivi stdin（una riga JSON + newline）
            synchronized (processStdin) {
                processStdin.write(jsonRpcRequest);
                processStdin.newLine();
                processStdin.flush();
            }

            log.debug("Sent MCP request (id={}): {}", id, truncate(jsonRpcRequest, 200));

            // attendeRisposta
            JsonNode response = future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Controlla JSON-RPC error
            JsonNode errorNode = response.get("error");
            if (errorNode != null && !errorNode.isNull()) {
                int code = errorNode.has("code") ? errorNode.get("code").asInt() : -1;
                String msg = errorNode.has("message") ? errorNode.get("message").asText() : "Unknown error";
                throw new McpException("MCP server returned error: " + msg, code);
            }

            return response;

        } catch (McpException e) {
            throw e;
        } catch (TimeoutException e) {
            throw new McpException("MCP request timeout (" + DEFAULT_TIMEOUT_SECONDS + "s)", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof McpException mcp) {
                throw mcp;
            }
            throw new McpException("MCP request execution exception: " + cause.getMessage(), cause);
        } catch (Exception e) {
            throw new McpException("MCP request send failed: " + e.getMessage(), e);
        } finally {
            // nessunoSuccesso、Timeoutoeccezione，GestisceRichiesta，memoria
            if (id != null) {
                pendingRequests.remove(id);
            }
        }
    }

    @Override
    public void sendNotification(String jsonRpcNotification) throws McpException {
        if (!connected) {
            throw new McpException("MCP transport not connected");
        }

        try {
            synchronized (processStdin) {
                processStdin.write(jsonRpcNotification);
                processStdin.newLine();
                processStdin.flush();
            }
            log.debug("Sent MCP notification: {}", truncate(jsonRpcNotification, 200));
        } catch (IOException e) {
            throw new McpException("MCP notification send failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isConnected() {
        return connected && process != null && process.isAlive();
    }

    @Override
    public void close() throws Exception {
        connected = false;
        log.info("Closing MCP StdIO transport...");

        // Chiudi stdin（notificaServizioEsci）
        if (processStdin != null) {
            try {
                processStdin.close();
            } catch (IOException e) {
                log.debug("Exception closing stdin: {}", e.getMessage());
            }
        }

        // attendeEsci
        if (process != null && process.isAlive()) {
            boolean exited = process.waitFor(5, TimeUnit.SECONDS);
            if (!exited) {
                log.warn("MCP server process did not exit within 5s, force terminating");
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
        }

        // interrompe il thread di lettura
        if (readerThread != null && readerThread.isAlive()) {
            readerThread.interrupt();
        }
        if (stderrThread != null && stderrThread.isAlive()) {
            stderrThread.interrupt();
        }

        // GestisceRichiesta
        pendingRequests.forEach((id, future) ->
                future.completeExceptionally(new McpException("MCP transport closed")));
        pendingRequests.clear();

        log.info("MCP StdIO transport closed");
    }

    /**
     * troncastringainLogOutput。
     */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
