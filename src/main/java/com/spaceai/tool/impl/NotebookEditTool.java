package com.spaceai.tool.impl;

import com.spaceai.tool.Tool;
import com.spaceai.tool.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Jupyter Notebook modificaStrumento —— Corrisponde a space-ai/src/tools/NotebookEditTool.ts。
 * <p>
 * operazione .ipynb Fileincella（cell），supporta:
 * <ul>
 *   <li>Inserimentonuovocella（code / markdown）</li>
 *   <li>sostituiscegiàc'ècellacontenuto</li>
 *   <li>Eliminazionecella</li>
 *   <li>cellaposizione</li>
 * </ul>
 * <p>
 * Notebook formato nbformat 4.x 。
 */
public class NotebookEditTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "NotebookEdit";
    }

    @Override
    public String description() {
        return """
            Edit Jupyter Notebook (.ipynb) cells. Supports insert, replace, delete, \
            and move operations on notebook cells. Works with nbformat 4.x notebooks. \
            Use this to modify code cells, add markdown documentation, or restructure notebooks.""";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "Path to the .ipynb notebook file"
                },
                "operation": {
                  "type": "string",
                  "enum": ["insert", "replace", "delete", "move", "read"],
                  "description": "The operation to perform on the notebook"
                },
                "cellIndex": {
                  "type": "integer",
                  "description": "Target cell index (0-based)"
                },
                "cellType": {
                  "type": "string",
                  "enum": ["code", "markdown", "raw"],
                  "description": "Type of cell (for insert/replace, default: code)"
                },
                "source": {
                  "type": "string",
                  "description": "Cell content/source code (for insert/replace)"
                },
                "targetIndex": {
                  "type": "integer",
                  "description": "Target position for move operation"
                }
              },
              "required": ["path", "operation"]
            }""";
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String pathStr = (String) input.get("path");
        String operation = (String) input.get("operation");

        if (pathStr == null || operation == null) {
            return "Error: 'path' and 'operation' are required";
        }

        Path filePath = context.getWorkDir().resolve(pathStr).normalize();

        return switch (operation) {
            case "read" -> readNotebook(filePath);
            case "insert" -> insertCell(filePath, input);
            case "replace" -> replaceCell(filePath, input);
            case "delete" -> deleteCell(filePath, input);
            case "move" -> moveCell(filePath, input);
            default -> "Error: Unknown operation '" + operation + "'. Use: read, insert, replace, delete, move";
        };
    }

    /** Leggi notebook  */
    private String readNotebook(Path filePath) {
        try {
            JsonNode root = readNotebookJson(filePath);
            ArrayNode cells = (ArrayNode) root.get("cells");
            if (cells == null) {
                return "Error: Invalid notebook format (no 'cells' array)";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("📓 Notebook: ").append(filePath.getFileName()).append("\n");
            sb.append("Cells: ").append(cells.size()).append("\n\n");

            for (int i = 0; i < cells.size(); i++) {
                JsonNode cell = cells.get(i);
                String cellType = cell.has("cell_type") ? cell.get("cell_type").asText() : "unknown";
                String source = extractSource(cell);

                sb.append("--- Cell ").append(i).append(" [").append(cellType).append("] ---\n");
                // tronca i troppo lunghicontenuto
                if (source.length() > 200) {
                    sb.append(source, 0, 200).append("...(truncated)\n");
                } else {
                    sb.append(source).append("\n");
                }
            }

            return sb.toString().stripTrailing();

        } catch (IOException e) {
            return "Error reading notebook: " + e.getMessage();
        }
    }

    /** inposizionamentoInserimentonuovocella */
    private String insertCell(Path filePath, Map<String, Object> input) {
        int cellIndex = input.containsKey("cellIndex") ? ((Number) input.get("cellIndex")).intValue() : -1;
        String cellType = (String) input.getOrDefault("cellType", "code");
        String source = (String) input.get("source");

        if (source == null) {
            return "Error: 'source' is required for insert operation";
        }

        try {
            ObjectNode root = (ObjectNode) readNotebookJson(filePath);
            ArrayNode cells = (ArrayNode) root.get("cells");
            if (cells == null) {
                return "Error: Invalid notebook format";
            }

            // creanuovocella
            ObjectNode newCell = createCell(cellType, source);

            // Inserimentoposizione: -1 o fuori range → aggiunge alla fine
            if (cellIndex < 0 || cellIndex >= cells.size()) {
                cells.add(newCell);
                cellIndex = cells.size() - 1;
            } else {
                cells.insert(cellIndex, newCell);
            }

            writeNotebookJson(filePath, root);

            return "✅ Inserted " + cellType + " cell at index " + cellIndex;

        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    /** sostituiscespecificatocellacontenuto */
    private String replaceCell(Path filePath, Map<String, Object> input) {
        if (!input.containsKey("cellIndex")) {
            return "Error: 'cellIndex' is required for replace operation";
        }
        int cellIndex = ((Number) input.get("cellIndex")).intValue();
        String cellType = (String) input.get("cellType"); // null TabellaTipo
        String source = (String) input.get("source");

        if (source == null) {
            return "Error: 'source' is required for replace operation";
        }

        try {
            ObjectNode root = (ObjectNode) readNotebookJson(filePath);
            ArrayNode cells = (ArrayNode) root.get("cells");

            if (cellIndex < 0 || cellIndex >= cells.size()) {
                return "Error: Cell index " + cellIndex + " out of range (0-" + (cells.size() - 1) + ")";
            }

            if (cellType == null) {
                cellType = cells.get(cellIndex).get("cell_type").asText();
            }

            ObjectNode newCell = createCell(cellType, source);
            cells.set(cellIndex, newCell);

            writeNotebookJson(filePath, root);

            return "✅ Replaced cell " + cellIndex + " [" + cellType + "]";

        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    /** Eliminazionespecificatocella */
    private String deleteCell(Path filePath, Map<String, Object> input) {
        if (!input.containsKey("cellIndex")) {
            return "Error: 'cellIndex' is required for delete operation";
        }
        int cellIndex = ((Number) input.get("cellIndex")).intValue();

        try {
            ObjectNode root = (ObjectNode) readNotebookJson(filePath);
            ArrayNode cells = (ArrayNode) root.get("cells");

            if (cellIndex < 0 || cellIndex >= cells.size()) {
                return "Error: Cell index " + cellIndex + " out of range (0-" + (cells.size() - 1) + ")";
            }

            String cellType = cells.get(cellIndex).get("cell_type").asText();
            cells.remove(cellIndex);

            writeNotebookJson(filePath, root);

            return "🗑️ Deleted cell " + cellIndex + " [" + cellType + "]. Remaining: " + cells.size() + " cells";

        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    /** cellaanuovoposizione */
    private String moveCell(Path filePath, Map<String, Object> input) {
        if (!input.containsKey("cellIndex") || !input.containsKey("targetIndex")) {
            return "Error: 'cellIndex' and 'targetIndex' are required for move operation";
        }
        int fromIndex = ((Number) input.get("cellIndex")).intValue();
        int toIndex = ((Number) input.get("targetIndex")).intValue();

        try {
            ObjectNode root = (ObjectNode) readNotebookJson(filePath);
            ArrayNode cells = (ArrayNode) root.get("cells");
            int size = cells.size();

            if (fromIndex < 0 || fromIndex >= size) {
                return "Error: Source index " + fromIndex + " out of range";
            }
            if (toIndex < 0 || toIndex >= size) {
                return "Error: Target index " + toIndex + " out of range";
            }
            if (fromIndex == toIndex) {
                return "Cell is already at index " + toIndex;
            }

            // cella
            JsonNode cell = cells.remove(fromIndex);
            // Inserimentoanuovoposizione
            cells.insert(toIndex, cell);

            writeNotebookJson(filePath, root);

            return "↕️ Moved cell from " + fromIndex + " to " + toIndex;

        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    // ==================== ausiliariometodo ====================

    /** Leggi notebook JSON */
    private JsonNode readNotebookJson(Path filePath) throws IOException {
        if (!Files.exists(filePath)) {
            throw new IOException("File not found: " + filePath);
        }
        String content = Files.readString(filePath, StandardCharsets.UTF_8);
        return MAPPER.readTree(content);
    }

    /** Scrivi notebook JSON（mantiene la formattazione) */
    private void writeNotebookJson(Path filePath, JsonNode root) throws IOException {
        String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        Files.writeString(filePath, json, StandardCharsets.UTF_8);
    }

    /** Crea nbformat 4.x cella */
    private ObjectNode createCell(String cellType, String source) {
        ObjectNode cell = MAPPER.createObjectNode();
        cell.put("cell_type", cellType);

        // source sì
        ArrayNode sourceArray = MAPPER.createArrayNode();
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i < lines.length - 1) {
                sourceArray.add(lines[i] + "\n");
            } else {
                sourceArray.add(lines[i]);
            }
        }
        cell.set("source", sourceArray);

        // metadata vuotosu
        cell.set("metadata", MAPPER.createObjectNode());

        // code cellarichiedeesternoCampo
        if ("code".equals(cellType)) {
            cell.putNull("execution_count");
            cell.set("outputs", MAPPER.createArrayNode());
        }

        return cell;
    }

    /** estrae il testo sorgente dal nodo cella */
    private String extractSource(JsonNode cell) {
        JsonNode source = cell.get("source");
        if (source == null) return "";

        if (source.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode line : source) {
                sb.append(line.asText());
            }
            return sb.toString();
        }
        return source.asText();
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        String op = (String) input.getOrDefault("operation", "editing");
        String path = (String) input.getOrDefault("path", "notebook");
        return "📓 Notebook " + op + ": " + path;
    }
}
