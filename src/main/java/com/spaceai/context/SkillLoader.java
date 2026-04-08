package com.spaceai.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Skills caricamento competenze — Corrisponde a space-ai/src/skills/ modulo。
 * <p>
 * scansiona e carica file di competenze in formato .md da più fonti:
 * <ol>
 *   <li>Utente: ~/.space-ai/skills/</li>
 *   <li>livello progetto: ./.space-ai/skills/</li>
 *   <li>ComandoDirectory: ./.space-ai/commands/ (AutomaticamenteConversionecome competenza)</li>
 * </ol>
 * <p>
 * ogniCompetenzaFilesupporta YAML frontmatter metadati：
 * <pre>
 * ---
 * name: verify-tests
 * description: Run all tests after changes
 * whenToUse: After modifying code
 * ---
 * [Competenzacontenuto markdown]
 * </pre>
 */
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

    private final Path projectDir;
    private final List<Skill> skills = new ArrayList<>();

    public SkillLoader(Path projectDir) {
        this.projectDir = projectDir;
    }

    /**
     * Scansiona ecaricac'èCompetenzaFile
     */
    public List<Skill> loadAll() {
        skills.clear();

        // 1. UtenteCompetenza
        Path userSkillsDir = Path.of(System.getProperty("user.home"), ".space-ai", "skills");
        loadFromDirectory(userSkillsDir, "user");

        // 2. livello progettoCompetenza
        Path projectSkillsDir = projectDir.resolve(".space-ai").resolve("skills");
        loadFromDirectory(projectSkillsDir, "project");

        // 3. ComandoDirectory（AutomaticamenteConversionecomeCompetenza）
        Path commandsDir = projectDir.resolve(".space-ai").resolve("commands");
        loadFromDirectory(commandsDir, "command");

        log.debug("Loaded {} skills in total", skills.size());
        return Collections.unmodifiableList(skills);
    }

    /**
     * carica file di competenze .md dalla directory specificata
     */
    private void loadFromDirectory(Path dir, String source) {
        if (!Files.isDirectory(dir)) {
            return;
        }

        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            Skill skill = parseSkillFile(p, source);
                            skills.add(skill);
                            log.debug("Loaded skill: {} [{}] from {}", skill.name(), source, p.getFileName());
                        } catch (IOException e) {
                            log.warn("Failed to load skill file: {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.debug("Failed to scan skill directory: {}: {}", dir, e.getMessage());
        }
    }

    /**
     * analisisingoloCompetenzaFile，Estrai frontmatter econtenuto
     */
    private Skill parseSkillFile(Path path, String source) throws IOException {
        String raw = Files.readString(path, StandardCharsets.UTF_8).strip();
        String fileName = path.getFileName().toString().replace(".md", "");

        // tentaEstrai YAML frontmatter
        String name = fileName;
        String description = "";
        String whenToUse = "";
        String content = raw;

        if (raw.startsWith("---")) {
            int endIdx = raw.indexOf("---", 3);
            if (endIdx > 0) {
                String frontmatter = raw.substring(3, endIdx).strip();
                content = raw.substring(endIdx + 3).strip();

                // analisi YAML semplice (formato key: value)
                for (String line : frontmatter.split("\n")) {
                    line = line.strip();
                    int colonIdx = line.indexOf(':');
                    if (colonIdx > 0) {
                        String key = line.substring(0, colonIdx).strip();
                        String value = line.substring(colonIdx + 1).strip();
                        // rimuove le virgolette
                        if (value.startsWith("\"") && value.endsWith("\"")) {
                            value = value.substring(1, value.length() - 1);
                        }
                        switch (key) {
                            case "name" -> name = value;
                            case "description" -> description = value;
                            case "whenToUse" -> whenToUse = value;
                        }
                    }
                }
            }
        }

        return new Skill(name, description, whenToUse, content, source, path);
    }

    /**
     * ottienicaricatoCompetenzaLista
     */
    public List<Skill> getSkills() {
        return Collections.unmodifiableList(skills);
    }

    /**
     * nomecercaCompetenza
     */
    public Optional<Skill> findByName(String name) {
        return skills.stream()
                .filter(s -> s.name().equalsIgnoreCase(name))
                .findFirst();
    }

    /**
     * BuildCompetenzacontestoriepilogo（iniettaSistemaprompt)
     */
    public String buildSkillsSummary() {
        if (skills.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Available Skills\n\n");
        for (Skill skill : skills) {
            sb.append("- **").append(skill.name()).append("**");
            if (!skill.description().isEmpty()) {
                sb.append(": ").append(skill.description());
            }
            if (!skill.whenToUse().isEmpty()) {
                sb.append(" (use when: ").append(skill.whenToUse()).append(")");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Competenzadatirecord
     */
    public record Skill(String name, String description, String whenToUse,
                         String content, String source, Path filePath) {
    }
}
