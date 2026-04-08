package com.spaceai.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SPACE.md caricatore — Corrisponde a space-ai/src/context.ts in SPACE.md caricatorelogica。
 * <p>
 * prioritàdabassoaaltocarica：
 * <ol>
 *   <li>livello sistema: /etc/space-ai/SPACE.md (Unix) oPredefinito</li>
 *   <li>Utentelivello: ~/.space-ai/SPACE.md</li>
 *   <li>livello progetto: ./SPACE.md o ./.space-ai/SPACE.md</li>
 *   <li>livello locale: ./SPACE.local.md</li>
 * </ol>
 */
public class ClaudeMdLoader {

    private static final Logger log = LoggerFactory.getLogger(ClaudeMdLoader.class);

    private final Path projectDir;

    public ClaudeMdLoader(Path projectDir) {
        this.projectDir = projectDir;
    }

    /**
     * carica e unisce il contenuto di SPACE.md.
     */
    public String load() {
        List<String> sections = new ArrayList<>();

        // 1. Utente
        Path userMd = Path.of(System.getProperty("user.home"), ".space-ai", "SPACE.md");
        loadFile(userMd, "user").ifPresent(sections::add);

        // 2. livello progetto — controlla prima .space-ai/SPACE.md, poi SPACE.md nella root
        Path projectSpaceDir = projectDir.resolve(".space-ai").resolve("SPACE.md");
        Path projectRoot = projectDir.resolve("SPACE.md");
        if (Files.exists(projectSpaceDir)) {
            loadFile(projectSpaceDir, "project").ifPresent(sections::add);
        } else {
            loadFile(projectRoot, "project").ifPresent(sections::add);
        }

        // 3. livello locale
        Path localMd = projectDir.resolve("SPACE.local.md");
        loadFile(localMd, "local").ifPresent(sections::add);

        // 4. Carica .space-ai/rules/*.md
        Path rulesDir = projectDir.resolve(".space-ai").resolve("rules");
        if (Files.isDirectory(rulesDir)) {
            try (var stream = Files.list(rulesDir)) {
                stream.filter(p -> p.toString().endsWith(".md"))
                        .sorted()
                        .forEach(p -> loadFile(p, "rule").ifPresent(sections::add));
            } catch (IOException e) {
                log.debug("Failed to load rules directory: {}", e.getMessage());
            }
        }

        if (sections.isEmpty()) {
            return "";
        }

        return String.join("\n\n---\n\n", sections);
    }

    private java.util.Optional<String> loadFile(Path path, String level) {
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return java.util.Optional.empty();
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8).strip();
            if (!content.isEmpty()) {
                log.debug("Loaded {} level SPACE.md: {}", level, path);
                return java.util.Optional.of(content);
            }
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", path, e.getMessage());
        }
        return java.util.Optional.empty();
    }
}
