package com.spaceai.tool.impl;

import com.spaceai.tool.Tool;
import com.spaceai.tool.ToolContext;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Glob FileRicercaStrumento —— Corrisponde a space-ai/src/tools/glob/GlobTool.ts。
 * <p>
 * in base a glob ModalitàRicercaFile。
 */
public class GlobTool implements Tool {

    private static final int MAX_RESULTS = 200;

    @Override
    public String name() {
        return "Glob";
    }

    @Override
    public String description() {
        return """
            Find files matching a glob pattern. Returns a list of matching file paths \
            relative to the working directory. Useful for finding files by name or extension.""";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "pattern": {
                  "type": "string",
                  "description": "Glob pattern (e.g., '**/*.java', 'src/**/*.ts')"
                },
                "path": {
                  "type": "string",
                  "description": "Directory to search in (default: working directory)"
                }
              },
              "required": ["pattern"]
            }""";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String pattern = (String) input.get("pattern");
        String searchPath = (String) input.getOrDefault("path", ".");
        Path baseDir = context.getWorkDir().resolve(searchPath).normalize();

        if (!Files.isDirectory(baseDir)) {
            return "Error: Directory not found: " + baseDir;
        }

        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<String> matches = new ArrayList<>();

            Files.walkFileTree(baseDir, EnumSet.of(FileVisitOption.FOLLOW_LINKS), 20, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path relative = baseDir.relativize(file);
                    // verràPercorsodividicome /（piattaforma）
                    String relStr = relative.toString().replace('\\', '/');
                    if (matcher.matches(Path.of(relStr))) {
                        matches.add(relStr);
                    }
                    if (matches.size() >= MAX_RESULTS) {
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    // saltanascondeDirectoryeIgnoraDirectory
                    if (name.startsWith(".") || name.equals("node_modules") || name.equals("target") || name.equals("build")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });

            Collections.sort(matches);

            if (matches.isEmpty()) {
                return "No files matching pattern: " + pattern;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(matches.size()).append(" file(s):\n");
            for (String m : matches) {
                sb.append("  ").append(m).append("\n");
            }
            if (matches.size() >= MAX_RESULTS) {
                sb.append("  ... (results truncated at ").append(MAX_RESULTS).append(")\n");
            }
            return sb.toString().stripTrailing();

        } catch (IOException e) {
            return "Error searching files: " + e.getMessage();
        }
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "🔍 Searching " + input.getOrDefault("pattern", "files");
    }
}
