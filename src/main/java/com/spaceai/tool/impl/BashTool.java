package com.spaceai.tool.impl;

import com.spaceai.tool.Tool;
import com.spaceai.tool.ToolContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Bash Strumento —— Corrisponde a space-ai/src/tools/bash/BashTool.ts。
 * <p>
 * inspecificatolavoroDirectoryinEsegui shell Comando，Restituisce stdout/stderr Output。
 */
public class BashTool implements Tool {

    /** PredefinitoTimeout（secondi） */
    private static final int DEFAULT_TIMEOUT = 120;

    @Override
    public String name() {
        return "Bash";
    }

    @Override
    public String description() {
        return """
            Execute a bash command in the working directory. \
            Use this for file operations, running scripts, installing packages, \
            or any system command. Commands run in a subprocess with timeout protection.""";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "description": "The shell command to execute"
                },
                "timeout": {
                  "type": "integer",
                  "description": "Timeout in seconds (default: 120)"
                }
              },
              "required": ["command"]
            }""";
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String command = (String) input.get("command");
        int timeout = input.containsKey("timeout")
                ? ((Number) input.get("timeout")).intValue()
                : DEFAULT_TIMEOUT;
        Path workDir = context.getWorkDir();

        try {
            // in base aoperazioneSistemaselezione shell
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            ProcessBuilder pb;
            if (isWindows) {
                pb = new ProcessBuilder("cmd", "/c", command);
            } else {
                pb = new ProcessBuilder("bash", "-c", command);
            }

            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return output + "\n[ERROR: Command timed out after " + timeout + " seconds]";
            }

            int exitCode = process.exitValue();
            String result = output.toString().stripTrailing();

            if (exitCode != 0) {
                return result + "\n[Exit code: " + exitCode + "]";
            }
            return result;

        } catch (Exception e) {
            return "Error executing command: " + e.getMessage();
        }
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        String cmd = (String) input.getOrDefault("command", "");
        // tronca i troppo lunghiComando
        if (cmd.length() > 60) {
            cmd = cmd.substring(0, 57) + "...";
        }
        return "⚡ " + cmd;
    }
}
