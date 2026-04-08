package com.spaceai.tool;

import java.util.Map;

/**
 * StrumentoProtocolloInterfaccia —— Corrisponde a space-ai/src/Tool.ts definizione del tipo Tool in
 * <p>
 * ogniStrumentosìuncompletoProtocolloImplementazione，package：
 * <ul>
 *   <li>Strumentodefinizione（name、description、inputSchema）——  LLM chiamata</li>
 *   <li>logica di esecuzione（execute）—— realeEsecuzione</li>
 *   <li>controllo permessi（checkPermission）—— sicurezzacontrollo preliminare</li>
 *   <li>（isEnabled）—— condizioneregistra</li>
 *   <li>descrizione（activityDescription）—— utenteclassepuòprogresso</li>
 * </ul>
 */
public interface Tool {

    /** Strumentounivoconomeidentificatore */
    String name();

    /**  LLM Strumentodescrizione */
    String description();

    /**
     * InputParametri JSON Schema definizione。
     * <p>
     * Esempio：
     * <pre>{@code
     * {
     *   "type": "object",
     *   "properties": {
     *     "command": { "type": "string", "description": "Shell command to execute" }
     *   },
     *   "required": ["command"]
     * }
     * }</pre>
     */
    String inputSchema();

    /**
     * esegueStrumento。
     *
     * @param input   JSON analisidopoInputParametri
     * @param context eseguecontesto（lavoroDirectory、SessioneStatoecc.)
     * @return esegueRisultatotesto
     */
    String execute(Map<String, Object> input, ToolContext context);

    /**
     * Permessocontrollo preliminare，in execute primachiamata。
     * Predefinito。
     */
    default PermissionResult checkPermission(Map<String, Object> input, ToolContext context) {
        return PermissionResult.ALLOW;
    }

    /** StrumentoseAbilitato（），Restituisce false nonRegistra */
    default boolean isEnabled() {
        return true;
    }

    /** secomeoperazione */
    default boolean isReadOnly() {
        return false;
    }

    /** descrizione dell'attività leggibile, usata dalla UI per mostrare il progresso */
    default String activityDescription(Map<String, Object> input) {
        return "Running " + name() + "...";
    }
}
