package com.spaceai.tool.impl;

import com.spaceai.tool.Tool;
import com.spaceai.tool.ToolContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * FileleggeStrumento —— Corrisponde a space-ai/src/tools/read/ReadFileTool.ts。
 * <p>
 * leggeFilecontenuto，supportaintervalloFiltraggio。
 */
public class FileReadTool implements Tool {

    /** leggeMassimo */
    private static final int MAX_LINES = 2000;

    @Override
    public String name() {
        return "Read";
    }

    @Override
    public String description() {
        return """
            Read the contents of a file. Use line_start and line_end to read specific line ranges. \
            For large files, read in chunks. Supports text files only.""";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "file_path": {
                  "type": "string",
                  "description": "Absolute or relative path to the file"
                },
                "line_start": {
                  "type": "integer",
                  "description": "Starting line number (1-based, inclusive)"
                },
                "line_end": {
                  "type": "integer",
                  "description": "Ending line number (1-based, inclusive)"
                }
              },
              "required": ["file_path"]
            }""";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String filePath = (String) input.get("file_path");
        Path path = context.getWorkDir().resolve(filePath).normalize();

        if (!Files.exists(path)) {
            return "Error: File not found: " + path;
        }
        if (!Files.isRegularFile(path)) {
            return "Error: Not a regular file: " + path;
        }

        try {
            var allLines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int total = allLines.size();

            int start = 1;
            int end = total;

            if (input.containsKey("line_start")) {
                start = ((Number) input.get("line_start")).intValue();
            }
            if (input.containsKey("line_end")) {
                end = ((Number) input.get("line_end")).intValue();
            }

            // Parametrivalidazione
            start = Math.max(1, start);
            end = Math.min(total, end);

            if (start > end) {
                return "Error: line_start (" + start + ") > line_end (" + end + ")";
            }

            // Limitazione
            if (end - start + 1 > MAX_LINES) {
                end = start + MAX_LINES - 1;
            }

            // BuildOutput
            StringBuilder sb = new StringBuilder();
            for (int i = start - 1; i < end; i++) {
                sb.append(String.format("%4d | %s%n", i + 1, allLines.get(i)));
            }

            if (end < total) {
                sb.append(String.format("... (%d more lines)%n", total - end));
            }

            return sb.toString().stripTrailing();

        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "📄 Reading " + input.getOrDefault("file_path", "file");
    }
}
