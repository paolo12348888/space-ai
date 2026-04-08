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
 * /init Comando —— Inizializzazioneprogetto SPACE.md。
 * <p>
 * Corrisponde a space-ai/src/commands/init.ts。
 * rilevaprogettoTipoegenera SPACE.md File。
 */
public class InitCommand implements SlashCommand {

    @Override
    public String name() {
        return "init";
    }

    @Override
    public String description() {
        return "Initialize SPACE.md for the current project";
    }

    @Override
    public String execute(String args, CommandContext context) {
        Path projectDir = Path.of(System.getProperty("user.dir"));
        Path spaceMdPath = projectDir.resolve("SPACE.md");

        // controllasegiàin
        if (Files.exists(spaceMdPath)) {
            return AnsiStyle.yellow("  ⚠ SPACE.md already exists at: " + spaceMdPath) + "\n"
                    + AnsiStyle.dim("  Use a text editor to modify it, or delete and re-run /init.");
        }

        // rilevaprogettoTipo
        ProjectType type = detectProjectType(projectDir);

        // genera SPACE.md contenuto
        String content = generateSpaceMd(projectDir, type);

        try {
            Files.writeString(spaceMdPath, content, StandardCharsets.UTF_8);

            // Crea la directory .space-ai/skills/ del progetto
            Path spaceDir = projectDir.resolve(".space-ai");
            Path skillsDir = spaceDir.resolve("skills");
            if (!Files.exists(skillsDir)) {
                Files.createDirectories(skillsDir);
            }

            StringBuilder sb = new StringBuilder();
            sb.append(AnsiStyle.green("  ✅ Created SPACE.md")).append("\n");
            sb.append(AnsiStyle.dim("  Path: " + spaceMdPath)).append("\n");
            sb.append(AnsiStyle.dim("  Project type: " + type.displayName)).append("\n");
            sb.append(AnsiStyle.dim("  Skills dir: " + skillsDir)).append("\n\n");
            sb.append(AnsiStyle.dim("  Edit SPACE.md to customize instructions for the AI assistant."));
            return sb.toString();

        } catch (IOException e) {
            return AnsiStyle.red("  ✗ Failed to create SPACE.md: " + e.getMessage());
        }
    }

    /** rilevaprogettoTipo */
    private ProjectType detectProjectType(Path projectDir) {
        if (Files.exists(projectDir.resolve("pom.xml"))) return ProjectType.MAVEN;
        if (Files.exists(projectDir.resolve("build.gradle")) || Files.exists(projectDir.resolve("build.gradle.kts")))
            return ProjectType.GRADLE;
        if (Files.exists(projectDir.resolve("package.json"))) return ProjectType.NODE;
        if (Files.exists(projectDir.resolve("pyproject.toml")) || Files.exists(projectDir.resolve("setup.py")))
            return ProjectType.PYTHON;
        if (Files.exists(projectDir.resolve("Cargo.toml"))) return ProjectType.RUST;
        if (Files.exists(projectDir.resolve("go.mod"))) return ProjectType.GO;
        if (Files.exists(projectDir.resolve("Gemfile"))) return ProjectType.RUBY;
        return ProjectType.GENERIC;
    }

    /** genera SPACE.md contenuto */
    private String generateSpaceMd(Path projectDir, ProjectType type) {
        String projectName = projectDir.getFileName().toString();

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(projectName).append("\n\n");
        sb.append("## Project Overview\n\n");
        sb.append("<!-- Describe your project here -->\n\n");

        sb.append("## Tech Stack\n\n");
        sb.append(type.techStackHint).append("\n\n");

        sb.append("## Build & Run\n\n");
        sb.append("```bash\n");
        sb.append(type.buildCommand).append("\n");
        sb.append("```\n\n");

        sb.append("## Test\n\n");
        sb.append("```bash\n");
        sb.append(type.testCommand).append("\n");
        sb.append("```\n\n");

        sb.append("## Code Style\n\n");
        sb.append("- Follow existing patterns in the codebase\n");
        sb.append("- Write clear, descriptive variable names\n");
        sb.append("- Add comments for complex business logic\n\n");

        sb.append("## Project Structure\n\n");
        sb.append("<!-- Describe key directories and files -->\n");

        return sb.toString();
    }

    enum ProjectType {
        MAVEN("Maven/Java", "- Java (Maven)\n- Spring Boot (if applicable)", "mvn clean install", "mvn test"),
        GRADLE("Gradle/Java", "- Java/Kotlin (Gradle)\n- Spring Boot (if applicable)", "gradle build", "gradle test"),
        NODE("Node.js", "- Node.js\n- TypeScript/JavaScript", "npm install && npm run build", "npm test"),
        PYTHON("Python", "- Python 3\n- pip/poetry", "pip install -e .", "pytest"),
        RUST("Rust", "- Rust\n- Cargo", "cargo build", "cargo test"),
        GO("Go", "- Go", "go build ./...", "go test ./..."),
        RUBY("Ruby", "- Ruby\n- Bundler", "bundle install", "bundle exec rspec"),
        GENERIC("Generic", "<!-- List your tech stack -->", "# add build command", "# add test command");

        final String displayName;
        final String techStackHint;
        final String buildCommand;
        final String testCommand;

        ProjectType(String displayName, String techStackHint, String buildCommand, String testCommand) {
            this.displayName = displayName;
            this.techStackHint = techStackHint;
            this.buildCommand = buildCommand;
            this.testCommand = testCommand;
        }
    }
}
