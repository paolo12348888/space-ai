package com.spaceai.mcp;

/**
 * MCP Correlatoeccezione ——  MCP 、Protocolloanalisiechiamata strumentoinErrore。
 */
public class McpException extends Exception {

    /** JSON-RPC Errore（se JSON-RPC error Risposta） */
    private final int errorCode;

    public McpException(String message) {
        super(message);
        this.errorCode = -1;
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = -1;
    }

    public McpException(String message, int errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public McpException(String message, int errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * Ottieni JSON-RPC Errore。
     *
     * @return Errore，se JSON-RPC ErroreRestituisce {@code -1}
     */
    public int getErrorCode() {
        return errorCode;
    }

    /**
     * secome JSON-RPC ProtocolloErrore。
     */
    public boolean isJsonRpcError() {
        return errorCode != -1;
    }
}
