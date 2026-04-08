package com.spaceai.tool.impl;

import com.spaceai.tool.Tool;
import com.spaceai.tool.ToolContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * FilemodificaStrumento —— Corrisponde a space-ai/src/tools/edit/EditFileTool.ts。
 * <p>
 * tramiteCorrispondenza esatta old_string esostituiscecome new_string modificaFile。
 */
public class FileEditTool implements Tool {

    @Override
    public String name() {
        return "Edit";
    }

    @Override
    public String description() {
        return """
            Make a targeted edit to a file by replacing an exact string match with new content. \
            The old_string must match exactly one location in the file. \
            Use Read tool first to understand the file content before editing.""";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "file_path": {
                  "type": "string",
                  "description": "Path to the file to edit"
                },
                "old_string": {
                  "type": "string",
                  "description": "The exact string to find and replace (must be unique)"
                },
                "new_string": {
                  "type": "string",
                  "description": "The replacement string"
                }
              },
              "required": ["file_path", "old_string", "new_string"]
            }""";
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String filePath = (String) input.get("file_path");
        String oldString = (String) input.get("old_string");
        String newString = (String) input.get("new_string");
        Path path = context.getWorkDir().resolve(filePath).normalize();

        if (!Files.exists(path)) {
            return "Error: File not found: " + path;
        }

        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);

            // Controlla old_string univoco
            int firstIdx = content.indexOf(oldString);
            if (firstIdx == -1) {
                return "Error: old_string not found in file";
            }

            int secondIdx = content.indexOf(oldString, firstIdx + 1);
            if (secondIdx != -1) {
                return "Error: old_string matches multiple locations. Be more specific.";
            }

            // eseguesostituisce
            String newContent = content.substring(0, firstIdx) + newString + content.substring(firstIdx + oldString.length());
            Files.writeString(path, newContent, StandardCharsets.UTF_8);

            // modificheintervallo
            long oldLines = oldString.lines().count();
            long newLines = newString.lines().count();

            return "✅ Edited " + path + " (replaced " + oldLines + " lines with " + newLines + " lines)";

        } catch (IOException e) {
            return "Error editing file: " + e.getMessage();
        }
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "✏️ Editing " + input.getOrDefault("file_path", "file");
    }
}
