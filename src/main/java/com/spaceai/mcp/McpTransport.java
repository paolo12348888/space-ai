package com.spaceai.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP interfaccia del livello di trasporto — astrae diversi metodi di comunicazione (StdIO / SSE / HTTP).
 * <p>
 * MCP Protocollolivellobasato su JSON-RPC 2.0，trasportolivellonegativoverrà JSON-RPC messaggio
 * invia a MCP ServizioericeveRisposta。diversoimplementazione del trasporto（ StdIO sottoprocesso、
 * HTTP+SSE remotoConnessione）tramitequestoInterfaccia。
 *
 * @see StdioTransport
 */
public interface McpTransport extends AutoCloseable {

    /**
     * Invia JSON-RPC RichiestaeattendeRisposta。
     * <p>
     * Implementazionein base aRichiestain {@code id} CampoverràRispostaeRichiestachiuso。
     *
     * @param jsonRpcRequest completo JSON-RPC 2.0 Richiestastringa
     * @return Servizioritorna JSON-RPC Risposta
     * @throws McpException eccezioneoTimeout
     */
    JsonNode sendRequest(String jsonRpcRequest) throws McpException;

    /**
     * Invia JSON-RPC notifica（nessunoRisposta）。
     * <p>
     * notificamessaggiononpackage {@code id} Campo，Servizionessuno。
     *
     * @param jsonRpcNotification completo JSON-RPC 2.0 notificastringa
     * @throws McpException eccezione
     */
    void sendNotification(String jsonRpcNotification) throws McpException;

    /**
     * se il livello di trasporto è già connesso e disponibile.
     *
     * @return SelivelloConnessioneancoraRestituisce {@code true}
     */
    boolean isConnected();
}
