package com.spaceai.command.impl;

import com.spaceai.command.CommandContext;
import com.spaceai.command.SlashCommand;
import com.spaceai.console.AnsiStyle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * /memory Comando —— emodifica SPACE.md memoriaFile。
 * <p>
 * Corrisponde a space-ai  /memory Comando，supporta:
 * <ul>
 *   <li>/memory —— visualizzaCorrente SPACE.md contenuto</li>
 *   <li>/memory add [contenuto] —— aggiungecontenutoalivello progetto SPACE.md</li>
 *   <li>/memory edit —— Sistemaapre nell'editor SPACE.md</li>
 *   <li>/memory user —— Utente SPACE.md</li>
 * </ul>
 */
public class MemoryCommand implements SlashCommand {

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public String description() {
        return "View/edit SPACE.md memory files";
    }

    @Override
    public List<String> aliases() {
        return List.of("mem");
    }

    @Override
    public String execute(String args, CommandContext context) {
        args = args == null ? "" : args.strip();

        if (args.startsWith("add ")) {
            return handleAdd(args.substring(4).strip());
        } else if (args.equals("edit")) {
            return handleEdit();
        } else if (args.equals("user")) {
            return showUserMemory();
        } else {
            return showProjectMemory();
        }
    }

    /** visualizzalivello progetto SPACE.md */
    private String showProjectMemory() {
        Path projectSpaceMd = Path.of(System.getProperty("user.dir"), "SPACE.md");
        return showMemoryFile(projectSpaceMd, "Project");
    }

    /** visualizzaUtente SPACE.md */
    private String showUserMemory() {
        Path userSpaceMd = Path.of(System.getProperty("user.home"), ".space-ai", "SPACE.md");
        return showMemoryFile(userSpaceMd, "User");
    }

    private String showMemoryFile(Path path, String level) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  📝 SPACE.md (" + level + ")\n"));
        sb.append("  ").append("─".repeat(50)).append("\n");
        sb.append("  ").append(AnsiStyle.dim("Path: " + path)).append("\n\n");

        if (Files.exists(path)) {
            try {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                if (content.isBlank()) {
                    sb.append(AnsiStyle.dim("  (File is empty)\n"));
                } else {
                    content.lines().forEach(line -> sb.append("  ").append(line).append("\n"));
                }
            } catch (IOException e) {
                sb.append(AnsiStyle.red("  ✗ Read failed: " + e.getMessage() + "\n"));
            }
        } else {
            sb.append(AnsiStyle.dim("  (File does not exist)\n\n"));
            sb.append(AnsiStyle.dim("  Use /memory add <content> to create and add content\n"));
            sb.append(AnsiStyle.dim("  Or use /init command to initialize\n"));
        }

        return sb.toString();
    }

    /** aggiungecontenutoalivello progetto SPACE.md */
    private String handleAdd(String content) {
        if (content.isEmpty()) {
            return AnsiStyle.yellow("  ⚠ Please provide content: /memory add <content>");
        }

        Path projectSpaceMd = Path.of(System.getProperty("user.dir"), "SPACE.md");
        try {
            // Filein
            if (!Files.exists(projectSpaceMd)) {
                Files.writeString(projectSpaceMd,
                        "# SPACE.md\n\n" + content + "\n",
                        StandardCharsets.UTF_8);
                return AnsiStyle.green("  ✓ Created SPACE.md and added content");
            }

            // aggiungecontenuto
            String existing = Files.readString(projectSpaceMd, StandardCharsets.UTF_8);
            String newContent = existing.endsWith("\n") ? existing + "\n" + content + "\n" : existing + "\n\n" + content + "\n";
            Files.writeString(projectSpaceMd, newContent, StandardCharsets.UTF_8);

            return AnsiStyle.green("  ✓ Content appended to SPACE.md");
        } catch (IOException e) {
            return AnsiStyle.red("  ✗ Write failed: " + e.getMessage());
        }
    }

    /** Sistemaapre nell'editor SPACE.md */
    private String handleEdit() {
        Path projectSpaceMd = Path.of(System.getProperty("user.dir"), "SPACE.md");
        try {
            if (!Files.exists(projectSpaceMd)) {
                Files.writeString(projectSpaceMd, "# SPACE.md\n\n", StandardCharsets.UTF_8);
            }

            // tentaSistemaapre nell'editor
            String editor = System.getenv("EDITOR");
            if (editor == null || editor.isBlank()) {
                editor = System.getenv("VISUAL");
            }

            if (editor != null && !editor.isBlank()) {
                ProcessBuilder pb = new ProcessBuilder(editor, projectSpaceMd.toString());
                pb.inheritIO();
                Process p = pb.start();
                p.waitFor();
                return AnsiStyle.green("  ✓ Editor closed");
            }

            // Windows: tenta notepad
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                ProcessBuilder pb = new ProcessBuilder("notepad", projectSpaceMd.toString());
                pb.start(); // non attende
                return AnsiStyle.green("  ✓ Opened SPACE.md with Notepad");
            }

            return AnsiStyle.yellow("  ⚠ No editor found. Set EDITOR environment variable, or manually edit:\n  " + projectSpaceMd);

        } catch (Exception e) {
            return AnsiStyle.red("  ✗ Failed to open editor: " + e.getMessage());
        }
    }
}
