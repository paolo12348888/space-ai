package com.spaceai.tool.impl;

import com.spaceai.tool.Tool;
import com.spaceai.tool.ToolContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Config Strumento —— ottienioImpostazioniConfigurazione。
 * <p>
 * in P2 prioritàausiliarioStrumento。supportadue tipi diArchiviazionedopo：
 * </p>
 * <ol>
 *   <li>da ToolContext inConfigurazioneMappa（ "CONFIG_STORE"）lettura/scrittura</li>
 *   <li>fallbacka {@link System#getProperty} / {@link System#setProperty}</li>
 * </ol>
 *
 * <h3>Parametri</h3>
 * <ul>
 *   <li><b>action</b>（obbligatorio)—— "get" o "set"</li>
 *   <li><b>key</b>（obbligatorio)—— Configurazione</li>
 *   <li><b>value</b>（Opzionale，set obbligatorio)—— Configurazione</li>
 * </ul>
 *
 * <h3>ritorna</h3>
 * <p>JSON formato：get ritornaCorrente，set ritornaconfermaInformazione。</p>
 */
public class ConfigTool implements Tool {

    /** ToolContext nome chiave per l'archiviazione della configurazione in */
    private static final String CONFIG_STORE_KEY = "CONFIG_STORE";

    @Override
    public String name() {
        return "Config";
    }

    @Override
    public String description() {
        return "Get or set configuration values";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "action": {
                      "type": "string",
                      "description": "Action type: get or set",
                      "enum": ["get", "set"]
                    },
                    "key": {
                      "type": "string",
                      "description": "Configuration key name"
                    },
                    "value": {
                      "type": "string",
                      "description": "Configuration value (required for set operation)"
                    }
                  },
                  "required": ["action", "key"]
                }""";
    }

    /**
     * Config Strumentonon è puramente in sola lettura（l'operazione set modifica lo stato），
     * ma per motivi di sicurezza è ancora contrassegnato come false.
     */
    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        // analisiobbligatorioParametri: action
        String action = (String) input.get("action");
        if (action == null || action.isBlank()) {
            return errorJson("Parameter 'action' is required, valid values: get, set");
        }
        action = action.trim().toLowerCase();

        // analisiobbligatorioParametri: key
        String key = (String) input.get("key");
        if (key == null || key.isBlank()) {
            return errorJson("Parameter 'key' is required and cannot be empty");
        }

        // ottienioInizializzazioneConfigurazioneArchiviazione
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, String> configStore =
                context.getOrDefault(CONFIG_STORE_KEY, null);

        if (configStore == null) {
            configStore = new ConcurrentHashMap<>();
            context.set(CONFIG_STORE_KEY, configStore);
        }

        return switch (action) {
            case "get" -> executeGet(key, configStore);
            case "set" -> executeSet(key, input, configStore);
            default -> errorJson("Invalid action value: '" + action + "'. Valid values: get, set");
        };
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        String action = (String) input.getOrDefault("action", "?");
        String key = (String) input.getOrDefault("key", "?");
        if ("set".equalsIgnoreCase(action)) {
            return "⚙️ Setting config: " + key;
        }
        return "⚙️ Getting config: " + key;
    }

    /* ------------------------------------------------------------------ */
    /*  get / set Implementazione                                                 */
    /* ------------------------------------------------------------------ */

    /**
     * Esegui get operazione：prioritàdaConfigurazioneMappalegge，fallbackaSistemaattributo。
     *
     * @param key         Configurazione
     * @param configStore ConfigurazioneMappa
     * @return JSON formatoRisultato
     */
    private String executeGet(String key, ConcurrentHashMap<String, String> configStore) {
        // preferisce ottenere dalla mappa di configurazione del contesto
        String value = configStore.get(key);

        // fallbackaSistemaattributo
        if (value == null) {
            value = System.getProperty(key);
        }

        if (value == null) {
            return """
                    {
                      "action": "get",
                      "key": "%s",
                      "value": null,
                      "found": false,
                      "message": "Config key '%s' not found"
                    }""".formatted(escapeJson(key), escapeJson(key));
        }

        return """
                {
                  "action": "get",
                  "key": "%s",
                  "value": "%s",
                  "found": true
                }""".formatted(escapeJson(key), escapeJson(value));
    }

    /**
     * Esegui set operazione：scriveConfigurazioneMappaeSistemaattributo。
     *
     * @param key         Configurazione
     * @param input       InputParametriMappa
     * @param configStore ConfigurazioneMappa
     * @return JSON formatoconferma
     */
    private String executeSet(String key, Map<String, Object> input,
                              ConcurrentHashMap<String, String> configStore) {
        String value = (String) input.get("value");
        if (value == null) {
            return errorJson("set operation requires 'value' parameter");
        }

        // ottienivecchio（inritornaInformazione）
        String oldValue = configStore.get(key);
        if (oldValue == null) {
            oldValue = System.getProperty(key);
        }

        // scriveConfigurazioneMappa
        configStore.put(key, value);

        // SincronoscriveSistemaattributo（Implementazione，ProduzioneConfigurazione）
        try {
            System.setProperty(key, value);
        } catch (SecurityException e) {
            // Sec'èimpostazioni permessiSistemaattributo，ConfigurazioneMappapuò
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"action\": \"set\",\n");
        sb.append("  \"key\": \"").append(escapeJson(key)).append("\",\n");
        sb.append("  \"value\": \"").append(escapeJson(value)).append("\",\n");

        if (oldValue != null) {
            sb.append("  \"previous_value\": \"").append(escapeJson(oldValue)).append("\",\n");
        } else {
            sb.append("  \"previous_value\": null,\n");
        }

        sb.append("  \"success\": true,\n");
        sb.append("  \"message\": \"Config key '").append(escapeJson(key)).append("' has been set\"\n");
        sb.append("}");

        return sb.toString();
    }

    /* ------------------------------------------------------------------ */
    /*  ausiliariometodo                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * escape JSON caratteri speciali.
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * BuildErrore JSON Risposta。
     */
    private String errorJson(String message) {
        return """
                {
                  "error": true,
                  "message": "%s"
                }""".formatted(escapeJson(message));
    }
}
